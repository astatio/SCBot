package commands.core

import helpers.JobProducer
import helpers.JobProducerScheduler
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Activity
import org.apache.commons.rng.simple.RandomSource
import org.json.JSONObject
import kotlin.time.Duration.Companion.minutes

object BotStatuses {

    //There's some potential here for more than just show "playing" statuses. However, its not a priority or something being considered.

    val file = java.io.File("src/main/resources/settings.json")
    private val rng = RandomSource.MT.create()

    // Cycle through random statuses and display them in the bot's status.
    // Interval is 50 seconds.

    fun resolveRndType(value: Int) =
        when (value) {
            0 -> "playing"
            1 -> "streaming"
            2 -> "listening"
            3 -> "watching"
            4 -> "competing"
            else -> "playing"
        }

    // this will create a job that will cycle through the statuses and display them in the bot's status.
    // the JobProducer is from the helpers package
    private fun botStatusJob(jda: JDA) = JobProducerScheduler(
        JobProducer({
            file.readText().let {
                val statuses = JSONObject(it).getJSONArray("statuses")
                val rndStatus = statuses[rng.nextInt(statuses.length() - 1)].toString()
                jda.presence.activity = Activity.playing(rndStatus)
            }
        }, 50.minutes, 0)
    )

    fun startBotStatuses(jda: JDA) = botStatusJob(jda).start()

}