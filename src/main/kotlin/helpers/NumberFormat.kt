package helpers

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

object NumberFormat {

    /**
     * Format a number to 1.1M pattern
     *
     *
     * If the number is bigger than 999, it will be formatted to a string with 3 digits and a K at the end.
     *
     * If the number is bigger than 999999, it will be formatted to a string with 3 digits and a M at the end.
     *
     * If the number is bigger than 999999999, it will be formatted to a string with 3 digits and a B at the end.
     *
     * @return The formatted number as a String
     */
    fun shorten(num: Long): String {
        val decimalFormat = DecimalFormat("#.#")
        return when {
            num > 1_000_000_000 -> "${decimalFormat.format(num / 1_000_000_000.0)}b"
            num > 1_000_000 -> "${decimalFormat.format(num / 1_000_000.0)}m"
            num > 1_000 -> "${decimalFormat.format(num / 1_000.0)}k"
            else -> decimalFormat.format(num)
        }
    }

    /**
     * Format a number to 1,000,000.00 pattern
     *
     * @return The formatted number as a String
     */
    fun withCommas(num: Long): String {
        val decimalFormat = DecimalFormat("#,###.#")
        return decimalFormat.format(num)
    }

    /**
     * Format a number to 1 000 000.00 pattern
     *
     * @return The formatted number as a String
     */
    fun withWhitespaces(num: Long): String {
        val symbols = DecimalFormatSymbols().apply { groupingSeparator = ' ' }
        val dec = DecimalFormat("###,###", symbols)
        return dec.format(num)
    }
}

/**
 * Format a number to 1.1M pattern
 *
 *
 * If the number is bigger than 999, it will be formatted to a string with 3 digits and a K at the end.
 *
 * If the number is bigger than 999999, it will be formatted to a string with 3 digits and a M at the end.
 *
 * If the number is bigger than 999999999, it will be formatted to a string with 3 digits and a B at the end.
 *
 * @return The formatted number as a String
 */
fun Long.shorten() = NumberFormat.shorten(this)
/**
 * Format a number to 1,000,000.00 pattern
 *
 * @return The formatted number as a String
 */
fun Long.withCommas() = NumberFormat.withCommas(this)
/**
 * Format a number to 1 000 000.00 pattern
 *
 * @return The formatted number as a String
 */
fun Long.withWhitespaces() = NumberFormat.withWhitespaces(this)

