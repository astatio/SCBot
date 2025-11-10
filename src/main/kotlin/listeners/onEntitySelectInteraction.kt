package listeners

import commands.Ticketing.assignTicket
import helpers.NeoSuperEmbed
import helpers.permissionCheckMessageless
import helpers.SuperEmbed
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent

//TODO: I dont think this is the best way to handle Modals, not at all. The "Assign to..."
// needs to be tested.
suspend fun onEntitySelectInteraction(event: EntitySelectInteractionEvent) {
    if (!event.componentId.startsWith("ticket-assignselect-")) return

    event.deferEdit().queue()

    val member = event.member
    if (member == null) {
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "I couldn't find you in this server."
            }
        ).setEphemeral(true).queue()
        event.hook.editOriginalComponents(emptyList()).queue()
        return
    }

    if (!permissionCheckMessageless(member, Permission.MANAGE_ROLES)) {
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "You don't have permission to assign tickets."
            }
        ).setEphemeral(true).queue()
        event.hook.editOriginalComponents(emptyList()).queue()
        return
    }

    val ticketId = event.componentId.substringAfter("ticket-assignselect-")
    if (ticketId.isBlank()) {
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "I couldn't parse the ticket ID."
            }
        ).setEphemeral(true).queue()
        event.hook.editOriginalComponents(emptyList()).queue()
        return
    }

    assignTicket(event, ticketId)
}
