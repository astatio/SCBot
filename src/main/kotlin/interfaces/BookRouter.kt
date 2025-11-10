package interfaces

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent

interface BookRouter {
    suspend fun route(event: ButtonInteractionEvent, wantedBook: String, wantedPage: String)
    val supportedBooks: List<String>
}