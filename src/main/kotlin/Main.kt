import com.mongodb.kotlin.client.coroutine.MongoClient
import commands.core.BotStatuses
import commands.ranks.Ranks
import dev.minn.jda.ktx.jdabuilder.createJDA
import helpers.TicketNotifier
import io.prometheus.metrics.exporter.httpserver.HTTPServer
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import listeners.ButtonInteractionsImpl
import listeners.GuildCommandsImpl
import listeners.Listeners
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import java.util.*
import kotlin.contracts.ExperimentalContracts


//Create a coroutine dispatcher and a supervisor job
// stop all the jobs when one of the coroutines fails

val localDatabase by lazy {
    MongoClient.create("mongodb://yourdb:yourpassword@123.456.789.455:27017").getDatabase("scbot_db")
} 

val myScope = CoroutineScope(Dispatchers.Default + SupervisorJob())


@OptIn(ExperimentalContracts::class)
fun main() {
    // trackCoroutines()
    val token = System.getenv("BOT_TOKEN") ?: throw IllegalArgumentException("No token found")
    
    val jda = createJDA(
        token,
        intents = listOf(
            GatewayIntent.GUILD_MEMBERS,
            GatewayIntent.GUILD_MODERATION,
            GatewayIntent.GUILD_EXPRESSIONS,
            GatewayIntent.GUILD_INVITES,
            GatewayIntent.GUILD_MESSAGES,
            GatewayIntent.DIRECT_MESSAGES,
            GatewayIntent.MESSAGE_CONTENT,
            GatewayIntent.GUILD_PRESENCES,
            GatewayIntent.GUILD_MESSAGE_REACTIONS,
        )
    ) {
        setMemberCachePolicy(MemberCachePolicy.ALL) //necessary to make GuildMemberRemove work
        setBulkDeleteSplittingEnabled(false) //required in order to enable MessageBulkDeleteEvent
        enableCache(CacheFlag.ACTIVITY)
        disableCache(
            CacheFlag.SCHEDULED_EVENTS,
            CacheFlag.VOICE_STATE,
            CacheFlag.CLIENT_STATUS,
            CacheFlag.ONLINE_STATUS
        )
    }.awaitReady()

    JvmMetrics.builder().register()
    HTTPServer.builder().port(19080).buildAndStart()
    logger.info { "Prometheus metrics server started at http://localhost:19080/metrics" }

    initializeRequirements {
        database = localDatabase
        this.jda = jda
    }

    initializeOptionals {
        spamPrevention = true
        onMemberJoin = false
        owners = arrayOf(186147402078617600L) // I need to add my user here
    }

    Ranks.setBannerHandler(CustomBannerHandler)

    Locale.setDefault(
        Locale.Builder()
            .setLanguage("en")
            .setRegion("US")
            .build()
    )
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    Listeners.start(jda, SlashCommandsImpl, GuildCommandsImpl, ButtonInteractionsImpl)

    myScope.launch {
        SlashCommandsUpdatesImpl.start(jda)
    }
    myScope.launch(handler) {
        BotStatuses.startBotStatuses(jda)
    }

    myScope.launch(handler) {
        TicketNotifier.schedule()
    }


    // To perform tasks
    myScope.launch(handler) {
        try {
            mimeJob = EntryController.mimeCheckJob(jda)
            returnJob = EntryController.returnCheckJob(jda)
            EntryController.jobInit(jda)
        } catch (e: Exception) {
            // Log the exception, or handle it in some other way
            logger.error(e) { "Exception caught in job: ${e.message}" }
        }
    }
}
