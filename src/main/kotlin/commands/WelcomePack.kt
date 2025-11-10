package commands

import EntryController
import SONIC_THE_HEDGEHOG
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.client.model.Updates.combine
import com.mongodb.client.model.Updates.setOnInsert
import commands.ranks.Ranks
import database
import dev.minn.jda.ktx.coroutines.await
import helpers.NeoSuperEmbed
import helpers.SuperEmbed
import helpers.toWrapper
import interfaces.EventContext
import interfaces.MessageReplacements
import interfaces.appendRelevantReplacementsInfo
import kotlinx.coroutines.flow.firstOrNull
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent

object WelcomePack {

    /*
    Sends a welcome message when a member joins the server.
    The welcome message can be customized for first time members and returning members - including channels where it's sent.
    The bot will add relevant roles to members accordingly.
    */

    data class WelcomeSettings(
        val status: Boolean = false, // by default, this is false
        val firstTimeChannel: Long? = null, // channel ID for first time members
        val firstTimeRole: Long? = null, // role ID for first time members
        val firstTimeMessage: String = "Welcome to the server, {user}!", // message for first time members, if not customized.

        val returningChannel: Long? = null, // channel ID for returning members
        val returningRole: Long? = null, // role ID for returning members
        val returningMessage: String = "Welcome back, {user}!", // message for returning members, if not customized.

        val minLevel: Int = 0, // minimum rank level to be considered a returning member.
        val guildId: Long
    )

    val welcomeSettingsCollection = database.getCollection<WelcomeSettings>("welcomeSettings")

    suspend fun getSettings(guildId: Long): WelcomeSettings? {
        return welcomeSettingsCollection.find(
            eq(
                WelcomeSettings::guildId.name, guildId
            )
        ).firstOrNull()
    }


    // This will have the following commands
    // [x] /welcome firsttimers channel <channel>
    // [x] /welcome firsttimers role <role>
    // [x] /welcome firsttimers message <message>

    // [x] /welcome back channel <channel>
    // [x] /welcome back role <role>
    // [x] /welcome back message <message>

    // [x] /welcome minlevel <level>
    // [x] /welcome switch [on/off]
    // [x] /welcome status // This will send a message the current status and Welcome messages in code blocks

