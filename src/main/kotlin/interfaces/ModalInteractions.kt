package interfaces

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent

interface ModalInteractions {
    suspend fun onModalInteraction(event: ModalInteractionEvent)
}
