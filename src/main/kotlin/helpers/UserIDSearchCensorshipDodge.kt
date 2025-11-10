package helpers

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User

// deleted user IDs can't be searched for, so we need to dodge the censorship
// we add a "x" before the last digit of the user ID

/**
 * Add an "x" before the last digit of the user ID. This is used to dodge the search censorship of deleted user IDs in Discord.
 *
 * @return The dodged user ID.
 */
fun Member.idDodged(): String {
    val userId = this.id
    return userId.substring(0, userId.length - 1) + "x" + userId.last()
}

fun User.idDodged(): String {
    val userId = this.id
    return userId.substring(0, userId.length - 1) + "x" + userId.last()
}

fun Long.idDodged(): String {
    val userId = this.toString()
    return userId.substring(0, userId.length - 1) + "x" + userId.last()
}