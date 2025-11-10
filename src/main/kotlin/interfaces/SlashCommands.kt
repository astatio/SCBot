package interfaces

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent

interface SlashCommands {
        suspend fun onSlashCommandInteraction(event: SlashCommandInteractionEvent)
        suspend fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent)
        suspend fun onStringSelectInteraction(event: StringSelectInteractionEvent)
}
