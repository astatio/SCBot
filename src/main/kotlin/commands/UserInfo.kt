package commands

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates.push
import commands.CommandsGeneral.getMemberNumberInGuild
import commands.Ticketing.assigneeFullString
import commands.Ticketing.status
import commands.Ticketing.statusToEmoji
import commands.ranks.Ranks
import commands.ranks.Ranks.getMemberExp
import core.ModLog
import database
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.interactions.components.Container
import dev.minn.jda.ktx.interactions.components.InlineContainer
import dev.minn.jda.ktx.interactions.components.TextDisplay
import dev.minn.jda.ktx.interactions.components.Thumbnail
import dev.minn.jda.ktx.messages.EmbedBuilder
import dev.minn.jda.ktx.messages.InlineEmbed
import dev.minn.jda.ktx.messages.send
import helpers.CacheMap
import helpers.permissionCheckMessageless
import io.ktor.util.*
import jda
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.utils.TimeFormat
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import onlineStatusEmojiRepresentation
import java.time.Instant
import java.util.*

object UserInfo {

    private val superUserinfoCache = CacheMap<Long, MessageCreateBuilder>(300000) // 5 minutes

    data class MemberPunishmentHistory(
        val id: Long, // This is the user ID of the member
        val timeoutsHistory: List<PastTimeouts>,
        val bansHistory: List<PastBans>,
        val kicksHistory: List<PastKicks>,
        val leavesHistory: List<PastLeaves>,
        val guildId: Long
    )

    data class PastTimeouts(
        val dateOfOccurrence: Instant,
        val reason: String?, // This needs to be a max of 1000 characters
        val moderatorId: Long? // The moderator who timed out the user, null if it was automated or can't be determined
    )

    data class PastBans(
        val dateOfOccurrence: Instant,
        val reason: String?, // This needs to be a max of 1000 characters
        val moderatorId: Long // The moderator who banned the user
    )

    data class PastKicks(
        val dateOfOccurrence: Instant,
        val reason: String?, // This needs to be a max of 1000 characters.
        val moderatorId: Long // The moderator who kicked the user
    )

    data class PastLeaves(
        val dateOfOccurrence: Instant // If this doesn't work it needs to be switched to Date
    )

    val memberPunishmenthistoryCollection = database.getCollection<MemberPunishmentHistory>("memberPunishmentHistory")


    //TODO: call it from Modlogs.kt
    suspend fun saveUserTimeout(
        userId: Long,
        guildId: Long,
        dateOfOccurrence: Instant,
        reason: String?,
        moderatorId: Long?
    ) {
        val pastTimeout = PastTimeouts(dateOfOccurrence, reason, moderatorId)
        val existingHistory = memberPunishmenthistoryCollection.find(
            and(
                Filters.eq(MemberPunishmentHistory::id.name, userId),
                Filters.eq(MemberPunishmentHistory::guildId.name, guildId)
            )
        ).firstOrNull()
        if (existingHistory != null) {
            memberPunishmenthistoryCollection.updateOne(
                Filters.eq(MemberPunishmentHistory::id.name, userId),
                push(MemberPunishmentHistory::timeoutsHistory.name, pastTimeout)
            )
        } else {
            // If the user doesn't have a history, create a new one
            val newHistory = MemberPunishmentHistory(
                id = userId,
                timeoutsHistory = listOf(pastTimeout),
                bansHistory = emptyList(),
                kicksHistory = emptyList(),
                leavesHistory = emptyList(),
                guildId = guildId
            )
            memberPunishmenthistoryCollection.insertOne(newHistory)
        }
    }

