package commands

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Updates
import com.mongodb.client.model.changestream.FullDocumentBeforeChange
import database
import dev.minn.jda.ktx.coroutines.await
import handler
import helpers.NeoSuperEmbed
import helpers.SuperEmbed
import helpers.formatDuration
import helpers.sendConfirmationPrompt
import jda
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import myScope
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import java.util.*
import java.util.concurrent.TimeUnit

object EphemeralRole {

    data class EphemeralRoleDB(
        val roleId: Long,
        val duration: Long, // Duration in minutes for which the role will be active
        val guildId: Long
    )

    data class UserWithEphemeralRole(
        val userId: Long,
        val roleId: Long,
        val guildId: Long,
        val expireAt: Date // The timestamp when this document should be removed
    )

    // Listen to changes from mongoDB


    val ephemeralRoleCollection = database.getCollection<EphemeralRoleDB>("ephemeralRole")
    val usersWithEphemeralRoleCollection = database.getCollection<UserWithEphemeralRole>("usersWithEphemeralRole")


    val pipeline = listOf(
        Aggregates.match(
            Filters.`in`(
                "operationType",
                listOf("delete")
            )
        )
    )

    init {
        listenForExpiredRoles()
    }

    suspend fun onGuildMemberRoleAddEvent(event: GuildMemberRoleAddEvent) {
        // Check if the added roles are ephemeral roles
        event.roles.forEach {
            val ephemeralRole = ephemeralRoleCollection.find(
                and(
                    Filters.eq(EphemeralRoleDB::roleId.name, it.idLong),
                    Filters.eq(EphemeralRoleDB::guildId.name, event.guild.idLong)
                )
            ).firstOrNull()

            if (ephemeralRole != null) {
                // If the role is ephemeral, add the user to the usersWithEphemeralRole collection
                usersWithEphemeralRoleCollection.insertOne(
                    UserWithEphemeralRole(
                        userId = event.user.idLong,
                        roleId = it.idLong,
                        guildId = event.guild.idLong,
                        expireAt = Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(ephemeralRole.duration))
                    )
                )
            }
        }
    }


    private fun listenForExpiredRoles() {
        myScope.launch(handler) {
            val changeStream = usersWithEphemeralRoleCollection.watch(pipeline)
                .fullDocumentBeforeChange(FullDocumentBeforeChange.WHEN_AVAILABLE)
            changeStream.collect { changeEvent ->
                val deletedDoc = changeEvent.fullDocumentBeforeChange ?: return@collect
                // Remove the role from the user
                val guild = jda.getGuildById(deletedDoc.guildId) ?: return@collect
                val member = guild.retrieveMemberById(deletedDoc.userId).await()
                val role = guild.getRoleById(deletedDoc.roleId)
                if (member != null && role != null)
                    guild.removeRoleFromMember(member, role).await()
            }
        }
    }

    // The duration is in minutes.
    suspend fun add(
        event: SlashCommandInteractionEvent,
        role: Role,
        duration: Long
    ) {
        // First, check if the role already exists in the database
        ephemeralRoleCollection.find(Filters.eq(EphemeralRoleDB::roleId.name, role.idLong)).firstOrNull().let {
            if (it != null) {
                event.sendConfirmationPrompt(
                    "This role is already registered as an ephemeral role with a duration of ${durationLabel(it.duration)}.\nDo you wish to set the duration to ${durationLabel(duration)}?"
                ) {
                    ephemeralRoleCollection.updateOne(
                        and(
                            Filters.eq(EphemeralRoleDB::guildId.name, role.idLong),
                            Filters.eq(EphemeralRoleDB::guildId.name, event.guild!!.idLong)
                        ),
                        Updates.set(EphemeralRoleDB::duration.name, duration)
                    )
                    NeoSuperEmbed {
                        description =
                            "The duration for the role ${role.asMention} has been updated to ${durationLabel(duration)}."
                        type = SuperEmbed.ResultType.SUCCESS
                    }
                }
                return
            }
            // If the role does not exist, add it to the database
            ephemeralRoleCollection.insertOne(
                EphemeralRoleDB(
                    roleId = role.idLong,
                    duration = duration,
                    guildId = event.guild!!.idLong
                )
            )
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    description =
                        "The role ${role.asMention} has been registered as an ephemeral role with a duration of ${durationLabel(duration)}."
                }).queue()

        }
    }

    suspend fun remove(
        event: SlashCommandInteractionEvent,
        role: Role
    ) {
        ephemeralRoleCollection.deleteOne(
            and(
                Filters.eq(EphemeralRoleDB::roleId.name, role.idLong),
                Filters.eq(EphemeralRoleDB::guildId.name, event.guild!!.idLong)
            )
        ).deletedCount.let {
            if (it > 0) {
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        description = "The role ${role.asMention} has been removed from the ephemeral roles list."
                        type = SuperEmbed.ResultType.SUCCESS
                    }).queue()
            } else {
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        description = "The role ${role.asMention} is not registered as an ephemeral role."
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                    }).queue()
            }
        }
    }

    suspend fun removeMissing(event: SlashCommandInteractionEvent) {
        val roles = ephemeralRoleCollection.find(
            Filters.eq(EphemeralRoleDB::guildId.name, event.guild!!.idLong)
        ).toList()

        var removedMissingRoles = 0

        roles.forEach { role ->
            val guildRole = event.guild!!.getRoleById(role.roleId)
            if (guildRole == null) {
                ephemeralRoleCollection.deleteOne(
                    and(
                        Filters.eq(EphemeralRoleDB::roleId.name, role.roleId),
                        Filters.eq(EphemeralRoleDB::guildId.name, event.guild!!.idLong)
                    )
                )
                removedMissingRoles++
            }
        }

        event.hook.editOriginalEmbeds(
            NeoSuperEmbed {
                description = "Removed $removedMissingRoles missing role(s) from the ephemeral roles list."
                type = SuperEmbed.ResultType.SUCCESS
            }).queue()
    }

    suspend fun list(event: SlashCommandInteractionEvent) {
        val roles = ephemeralRoleCollection.find(
            Filters.eq(EphemeralRoleDB::guildId.name, event.guild!!.idLong)
        ).toList()

        if (roles.isEmpty()) {
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    description = "No ephemeral roles found in this server."
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                }).queue()
            return
        }

        var missingRole = false

        val embed = NeoSuperEmbed {
            description = "**Ephemeral Roles**\n" + roles.joinToString("\n") { role ->
                val guildRole = event.guild!!.getRoleById(role.roleId)

                if (guildRole != null) {
                    "${guildRole.asMention} - Duration: ${durationLabel(role.duration)}"
                } else {
                    missingRole = true
                    "${role.roleId} - Duration: ${durationLabel(role.duration)} (missing role)"
                }
            }
            if (missingRole) {
                description += "\nSome roles are missing from the server. It's recommended to use `/ephemeral remove-missing` to clean up."
            }
            type = SuperEmbed.ResultType.SUCCESS
        }
        event.hook.editOriginalEmbeds(embed).queue()
    }

    suspend fun flush(event: SlashCommandInteractionEvent) {
        ephemeralRoleCollection.deleteMany(
            Filters.eq(EphemeralRoleDB::guildId.name, event.guild!!.idLong)
        )
        event.hook.editOriginalEmbeds(
            NeoSuperEmbed {
                description = "All ephemeral roles have been removed from the database."
                type = SuperEmbed.ResultType.SUCCESS
            }).queue()
    }


}

private fun durationLabel(minutes: Long): String = formatDuration(minutes, TimeUnit.MINUTES)
