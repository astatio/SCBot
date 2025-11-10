import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.client.model.Updates.set
import com.mongodb.client.model.Updates.setOnInsert
import commands.Ticketing
import commands.Ticketing.ticketingSettings
import commands.WelcomePack
import commands.ranks.Ranks
import commands.ranks.Ranks.giveRankRoles
import commands.ranks.Ranks.ranksCollection
import dev.minn.jda.ktx.coroutines.await
import helpers.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.yield
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.audit.ActionType
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.events.guild.GuildAuditLogEntryCreateEvent
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.minutes


object EntryController {

    //todo: This is a s/SonicTheHedgehog exclusive function. Make it so that other guilds cant turn it on.
    // This needs to be moved out of here and into the SCBot project itself.

    data class EntrySettings(
        val status: Boolean, //This is for the Entry Controller. The job run on r/SonicTheHedgehog
        val mimeJob: Boolean,
        val returnJob: Boolean,
        val guildId: Long
    ) {
        companion object {
            fun create(
                status: Boolean = false,
                mimeJob: Boolean = false,
                returnJob: Boolean = false,
                guildId: Long
            ) = EntrySettings(
                status,
                mimeJob,
                returnJob,
                guildId
            )
        }
    }

    data class ExceptionMember(
        val id: Long,
        val guildId: Long
    )


    private val entrySettingsCollection = localDatabase.getCollection<EntrySettings>("entrySettings")
    val exceptionMembersCollection = localDatabase.getCollection<ExceptionMember>("exceptionMember")

    //todo: make it so that it can be turned off and on

