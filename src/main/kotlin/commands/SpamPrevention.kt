package commands

import dev.minn.jda.ktx.coroutines.await
import helpers.messageCacheCaffeine
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.sampling.DiscreteProbabilityCollectionSampler
import org.apache.commons.rng.simple.RandomSource
import spamPrevention

object SpamPrevention {

    private val warningMessagesCommon = listOf(
        "Please refrain from spamming",
        "Please stop spamming",
        "Please do not spam",
        "Please stop spamming, thank you",
        "Spamming is not nice",
        "Spamming is not good. Please stop",
        "You're spamming. Please stop",
        "Please stop spamming, it's not nice",
    )
    private val warningMessagesRare = listOf(
        "Please stop spamming, thank you :)",
        "Please stop spamming, thank you :D",
        "Please stop spamming, thank you :3",
        "Please stop spamming, thank you :>",
        "Please stop spamming, it's not nice :(",
        "Please stop spamming, it's not nice :<",
        "Please stop spamming, it's not nice :c",
        "Spamming is not nice :(",
        "You're spamming. Please stop ^-^",
    )

    private val rng: UniformRandomProvider = RandomSource.MT.create()
    private val bangCommandPattern = Regex("^![A-Za-z].*")

    fun warningMessageRNG(): String {

        val allStrings = warningMessagesCommon + warningMessagesRare

        // Weighted probability for each string
        val probabilities = warningMessagesCommon.map { 0.98 } + warningMessagesRare.map { 0.02 }

        // Create a map with string as key and its probability as value
        val weightedStrings = allStrings.zip(probabilities).toMap()

        // Create a sampler
        val sampler = DiscreteProbabilityCollectionSampler(rng, weightedStrings)

        // Get a random string based on probabilities

        return sampler.sample()
    }
    
    suspend fun onMessageReceived(event: MessageReceivedEvent) {
        if (!event.isFromGuild) return
        val message = event.message
        val author = message.author
        if (author.idLong == event.jda.selfUser.idLong || author.isBot) return
        if (!spamPrevention) return
        if (message.messageSnapshots.isNotEmpty()) return

        val rawContent = message.contentRaw
        val startsWithBangCommand = bangCommandPattern.matches(rawContent)
        if (startsWithBangCommand) return

        var counter = 0
        val thisAuthor = message.author
        val thisMessage = message.contentDisplay
        var spammed = false
        //these messages should be in cache so we don't need to retrieve them from the API
        messageCacheCaffeine.getMostRecentFiltered(3) { it.channelID == event.channel.idLong }.forEach {
            if (it.authorID == thisAuthor.idLong && it.contentDisplay == thisMessage) {
                if (it.contentDisplay.isBlank())
                    return@forEach //they might be spamming images by uploading them. we can't do anything about that.
                counter++
            }
            if (counter >= 3) {
                spammed = true
                purgeAuthorSpam(event.channel.asGuildMessageChannel(), thisAuthor, thisMessage)
            }
        }
        if (spammed) {
            val warningMessage = warningMessageRNG()
            try {
                author.openPrivateChannel().await().sendMessage(warningMessage).await()
            } catch (e: Exception) {
                message.channel.sendMessage("${author.asMention} $warningMessage").queue()
            }
        }
    }

    private suspend fun purgeAuthorSpam(
        channel: MessageChannel,
        author: User,
        initialMsgContentDisplay: String
    ) {
        messageCacheCaffeine.getMostRecentFiltered(3) {
            it.channelID == channel.idLong && it.authorID == author.idLong && it.contentDisplay == initialMsgContentDisplay
        }.forEach {
            try {
                channel.deleteMessageById(it.id).await()
                // if i delete from cache here, modlogs won't be able to get the message
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