    suspend fun saveUserBan(
        userId: Long,
        guildId: Long,
        dateOfOccurrence: Instant,
        reason: String?,
        moderatorId: Long
    ) {
        val pastBan = PastBans(dateOfOccurrence, reason, moderatorId)
        val existingHistory = memberPunishmenthistoryCollection.find(
            and(
                Filters.eq(MemberPunishmentHistory::id.name, userId),
                Filters.eq(MemberPunishmentHistory::guildId.name, guildId)
            )
        ).firstOrNull()
        if (existingHistory != null) {
            memberPunishmenthistoryCollection.updateOne(
                Filters.eq(MemberPunishmentHistory::id.name, userId),
                push(MemberPunishmentHistory::bansHistory.name, pastBan)
            )
        } else {
            // If the user doesn't have a history, create a new one
            val newHistory = MemberPunishmentHistory(
                id = userId,
                timeoutsHistory = emptyList(),
                bansHistory = listOf(pastBan),
                kicksHistory = emptyList(),
                leavesHistory = emptyList(),
                guildId = guildId
            )
            memberPunishmenthistoryCollection.insertOne(newHistory)
        }
    }

    //todo: call it from Modlogs.kt
    suspend fun saveUserKick(
        userId: Long,
        guildId: Long,
        dateOfOccurrence: Instant,
        reason: String?,
        moderatorId: Long
    ) {
        val pastKick = PastKicks(dateOfOccurrence, reason, moderatorId)
        val existingHistory = memberPunishmenthistoryCollection.find(
            and(
                Filters.eq(MemberPunishmentHistory::id.name, userId),
                Filters.eq(MemberPunishmentHistory::guildId.name, guildId)
            )
        ).firstOrNull()
        if (existingHistory != null) {
            memberPunishmenthistoryCollection.updateOne(
                Filters.eq(MemberPunishmentHistory::id.name, userId),
                push(MemberPunishmentHistory::kicksHistory.name, pastKick)
            )
        } else {
            // If the user doesn't have a history, create a new one
            val newHistory = MemberPunishmentHistory(
                id = userId,
                timeoutsHistory = emptyList(),
                bansHistory = emptyList(),
                kicksHistory = listOf(pastKick),
                leavesHistory = emptyList(),
                guildId = guildId
            )
            memberPunishmenthistoryCollection.insertOne(newHistory)
        }
    }

    //modlogs will call these functions to store the data in the database
    //todo: call it from Modlogs.kt
    suspend fun saveUserLeave(
        userId: Long,
        guildId: Long,
        dateOfOcurrance: Instant
    ) {
        val pastLeave = PastLeaves(dateOfOcurrance)
        val existingHistory = memberPunishmenthistoryCollection.find(
            and(
                Filters.eq(MemberPunishmentHistory::id.name, userId),
                Filters.eq(MemberPunishmentHistory::guildId.name, guildId)
            )
        ).firstOrNull()
        if (existingHistory != null) {
            memberPunishmenthistoryCollection.updateOne(
                Filters.eq(MemberPunishmentHistory::id.name, userId),
                push(MemberPunishmentHistory::leavesHistory.name, pastLeave)
            )
        } else {
            // If the user doesn't have a history, create a new one
            val newHistory = MemberPunishmentHistory(
                id = userId,
                timeoutsHistory = emptyList(),
                bansHistory = emptyList(),
                kicksHistory = emptyList(),
                leavesHistory = listOf(pastLeave),
                guildId = guildId
            )
            memberPunishmenthistoryCollection.insertOne(newHistory)
        }
    }



