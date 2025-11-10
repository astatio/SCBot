package commands

import INVISIBLE_EMBED_COLOR
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.Updates
import commands.Ticketing.assigneeFullString
import database
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.emoji.toUnicodeEmoji
import dev.minn.jda.ktx.interactions.components.button
import dev.minn.jda.ktx.messages.Embed
import dev.minn.jda.ktx.messages.InlineEmbed
import helpers.EventWrapper
import helpers.NeoSuperEmbed
import helpers.ScheduledExpiration.cancel
import helpers.ScheduledExpiration.expire
import helpers.SuperEmbed
import helpers.idDodged
import jda
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.firstOrNull
import logger
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent
import net.dv8tion.jda.api.modals.Modal
import org.bson.BsonDateTime
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

object Ticketing {


    data class Ticket(
        @BsonId val id: ObjectId,
        val ticketNumber: Long, // This will be the number of the ticket for the user. It's individual.
        val userId: Long,
        val channelId: Long,
        val originMessageId: Long? = null, // This will be the origin message ID, if toAlertChannel is not null. It will be needed to update with information about the status.
        val threadFirstMessageId: Long, // The ID of the first message in the thread. It's useful for updating the embed.
        val creationTime: BsonDateTime,
        // val expirationTime: Long,
        val reason: String? = null, // The reason for the ticket. It doesn't have to be provided. Only useful for staff at a later point.
        val assigneeId: Long? = null, // The ID of the user who is assigned to the ticket. If null, it's unassigned.
        val closedBy: Long? = null, // The ID of the user who closed the ticket. If null, it's still open.
        val notificationLevel: Int = 0 // This is the level of notification for the ticket. It will be used to determine how many hours have passed since the ticket was created.
    )

    // A guild can have more than one channel meant to be used for ticketing, where threads will be created.
    data class TicketingSettings(
        val defaultChannel: Long,
        val toAlertRoles: List<Long>?,
        val toAlertUsers: List<Long>?,
        val toAlertChannel: Long? = null,
        val ticketingChannels: List<Long>, // To turn this off, all ticketing channels must be removed.
        val guildId: Long
    )

    val ticketingSettings = database.getCollection<TicketingSettings>("ticketingSettings")
    val ticketingCollection = database.getCollection<Ticket>("tickets")


    /*
    * This function returns a string based on the status of the ticket.
    * If there's no one assigned to the ticket, it will return "Open".
    * If there's someone assigned to the ticket, it will return "Pending".
    * If the ticket is closed, it will return "Closed".
     */
    fun Ticket.status(): String {
        return if (closedBy != null) {
            "Closed"
        } else if (assigneeId != null) {
            "Pending"
        } else {
            "Open"
        }
    }

    fun Ticket.statusToEmoji(): String {
        return when (status()) {
            "Closed" -> "\uD83D\uDD12" // 🔒
            "Pending" -> "\uD83D\uDFE1" // 🟡
            else -> "\uD83D\uDFE2" // 🟢
        }
    }

    /**
     * This is the equivalent of [assigneeFullString] but for using a Long.
     */
    suspend fun assigneeFullString(userId: Long): String {
        val user = jda.retrieveUserById(userId).await()
        return user.asMention + " - " + user.name + " - " + user.idDodged()
    }

    /**
     * This is the equivalent of
     *
     * `assignee.user?.asMention + " - " + assignee?.user?.name + " - " + assignee?.idDodged()`
     */
    suspend fun assigneeFullString(user: User): String {
        return user.asMention + " - " + user.name + " - " + user.idDodged()
    }