    suspend fun setWelcomeFirstChannel(
        event: SlashCommandInteractionEvent,
        channel: TextChannel
    ) {
        val guild = event.guild ?: return
        val guildId = guild.idLong

        // Update the settings in the database
        welcomeSettingsCollection.updateOne(
            eq(WelcomeSettings::guildId.name, guildId),
            combine(
                setOnInsert(WelcomeSettings::guildId.name, guildId),
                Updates.set(WelcomeSettings::firstTimeChannel.name, channel.idLong)
            ),
            UpdateOptions().upsert(true)
        )
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "Welcome for first timers channel updated successfully!"
            }
        ).queue()
    }

    suspend fun setWelcomeFirstRole(
        event: SlashCommandInteractionEvent,
        role: Role
    ) {
        val guild = event.guild ?: return
        val guildId = guild.idLong

        // Update the settings in the database
        welcomeSettingsCollection.updateOne(
            eq(WelcomeSettings::guildId.name, guildId),
            combine(
                setOnInsert(WelcomeSettings::guildId.name, guildId),
                Updates.set(WelcomeSettings::firstTimeRole.name, role.idLong)
            ),
            UpdateOptions().upsert(true)
        )
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "Welcome for first timers role updated successfully!"
            }
        ).queue()
    }

    suspend fun setWelcomeFirstMessage(
        event: SlashCommandInteractionEvent,
        message: String
    ) {
        val guild = event.guild ?: return
        val guildId = guild.idLong

        // Update the settings in the database
        welcomeSettingsCollection.updateOne(
            eq(WelcomeSettings::guildId.name, guildId),
            combine(
                setOnInsert(WelcomeSettings::guildId.name, guildId),
                Updates.set(WelcomeSettings::firstTimeMessage.name, message)
            ),
            UpdateOptions().upsert(true)
        )
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "Welcome for first timers message updated successfully!"
            }
        ).queue()
    }

    suspend fun setWelcomeBackChannel(
        event: SlashCommandInteractionEvent,
        channel: TextChannel
    ) {
        val guild = event.guild ?: return
        val guildId = guild.idLong

        // Update the settings in the database
        welcomeSettingsCollection.updateOne(
            eq(WelcomeSettings::guildId.name, guildId),
            combine(
                setOnInsert(WelcomeSettings::guildId.name, guildId),
                Updates.set(WelcomeSettings::returningChannel.name, channel.idLong)
            ),
            UpdateOptions().upsert(true)
        )
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "Welcome back channel updated successfully!"
            }
        ).queue()
    }


    suspend fun setWelcomeBackRole(
        event: SlashCommandInteractionEvent,
        role: Role
    ) {
        val guild = event.guild ?: return
        val guildId = guild.idLong

        // Update the settings in the database
        welcomeSettingsCollection.updateOne(
            eq(WelcomeSettings::guildId.name, guildId),
            combine(
                setOnInsert(WelcomeSettings::guildId.name, guildId),
                Updates.set(WelcomeSettings::returningRole.name, role.idLong)
            ),
            UpdateOptions().upsert(true)
        )
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "Welcome back role updated successfully!"
            }
        ).queue()
    }

    suspend fun setWelcomeBackMessage(
        event: SlashCommandInteractionEvent,
        message: String
    ) {
        val guild = event.guild ?: return
        val guildId = guild.idLong

        // Update the settings in the database
        welcomeSettingsCollection.updateOne(
            eq(WelcomeSettings::guildId.name, guildId),
            combine(
                setOnInsert(WelcomeSettings::guildId.name, guildId),
                Updates.set(WelcomeSettings::returningMessage.name, message)
            ),
            UpdateOptions().upsert(true)
        )
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "Welcome back message updated successfully!"
            }
        ).queue()
    }

    suspend fun setMinLevel(
        event: SlashCommandInteractionEvent,
        level: Int
    ) {
        val guild = event.guild ?: return
        val guildId = guild.idLong

        // Update the settings in the database
        welcomeSettingsCollection.updateOne(
            eq(WelcomeSettings::guildId.name, guildId),
            combine(
                setOnInsert(WelcomeSettings::guildId.name, guildId),
                Updates.set(WelcomeSettings::minLevel.name, level)
            ),
            UpdateOptions().upsert(true)
        )
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "Minimum level updated to $level."
            }
        ).queue()
    }

    suspend fun status(event: SlashCommandInteractionEvent) {
        welcomeSettingsCollection.find(
            eq(
                WelcomeSettings::guildId.name, event.guild?.idLong
            )
        ).firstOrNull()?.let {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE
                    text = "Welcome messages are currently ${if (it.status) "enabled" else "disabled"}.\n" +
                            "The messages are:\n" +
                            "First time message: `${it.firstTimeMessage}`\n" +
                            "Returning message: `${it.returningMessage}`" +
                            appendRelevantReplacementsInfo(text, event.toWrapper())
                }
            ).queue()
        } ?: event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "**Welcome Pack** is not set up for this server yet.\n" +
                        "Please set it up using `/welcome firsttimers channel <channel>` and `/welcome firsttimers message <message>`."
            }
        ).queue()
    }

    suspend fun switch(event: SlashCommandInteractionEvent, mode: Boolean) {
        val guild = event.guild ?: return
        val guildId = guild.idLong

        // Update the settings in the database
        welcomeSettingsCollection.updateOne(
            eq(WelcomeSettings::guildId.name, guildId),
            combine(
                setOnInsert(WelcomeSettings::guildId.name, guildId),
                Updates.set(WelcomeSettings::status.name, mode)
            ),
            UpdateOptions().upsert(true)
        )
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "Welcome messages are now ${if (mode) "enabled" else "disabled"}."
            }
        ).queue()
    }

    suspend fun findAuthor(event: MessageReceivedEvent){
        //script that find the author of a message ID
        val msg = event.channel.retrieveMessageById("1356407306556014592").await()
        val author = msg.author
        event.channel.sendMessage("The author of the message is ${author.asTag}\n" +
                "Their ID is ${author.idLong}").await()
    }

    // I need now to write the function that decides whether the user is a first time member or a returning member
    // the member needs to have at least more than 0 exp to be considered a returning member
    // a long time ago, users would be considered returning if they had at least 1 level (100 exp)
    // and send the message accordingly
    suspend fun decideWelcomeType(event: GuildMemberJoinEvent) {
        val member = event.member
        val guild = member.guild
        // Check if WelcomePack is disabled or does not exist
        val settings = getSettings(guild.idLong) ?: return

        // If we give the returning role to a member that was kicked or unbanned in the
        //  server, they'll have more access than they should.
        // As such, we will have to add a check to see if the member is banned or kicked.
        // If so no role will be given and no message will be sent.
        if (
            guild.idLong == SONIC_THE_HEDGEHOG
            &&
            (EntryController.exceptionMembersCollection.find(
                and(
                    eq(EntryController.ExceptionMember::guildId.name, guild.idLong),
                    eq(EntryController.ExceptionMember::id.name, member.idLong)
                )
            ).firstOrNull() != null)
        ) {
            // this means the member was kicked or unbanned
            // we will not do anything about it
            return
        }
        // Check if the user is a returning member
        val isReturning = isReturningMember(member)
        if (isReturning) {
            // Give corresponding role
            settings.returningRole?.let { roleId ->
                val role = guild.getRoleById(roleId) ?: return
                guild.addRoleToMember(member, role).queue()
            }
            sendWelcomeBackMessage(member, settings)
        } else {
            // Give corresponding role
            settings.firstTimeRole?.let { roleId ->
                val role = guild.getRoleById(roleId) ?: return
                guild.addRoleToMember(member, role).queue()
            }
            sendWelcomeMessage(member, settings)
        }
    }



    private suspend fun isReturningMember(member: Member): Boolean {
        // Assuming we have access to the Ranks functionality from other parts of the code
        val guildId = member.guild.idLong
        val memberId = member.idLong

        val rankMember = Ranks.ranksMemberCollection.find(
            and(
                eq(Ranks.RankMember::guildId.name, guildId),
                eq(Ranks.RankMember::id.name, memberId
            )
        )).firstOrNull() ?: return false
        // If it's null, the member is new.
        Ranks.getMemberExp(memberId, guildId)
        val welcomeSettings = getSettings(guildId)
        // Check if the member meets the minimum rank requirement.
        // 0 means 0 exp but must exist in the database
        // However, minLevel is a level, and we need as EXP
        val minExp = Ranks.calculateExp(welcomeSettings?.minLevel ?: 0)
        return rankMember.exp >= minExp
    }

    fun sendWelcomeBackMessage(member: Member, settings: WelcomeSettings) {
        val channelId = settings.returningChannel
        val message = settings.returningMessage
        if (channelId != null) {
            val channel = member.guild.getTextChannelById(channelId)
            if (channel != null && message.isNotEmpty()) {
                val formattedMessage =
                    MessageReplacements.applyReplacements(message, EventContext(member.user, member.guild, member))
                channel.sendMessage(formattedMessage).queue()
            }
        }
    }

    fun sendWelcomeMessage(member: Member, settings: WelcomeSettings) {
        val channelId = settings.firstTimeChannel
        val message = settings.firstTimeMessage
        if (channelId != null) {
            val channel = member.guild.getTextChannelById(channelId)
            if (channel != null && message.isNotEmpty()) {
                val formattedMessage = MessageReplacements.applyReplacements(
                    message,
                    EventContext(member.user, member.guild, member)
                )
                channel.sendMessage(formattedMessage).queue()
            }
        }
    }

    // The returning channel ID is 808426957553139762


}