    suspend fun userinfoUser(event: GenericCommandInteractionEvent, theUser: User) {
        var theUserAsMember: Member? = null
        var footerAddOn = ""
        var rankInfo: String? = null
        if (event.isFromGuild) {
            try {
                theUserAsMember = event.guild?.retrieveMember(theUser)?.await()
            } catch (_: Throwable) {
            } // If it throws, we can continue without it.
        }
        val userProfile = theUser.retrieveProfile().await()

        val emoji = onlineStatusEmojiRepresentation[theUserAsMember?.onlineStatus]

        val userinfoMessage = EmbedBuilder {
            title = if (emoji != null && emoji != "null") "$emoji ${theUser.effectiveName}" else theUser.effectiveName
            color = userProfile.accentColorRaw
            thumbnail = theUser.effectiveAvatarUrl
            description = "${theUser.asMention}\n${theUser.name}"
        }

        if (theUserAsMember != null) {
            userinfoMessage.title = if (emoji != null && emoji != "null") "$emoji ${theUserAsMember.effectiveName}" else theUserAsMember.effectiveName
            userinfoMessage.thumbnail = theUserAsMember.effectiveAvatarUrl
            footerAddOn = " • Member #${getMemberNumberInGuild(event, theUser)}"
        }


        context(theUser, event.guild!!)
        {
            userinfoMessage.addCreationDate()
            if (theUserAsMember != null) context(theUserAsMember) { userinfoMessage.addJoinDate() }
            userinfoMessage.addLastTime()
            userinfoMessage.addRankInfo()
            if (theUserAsMember != null) context(theUserAsMember) {  userinfoMessage.addBoostDate() }
        }

        // Add user activities/presence
        theUserAsMember?.activities?.let { activities ->
            if (activities.isNotEmpty()) {
                val activitiesString = activities
                    .mapNotNull { activity ->
                        val activityName = activity.name
                        when (activity.type) {
                            net.dv8tion.jda.api.entities.Activity.ActivityType.LISTENING -> activityName.let { "Listening to $it" }
                            net.dv8tion.jda.api.entities.Activity.ActivityType.PLAYING -> activityName.let { "Playing $it" }
                            net.dv8tion.jda.api.entities.Activity.ActivityType.STREAMING -> {
                                activityName.let {
                                    var streamText = "Streaming $it"
                                    activity.url?.let { url -> streamText += " ($url)" }
                                    streamText
                                }
                            }

                            net.dv8tion.jda.api.entities.Activity.ActivityType.WATCHING -> activityName.let { "Watching $it" }
                            net.dv8tion.jda.api.entities.Activity.ActivityType.COMPETING -> activityName.let { "Competing in $it" }
                            else -> activityName.takeIf { it.isNotBlank() } // Default for other types
                        }
                    }
                    .joinToString("\n")

                if (activitiesString.isNotBlank()) {
                    userinfoMessage.field {
                        this.name = "Activities"
                        this.value = activitiesString
                        this.inline = false
                    }
                }
            }
        }

        // Only access guild-related data if we're in a guild
        if (event.isFromGuild) {
            // check if the nametracker is on
            if (NameTracker.getNameHistoryStatus(event.guild!!.idLong)){
                val nameHistory = NameTracker.getNames(theUser.idLong)
                if (nameHistory.isNotEmpty()) {
                    userinfoMessage.field {
                        name = "Previous names"
                        value = nameHistory.joinToString(", ")
                        inline = false
                    }
                }

                val globalDisplayHistory = NameTracker.getGlobalNames(theUser.idLong)
                if (globalDisplayHistory.isNotEmpty()) {
                    userinfoMessage.field {
                        name = "Previous global display names"
                        value = globalDisplayHistory.joinToString(", ")
                        inline = false
                    }
                }
                val nicknameHistory = NameTracker.getNicknames(theUser.idLong, event.guild!!.idLong)
                if (nicknameHistory.isNotEmpty()) {
                    userinfoMessage.field {
                        name = "Previous nicknames"
                        value = nicknameHistory.joinToString(", ")
                        inline = false
                    }
                }
            }
        }

        userinfoMessage.footer {
            name = "User ID: ${theUser.id}$footerAddOn"
        }

        val actionResponse = event.hook.editOriginalEmbeds(userinfoMessage.build())

        // Only add the button if we're in a guild and the user is admin
        if (event.isFromGuild) {
            actionResponse.setComponents(ActionRow.of(Button.danger("super-userinfo-${theUser.idLong}", "Super Userinfo"))).queue()
        }
        actionResponse.mentionRepliedUser(false).queue()
    }

