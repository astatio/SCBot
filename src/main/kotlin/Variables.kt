import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.message.MessageReceivedEvent


const val MEMBER_ROLE = 123L
const val MIME_ROLE = 123L
const val RETURN_ROLE = 123L
const val EXCEPTION_ROLE = 123L
const val NOIMAGE_ROLE = 123L

const val MEDIATORS_ROLE = 123L
const val GAIA_GATE_MANAGERS_ROLE = 123L

const val SONIC_THE_HEDGEHOG = 123L

context(event: MessageReceivedEvent)
fun deprecationWarning(newUsage: String) {
    event.message.reply("Please use the following instead `$newUsage`").mentionRepliedUser(false).queue()
}

fun replaceKeywords(string: String, user: User?, guild: Guild?): String {
    var newString = string
    if (user != null) {
        newString = newString.replace("{usermention}", user.asMention)
    }
    if (guild != null) {
        newString = newString.replace("{guildname}", guild.name)
    }
    return newString
}
