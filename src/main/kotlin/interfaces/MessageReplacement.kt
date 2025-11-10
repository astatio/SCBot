package interfaces

import helpers.EventWrapper
import helpers.SuperEmbed
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

// A data class to hold relevant context from different event types
data class EventContext(
    val user: User?,
    val guild: Guild?,
    val member: net.dv8tion.jda.api.entities.Member?
)

// Simplified interface that works with generic event context
interface MessageReplacement {
    fun toReplace(): String
    val thisWill: String

    // Primary method using EventContext
    fun replacement(context: EventContext): String

    fun condition(event: EventWrapper): Boolean = true
}


/**
 * Appends information about relevant message replacements to the NeoSuperEmbed's text.
 * It checks if the current text contains placeholders and if their conditions are met.
 */
fun appendRelevantReplacementsInfo(
    contentToAnalyze: String,
    eventWrapper: EventWrapper): String {
    val text = StringBuilder()
    MessageReplacements.getAllReplacements().forEach { replacement ->
        if (contentToAnalyze.contains(replacement.toReplace()) && replacement.condition(eventWrapper)) {
            text.append("\n${replacement.thisWill}")
        }
    }
    return text.toString()
}

/**
 * A centralized registry for all message replacements in the bot
 */
object MessageReplacements {

    private val replacements = mutableMapOf<String, MessageReplacement>()

    init {
        // Register standard replacements
        register(
            "{usermention}",
            "will be replaced with the user's mention"
        ) { ctx -> ctx.user?.asMention ?: "User" }

        register(
            "{membermention}",
            "will be replaced with the user's mention"
        ) { ctx -> ctx.member?.asMention ?: "Member" }

        register(
            "{guildname}",
            "will be replaced with the server name"
        ) { ctx -> ctx.guild?.name ?: "Guild" }

        register(
            "{blissdog}",
            "will be replaced by nothing and append a dog image URL to the message"
        ) { _ -> getRandomDogImage() }
    }

    private fun getRandomDogImage(): String {
        val json = JSONObject(File("src/main/resources/rank/blissdogs/dog_images.json").readText())
            .getJSONArray("dog_urls")
        val randomIndex = Random.nextInt(0, json.length())
        return json[randomIndex] as String
    }

    /**
     * Register a new replacement pattern
     */
    fun register(pattern: String, description: String, replacer: (EventContext) -> String) {
        replacements[pattern] = object : MessageReplacement {
            override fun toReplace() = pattern
            override val thisWill = "`$pattern` $description"
            override fun replacement(context: EventContext) = replacer(context)
        }
    }

    /**
     * Apply all registered replacements to a message
     */
    fun applyReplacements(message: String, context: EventContext): String {
        var result = message
        replacements.forEach { (pattern, replacement) ->
            if (result.contains(pattern)) {
                result = result.replace(pattern, replacement.replacement(context))
            }
        }
        return result
    }

    // Convenience methods for event types
    fun applyReplacements(message: String, event: MessageReceivedEvent): String =
        applyReplacements(message, EventContext(event.author, event.guild, event.member))

    fun applyReplacements(message: String, event: GuildMemberJoinEvent): String =
        applyReplacements(message, EventContext(event.user, event.guild, event.member))

    /**
     * Get all registered replacements
     */
    fun getAllReplacements(): List<MessageReplacement> = replacements.values.toList()
}