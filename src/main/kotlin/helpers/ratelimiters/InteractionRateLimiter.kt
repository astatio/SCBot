package helpers.ratelimiters

import helpers.ReusableScope
import helpers.formatDurationFromInstant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logger
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.utils.TimeFormat
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.*

data class UserRateLimit(
    val usage: Int, // The amount of times the user has used the command
    val rateLimitEnd: Long? // UNIX timestamp of when the rate limit end. If null, the user is not rate limited
)

private const val rateLimit = 5 // 5 times in checkDuration
private const val cooldown = 120000L // 2 minutes in milliseconds
private const val checkDuration = 120000L // 2 minutes in milliseconds

@OptIn(ExperimentalTime::class)
object InteractionRateLimiter {
    // Stores userID and the userRateLimit
    private val concurrentMap = ConcurrentHashMap<Long, UserRateLimit>()

    //the userID and the job that will remove them from the rlLimiter
    private val rlJobsCheckCD = ConcurrentHashMap<Long, Job>()
    private val rlJobsLimitedCD = ConcurrentHashMap<Long, Job>()

    private val ratelimitScope = ReusableScope.virtualThreadScope("ScheduledExpirationScope")


    // If its null means its not rate limited
    // otherwise return the markdown equivalent of the time left to end the rate limit
    //
    // i need this to return a boolean or a string
    //using TimeFormat.RELATIVE.format()

    /**
     * Checks if the user is rate limited. If they are, it will return the time left in Discords' markdown format.
     *
     * A user is considered rate limited if the same type of event is triggered 5 times in 2 minutes.
     * The rate limit lasts for 2 minutes.
     */
    fun ButtonInteractionEvent.rateLimitCheck(): String? {
        val currentTime = Clock.System.now().toEpochMilliseconds()
        //insert if not exists. it returns the current value or null if it didn't exist
        val check = concurrentMap.putIfAbsent(this.user.idLong, UserRateLimit(1, null))
        if (check != null) {
            //If it did have something we must increase the usage by one
            concurrentMap[this.user.idLong] = check.copy(
                usage = check.usage + 1
            )
        } else {
            //If it didn't exist, we must create a job that will reset the usage after 3 minutes
            val job = ratelimitScope.launch {
                delay(checkDuration.toDuration(DurationUnit.MILLISECONDS))
                val newValue = concurrentMap[this@rateLimitCheck.user.idLong]?.copy(
                    usage = 0
                )
                concurrentMap[this@rateLimitCheck.user.idLong] = newValue!!
            }
            rlJobsCheckCD[this.user.idLong] = job
            return null
        }

        //get the new values
        val userRL = concurrentMap[this.user.idLong]

        // If the user is not rate limited, we must check if they are close to it
        if (userRL?.rateLimitEnd == null) {
            if (userRL!!.usage >= rateLimit) {
                // The user is going to hit the limit with this command.
                // This execution will be allowed, but they will start the countdown
                // Alongside, they will start a new job that will delete the user from the map after the cooldown
                val job = ratelimitScope.launch {
                    delay(cooldown.toDuration(DurationUnit.MILLISECONDS))
                    concurrentMap.remove(this@rateLimitCheck.user.idLong)
                }
                rlJobsLimitedCD[this.user.idLong] = job
                concurrentMap[this.user.idLong] = userRL.copy(
                    rateLimitEnd = currentTime + cooldown
                )
                return null
            }
            // Here the user is not rate limited, but they are close to it.
            return null
        }
        if (currentTime > userRL.rateLimitEnd) {
            // Time has passed, but the job has not been executed.
            val instant2 = Instant.fromEpochMilliseconds(userRL.rateLimitEnd)
            logger.error {
                "A job did not delete the UserRateLimit from the map at the expected time! It's currently off by ${
                    formatDurationFromInstant(instant2)
                }"
            }
            // Check if the job is running or was cancelled
            val job = rlJobsLimitedCD[this.user.idLong]
            with(job) {
                when {
                    this == null -> logger.error { "The job was null." }
                    isActive -> {
                        logger.error { "The job is still active. Cancelling it." }
                        this.cancel()
                    }
                }
            }
            if (job != null && job.isActive) {
                logger.error { "The job is still active. Cancelling it." }
                job.cancel()
            }
            concurrentMap.remove(this.user.idLong)
            return null
        }
        //send the rate limit time left
        return TimeFormat.RELATIVE.format(userRL.rateLimitEnd)
    }
}