    context(user: User, guild: Guild) private suspend fun InlineEmbed.addLastTime() {
        val lastTimeHere = ModLog.getUserLT(user, guild.idLong) ?: return

        val lastTimeDST = TimeFormat.DATE_TIME_SHORT.format(lastTimeHere.toInstant())
        val lastTimeR = TimeFormat.RELATIVE.format(lastTimeHere.toInstant())

        field {
            name = "Last Time Here"
            value = "${lastTimeDST}\n(${lastTimeR})"
            inline = true
        }
    }

    context(user: User, guild: Guild) private suspend fun InlineEmbed.addRankInfo() {
        try {
            val memberXP = getMemberExp(user.idLong, guild.idLong)
            val calculateLvl = Ranks.CalculateLevel(memberXP)
            field {
                name = "Rank Info"
                value = "Placement: **" +
                    Ranks.getRankPosition(user, guild.idLong, memberXP).toString() +
                    "**\nLevel: **" +
                    Ranks.CalculateLevel(memberXP).level +
                    "**\nGot **${calculateLvl.leftover}** out of **${calculateLvl.total}** XP needed for next level"
                inline = false
            }
        } catch (_: Throwable) {
        }
    }

    context(user: User) fun InlineEmbed.addCreationDate() {
        val timeCreatedDTS = TimeFormat.DATE_TIME_SHORT.format(user.timeCreated)
        val timeCreatedR = TimeFormat.RELATIVE.format(user.timeCreated)
        field {
            name = "Creation Date"
            value = "${timeCreatedDTS}\n(${timeCreatedR})"
            inline = true
        }
    }

    context(member: Member) fun InlineEmbed.addJoinDate() {
        val timeJoinedDTS = TimeFormat.DATE_TIME_SHORT.format(member.timeJoined)
        val timeJoinedR = TimeFormat.RELATIVE.format(member.timeJoined)

        field {
            name = "Join Date"
            value = "${timeJoinedDTS}\n(${timeJoinedR})"
            inline = true
        }
    }

    context(member: Member) fun InlineEmbed.addBoostDate() {
        val theMemberTimeBoosted = member.timeBoosted
        if (theMemberTimeBoosted != null) {
        val boostedTimeDST = TimeFormat.DATE_TIME_SHORT.format(theMemberTimeBoosted)
        val boostedTimeR = TimeFormat.RELATIVE.format(theMemberTimeBoosted)

        field {
            name = "Boost Date"
            value = "${boostedTimeDST}\n(${boostedTimeR})"
            inline = true
        }
    }
    }



