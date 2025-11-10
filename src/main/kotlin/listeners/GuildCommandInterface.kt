package listeners

import net.dv8tion.jda.api.events.message.MessageReceivedEvent

interface GuildCommands {
    suspend fun onGuildCommand(event: MessageReceivedEvent)
}