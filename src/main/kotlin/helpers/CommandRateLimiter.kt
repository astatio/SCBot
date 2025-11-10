package helpers

import net.dv8tion.jda.api.entities.Message
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class CommandRateLimit(
    val duration: Duration,
    val perUser: Int,
    val perGuild: Int,
)

enum class Command(val rateLimit: CommandRateLimit) {
    LEADERBOARD(CommandRateLimit(5.minutes, 1, 2)),
}

fun Command.isOnCooldown(userId: Long, guildId: Long): Boolean {
    return CommandRateLimiter.isOnCooldown(this.name, userId, guildId)
}

/**
 * Replies to the message with the following text:
 * "Please wait a bit before trying again."
 */
fun Message.sendRLmessage() {
    this.reply("Please wait a bit before trying again.").queue()
}

object CommandRateLimiter {
    private val rateLimits = ConcurrentHashMap<String, CommandRateLimit>()
    private val userCommandTimestamps = ConcurrentHashMap<Long, MutableList<Long>>()
    private val guildCommandTimestamps = ConcurrentHashMap<Long, MutableList<Long>>()

    fun isOnCooldown(command: String, userId: Long, guildId: Long): Boolean {
        val rateLimit = rateLimits[command] ?: return false

        if (isRateLimited(userCommandTimestamps, userId, rateLimit.perUser, rateLimit.duration.inWholeMilliseconds)) {
            return true
        }

        if (isRateLimited(
                guildCommandTimestamps,
                guildId,
                rateLimit.perGuild,
                rateLimit.duration.inWholeMilliseconds
            )
        ) {
            return true
        }

        addTimestamp(userCommandTimestamps, userId)
        addTimestamp(guildCommandTimestamps, guildId)

        return false
    }

    private fun isRateLimited(
        map: ConcurrentHashMap<Long, MutableList<Long>>,
        key: Long,
        limit: Int,
        durationMillis: Long
    ): Boolean {
        val list = map[key] ?: mutableListOf()
        val currentTime = Instant.now().toEpochMilli()
        list.removeIf { timestamp -> currentTime - timestamp > durationMillis }
        return list.size >= limit
    }

    private fun addTimestamp(map: ConcurrentHashMap<Long, MutableList<Long>>, key: Long) {
        val list = map.getOrDefault(key, mutableListOf())
        list.add(Instant.now().toEpochMilli())
        map[key] = list
    }

    fun setRateLimit(command: String, rateLimit: CommandRateLimit) {
        rateLimits[command] = rateLimit
    }
}