    // This function will send an ephemeral message to the channel where the button was clicked on.
    // It will need to check if the user who clicked the button has enough permission.
    // It will check privileged/sensitive information stored in the database meant to be seen by moderators and admins only.
    //The superUserInfo embed will show a max of 5 tickets.
    //If there's more than 5, in the 6th field the title of the field will be "Showing 5 out of xxx"
    //
    //
    //In Account Status, a function will check if the user is banned.
    //If it's banned it will show:
    //"This user is currently banned.
    //Ban date: 12/02/2022" if there's no information about the date it will show "Ban date: unknown" instead.
    //If the user is not banned, it will check if it's a server member. If it's a server member it will show
    //"This user is currently a member of $guildName" else, if not in the server but it's not banned
    //"This user is not banned.
    //
    //Additionally, all users will have a:
    //"Past bans:
    //Past kicks:
    //Past leaves:
    //"
    suspend fun superUserInfo(event: ButtonInteractionEvent) {
        //todo: i was doing this
        val user = event.user
        val guild = event.guild ?: return

        // Check if the user has administrator permission
        val member = guild.retrieveMember(user).await()
        if (!permissionCheckMessageless(member, Permission.MANAGE_ROLES)) {
            event.hook.sendMessage("You don't have permission to use this button").setEphemeral(true).queue()
            return
        }

        val userId = event.button.customId?.replace("super-userinfo-", "")?.toLong()
        if (userId == null) {
            event.hook.send("Something went wrong").setEphemeral(true).queue()
            return
        }

        //the targets are here
        val theUser = event.jda.retrieveUserById(userId).await()
        val theUserAsMember = try { event.guild?.retrieveMember(theUser)?.await() } catch (_: Throwable) { null }




        val userinfoMessage =
            MessageCreateBuilder()
                .useComponentsV2()
                .addComponents(Container  {
                        section(
                            Thumbnail(theUser.effectiveAvatarUrl)
                        ) {
                            text("## ${theUserAsMember?.effectiveName ?: theUser.effectiveName}\n${theUser.asMention}\n${theUser.name}")
                        }
                        separator(isDivider = false, spacing = Separator.Spacing.SMALL)
                        text("**${theUserAsMember?.onlineStatus?.key.toString()}**")
                        separator(isDivider = true, spacing = Separator.Spacing.SMALL)
                        text("### Account Status\n" + accountStatus(theUser, theUserAsMember, event.guild!!.idLong))
                        separator(isDivider = true, spacing = Separator.Spacing.SMALL)

                        if (event.isFromGuild) {
                            if (NameTracker.getNameHistoryStatus(event.guild!!.idLong)) {
                                val userHistory = NameTracker.nameHistoryNamesCollection.find(Filters.eq(NameTracker.NameHistoryNames::id.name, theUser.idLong)).firstOrNull()
                                val nicknameHistory = NameTracker.nameHistoryNicknamesCollection.find(and(Filters.eq(NameTracker.NameHistoryNicknames::id.name, theUser.idLong), Filters.eq(NameTracker.NameHistoryNicknames::guildId.name, event.guild!!.idLong))).firstOrNull()
                                val nameTrackTextDisplay = StringBuilder("### Name Tracker\n")

                                val hasPastUsernames = userHistory?.pastUsername?.isNotEmpty() == true
                                val hasPastGlobalNames = userHistory?.pastGlobalName?.isNotEmpty() == true
                                val hasPastNicknames = nicknameHistory?.pastNickname?.isNotEmpty() == true

                                if (hasPastUsernames)
                                    nameTrackTextDisplay.append("Previous names\n- ${userHistory.pastUsername.joinToString("\n- ")}\n")
                                if (hasPastGlobalNames)
                                    nameTrackTextDisplay.append("Previous global names\n- ${userHistory.pastGlobalName.joinToString("\n- ")}\n")
                                if (hasPastNicknames)
                                    nameTrackTextDisplay.append("Previous nicknames\n- ${nicknameHistory.pastNickname.joinToString("\n- ")}\n")

                                if (!hasPastUsernames && !hasPastGlobalNames && !hasPastNicknames) {
                                    nameTrackTextDisplay.append("× No other names found for this user.")
                                }

                                text(nameTrackTextDisplay.toString())
                            }
                            separator(isDivider = true, spacing = Separator.Spacing.SMALL)
                            this.handleTicketing(userId)
                        }
                        separator(isDivider = true, spacing = Separator.Spacing.SMALL)
                        text("-# User ID: ${theUser.id} • This feature is a work in progress")
                    })


        event.hook.sendMessage(userinfoMessage.build())
            .setAllowedMentions(EnumSet.of(Message.MentionType.ROLE))
            .mentionRepliedUser(false)
            .addComponents(
                ActionRow.of(
                    Button.secondary("show-userinfo-${theUser.idLong}", "Show in the chat"
                )
            ))
            .setEphemeral(true).queue()

        superUserinfoCache.put(userId, userinfoMessage)
    }

