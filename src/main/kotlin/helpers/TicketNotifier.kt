package helpers

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import commands.Ticketing
import commands.Ticketing.Ticket
import dev.minn.jda.ktx.messages.Embed
import jda
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import logger
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

object TicketNotifier {

    private const val HOURS_PER_NOTIFICATION_LEVEL = 24

    fun schedule() {
        val job = JobProducer(
            f = { checkOverdueTickets() },
            interval = 1.hours,
            initialDelay = null
        )
        JobProducerScheduler(job).start()
    }

    private suspend fun checkOverdueTickets() {
        logger.info { "Checking for overdue tickets..." }

        val overdueTickets = findOverdueTickets()

        if (overdueTickets.isEmpty()) {
            logger.info { "No overdue tickets found." }
            return
        }

        logger.info { "Found ${overdueTickets.size} overdue tickets to process." }
        processOverdueTickets(overdueTickets)
    }

    private suspend fun findOverdueTickets(): List<Ticket> =
        Ticketing.ticketingCollection.find(
            Filters.eq("closedBy", null)
        ).toList().filter { ticket ->
            val creationTime = ticket.creationTime.value
            val hoursSinceCreation = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - creationTime)
            val currentNotificationThreshold = (ticket.notificationLevel + 1) * HOURS_PER_NOTIFICATION_LEVEL
            hoursSinceCreation >= currentNotificationThreshold
        }

    private suspend fun processOverdueTickets(overdueTickets: List<Ticket>) {
        // Group tickets by guild and filter out invalid entries
        val ticketsByGuild = overdueTickets.mapNotNull { ticket ->
            getGuildIdForTicket(ticket)?.let { guildId -> guildId to ticket }
        }.groupBy({ it.first }, { it.second })

        for ((guildId, tickets) in ticketsByGuild) {
            processTicketsForGuild(guildId, tickets)
        }
    }

    private fun getGuildIdForTicket(ticket: Ticket): Long? {
        return try {
            jda.getThreadChannelById(ticket.channelId)?.guild?.idLong
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get guild for ticket channel: ${ticket.channelId}" }
            null
        }
    }

    private suspend fun processTicketsForGuild(guildId: Long, tickets: List<Ticket>) {
        val settings = Ticketing.ticketingSettings.find(Filters.eq("guildId", guildId)).firstOrNull()
        if (settings?.toAlertChannel == null) return

        val guild = jda.getGuildById(settings.guildId)
        if (guild == null) return

        val alertChannel = guild.getTextChannelById(settings.toAlertChannel)
        if (alertChannel == null) return

        for (ticket in tickets) {
            processIndividualTicket(ticket, guild, alertChannel, settings)
        }
    }

    private suspend fun processIndividualTicket(
        ticket: Ticket,
        guild: net.dv8tion.jda.api.entities.Guild,
        alertChannel: net.dv8tion.jda.api.entities.channel.concrete.TextChannel,
        settings: Ticketing.TicketingSettings
    ) {
        // Calculate the correct notification level based on elapsed time
        val creationTime = ticket.creationTime.value
        val hoursSinceCreation = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - creationTime)
        val correctNotificationLevel = (hoursSinceCreation / HOURS_PER_NOTIFICATION_LEVEL).toInt()

        // Update the ticket to the correct notification level before sending notification
        if (correctNotificationLevel > ticket.notificationLevel) {
            updateTicketNotificationLevel(ticket, correctNotificationLevel)
        }

        val threadChannel = guild.getThreadChannelById(ticket.channelId)
        val hours = correctNotificationLevel * HOURS_PER_NOTIFICATION_LEVEL

        val notificationMessage = buildNotificationMessage(ticket, threadChannel, settings)

        val embed = Embed {
            title = "Overdue Ticket"
            description = "Ticket has been open for over $hours hours."
            field("Ticket", threadChannel?.asMention ?: ticket.channelId.toString(), true)
            ticket.assigneeId?.let { field("Assignee", "<@$it>", true) }
            color = 0xFF0000
        }

        try {
            alertChannel.sendMessageEmbeds(embed)
                .setContent(notificationMessage)
                .queue(
                    {
                        logger.debug { "Sent overdue notification for ticket: ${ticket.channelId} (level $correctNotificationLevel)" }
                    },
                    { error ->
                        logger.error(error) { "Failed to send overdue notification for ticket: ${ticket.channelId}" }
                    }
                )
        } catch (e: Exception) {
            logger.error(e) { "Error sending notification for ticket: ${ticket.channelId}" }
        }
    }

    private fun buildNotificationMessage(
        ticket: Ticket,
        threadChannel: net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel?,
        settings: Ticketing.TicketingSettings
    ): String {
        return buildString {
            append("Ticket ${threadChannel?.asMention ?: ticket.channelId} is overdue. ")

            if (ticket.assigneeId != null) {
                append("Assignee: <@${ticket.assigneeId}>")
            } else {
                val mentions = buildList {
                    settings.toAlertRoles?.forEach { add("<@&$it>") }
                    settings.toAlertUsers?.forEach { add("<@$it>") }
                }
                if (mentions.isNotEmpty()) {
                    append(mentions.joinToString(" "))
                }
            }
        }
    }

    private suspend fun updateTicketNotificationLevel(ticket: Ticket, targetLevel: Int? = null) {
        try {
            val newNotificationLevel = targetLevel ?: run {
                val creationTime = ticket.creationTime.value
                val hoursSinceCreation = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - creationTime)
                (hoursSinceCreation / HOURS_PER_NOTIFICATION_LEVEL).toInt()
            }

            if (newNotificationLevel > ticket.notificationLevel) {
                Ticketing.ticketingCollection.updateOne(
                    Filters.eq("_id", ticket.id),
                    Updates.set(Ticket::notificationLevel.name, newNotificationLevel)
                )
                logger.debug { "Updated ticket ${ticket.id} notification level from ${ticket.notificationLevel} to $newNotificationLevel" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update notification level for ticket: ${ticket.id}" }
        }
    }
}
