package helpers

import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class TimeUtil {

    private var realInt: Long = 0
    private var lastChar: Char = '\u0000'
    private val allowedChars: List<Char> = listOf('s', 'm', 'h', 'd')


    /**
     *
     * Returns the time unit given to parse() in a human-readable format
     *
     * Example: 's' -> "seconds"
     * @return String
     */
    val humanReadableTimeUnit: String
        get() =
            when (lastChar) {
                's' -> "second(s)"
                'm' -> "minute(s)"
                'h' -> "hour(s)"
                'd' -> "day(s)"
                else -> "" //This shouldn't be returned but "when" statements need to be exhaustive.
            }

    /**
     *
     * Converts value given to parse() in millisecond
     * @return Long
     */
    val toMillisecond: Long
        get() =
            when (lastChar) {
                's' -> realInt * 1000
                'm' -> realInt * 60 * 1000
                'h' -> realInt * 60 * 60 * 1000
                'd' -> realInt * 24 * 60 * 60 * 1000
                else -> 0 //This shouldn't be returned but "when" statements need to be exhaustive.
            }

    /**
     * Parses the given string
     *
     * @param time
     * @throws NumberFormatException if string does not represent a number when the last character is dropped
     * @throws IllegalArgumentException if the last character is not 's', 'm', 'h' or 'd'
     */
    fun parse(time: String): TimeUtil {
        realInt = time.dropLast(1).toLongOrNull()
            ?: throw NumberFormatException("The string \"${time.dropLast(1)}\" cannot be cast to Long because it is not a number.")
        // realInt is the number used.
        // Example: If the input is "20s" then realInt is "20". The conversion is done afterward.

        if (time.last() !in allowedChars) {
            // If it's not one of the recognized characters then it's useless to continue.
            // This check allows "toMillisecond" and "humanReadableTimeUnit" to not return null values.
            throw IllegalArgumentException("The last character in the string \"${time}\" is not one of the following: 's', 'm', 'h', 'd'.")
        }
        lastChar = time.last()
        return this
    }
}


/**
 * Formats the given duration in a human-readable format.
 *
 * @param value The duration value.
 * @param unit The time unit of the value.
 * @return A string representing the formatted duration.
 */
fun formatDuration(value: Long, unit: TimeUnit): String {
    var totalSeconds = TimeUnit.SECONDS.convert(value, unit)

    if (totalSeconds == 0L) {
        return "0 seconds"
    }

    val days = totalSeconds / (24 * 60 * 60)
    totalSeconds %= (24 * 60 * 60)
    val hours = totalSeconds / (60 * 60)
    totalSeconds %= (60 * 60)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60

    val parts = mutableListOf<String>()

    if (days > 0) {
        parts.add("$days day${if (days > 1) "s" else ""}")
    }
    if (hours > 0) {
        parts.add("$hours hour${if (hours > 1) "s" else ""}")
    }
    if (minutes > 0) {
        parts.add("$minutes minute${if (minutes > 1) "s" else ""}")
    }
    if (seconds > 0) {
        parts.add("$seconds second${if (seconds > 1) "s" else ""}")
    }


    if (parts.isEmpty()) { // Should not happen if totalSeconds > 0, but as a fallback
        return "less than a second"
    }

    return when (parts.size) {
        1 -> parts[0]
        2 -> "${parts[0]} and ${parts[1]}"
        else -> "${parts.dropLast(1).joinToString(", ")} and ${parts.last()}"
    }
}

/**
 * Formats the duration from a given timestamp to the current time in a human-readable format.
 * This is similar to PrettyTime's formatDurationUnrounded() functionality.
 *
 * @param timestamp The timestamp in milliseconds (epoch time)
 * @return A string representing the formatted duration from the timestamp to now
 */
@OptIn(ExperimentalTime::class)
fun formatDurationFromTimestamp(timestamp: Long): String {
    val currentTime = Clock.System.now().toEpochMilliseconds()
    val differenceMs = kotlin.math.abs(currentTime - timestamp)

    return formatDuration(differenceMs, TimeUnit.MILLISECONDS)
}

/**
 * Formats the duration from a given Instant to the current time in a human-readable format.
 * This is similar to PrettyTime's formatDurationUnrounded() functionality.
 *
 * @param instant The Instant to calculate duration from
 * @return A string representing the formatted duration from the instant to now
 */
@OptIn(ExperimentalTime::class)
fun formatDurationFromInstant(instant: Instant): String {
    return formatDurationFromTimestamp(instant.toEpochMilliseconds())
}