    suspend fun showSuperUserInfoInChat(event: ButtonInteractionEvent){

        val userId = event.button.customId?.replace("show-userinfo-", "")?.toLong()
        if (userId == null) {
            event.hook.send("Something went wrong").setEphemeral(true).queue()
            return
        }

        val messageCreate = superUserinfoCache.get(userId)
        if (messageCreate == null) {
            event.hook.sendMessage("This action is no longer possible on this message. Generate a new Super Userinfo.").setEphemeral(true).queue()
            return
        }
        event.hook.sendMessage(
                messageCreate
                    .addComponents(TextDisplay("-# Requested by ${event.user.asMention}"))
                    .build()
        )
            .setAllowedMentions(emptySet<Message.MentionType>())
            .queue()
    }

    private suspend fun InlineContainer.handleTicketing(userId: Long) {
        // Get the ticketing data from the database. We want it to be order by
        // most recent to oldest
        val ticketingData = Ticketing.ticketingCollection.find(
            Filters.eq(Ticketing.Ticket::userId.name, userId)
        ).sort(
            Sorts.descending("_id")
        ).limit(5).toList() // This will return a list of the first 5 tickets

        // I need to make a String out of the channels except the first 5 like: <#channel1>, <#channel2>, <#channel3>
        val otherTicketChannels = Ticketing.ticketingCollection.find(
            Filters.eq(Ticketing.Ticket::userId.name, userId)
        ).sort(
            Sorts.descending("_id")
        )
            .skip(5)
            .toList()
            .joinToString(", ") { "<#${it.channelId}>" }

        val ticketCount = Ticketing.ticketingCollection.countDocuments(
            Filters.eq(Ticketing.Ticket::userId.name, userId)
        )

        if (ticketingData.firstOrNull() != null) {
            text("### Tickets\n-# (ordered by latest)")
            ticketingData.forEachIndexed { index, ticket ->
                text(
                    "${ticket.statusToEmoji()} <#${ticket.channelId}>\n" +
                            "-# **Ticket ID:** ${ticket.id}\n" +
                            "-# **Channel ID:** ${ticket.channelId}\n" +
                            "-# **Assignee:** ${ticket.assigneeId?.let { assigneeFullString(it) } ?: "Not assigned"}\n" +
                            if(ticket.status().toLowerCasePreservingASCIIRules() == "closed") {
                                "$**Resolution:** `${ticket.reason}`\n"
                            } else {
                                ""
                            }
                )
                // If it's not the last ticket, add a separator
                if (index < ticketingData.lastIndex) {
                    separator(isDivider = true, spacing = Separator.Spacing.SMALL)
                }
            }
            // If there are more than 5 tickets, add a field with the other ticket channels
            if (ticketCount > 5) {
                text("Showing 5 out of $ticketCount tickets\n" +
                    "Check the other ticket channels: $otherTicketChannels")
            } else {
                text("Showing $ticketCount out of $ticketCount")
            }
        } else {
            text("### Tickets\n× No tickets found for this user")
        }
    }


