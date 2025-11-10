package listeners

import commands.CommandsMisc
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import owners
import kotlin.collections.contains

context(event: MessageReceivedEvent)
fun onPrivateCommand() {
    if (event.author.idLong !in owners) {
        return
    }
    val content = event.message.contentDisplay
    val parts = content.split(" ", limit = 2)
    when (content.substringBefore(" ")) {
        "!guilds" -> CommandsMisc.getGuilds(event)
        "!leaveguild" -> CommandsMisc.leaveGuild(parts.getOrNull(1), event)
        "!shutdown" -> CommandsMisc.shutdown(event)
        "!throw" -> {
            event.jda.retrieveUserById("null").queue()
        }
        else -> return
    }
}
