package interfaces

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent

interface ButtonInteractions {
    suspend fun onButtonInteraction(event: ButtonInteractionEvent)
}