    private suspend fun accountStatus(user: User, member: Member?, guildId: Long) : String {
        val guild = member?.guild ?: jda.getGuildById(guildId)!!

        val isMember = try { guild.retrieveMember(user).await() } catch (_: Throwable) { null } != null
        val isBanned = if (!isMember) try { guild.retrieveBan(user).await() } catch (_: Throwable) { null } != null else false

        // Query punishment history
        val punishmentHistory = memberPunishmenthistoryCollection.find(
            and(
                Filters.eq(MemberPunishmentHistory::id.name, user.idLong),
                Filters.eq(MemberPunishmentHistory::guildId.name, guildId)
            )
        ).firstOrNull()

        val result = StringBuilder()

        // Current status
        result.append(when {
            isBanned -> {
                // Check if we have a stored ban date in punishment history
                val banDate = punishmentHistory?.bansHistory?.maxByOrNull { it.dateOfOccurrence }
                    ?.let { TimeFormat.DATE_TIME_SHORT.format(it.dateOfOccurrence) }
                    ?: "Unknown"
                "This user is currently banned.\nBan date: $banDate"
            }
            isMember -> "This user is currently a member of ${guild.name}."
            else -> "This user is not a member of ${guild.name}."
        })

        // Add punishment history if available
        punishmentHistory?.let { history ->
            result.append("\n\n")

            // Past bans
            if (history.bansHistory.isNotEmpty()) {
                result.append("**Past Bans:**\n")
                history.bansHistory.sortedByDescending { it.dateOfOccurrence }.take(3).forEach { ban ->
                    val date = TimeFormat.DATE_TIME_SHORT.format(ban.dateOfOccurrence)
                    val reason = ban.reason?.take(50) ?: "No reason provided"
                    result.append("• $date - $reason\n")
                }
                if (history.bansHistory.size > 3) {
                    result.append("• ... and ${history.bansHistory.size - 3} more\n")
                }
            }

            // Past kicks
            if (history.kicksHistory.isNotEmpty()) {
                result.append("\n**Past Kicks:**\n")
                history.kicksHistory.sortedByDescending { it.dateOfOccurrence }.take(3).forEach { kick ->
                    val date = TimeFormat.DATE_TIME_SHORT.format(kick.dateOfOccurrence)
                    val reason = kick.reason?.take(50) ?: "No reason provided"
                    result.append("• $date - $reason\n")
                }
                if (history.kicksHistory.size > 3) {
                    result.append("• ... and ${history.kicksHistory.size - 3} more\n")
                }
            }

            // Past timeouts
            if (history.timeoutsHistory.isNotEmpty()) {
                result.append("\n**Past Timeouts:**\n")
                history.timeoutsHistory.sortedByDescending { it.dateOfOccurrence }.take(3).forEach { timeout ->
                    val date = TimeFormat.DATE_TIME_SHORT.format(timeout.dateOfOccurrence)
                    val reason = timeout.reason?.take(50) ?: "No reason provided"
                    result.append("• $date - $reason\n")
                }
                if (history.timeoutsHistory.size > 3) {
                    result.append("• ... and ${history.timeoutsHistory.size - 3} more\n")
                }
            }

            // Past leaves
            if (history.leavesHistory.isNotEmpty()) {
                result.append("\n**Past Leaves:**\n")
                history.leavesHistory.sortedByDescending { it.dateOfOccurrence }.take(3).forEach { leave ->
                    val date = TimeFormat.DATE_TIME_SHORT.format(leave.dateOfOccurrence)
                    result.append("• $date\n")
                }
                if (history.leavesHistory.size > 3) {
                    result.append("• ... and ${history.leavesHistory.size - 3} more\n")
                }
            }
        }

        return result.toString()
    }

    //todo
    // For now, this will simply say if the user is banned or not and if they are a member of the server
    private suspend fun EmbedBuilder.handleAccountStatus(event: ButtonInteractionEvent, userId: Long) {
        val user = event.jda.retrieveUserById(userId).await()
        val guild = event.guild ?: return
        var member: Member? = null

        // Try to retrieve the member, but handle the case when they're not in the server
        runCatching {
            member = guild.retrieveMember(user).await()
        }.onFailure {
            // If we can't retrieve the member, they're not in the server
            member = null
        }

        var isBanned = false
        runCatching {
            event.guild!!.retrieveBan(user).await()
        } // This will throw an exception if the user is not banned
            .onFailure { isBanned = false }
            .onSuccess { isBanned = true }

        if (isBanned) {
            this.addField(
                "Account Status [Experimental Feature]",
                "This user is currently banned.\nBan date: UNKNOWN\n",
                false
            )
        } else {
            this.addField(
                "Account Status [Experimental Feature]",
                if (member != null) {
                    "This user is currently a member of ${guild.name}"
                } else {
                    "This user is not a member of ${guild.name}"
                },
                false
            )
        }
    }

}
