package helpers

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel

/**
 * This function removes all invalid IDs in the list (those which do not correspond to any channel in the guild).
 * It also announces the number of invalid IDs removed to a specific text channel, if any were removed.
 *
 * @param guild The guild to check the IDs against.
 * @param textChannel The text channel to announce the number of invalid IDs removed.
 * @return A new MutableList<Long> with all invalid IDs removed.
 */
fun MutableList<Long>.filterInvalidIDsAndAnnounce(guild: Guild, textChannel: GuildMessageChannel): MutableList<Long> {
    val invalids = filter { guild.getGuildChannelById(it) == null }

    // Announce the number of invalid IDs removed, if any were removed.
    when (invalids.size) {
        0 -> {}
        1 -> textChannel.sendMessage("${invalids.size} channel was removed from the list because it is no longer valid.")
            .queue()

        else -> textChannel.sendMessage("${invalids.size} channels were removed from the list because they are no longer valid.")
            .queue()
    }

    // Return a new MutableList<Long> with all invalid IDs removed.
    return filter { guild.getGuildChannelById(it) != null }.toMutableList()
}