    // There should be some utility functions to close tickets. For instance,
    // when one closes a ticket, this needs to be added to the database but also
    // the embed in the thread needs to be updated to reflect the closure of the ticket.
    /*
    * This function will close a ticket. It will update the database to reflect the closure of the ticket and
    * it will update the embed in the thread to reflect the closure of the ticket.
    * It will also archive the thread.
     */
    suspend fun internalCloseTicket(
        event: ModalInteractionEvent,
        member: Member,
        reason: String?,
        ticketID: ObjectId,
        closedBy: Long,
        messageWithButtonsId: Long
    ) {
        val ticket = ticketingCollection.findOneAndUpdate(
            Filters.eq("_id", ticketID),
            Updates.combine(
                Updates.set(Ticket::reason.name, reason),
                Updates.set(Ticket::closedBy.name, closedBy)
            )
        ) ?: run {
            logger.error { "The ticket with the ID $ticketID was not found." }
            return
        }
        // Now to update the embed in the thread
        val threadChannel = member.guild.getThreadChannelById(ticket.channelId)!!
        val firstMessage = threadChannel.retrieveMessageById(ticket.threadFirstMessageId).await()
        val og = firstMessage.embeds.firstOrNull()
        firstMessage.editMessageEmbeds(
            Embed {
                og?.title?.let { t -> title = t }
                og?.description.let { d -> description = d }
                og?.fields?.forEach { field ->
                    field {
                        name = field?.name.toString()
                        value = field?.value.toString()
                        inline = field?.isInline ?: false
                    }
                }
                // if the assignee and the user that is closing aren't the same, we need to add a field
                if (ticket.assigneeId != closedBy) {
                    field {
                        name = "Closed by"
                        value = member.asMention + " - " + member.user.name + " - " + member.idDodged()
                        inline = false
                    }
                }
                og?.colorRaw?.let { c -> color = c }
                footer {
                    name = "Ticket ID: $ticketID • Status: Closed"
                    iconUrl = og?.footer?.iconUrl
                }
            }
        ).setComponents().queue()
        // Now archive the thread
        val msg = threadChannel.retrieveMessageById(messageWithButtonsId).await()
        msg.expire()
        msg.delete().queue()
        // This won't work without "reply" due to the nature of modal interactions. Do not change from "reply" to something else.
        event.replyEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text =
                    "Ticket closed successfully" + if (reason != null) " with the below resolution.\n```$reason```" else "."
            }
        ).queue {
            threadChannel.manager.setLocked(true).setArchived(true).queue()
        }
        // Now update the origin message in the alert channel
        changeStatus(member.guild.idLong, ticketID, "Closed")
    }



    // The objective of the Ticketing system is to use threads as tickets.
    // All threads are private.

    // When a mod uses !talk on a user, only they and the user will be added to the private thread by the bot.

    /**
     * This function will create a ticket when called and has parameters for customization. It's send an embed indicating the ticket was created by the [assigneeMember] on behalf of the [targetMember].
     *
     *
     * @param event The event of type MessageReceivedEvent.
     * @param ticketTitle The title of the ticket. By default, it's the name of the target member + [Ticket.ticketNumber]
     * @param targetMember The member that the ticket is about. This member will be added to the thread.
     * @param assigneeMember The member that the ticket is assigned to. This member will be added to the thread.
     * @param channelId The channel where the ticket is going to be created. Must be a TextChannel specifically due to the nature of threads.
     */
    suspend fun createTicketOnBehalf(
        event: EventWrapper,
        // ticketSubject: String?,
        targetMember: Member,
        assigneeMember: Member,
        txtChannel: TextChannel
    ) {
        // Create a new thread in the channel with the target user.
        // Add the user to the thread.
        // Add the bot to the thread.
        // Add the user to the ticketing database. It will be useful for tracking tickets and target users who have more tickets.
        // check if it's a valid ticketing channel
        if (ticketingSettings.find(
                Filters.and(
                    Filters.eq(TicketingSettings::guildId.name, event.guild!!.idLong),
                    Filters.eq(TicketingSettings::ticketingChannels.name, txtChannel.idLong)
                )
            ).firstOrNull() == null
        ) {
            logger.error { "The provided channel ID is not a valid ticketing channel" }
            return
        }
        //We need to send an initial embed
        TicketBuilder(
            executorMember = event.member!!,
            shortSubject = targetMember.user.name,
            ticketDescription = null,
            targetMember = targetMember,
            assigneeMember = assigneeMember,
            ticketingChannel = txtChannel
        ).onBehalfOf()
    }

    suspend fun createTicketClassic(
        event: SlashCommandInteractionEvent,
        subject: String,
        ticketDescription: String?
    ) {
        val existingTicket = ticketingCollection.find(
            Filters.and(
                Filters.eq(Ticket::userId.name, event.user.idLong),
                Filters.eq(Ticket::closedBy.name, null)
            )
        ).firstOrNull()

        if (existingTicket != null) {
            val existingThread = event.guild!!.getThreadChannelById(existingTicket.channelId)

            if (existingThread != null) {
                val contextInfo = extractExistingTicketDetails(existingThread, existingTicket)
                val contextHint = contextInfo?.first
                val detailText = contextInfo?.second
                val openedTimestampSeconds = existingTicket.creationTime.value.let { if (it > 0) it / 1000 else null }
                val summaryBuilder = StringBuilder()

                contextHint?.let { summaryBuilder.append(it) }

                if (!detailText.isNullOrBlank()) {
                    if (summaryBuilder.isNotEmpty()) summaryBuilder.append("\n")
                    summaryBuilder.append("Latest details: ").append(detailText)
                }

                openedTimestampSeconds?.let {
                    if (summaryBuilder.isNotEmpty()) summaryBuilder.append("\n")
                    summaryBuilder.append("Opened <t:").append(it).append(":R>.")
                }

                val summary = summaryBuilder.toString()

                val threadNotice = buildString {
                    append("${event.user.asMention} attempted to open a new ticket. Let's keep the conversation in this thread.")
                    if (summary.isNotBlank()) {
                        append("\n\n")
                        append(summary)
                    }
                }

                val userNotice = buildString {
                    append("You already have an active ticket in ${existingThread.asMention}.")
                    if (summary.isNotBlank()) {
                        append("\n\n")
                        append(summary)
                    }
                }

                existingThread.sendMessageEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE
                        text = threadNotice
                    }
                ).await()

                event.hook.sendMessageEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = userNotice
                    }
                ).queue()
                return
            }
            logger.warn {
                "Ticket ${existingTicket.id} for user ${event.user.id} was found without an associated thread. Proceeding to create a new ticket."
            }
        }

        //get the default ticketing channel
        val defaultTicketChannel = ticketingSettings.find(
            Filters.eq(TicketingSettings::guildId.name, event.guild!!.idLong)
        ).firstOrNull()?.defaultChannel?.let { event.guild!!.getTextChannelById(it) } ?: run {
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "No default ticket channel has been set. Please contact a staff member."
                }
            ).queue()
            return
        }

        // Create a ticket in the default ticket channel
        TicketBuilder(
            executorMember = event.member!!,
            shortSubject = subject,
            ticketDescription = ticketDescription,
            ticketingChannel = defaultTicketChannel
        )
        //todo: at one point, this should have buttons

        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Ticket created successfully in ${defaultTicketChannel.asMention}"
            }
        ).queue()
    }

    private suspend fun extractExistingTicketDetails(
        thread: ThreadChannel,
        ticket: Ticket
    ): Pair<String?, String?>? {
        val message = runCatching { thread.retrieveMessageById(ticket.threadFirstMessageId).await() }.getOrNull()
            ?: return null
        val embed = message.embeds.firstOrNull() ?: return null

        val descriptionField = embed.fields.firstOrNull { it.name.equals("Description", ignoreCase = true) }?.value
        val bodyTextRaw = descriptionField?.takeIf { it.isNotBlank() }
            ?: embed.description?.takeIf { it.isNotBlank() }

        val contextHint = when {
            bodyTextRaw?.contains("kicked or unbanned", ignoreCase = true) == true ->
                "Context: this ticket was opened automatically after the system detected you rejoined following a kick or unban."
            else -> null
        }

        val detailRaw = bodyTextRaw ?: embed.title?.takeIf { it.isNotBlank() }
        val detail = detailRaw?.let { sanitizeContextText(it) }

        return contextHint to detail
    }

    private fun sanitizeContextText(raw: String, maxLength: Int = 350): String {
        val trimmed = raw.trim()
        return if (trimmed.length <= maxLength) trimmed else trimmed.take(maxLength - 1) + "…"
    }

    // create a class called TicketBuilder that serves the purpose of building embeds for tickets.
    // It also features a "classic" way of building tickets, which is the one that will be used when the ticket is created by the user.
    // The other way is the "onBehalfOf" way, which will be used when a staff member creates a ticket on behalf of another user.
    // Such ones require calling different functions to build the embeds.
    // But they all share Author, Assignee and the same footer.
    internal class TicketBuilder(
        val executorMember: Member,
        val shortSubject: String? = null, // Should be no more than 256 characters if it's not null.
        val ticketDescription: String? = null,
        val targetMember: Member = executorMember, // If it's not provided, it will be the executor of the command.
        val assigneeMember: Member? = null, // Classic tickets don't have an assignee by default, but onBehalfOf tickets do.
        val ticketingChannel: TextChannel
    ) {

        private lateinit var ticketInDB: Ticket
        private lateinit var threadChannel: ThreadChannel
        private lateinit var threadMessage: Message

        @OptIn(ExperimentalTime::class)
        private suspend fun prepareThread() {
            // Prepare the title of the thread
            // The title of the thread will be the name of the target member + # the quantity of tickets this member has.
            // If a title was already provided, it will just append the #number to it.
            val memberTckCollectionCount = ticketingCollection.find(
                Filters.and(
                    Filters.eq(Ticket::userId.name, targetMember.idLong)
                )
            ).count()

            val thrdTitle = targetMember.user.name + memberTckCollectionCount.let { if (it > 0) " #$it" else "" }
            // Now to create the thread and add both the target and the assignee to it - is there is one.
            threadChannel = ticketingChannel.createThreadChannel(thrdTitle, true).setInvitable(false).await()
            threadChannel.addThreadMember(targetMember).await()
            if (assigneeMember != null) threadChannel.addThreadMember(assigneeMember).await()
            // Send message saying its preparing the thread
            threadMessage = threadChannel.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE
                    text = "Preparing the thread..."
                }
            ).await()
            // Pin the message
            threadMessage.pin().await()
            // Add the ticket to the database
            ticketInDB =
                Ticket(
                    id = ObjectId(),
                    ticketNumber = memberTckCollectionCount.toLong() + 1, // To remember, this is the number of tickets the user has.
                    threadFirstMessageId = threadMessage.idLong,
                    userId = targetMember.idLong,
                    assigneeId = assigneeMember?.idLong,
                    channelId = threadChannel.idLong,
                    creationTime = BsonDateTime(Clock.System.now().toEpochMilliseconds()),
                    notificationLevel = 0
                )
            ticketingCollection.insertOne(ticketInDB)
            // Now to alert the roles, users and channel
            alertRoles(threadChannel)
            alertUsers(threadChannel)
            alertChannel(threadChannel, ticketInDB.id, targetMember)
        }

        private suspend fun InlineEmbed.commonLogic() {
            prepareThread()
            this.builder.apply {
                color = INVISIBLE_EMBED_COLOR
                field {
                    name = "Author"
                    value = targetMember.asMention + " - " + targetMember.user.name + " - " + targetMember.idDodged()
                    inline = false
                }
                field {
                    name = "Assignee"
                    value = if (assigneeMember != null)
                        assigneeFullString(assigneeMember.user)
                    else
                        "No assignee"
                    inline = false
                }
                footer {
                    name =
                        "Ticket ID: ${ticketInDB.id} • Status: ${if (assigneeMember != null) "Pending" else "Open"}"
                    assigneeMember?.effectiveAvatarUrl?.let { iconUrl = it }
                }
            }
            threadMessage.editMessageEmbeds(this.build())
                .setComponents(
                    ActionRow.of(
                        Button.danger(
                            "ticket-close-${ticketInDB.id}",
                            "Close"
                        ).withEmoji("\uD83D\uDD12".toUnicodeEmoji()),
                        Button.secondary(
                            "ticket-assign-${ticketInDB.id}",
                            "Claim"
                        ),
                        Button.primary(
                            "ticket-assignother-${ticketInDB.id}",
                            "Assign to ..."
                        )
                    )
                )
                .await()
        }

        suspend fun classic() = Embed {
            title = shortSubject ?: "No subject provided"
            field {
                name = "Description"
                value = ticketDescription ?: "No description provided"
                inline = false
            }
            commonLogic()
        }

        suspend fun onBehalfOf() = Embed {
            title = shortSubject ?: "No subject provided"
            description =
                "${assigneeMember!!.asMention} - ${assigneeMember.user.name} has created this ticket on behalf of ${targetMember.asMention} - ${targetMember.user.name}"
            ticketDescription?.let {
                field {
                    name = "Description"
                    value = it
                    inline = false
                }
            }
            commonLogic()
        }
    }


    private suspend fun assignTicketToMember(
        guild: Guild,
        ticket: Ticket,
        newAssignee: Member,
        actor: User
    ): Boolean {
        ticketingCollection.findOneAndUpdate(
            Filters.eq("_id", ticket.id),
            Updates.set(Ticket::assigneeId.name, newAssignee.idLong)
        )

        val threadChannel = guild.getThreadChannelById(ticket.channelId) ?: return false
        val firstMessage = runCatching { threadChannel.retrieveMessageById(ticket.threadFirstMessageId).await() }.getOrNull()
            ?: return false
        val originalEmbed = firstMessage.embeds.firstOrNull()

        val updatedEmbed = Embed {
            originalEmbed?.title?.let { title = it }
            originalEmbed?.description?.let { description = it }
            originalEmbed?.fields?.filterNot { it.name == "Assignee" }?.forEach { existingField ->
                field {
                    name = existingField.name ?: ""
                    value = existingField.value ?: ""
                    inline = existingField.isInline
                }
            }
            field {
                name = "Assignee"
                value = assigneeFullString(newAssignee.user)
            }
            color = INVISIBLE_EMBED_COLOR
            originalEmbed?.footer?.let {
                footer {
                    name = "Ticket ID: ${ticket.id} • Status: Pending"
                    iconUrl = newAssignee.effectiveAvatarUrl
                }
            }
        }

        runCatching { firstMessage.editMessageEmbeds(updatedEmbed).await() }.getOrElse { return false }

    runCatching { threadChannel.addThreadMember(newAssignee).queue() }

        val announcement =
            if (actor.idLong == newAssignee.idLong) {
                "${newAssignee.asMention} has been assigned to this ticket."
            } else {
                "${newAssignee.asMention} has been assigned to this ticket by ${actor.asMention}."
            }
        threadChannel.sendMessage(announcement).queue()

        return true
    }

    suspend fun assignTicket(event: SlashCommandInteractionEvent, assignee: User) {
        // This function is kept for backward compatibility
        // Implementation will be added if needed
    }

    suspend fun assignTicket(event: ButtonInteractionEvent, ticketId: String) {
        // Check if it's a valid ticket thread
        val ticket = ticketingCollection.find(
            Filters.and(
                Filters.eq("_id", ObjectId(ticketId)),
                Filters.eq(Ticket::closedBy.name, null)
            )
        ).firstOrNull() ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "This is not a valid ticket thread or the ticket has already been closed."
                }
            ).setEphemeral(true).queue()
            return
        }

        // Check if the ticket is already assigned to this user
        if (ticket.assigneeId == event.user.idLong) {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "You are already assigned to this ticket."
                }
            ).setEphemeral(true).queue()
            return
        }

        val guild = event.guild ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "This button can only be used inside a ticket thread."
                }
            ).setEphemeral(true).queue()
            return
        }

        val assigneeMember = event.member ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "I couldn't find you in this server to assign the ticket."
                }
            ).setEphemeral(true).queue()
            return
        }

        val updated = assignTicketToMember(guild, ticket, assigneeMember, event.user)
        if (!updated) {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "I couldn't update the ticket. The thread message might be missing."
                }
            ).setEphemeral(true).queue()
        }
    }

    suspend fun assignTicket(event: EntitySelectInteractionEvent, ticketId: String) {
        val ticket = ticketingCollection.find(
            Filters.and(
                Filters.eq("_id", ObjectId(ticketId)),
                Filters.eq(Ticket::closedBy.name, null)
            )
        ).firstOrNull() ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "This is not a valid ticket thread or the ticket has already been closed."
                }
            ).setEphemeral(true).queue()
            event.hook.editOriginalComponents(emptyList()).queue()
            return
        }

        val guild = event.guild ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "This action can only be used inside a server."
                }
            ).setEphemeral(true).queue()
            event.hook.editOriginalComponents(emptyList()).queue()
            return
        }

        val selectedAssigneeMentionable = event.values.firstOrNull() ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "No user was selected."
                }
            ).setEphemeral(true).queue()
            event.hook.editOriginalComponents(emptyList()).queue()
            return
        }

        val newAssignee: Member = when (selectedAssigneeMentionable) {
            is Member -> selectedAssigneeMentionable
            is User -> guild.getMember(selectedAssigneeMentionable)
            else -> guild.getMemberById(selectedAssigneeMentionable.idLong)
        } ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "I couldn't find that member in this server."
                }
            ).setEphemeral(true).queue()
            event.hook.editOriginalComponents(emptyList()).queue()
            return
        }

        if (ticket.assigneeId == newAssignee.idLong) {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "${newAssignee.asMention} is already assigned to this ticket."
                }
            ).setEphemeral(true).queue()
            event.hook.editOriginalComponents(emptyList()).queue()
            return
        }

        val updated = assignTicketToMember(guild, ticket, newAssignee, event.user)
        if (!updated) {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "I couldn't update the ticket. The thread message might be missing."
                }
            ).setEphemeral(true).queue()
            event.hook.editOriginalComponents(emptyList()).queue()
            return
        }

        event.hook.editOriginalComponents(emptyList()).queue()
        event.hook.editOriginal("Assigned to ${newAssignee.asMention}").queue()
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "${newAssignee.asMention} is now assigned to this ticket."
            }
        ).setEphemeral(true).queue()
    }

    // This function is used to close a ticket. It will check if
    // it's a valid ticket thread first, then it will send an embed
    // prompting the user to confirm the closure of the ticket.
    // The user will have to confirm it by clicking a button
    // that will be available for 30 seconds.
    // One of the buttons will be "close the ticket" and the other
    // will be "Nevermind".
    suspend fun closeTicket(event: ButtonInteractionEvent, ticketId: String) {
        // Check if it's a valid ticket thread
        val ticket = ticketingCollection.find(
            Filters.and(
                Filters.eq("_id", ObjectId(ticketId)),
                Filters.eq(Ticket::closedBy.name, null)
            )
        ).firstOrNull() ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "This is not a valid ticket thread or the ticket has already been closed."
                }
            ).setEphemeral(true).queue()
            return
        }

        val cancelButton = event.jda.button(
            label = "Nevermind", style = ButtonStyle.SECONDARY, user = event.user, expiration = 5.minutes
        ) {
            it.cancel()
        }

        val isAssignee = event.user.idLong == ticket.assigneeId

        // Send the embed
        // If the person trying to close isn't the one who's assigned to the ticket, it will send an extra text

        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text =
                    "Are you sure you want to close this ticket?" + if (!isAssignee) "\n*You are __**not**__ the assignee of this ticket.*" else ""
            }
        ).queue {
            val confirmButton = event.jda.button(label = "Close it", style = ButtonStyle.DANGER, user = event.user, expiration = 5.minutes)
            {
                btnEvent ->
                // Close the ticket
                btnEvent.deferEdit()
                //send a modal to ask for the reason
                val modal = Modal.create(
                    "ticket-reason-${it.idLong}-${event.channelId}-${ticket.id}",
                    "Ticket Closure Confirmation"
                )
                    .addComponents(
                        Label.of(
                            "Ticket resolution",
                            TextInput.create("modal-reason", TextInputStyle.PARAGRAPH)
                                .setPlaceholder("Shortly describe how this ticket was resolved or why it's being closed")
                                .setRequired(true)
                                .setRequiredRange(2, 1024)
                                .build()
                        )
                    ).build()
                btnEvent.replyModal(modal).queue()
                btnEvent.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE
                        text = "Awaiting ticket closure confirmation by ${event.user.asMention} ..."
                    }
                ).queue()
            }
            it.editMessageComponents(ActionRow.of(confirmButton, cancelButton)).queue { msg ->
                msg.expire(5.minutes)
            }
        }
    }

    suspend fun addAlertRole(event: SlashCommandInteractionEvent, role: Role) {
        // Try to add the role to the database, but don't if it's already there in the list.
        // If no document matches the filter, it will send error message.
        ticketingSettings.findOneAndUpdate(
            Filters.eq(TicketingSettings::guildId.name, event.guild!!.idLong),
            //try to add it, but do nothing if it's already there
            Updates.addToSet(TicketingSettings::toAlertRoles.name, role.idLong)
        ) ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "No ticketing settings were found for this server. Please contact a staff member."
                }
            ).queue()
            return
        }
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "The role has been added to the alert roles."
            }
        ).queue()
    }

    suspend fun addAlertUser(event: SlashCommandInteractionEvent, user: User) {
        // Try to add the user to the database, but don't if it's already there in the list.
        // If no document matches the filter, it will send error message.
        ticketingSettings.findOneAndUpdate(
            Filters.eq(TicketingSettings::guildId.name, event.guild!!.idLong),
            //try to add it, but do nothing if it's already there
            Updates.addToSet(TicketingSettings::toAlertUsers.name, user.idLong)
        ) ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "No ticketing settings were found for this server. Please contact a staff member."
                }
            ).queue()
            return
        }
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "The user has been added to the alert users."
            }
        ).queue()
    }

    suspend fun addAlertChannel(event: SlashCommandInteractionEvent, textChannel: TextChannel) {
        // Try to add the channel to the database, but don't if it's already there in the list.
        // If no document matches the filter, it will send error message.
        ticketingSettings.findOneAndUpdate(
            Filters.eq(TicketingSettings::guildId.name, event.guild!!.idLong),
            //try to add it, but do nothing if it's already there
            Updates.set(TicketingSettings::toAlertChannel.name, textChannel.idLong)
        ) ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "No ticketing settings were found for this server. Please contact a staff member."
                }
            ).queue()
            return
        }
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "The channel has been set as the alert channel for tickets."
            }
        ).queue()
    }

    suspend fun addTicketingChannel(event: SlashCommandInteractionEvent, textChannel: TextChannel) {
        //Try to add the channel to the database, but don't if it's already there in the list.
        ticketingSettings.findOneAndUpdate(
            Filters.eq(TicketingSettings::guildId.name, event.guild!!.idLong),
            //try to add it, but do nothing if it's already there
            Updates.combine(
                Updates.setOnInsert(TicketingSettings::guildId.name, event.guild!!.idLong),
                Updates.setOnInsert(TicketingSettings::defaultChannel.name, textChannel.idLong),
                Updates.setOnInsert(TicketingSettings::toAlertRoles.name, null),
                Updates.setOnInsert(TicketingSettings::toAlertUsers.name, null),
                Updates.setOnInsert(TicketingSettings::ticketingChannels.name, emptyList<Long>())
            ),
            FindOneAndUpdateOptions().upsert(true)
        )
        //Now to add the channel to the list of ticketing channels
        ticketingSettings.updateOne(
            Filters.eq(TicketingSettings::guildId.name, event.guild!!.idLong),
            Updates.addToSet(TicketingSettings::ticketingChannels.name, textChannel.idLong)
        )

        event.hook.editOriginalEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "The channel has been set as a ticketing channel."
            }
        ).queue()
    }

    suspend fun removeAlertRole(event: SlashCommandInteractionEvent, role: Role) {
        // Try to remove the role from the database, but don't if it's not there in the list.
        // If no document matches the filter, it will send error message.
        ticketingSettings.findOneAndUpdate(
            Filters.eq(TicketingSettings::guildId.name, event.guild!!.idLong),
            //try to remove it, but do nothing if it's not there
            Updates.pull(TicketingSettings::toAlertRoles.name, role.idLong)
        ) ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "No ticketing settings were found for this server. Please contact a staff member."
                }
            ).queue()
            return
        }
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "The role has been removed from the alert roles."
            }
        ).queue()
    }

    suspend fun removeAlertUser(event: SlashCommandInteractionEvent, user: User) {
        // Try to remove the user from the database, but don't if it's not there in the list.
        // If no document matches the filter, it will send error message.
        ticketingSettings.findOneAndUpdate(
            Filters.eq(TicketingSettings::guildId.name, event.guild!!.idLong),
            //try to remove it, but do nothing if it's not there
            Updates.pull(TicketingSettings::toAlertUsers.name, user.idLong)
        ) ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "No ticketing settings were found for this server. Please contact a staff member."
                }
            ).queue()
            return
        }
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "The user has been removed from the alert users."
            }
        ).queue()
    }

    suspend fun removeAlertChannel(event: SlashCommandInteractionEvent, textChannel: TextChannel) {
        // Try to remove the channel from the database, but don't if it's not there in the list.
        // If no document matches the filter, it will send error message.
        ticketingSettings.findOneAndUpdate(
            Filters.and(
                Filters.eq(TicketingSettings::guildId.name, event.guild!!.idLong),
                Filters.eq(TicketingSettings::toAlertChannel.name, textChannel.idLong)
            ),
            //try to remove it, but do nothing if it's not there
            Updates.unset(TicketingSettings::toAlertChannel.name)
        ) ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "No ticketing settings were found for this server. Please contact a staff member."
                }
            ).queue()
            return
        }
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "The channel has been removed from the alert channel."
            }
        ).queue()
    }

    suspend fun removeTicketingChannel(event: SlashCommandInteractionEvent, textChannel: TextChannel) {
        // Try to remove the channel from the database, but don't if it's not there in the list.
        // If no document matches the filter, it will send error message.
        ticketingSettings.findOneAndUpdate(
            Filters.and(
                Filters.eq(TicketingSettings::guildId.name, event.guild!!.idLong),
                Filters.eq(TicketingSettings::ticketingChannels.name, textChannel.idLong)
            ),
            //try to remove it, but do nothing if it's not there
            Updates.pull(TicketingSettings::ticketingChannels.name, textChannel.idLong)
        ) ?: run {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "No ticketing settings were found for this server. Please contact a staff member."
                }
            ).queue()
            return
        }
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text =
                    "The channel has been removed from the ticketing channels.\nPlease note that this doesn't remove any tickets that were created in this channel from the database nor the server."
            }
        ).queue()
    }

    suspend fun alertRoles(threadChannel: ThreadChannel) {
        // This function will be used to alert the roles when a ticket is created.
        // This will be called on ticket creation, so we only need to get the roles from the database
        val roles = ticketingSettings.find(
            Filters.eq(TicketingSettings::guildId.name, threadChannel.guild.idLong)
        ).firstOrNull()?.toAlertRoles ?: return
        threadChannel.sendMessage(roles.joinToString { "<@&$it>" }).queue()
    }

    suspend fun alertUsers(threadChannel: ThreadChannel) {
        // This function will be used to alert the users when a ticket is created.
        // This will be called on ticket creation, so we only need to get the users from the database
        val users = ticketingSettings.find(
            Filters.eq(TicketingSettings::guildId.name, threadChannel.guild.idLong)
        ).firstOrNull()?.toAlertUsers ?: return
        threadChannel.sendMessage(users.joinToString { "<@$it>" }).queue()
    }

    suspend fun alertChannel(threadChannel: ThreadChannel, ticketId: ObjectId, targetMember: Member) {
        // This function will be used to alert the channel when a ticket is created.
        // This will be called on ticket creation, so we only need to get the channel from the database
        val channel = ticketingSettings.find(
            Filters.eq(TicketingSettings::guildId.name, threadChannel.guild.idLong)
        ).firstOrNull()?.toAlertChannel?.let { threadChannel.guild.getTextChannelById(it) } ?: return
        val originMsg = channel.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text =
                    "A new ticket has been created. Check out ${threadChannel.asMention} - `${targetMember.effectiveName}`. It's currently OPEN"
            }).await()
        ticketingCollection.updateOne(
            Filters.eq("_id", ticketId),
            Updates.set("originMessageId", originMsg.idLong)
        )
    }

    //we need a way to "listen" to status updates. For now, the only possible interaction that changes
    // the status is "/ticket close".
    suspend fun changeStatus(guildId: Long, ticketId: ObjectId, status: String) {
        val updatedTicket = ticketingCollection.find(Filters.eq("_id", ticketId)).firstOrNull() ?: return
        // now we update the origin message
        val alertChannelId = ticketingSettings.find(
            Filters.eq(TicketingSettings::guildId.name, guildId)
        ).firstOrNull()?.toAlertChannel ?: return
        val channel = jda.getGuildById(guildId)?.getTextChannelById(alertChannelId) ?: return
        val originMsg = channel.retrieveMessageById(updatedTicket.originMessageId!!).await()
        // Now the embed needs to be updated. We just get the text from the first field.
        // I need a regex to use on the first field value to replace all text after the last "."
        val og = originMsg?.embeds?.firstOrNull()?.description ?: return
        val lastDotIndex = og.lastIndexOf(".")
        val textBeforeLastDot = if (lastDotIndex != -1) og.substring(0, lastDotIndex) else og

        originMsg.editMessageEmbeds(
            Embed {
                description = "$textBeforeLastDot. It's currently ${status.uppercase()}"
                color = INVISIBLE_EMBED_COLOR
            }
        ).await()
    }


}