    //todo: idk whats the purpose of this so ill leave it uncalled
    suspend fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
        val guildId = event.guild.idLong
        val member = event.member
        if (guildId == SONIC_THE_HEDGEHOG) {
            if (readFromDatabase(member.idLong, guildId)) {
                //apply an exception role
                member.guild.addRoleToMember(member, event.guild.getRoleById(EXCEPTION_ROLE)!!)
                    .queue() //This is the talk role
            }
        }
    }

    //This needs to remove all other roles. If it has NOIMAGES_ROLE, that one should be kept.
    suspend fun giveExceptionRole(event: MessageReceivedEvent) {
        if (
            !permissionCheckMessageless(event.member!!, Permission.MANAGE_PERMISSIONS)
            &&
            (event.member!!.roles.find { it.idLong == MEDIATORS_ROLE || it.idLong == GAIA_GATE_MANAGERS_ROLE } == null)
        ) return

        val targetMembers = validateManyMembers(event)

        commandManyMembersValidation(
            event,
            ifMentioned = EntryController::giveExceptionRoleMentioned,
            ifUserID = EntryController::giveExceptionRoleID,
            ifNoMentionNorID =
                suspend {
                    event.message.replyEmbeds(
                        NeoSuperEmbed {
                            type = SuperEmbed.ResultType.SIMPLE_ERROR
                            text = "You need to mention a user or provide a user ID."
                        }).queue()
                }
        )

        // The user must be assigned the role before it's possible to create a ticket on their behalf.
        // But, first, we need to check if Ticketing has a channel to create the ticket in.
        if (ticketingSettings.find(eq(Ticketing.TicketingSettings::guildId.name, event.guild.idLong))
                .firstOrNull() == null
        ) {
            event.message.replyEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "Ticketing is not set up on this server."
                }).queue()
            return
        }

        targetMembers.forEach {
            Ticketing.createTicketOnBehalf(
                event = event.toWrapper(),
                targetMember = it,
                assigneeMember = event.member!!,
                txtChannel = event.guild.getTextChannelById(1338214329157419058)!!, // This is the exception members channel
            )
        }
    }

    suspend fun giveExceptionRoleMentioned(event: MessageReceivedEvent, members: List<Member>) {
        val guild = event.guild
        val excptRole = guild.getRoleById(EXCEPTION_ROLE) ?: guild.getRoleById(717391817192243240L)!!
        val success = mutableListOf<String>()
        val failures = mutableListOf<String>()
        //send a message telling the user that its processing
        val msg = event.message.replyEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "Processing... Please wait."
            }).await()
        var firstThrow: Throwable? = null
        members.forEach { member ->
            try {
                // Remove only member role
                // Ranks.getRankRoles(member).forEach { role -> guild.removeRoleFromMember(member, role).await() }
                guild.removeRoleFromMember(member, guild.getRoleById(MEMBER_ROLE)!!).await()
                guild.addRoleToMember(member, excptRole).await()
                success.add(member.asMention)
            } catch (e: Exception) {
                firstThrow = e
                failures.add(member.asMention)
            }
        }
        success.ifNotEmpty {
            msg.editMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "Successfully gave ${
                        success.joinToString(
                            ", "
                        )
                    } the Talk role."
                }).queue()
        }
        failures.ifNotEmpty {
            msg.editMessageEmbeds(
                throwEmbed(
                    firstThrow,
                    "Failed to give ${
                        failures.joinToString(
                            ", "
                        )
                    } the Talk role."
                )
            ).queue()
        }
    }


    suspend fun giveExceptionRoleID(event: MessageReceivedEvent, userIDs: List<String>) {
        val guild = event.guild
        val excptRole = guild.getRoleById(EXCEPTION_ROLE)!!
        val success = mutableListOf<String>()
        val failures = mutableListOf<String>()
        //send a message telling the user that its processing
        val msg = event.message.replyEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "Processing... Please wait."
            }).await()
        var firstThrow: Throwable? = null
        userIDs.forEach { userId ->
            try {
                val member = guild.getMemberById(userId)
                if (member != null) {
                    // Remove only member role
                    // Ranks.getRankRoles(member).forEach { role -> guild.removeRoleFromMember(member, role).await() }
                    guild.removeRoleFromMember(member, guild.getRoleById(MEMBER_ROLE)!!).await()
                    guild.addRoleToMember(member, excptRole).await()
                    success.add(member.asMention)
                } else {
                    failures.add(userId)
                }
            } catch (e: Exception) {
                firstThrow = e
                failures.add(userId)
            }
        }
        success.ifNotEmpty {
            msg.editMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "Successfully gave ${
                        success.joinToString(
                            ", "
                        )
                    } the Talk role."
                }).queue()
        }
        failures.ifNotEmpty {
            msg.editMessageEmbeds(
                throwEmbed(
                    firstThrow,
                    "Failed to give ${
                        failures.joinToString(
                            ", "
                        )
                    } the Talk role."
                )
            ).queue()
        }
    }

    suspend fun onGuildAuditLogEntryCreate(event: GuildAuditLogEntryCreateEvent) {
        //check if it was a kick
        if (event.entry.type == ActionType.KICK) {
            if (event.guild.idLong == SONIC_THE_HEDGEHOG) {
                addToDatabase(event.entry.targetIdLong, event.guild.idLong)
            }
        }
    }

    suspend fun onGuildUnban(event: GuildUnbanEvent) {
        // Unbanned users are meant to become members again, so they should be added to Returning Members.
        if (event.guild.idLong == SONIC_THE_HEDGEHOG) {
            addToDatabase(event.user.idLong, event.guild.idLong)
        }
    }

    // I thought this didn't work, but it does. The member must have some XP or else they'll always end up in gaia gate
    suspend fun addToDatabase(userId: Long, guildId: Long) {
        //Check if entry controller is on. If it is not, return.
        if (entrySettingsCollection.find(eq(EntrySettings::guildId.name, guildId))
                .firstOrNull()?.status == false
        ) return
        // Members can be kicked then banned and unbanned. That would create duplicates in the database or errors. For that reason, we need to upsert.
        exceptionMembersCollection.updateOne(
            and(
                eq(ExceptionMember::guildId.name, guildId),
                eq(ExceptionMember::id.name, userId)
            ),
            Updates.combine(
                setOnInsert(ExceptionMember::id.name, userId),
                setOnInsert(ExceptionMember::guildId.name, guildId)
            ),
            UpdateOptions().upsert(true)
        )
    }

    suspend fun readFromDatabase(userId: Long, guildId: Long): Boolean {
        return try {
            exceptionMembersCollection.find(
                and(
                    eq(ExceptionMember::guildId.name, guildId),
                    eq(ExceptionMember::id.name, userId)
                )
            ).firstOrNull()?.let {
                return true
            } ?: false
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    suspend fun removeFromDatabase(userId: Long, guildId: Long) {
        exceptionMembersCollection.deleteOne(
            and(
                eq(ExceptionMember::guildId.name, guildId),
                eq(ExceptionMember::id.name, userId)
            )
        )
    }


    //When a user get the Member role, their ID is checked in the database.
    // If they are in the database, they are removed from the database.
    suspend fun onGuildMemberRoleAdd(event: GuildMemberRoleAddEvent) {
        if (event.guild.idLong != SONIC_THE_HEDGEHOG) return
        if (event.roles.any { it.idLong == MEMBER_ROLE }) {
            removeFromDatabase(event.member.idLong, event.guild.idLong)
        }
    }

    suspend fun switch(event: SlashCommandInteractionEvent, mode: Boolean) {
        if (event.guild!!.idLong != SONIC_THE_HEDGEHOG) {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "This command is restricted. This server is not allowed to use this command."
                }).queue()
            return
        }
        val guildId = event.guild!!.idLong
        try {
            entrySettingsCollection.updateOne(
                filter = eq(EntrySettings::guildId.name, guildId),
                update = set(EntrySettings::status.name, mode),
            )
        } catch (e: Throwable) {
            e.printStackTrace()
            event.hook.sendMessageEmbeds(
                throwEmbed(e)
            ).queue()
            return
        }
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "**Entry Controller** is now ${if (mode) "ON" else "OFF"}"
            }).queue()
    }

    suspend fun mimeCheckSwitch(event: SlashCommandInteractionEvent, mode: Boolean) {
        if (event.guild!!.idLong != SONIC_THE_HEDGEHOG) {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "This command is restricted. This server is not allowed to use this command."
                }).queue()
            return
        }
        val guildId = event.guild!!.idLong
        try {
            entrySettingsCollection.updateOne(
                filter = eq(EntrySettings::guildId.name, guildId),
                update = set(EntrySettings::mimeJob.name, mode),
            )
        } catch (e: Throwable) {
            e.printStackTrace()
            event.hook.sendMessageEmbeds(
                throwEmbed(e)
            ).queue()
            return
        }
        val toggleAsString: String = if (mode) {
            mimeJob?.start()
            "ON"
        } else {
            mimeJob?.stop()
            "OFF"
        }
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "**Mime Check** is now $toggleAsString"
            }).queue()
    }

    suspend fun returnCheckSwitch(event: SlashCommandInteractionEvent, mode: Boolean) {
        if (event.guild!!.idLong != SONIC_THE_HEDGEHOG) {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "This command is restricted. This server is not allowed to use this command."
                }).queue()
            return
        }
        val guildId = event.guild!!.idLong
        try {
            entrySettingsCollection.updateOne(
                filter = eq(EntrySettings::guildId.name, guildId),
                update = set(EntrySettings::returnJob.name, mode),
            )
        } catch (e: Throwable) {
            e.printStackTrace()
            event.hook.sendMessageEmbeds(
                throwEmbed(e)
            ).queue()
            return
        }
        val toggleAsString: String = if (mode) {
            returnJob?.start()
            "ON"
        } else {
            returnJob?.stop()
            "OFF"
        }
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "**Return Check** is now $toggleAsString"
            }).queue()
    }

    /**
     * This handles the exception members.
     *
     * @return true if the member was an exception member and got handled, false otherwise.
     */
    private suspend fun handleExceptionMember(
        member: Member,
        guild: Guild
    ): Boolean {
        val memberId = member.idLong
        val guildId = guild.idLong
        if (guild.idLong == SONIC_THE_HEDGEHOG) {
            val exp = Ranks.getMemberExp(memberId, guildId)
            if (exp > 0L) {
                // The member, in this case, is a returning member
                // but if they were kicked or were unbanned, we don't want them to receive the return role. instead
                // they will get the exception role
                // they will only be cleansed when !g is called on them

                //we want a ticket to be created for them
                if (exceptionMembersCollection.find(
                        and(
                            eq(ExceptionMember::guildId.name, guildId),
                            eq(ExceptionMember::id.name, memberId)
                        )
                    ).firstOrNull() != null
                ) {
                    var attempts = 0

                    while (true) {
                        try {
                            guild.getRoleById(EXCEPTION_ROLE)?.also {
                                guild.addRoleToMember(member, it).await() // Use await() instead of queue()
                            }
			    break
                        } catch (e: Exception) {
                            attempts++
                            if (attempts == 1) {
                                logger.error(e) {
                                    "Failed to add exception role to returning user ID ${member.idLong}. Retrying 1 more time..." // This is the first attempt.
                                }
                                delay(2000)
                            } else {
                                logger.error {
                                    "Failed to add exception role to returning user ID ${member.idLong}. Will not try again until they rejoin."
                                }
                                break
                            }
                        }
                    }
                    // First try to add the role and wait for completion
                    guild.getRoleById(EXCEPTION_ROLE)?.also {
                        guild.addRoleToMember(member, it).await() // Use await() instead of queue()
                    }

                    // Create a ticket only if role assignment succeeded
                    Ticketing.TicketBuilder(
                        executorMember = guild.selfMember,
                        ticketDescription = "This user was kicked or unbanned and is now returning to the server.",
                        shortSubject = member.user.name,
                        targetMember = member,
                        ticketingChannel = guild.getTextChannelById(1338214329157419058)!!, // This is the exception members channel
                    ).classic()
                    return true
                }
            }
        }
        return false
    }

    // Decides whether to give the member the Returning Member role or the Mimes role
    // NOTE: This was previously in Ranks.
    suspend fun runEntryController(event: GuildMemberJoinEvent) {
        if (event.user.isBot) return
        if (event.guild.idLong != SONIC_THE_HEDGEHOG) return // only run on the r/Sonic server
        val guildId = event.guild.idLong
        entrySettingsCollection.find(eq(EntrySettings::guildId.name, guildId)).firstOrNull().let {
            if (it == null || !it.status) return
        }
        ranksCollection.find(eq(Ranks.RankSettings::guildId.name, guildId)).firstOrNull().let {
            if (it == null || !it.status) return
        }
        handleExceptionMember(
            member = event.member,
            guild = event.guild
        )
    }

    // Return check runs every 5 minutes (300 000 ms)
    //todo: need a way to print something when an error occurs
    suspend fun returnCheckJob(jda: JDA): JobProducerScheduler {
        return JobProducerScheduler(JobProducer({
            val guild = jda.getGuildById(SONIC_THE_HEDGEHOG) ?: return@JobProducer
            val returnRole = guild.getRoleById(RETURN_ROLE)!!
            val memberRole = guild.getRoleById(MEMBER_ROLE)!!
            guild.findMembersWithRoles(returnRole).await().forEach { member ->
                if ( //if timeJoined is more than 5 minutes ago and has no other roles
                    member.timeJoined.toInstant().isBefore(
                        Instant.now().minus(
                            5,
                            ChronoUnit.MINUTES
                        )
                    ) && member.roles.size == 1
                ) {
                    //replace the role with the member role
                    member.guild.removeRoleFromMember(member, returnRole).await()
                    member.guild.addRoleToMember(member, memberRole).await()
                    giveRankRoles(member, guild)
                }
            }
        }, 5.minutes, 300000))
    }

    // when doing "!g [user_id]" or "!g [@mention]", the person gets its Mime role replaced with the Member role.
    // if the person has a returning member role, it gets replaced with the Member role plus their rank roles.
    // this is exclusive to the r/Sonic server.
    suspend fun turnToMember(event: MessageReceivedEvent) {
        //if they don't have the manage permission, nor the helpers role, nor gaia gate verify
        if (
            !permissionCheckMessageless(event.member!!, Permission.MANAGE_PERMISSIONS)
            &&
            !event.member!!.roles.any { it.idLong == MEDIATORS_ROLE || it.idLong == GAIA_GATE_MANAGERS_ROLE }
        ) return
        commandManyMembersValidation(
            event,
            ifMentioned = EntryController::turnToMemberMentioned,
            ifUserID = EntryController::turnToMemberID,
            ifNoMentionNorID =
                suspend {
                    event.message.replyEmbeds(
                        NeoSuperEmbed {
                            type = SuperEmbed.ResultType.SIMPLE_ERROR
                            text = "You need to mention a user or provide a user ID."
                        }).queue()
                }
        )
    }

    suspend fun turnToMemberMentioned(event: MessageReceivedEvent, members: List<Member>) {
        // Check if the member has a Mime role or if it has a Returning Member role.
        val guild = event.guild
        val memberRole = guild.getRoleById(MEMBER_ROLE)!!
        val xptRole = guild.getRoleById(EXCEPTION_ROLE)!!

        val successMime = mutableListOf<String>()
        val successReturn = mutableListOf<String>()
        val failures = mutableListOf<String>()

        members.forEach { member ->
            kotlin.runCatching {
                processMember(member, guild, memberRole, xptRole)
            }.onSuccess {
                when (it) {
                    MemberResult.Mime -> {
                        successMime.add(member.asMention)
                    }

                    MemberResult.Return -> {
                        successReturn.add(member.asMention)
                    }

                    MemberResult.Failure -> {
                        failures.add(member.asMention)
                    }
                }
            }.onFailure {
                failures.add(member.asMention)
            }
        }

        successMime.ifNotEmpty {
            event.message.replyEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "Successfully turned ${
                        successMime.joinToString(
                            ", "
                        )
                    } into member(s)."
                }).queue()
        }
        successReturn.ifNotEmpty {
            event.message.replyEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "Successfully turned ${
                        successReturn.joinToString(
                            ", "
                        )
                    } into member(s) and gave their rank roles back."
                }).queue()
        }
        failures.ifNotEmpty {
            event.message.replyEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "Failed to turn ${
                        failures.joinToString(
                            ", "
                        )
                    } into member(s). They don't have the Mime, Returning Member or Talk role."
                }).queue()
        }
    }

    suspend fun turnToMemberID(event: MessageReceivedEvent, userIDs: List<String>) {
        // Check if the member has a Mime role or if it has a Returning Member role.
        val guild = event.guild
        val memberRole = guild.getRoleById(MEMBER_ROLE)!!
        val xeptRole = guild.getRoleById(EXCEPTION_ROLE)!!

        val successMime = mutableListOf<String>()
        val successReturn = mutableListOf<String>()
        val failures = mutableListOf<String>()

        userIDs.forEach { userId ->
            try {
                val member = guild.getMemberById(userId)
                if (member != null) {
                    kotlin.runCatching {
                        processMember(member, guild, memberRole, xeptRole)
                    }.onSuccess {
                        when (it) {
                            MemberResult.Mime -> {
                                successMime.add(member.asMention)
                            }

                            MemberResult.Return -> {
                                successReturn.add(member.asMention)
                            }

                            MemberResult.Failure -> {
                                failures.add(member.asMention)
                            }
                        }
                    }.onFailure {
                        failures.add(member.asMention)
                    }
                } else {
                    failures.add(userId)
                }
            } catch (e: NumberFormatException) {
                event.message.replyEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "$userId is not a valid user ID. Please try again."
                    }).queue()
                return
            }
        }
        successMime.ifNotEmpty {
            event.message.replyEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "Successfully turned ${
                        successMime.joinToString(
                            ", "
                        )
                    } into member(s)."
                }).queue()
        }
        successReturn.ifNotEmpty {
            event.message.replyEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "Successfully turned ${
                        successReturn.joinToString(
                            ", "
                        )
                    } into member(s) and gave their rank roles back."
                }).queue()
        }
        failures.ifNotEmpty {
            event.message.replyEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "Failed to turn ${
                        failures.joinToString(
                            ", "
                        )
                    } into member(s). They don't have the Mime, Returning Member or Exception role."
                }).queue()
        }
    }

    enum class MemberResult {
        Mime, Return, Failure
    }

    private suspend fun processMember(member: Member, guild: Guild, memberRole: Role, xeptRole: Role?): MemberResult {
        val mimeRole = guild.getRoleById("372260165493325824")!!
        val returnRole = guild.getRoleById(RETURN_ROLE)!!
        return when {
            member.roles.contains(mimeRole) -> {
                guild.removeRoleFromMember(member, mimeRole).queue()
                guild.addRoleToMember(member, memberRole).queue()
                if (xeptRole != null && member.roles.contains(xeptRole)) {
                    guild.removeRoleFromMember(member, xeptRole).queue()
                }
                MemberResult.Mime
            }

            member.roles.contains(returnRole) -> {
                guild.removeRoleFromMember(member, returnRole).queue()
                guild.addRoleToMember(member, memberRole).queue()
                giveRankRoles(member, guild)
                if (xeptRole != null && member.roles.contains(xeptRole)) {
                    guild.removeRoleFromMember(member, xeptRole).queue()
                }
                MemberResult.Return
            }

            member.roles.contains(xeptRole) -> {
                if (xeptRole != null) {
                    //cleanse the user from the "wasKickedOrUnbanned"
                    exceptionMembersCollection.findOneAndDelete(
                        and(
                            eq(ExceptionMember::guildId.name, guild.idLong),
                            eq(ExceptionMember::id.name, member.idLong)
                        )
                    )
                    guild.removeRoleFromMember(member, xeptRole).queue()
                    guild.addRoleToMember(member, memberRole).queue()
                    giveRankRoles(member, guild)
                }
                MemberResult.Return
            }

            else -> {
                MemberResult.Failure
            }
        }
    }


    // Mime check runs every 20 minutes (1200000 ms)
    //todo: need a way to print something when an error occurs
    suspend fun mimeCheckJob(jda: JDA): JobProducerScheduler {
        return JobProducerScheduler(JobProducer({
            val guild = jda.getGuildById(SONIC_THE_HEDGEHOG) ?: return@JobProducer
            val mimesRole = guild.getRoleById("372260165493325824")!!
            val memberRole = guild.getRoleById(MEMBER_ROLE)!!
            val mimes = guild.getMembersWithRoles(mimesRole)
            mimes.forEach { member ->
                if ( //if timeJoined is more than 24 hours ago and has no other roles
                    member.timeJoined.toInstant().isBefore(
                        Instant.now().minus(
                            1,
                            ChronoUnit.DAYS
                        )
                    ) && member.roles.size == 1
                ) {
                    //replace the role with the member role
                    member.guild.removeRoleFromMember(member, mimesRole).await()
                    member.guild.addRoleToMember(member, memberRole).await()
                }
            }
        }, 20.minutes, 1200000))
    }

    // Retrieves all Mimes and turns them into members. Requires the Manage Permissions permission
    suspend fun flush(event: SlashCommandInteractionEvent) {
        if (!permissionCheck(event.toWrapper(), Permission.MANAGE_PERMISSIONS)) return
        if (event.guild!!.idLong != SONIC_THE_HEDGEHOG) return // only run on the r/Sonic server
        val hook = event.hook
        hook.sendMessage("Emptying... This may take a while.").mentionRepliedUser(false).await()
        val guild = event.guild!!
        val mimesRole = guild.getRoleById(372260165493325824)!!
        val membersRole = guild.getRoleById(MEMBER_ROLE)!!
        val mimes = guild.findMembersWithRoles(mimesRole)
        var membersMigrated = 0
        val failedMembers = mutableListOf<String>()
        try {
            mimes.await().forEach {
                try {
                    guild.removeRoleFromMember(it, mimesRole).await()
                    guild.addRoleToMember(it, membersRole).await()
                    membersMigrated++
                } catch (e: Exception) {
                    membersMigrated--
                    failedMembers.add(it.id)
                }
            }
        } catch (e: Exception) {
            hook.editOriginal("Failed to empty the Mimes. Please ensure I have enough permissions and try again.")
                .await()
            return
        }
        hook.editOriginal(
            "Done. $membersMigrated mime(s) are now member(s). Failed to migrate ${failedMembers.size} members: ${
                failedMembers.joinToString("\n")
            }"
        ).await()
    }

    suspend fun flush(event: MessageReceivedEvent) {
        if (!permissionCheck(event.toWrapper(), Permission.MANAGE_PERMISSIONS)) return
        if (event.guild.idLong != SONIC_THE_HEDGEHOG) return // only run on the r/Sonic server
        val message = event.message
        message.reply("Emptying... This may take a while.").mentionRepliedUser(false).await()
        val guild = event.guild
        val mimesRole = guild.getRoleById(372260165493325824)!!
        val membersRole = guild.getRoleById(MEMBER_ROLE)!!
        val mimes = guild.findMembersWithRoles(mimesRole)
        var membersMigrated = 0
        val failedMembers = mutableListOf<String>()
        try {
            mimes.await().forEach {
                try {
                    guild.removeRoleFromMember(it, mimesRole).await()
                    guild.addRoleToMember(it, membersRole).await()
                    membersMigrated++
                } catch (e: Exception) {
                    membersMigrated--
                    failedMembers.add(it.id)
                }
            }
        } catch (e: Exception) {
            message.channel.sendMessage("Failed to empty the Mimes. Please ensure I have enough permissions and try again.")
                .await()
            return
        }
        message.channel.sendMessage(
            "Done. $membersMigrated mime(s) are now member(s). Failed to migrate ${failedMembers.size} members: ${
                failedMembers.joinToString("\n")
            }"
        ).await()
    }

    /**
     * Assigns the Mimes, Returning Members and Talk roles to all members without roles according to their XP.
     */
    //todo: i would like this to go to WelcomePack and have it happen from time to time or at startup so that
    // using it manually becomes unnecessary
    suspend fun rolecall(event: MessageReceivedEvent) {
        if (!permissionCheck(event.toWrapper(), Permission.MANAGE_PERMISSIONS)) return
        if (event.guild.idLong != SONIC_THE_HEDGEHOG) return // only run on the r/Sonic server
        val message = event.message
        message.reply("Assigning roles... This may take a while.").mentionRepliedUser(false).queue()

        val guild = event.guild
        val guildId = guild.idLong
        val mimeRole = guild.getRoleById(MIME_ROLE)!!
        val returningMemberRole = guild.getRoleById(RETURN_ROLE)!!
        val membersWithoutRoles = guild.loadMembers().await().filter {
            it.roles.isEmpty() && !it.user.isBot
        }
        var membersMigrated = 0
        val failedMembers = mutableListOf<String>()
        try {
            membersWithoutRoles.forEach { member ->
                try {
                    val memberId = member.idLong
                    entrySettingsCollection.find(eq(EntrySettings::guildId.name, guildId)).firstOrNull().let {
                        if (it == null || !it.status) return
                    }
                    ranksCollection.find(eq(Ranks.RankSettings::guildId.name, guildId)).firstOrNull().let {
                        if (it == null || !it.status) return
                    }

                    // The member needs to have at least more than 0 exp to be considered a returning member
                    val exp = Ranks.getMemberExp(memberId, guildId)

                    if (exp == 0L) {
                        if (guild.idLong == SONIC_THE_HEDGEHOG) {
                            guild.addRoleToMember(member, mimeRole).await()
                            WelcomePack.sendWelcomeMessage(
                                member,
                                WelcomePack.getSettings(guildId)!!
                            )
                        }
                    } else {
                        if (guild.idLong == SONIC_THE_HEDGEHOG) {
                            // handle if they are an exception
                            val isException = handleExceptionMember(member, guild)
                            if (!isException) {
                                // this means they're not exception, just regular returning members
                                guild.addRoleToMember(member, returningMemberRole).await()
                                WelcomePack.sendWelcomeBackMessage(
                                    member,
                                    WelcomePack.getSettings(guildId)!!
                                )
                            }
                        }
                    }
                    membersMigrated++
                } catch (e: Exception) {
                    membersMigrated--
                    failedMembers.add(member.id)
                }
                yield() //This will suspend the coroutine to allow other coroutines to run, preventing blocking.
            }
        } catch (e: Exception) {
            message.channel.sendMessage("Failed to assign roles. Please ensure I have enough permissions and try again.")
                .queue()
            return
        }

        message.channel.sendMessage(
            "Done. **$membersMigrated** role-less user(s) was/were assigned their appropriate roles."
                    + if (failedMembers.isNotEmpty()) " Failed to assign roles on **${failedMembers.size}** user(s): ${
                failedMembers.joinToString(
                    "\n"
                )
            }" else ""
        ).queue()
    }

    // Entry Controller is a listener, not a job hence why it doesn't need to be initialized.
    suspend fun jobInit(jda: JDA) {
        val guild = jda.getGuildById(SONIC_THE_HEDGEHOG) ?: return
        entrySettingsCollection.find(
            eq(EntrySettings::guildId.name, guild.idLong)
        ).firstOrNull().let {
            if (it != null) {
                if (it.mimeJob) {
                    mimeJob?.start()
                }
                if (it.returnJob) {
                    returnJob?.start()
                }
            }
        }
    }
}

