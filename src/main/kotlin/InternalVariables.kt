import com.mongodb.kotlin.client.coroutine.MongoDatabase
import helpers.JobProducerScheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.serialization.json.Json
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDAInfo
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent


val logger = KotlinLogging.logger {}

//STATIC VALUES
const val INVISIBLE_EMBED_COLOR = 0x2b2d31
const val FEATURE_NOT_IMPLEMENTED = "This feature is not implemented yet. Try again in a few days!"

//ENVIRONMENT VARIABLES
val BOT_NAME = System.getenv("BOT_NAME") ?: "BOT_NAME"
val OMDB_KEY: String? = System.getenv("OMDB_KEY") ?: run {
    logger.error { "The OMDB_KEY environment variable is not set. The OMDB API will not be available." }
    null
}

val onlineStatusEmojiRepresentation = mapOf(
    OnlineStatus.ONLINE to "🟢",
    OnlineStatus.IDLE to "🟡",
    OnlineStatus.DO_NOT_DISTURB to "🔴",
    OnlineStatus.INVISIBLE to "⚫",
    OnlineStatus.OFFLINE to "⚫",
    OnlineStatus.UNKNOWN to "❓"
)

var mimeJob: JobProducerScheduler? = null
var returnJob: JobProducerScheduler? = null

/**
 * Send an ephemeral message to the hook with the [FEATURE_NOT_IMPLEMENTED] text.
 */
fun SlashCommandInteractionEvent.featureNotImplemented() =
    this.hook.sendMessage(FEATURE_NOT_IMPLEMENTED).setEphemeral(true).queue()

val JDAVERSION = "%s.%s.%s%s".format(
    JDAInfo.VERSION_MAJOR, JDAInfo.VERSION_MINOR, JDAInfo.VERSION_REVISION,
    "-" + JDAInfo.VERSION_CLASSIFIER
)


val mjson: Json by lazy {
    Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
}

val handler = CoroutineExceptionHandler { context, exception ->
    logger.trace(exception) {
        "Exception caught in $context: $exception"
    }
}

internal lateinit var database: MongoDatabase
internal lateinit var jda: JDA

internal var spamPrevention: Boolean = false
internal var memberJoin: Boolean = false
//This can be changed at any moment
var owners: Array<Long> = arrayOf()
