package filters

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates.*
import commands.core.Alerts
import commands.core.sendAlert
import commands.queueFailure
import database
import dev.minn.jda.ktx.coroutines.await
import helpers.*
import info.debatty.java.stringsimilarity.JaroWinkler
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import logger
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

object InstantBan {

    val wordCache = mutableMapOf<Long, Cache<List<Word>>>()

    suspend fun fetchWords(guildId: Long): List<Word> {
        return instantBanCollection.find(eq(Word::guildId.name, guildId)).toList()
    }

    data class Word(
        val name: String,
        val action: String,
        val guildId: Long,
        val timeoutMinutes: Long? = null,
        val warnThreshold: Int? = null,
        val onMaxWarn: String? = null,
        val alert: Boolean = false,
        val severeAlert: Boolean = false
    )

    data class WarnState(
        val userId: Long,
        val guildId: Long,
        val warns: Int
    )

    private const val DEFAULT_TIMEOUT_MINUTES = 10L
    private const val DEFAULT_WARN_THRESHOLD = 3
    private const val INSTANT_BAN_REASON = "Filtered by InstantBan (prohibited link or word)"
    private val allowedActions = setOf("ban", "kick", "timeout", "warn")
    private val allowedEscalations = setOf("ban", "kick", "timeout", "none")

    val instantBanCollection = database.getCollection<Word>("word")
    private val warnStateCollection = database.getCollection<WarnState>("instantBanWarns")

    // [x] /instantban add <word> <action> (action is optional)
    // [-] /instantban remove <word> - removes the word from the list. Needs to use autocomplete.
    // [x] /instantban addmany <word> <word> ... - adds many words at once. They'll all have the default action of "ban".
    // [x] /instantban list - lists all the words and their actions. It's sent through DMs.
    // [x] /instantban change <word> <action> - changes the action of the word. It can only be "ban" or "kick". Needs to use autocomplete.
    // [x] /instantban flush - flushes the entire list of words. Needs confirmation.

    suspend fun addWord(
        event: SlashCommandInteractionEvent,
        word: String,
        action: String,
        timeoutMinutes: Long?,
        warnThreshold: Int?,
        onMaxWarn: String?,
        alert: Boolean?,
        severeAlert: Boolean?
    ) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val guildId = event.guild!!.idLong

        try {
            // Reject words with more than 100 characters
            if (word.length > 100) {
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "The word is too long. It must be less than 100 characters."
                    }).queue()
                return
            }
            val normalizedAction = action.lowercase(Locale.getDefault())
            if (normalizedAction !in allowedActions) {
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "Invalid action specified. Allowed actions: ${allowedActions.joinToString(", ")}"
                    }).queue()
                return
            }

            // Check if the word already exists. Lowercase is needed to avoid duplicates.
            val lowerWord = word.lowercase(Locale.getDefault())
            if (instantBanCollection.find(
                    and(
                        eq(Word::guildId.name, guildId),
                        eq(Word::name.name, lowerWord)
                    )
            ).firstOrNull() != null) {
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text =
                            "The word is already in the list. If you want to change the action, use `/instantban change`."
                    }).queue()
                return
            }
            val sanitizedTimeout = timeoutMinutes?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_MINUTES
            val sanitizedWarnThreshold = warnThreshold?.takeIf { it > 0 } ?: DEFAULT_WARN_THRESHOLD
            val normalizedOnMaxWarn = onMaxWarn?.lowercase(Locale.getDefault())
                ?.takeIf { it in allowedEscalations } ?: "none"
            val shouldAlert = alert ?: false
            val shouldSevereAlert = (severeAlert ?: false) && shouldAlert

            // Create new word
            val newWord = Word(
                name = lowerWord,
                action = normalizedAction,
                guildId = guildId,
                timeoutMinutes = when {
                    normalizedAction == "timeout" -> sanitizedTimeout
                    normalizedOnMaxWarn == "timeout" -> sanitizedTimeout
                    else -> null
                },
                warnThreshold = if (normalizedAction == "warn") sanitizedWarnThreshold else null,
                onMaxWarn = if (normalizedAction == "warn") normalizedOnMaxWarn else null,
                alert = shouldAlert,
                severeAlert = shouldSevereAlert
            )

            // Insert a new word into the collection
            instantBanCollection.insertOne(newWord)

            //Force update the cache
            wordCache[guildId]?.forceUpdate { fetchWords(guildId) }

            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "Added the word to the list with the action of `${newWord.action}`."
                }).queue()
        } catch (t: Throwable) {
            logger.trace(t) {
                "Error while adding a word to an InstantBan collection."
            }
            event.hook.sendMessageEmbeds(
                throwEmbed(t, "Error while adding a word to InstantBan.")
            ).queue()
            return
        }
    }


    //Precompiled regex pattern
    private val spacePatternRegex = "\\s+".toRegex()

    suspend fun addMany(event: SlashCommandInteractionEvent, manyWords: String) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val guildId = event.guild!!.idLong

        // Split words by space
        val wordsList = manyWords.split(spacePatternRegex)

	// Check for words longer than 100 characters.
        wordsList.forEach {
            if (it.length > 100) {
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "The word `$it` is too long. It must be less than 100 characters. No words were added."
                    }).queue()
                return
            }
        }

        if (wordsList.isEmpty()) {
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "There are no words to add."
                }
            ).queue()
            return
        }

        try {
            event.sendConfirmationPrompt(
                description = "Are you sure you want to add all these words? They will all have the default action of `ban` which can only be changed afterwards."
            ) {
                // Add the words to the database
                wordsList.forEach { word ->
                    instantBanCollection.insertOne(
                        Word(
                            name = word.lowercase(Locale.getDefault()),
                            action = "ban",
                            guildId = guildId
                        )
                    )
                }

                //Force update the cache
                wordCache[guildId]?.forceUpdate { fetchWords(guildId) }

                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "All words have been added. If you wish to change the action of them, use `/instantban change`."
                }
            }
        } catch (e: Throwable) {
            logger.trace(e) {
                "Error while adding many words to an InstantBan collection."
            }
            event.hook.sendMessageEmbeds(
                throwEmbed(e)
            ).queue()
        }
    }

    suspend fun handleAutoComplete(event: CommandAutoCompleteInteractionEvent) {
        if (event.focusedOption.name == "word") {
            if (!wordCache.containsKey(event.guild!!.idLong)) {
                wordCache[event.guild!!.idLong] = Cache()
            }
            val words = wordCache[event.guild!!.idLong]!!.request { fetchWords(event.guild!!.idLong) }
                .map { it.name }
                .filter { JaroWinkler().similarity(it, event.focusedOption.value) > 0.6 }
                .take(25)
            event.replyChoiceStrings(words).queue()
        }
    }

    suspend fun removeWord(event: SlashCommandInteractionEvent, word: String) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val guildId = event.guild!!.idLong

        try {
            // Check if word exists
            val lowerWord = word.lowercase(Locale.getDefault())
            val existingWord = instantBanCollection.find(
                and(
                    eq(Word::guildId.name, guildId),
                    eq(Word::name.name, lowerWord)
                )
            ).firstOrNull()
            if (existingWord == null) {
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "The word is not in the list."
                    }).queue()
                return
            }

            // Remove the word from the collection
            val deleteResult = instantBanCollection.deleteOne(
                and(
                    eq(
                        Word::guildId.name,
                        guildId
                    ), eq(Word::name.name, lowerWord)
                )
            )

            if (deleteResult.deletedCount > 0) {
                wordCache[guildId]?.forceUpdate { fetchWords(guildId) }
            }

            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "Removed the word from the list."
                }).queue()
        } catch (e: Throwable) {
            e.printStackTrace()
            event.hook.sendMessageEmbeds(
                throwEmbed(e)
            ).queue()
            return
        }
    }

    suspend fun listWords(event: SlashCommandInteractionEvent) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val guildId = event.guild!!.idLong

        try {
            val guildWords = fetchWords(guildId)
            if (guildWords.isEmpty()) {
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "There are no words set."
                    }
                ).queue()
                return
            }

            val wordList = guildWords.joinToString("\n") { it.toDisplayString() }
            try {
                event.user.openPrivateChannel().await().sendMessage("## WORDS LIST\n${event.guild!!.name}\n$wordList")
                    .await()
            } catch (e: Throwable) {
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "I couldn't send you a DM. Please enable DMs from server members and try again."
                    }
                ).queue()
                return
            }
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "I've sent you a DM with the list of words."
                }
            ).queue()
        } catch (e: Throwable) {
            e.printStackTrace()
            event.hook.sendMessageEmbeds(
                throwEmbed(e)
            ).queue()
        }
    }

    suspend fun changeWord(
        event: SlashCommandInteractionEvent,
        wordToChange: String,
        newAction: String,
        timeoutMinutes: Long?,
        warnThreshold: Int?,
        onMaxWarn: String?,
        alert: Boolean?,
        severeAlert: Boolean?
    ) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val lowercaseAction = newAction.lowercase(Locale.getDefault())
        if (lowercaseAction !in allowedActions) {
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "Invalid action specified. Allowed actions: ${allowedActions.joinToString(", ")}"
                }
            ).queue()
            return
        }
        val guildId = event.guild!!.idLong

        try {
            val lowerWord = wordToChange.lowercase(Locale.getDefault())
            val word = instantBanCollection.find(
                and
                (eq(Word::guildId.name, guildId),
                eq(Word::name.name, lowerWord))
            ).firstOrNull()
            if (word == null) {
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "The specified word does not exist."
                    }
                ).queue()
                return
            }
            if (word.action == lowercaseAction) {
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "The word is already set to that action."
                    }
                ).queue()
                return
            }

            val sanitizedTimeout = timeoutMinutes?.takeIf { it > 0 }
                ?: word.timeoutMinutes
                ?: DEFAULT_TIMEOUT_MINUTES
            val sanitizedWarnThreshold = warnThreshold?.takeIf { it > 0 }
                ?: word.warnThreshold
                ?: DEFAULT_WARN_THRESHOLD
            val normalizedOnMaxWarn = onMaxWarn?.lowercase(Locale.getDefault())
                ?.takeIf { it in allowedEscalations }
                ?: word.onMaxWarn
                ?: "none"
            val shouldAlert = alert ?: word.alert
            val shouldSevereAlert = if (shouldAlert) (severeAlert ?: word.severeAlert) else false

            val updatedTimeout = when {
                lowercaseAction == "timeout" -> sanitizedTimeout
                normalizedOnMaxWarn == "timeout" -> sanitizedTimeout
                else -> null
            }
            val updatedWarnThreshold = if (lowercaseAction == "warn") sanitizedWarnThreshold else null
            val updatedOnMaxWarn = if (lowercaseAction == "warn") normalizedOnMaxWarn else null

            instantBanCollection.updateOne(
                filter = and(eq(Word::guildId.name, guildId), eq(Word::name.name, lowerWord)),
                update = combine(
                    set(Word::action.name, lowercaseAction),
                    set(Word::timeoutMinutes.name, updatedTimeout),
                    set(Word::warnThreshold.name, updatedWarnThreshold),
                    set(Word::onMaxWarn.name, updatedOnMaxWarn),
                    set(Word::alert.name, shouldAlert),
                    set(Word::severeAlert.name, shouldSevereAlert)
                )
            )

            wordCache[guildId]?.forceUpdate { fetchWords(guildId) }

            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "Updated the punishment for '$wordToChange' to '$lowercaseAction'."
                }
            ).queue()
        } catch (e: Throwable) {
            e.printStackTrace()
            event.hook.sendMessageEmbeds(
                throwEmbed(e)
            ).queue()
        }
    }

    suspend fun flush(event: SlashCommandInteractionEvent) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val guildId = event.guild!!.idLong

        try {
            val wordCount = instantBanCollection.find(eq(Word::guildId.name, guildId)).toList()

            if (wordCount.isEmpty()) {
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "There are no words to flush."
                    }
                ).queue()
                return
            }

            event.sendConfirmationPrompt(
                description = "Are you sure you want to flush all words? This cannot be undone."
            ) {
                instantBanCollection.deleteMany(eq(Word::guildId.name, guildId))

                //Force update the cache
                wordCache[guildId]?.forceUpdate { fetchWords(guildId) }

                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "All words have been flushed."
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            event.hook.sendMessageEmbeds(
                throwEmbed(e)
            ).queue()
        }
    }

    suspend fun findWords(event: MessageReceivedEvent) {
        if (!event.isFromGuild || event.author.isBot) return
        val guildId = event.guild.idLong
        if (!wordCache.containsKey(guildId)) {
            wordCache[guildId] = Cache()
        }
        try {
            val wordObjects = wordCache[guildId]?.request { fetchWords(guildId) } ?: return
            val content = event.message.contentDisplay
            val matched = wordObjects.firstOrNull { content.contains(it.name, ignoreCase = true) }
            if (matched != null) {
                applyMatchedWord(matched, event)
                event.message.delete().queue()
            }
        } catch (e: Throwable) {
            logger.error(e) {
                "Error while checking for prohibited words in InstantBan"
            }
        }
    }

    private suspend fun applyMatchedWord(word: Word, event: MessageReceivedEvent) {
        val member = event.member ?: return
        when (word.action) {
            "ban" -> banMember(member, event, "for sending a prohibited link or word")
            "kick" -> kickMember(member, event, "for sending a prohibited link or word")
            "timeout" -> timeoutMember(
                member,
                event,
                word.timeoutMinutes ?: DEFAULT_TIMEOUT_MINUTES,
                "for sending a prohibited link or word"
            )
            "warn" -> warnMember(word, member, event)
            else -> logger.warn { "InstantBan action '${word.action}' is not supported." }
        }

        if (word.alert) {
            sendInstantBanAlert(word, event)
        }
    }

    private suspend fun banMember(member: Member, event: MessageReceivedEvent, context: String) {
        runCatching {
            member.ban(0, TimeUnit.DAYS).reason(INSTANT_BAN_REASON).await()
        }.onSuccess {
            event.channel.sendMessage("A user was banned $context.").queue()
        }.onFailure {
            event.channel.sendMessageEmbeds(
                throwEmbed(it, "I was unable to ban due to:")
            ).queue()
        }
    }

    private fun kickMember(member: Member, event: MessageReceivedEvent, context: String) {
        member.kick().reason(INSTANT_BAN_REASON).queue({
            event.channel.sendMessage("A user was kicked $context.").queue()
        }, queueFailure(event.message, "I was unable to kick due to:"))
    }

    private suspend fun timeoutMember(
        member: Member,
        event: MessageReceivedEvent,
        minutes: Long,
        context: String
    ) {
        val safeMinutes = if (minutes <= 0) DEFAULT_TIMEOUT_MINUTES else minutes
        runCatching {
            member.timeoutFor(Duration.ofMinutes(safeMinutes)).reason(INSTANT_BAN_REASON).await()
        }.onSuccess {
            val formattedDuration = formatDuration(safeMinutes, TimeUnit.MINUTES)
            event.channel.sendMessage("A user was timed out for $formattedDuration $context.").queue()
        }.onFailure {
            event.channel.sendMessageEmbeds(
                throwEmbed(it, "I was unable to timeout due to:")
            ).queue()
        }
    }

    private suspend fun warnMember(word: Word, member: Member, event: MessageReceivedEvent) {
        val threshold = word.warnThreshold ?: DEFAULT_WARN_THRESHOLD
        val totalWarns = incrementWarns(event.guild.idLong, member.idLong)
        val channelMessage = "${member.asMention} received a warning for sending a prohibited link or word. ($totalWarns/$threshold)"
        event.channel.sendMessage(channelMessage).queue()

        runCatching {
            member.user.openPrivateChannel().await()
                .sendMessage("You received a warning in ${event.guild.name}. Current warnings: $totalWarns/$threshold.")
                .await()
        }.onFailure {
            logger.debug(it) { "Failed to DM user after InstantBan warning." }
        }

        if (totalWarns >= threshold) {
            resetWarns(event.guild.idLong, member.idLong)
            val escalation = word.onMaxWarn?.lowercase(Locale.getDefault()) ?: "none"
            if (escalation != "none") {
                executeEscalation(escalation, word, event, member)
            }
        }
    }

    private suspend fun incrementWarns(guildId: Long, userId: Long): Int {
        val options = FindOneAndUpdateOptions()
            .returnDocument(ReturnDocument.AFTER)
            .upsert(true)

        val updated = warnStateCollection.findOneAndUpdate(
            filter = and(
                eq(WarnState::guildId.name, guildId),
                eq(WarnState::userId.name, userId)
            ),
            update = combine(
                setOnInsert(WarnState::guildId.name, guildId),
                setOnInsert(WarnState::userId.name, userId),
                inc(WarnState::warns.name, 1)
            ),
            options = options
        )

        return updated?.warns ?: 1
    }

    private suspend fun resetWarns(guildId: Long, userId: Long) {
        warnStateCollection.deleteOne(
            and(
                eq(WarnState::guildId.name, guildId),
                eq(WarnState::userId.name, userId)
            )
        )
    }

    private suspend fun executeEscalation(
        escalationAction: String,
        word: Word,
        event: MessageReceivedEvent,
        member: Member
    ) {
        when (escalationAction) {
            "ban" -> banMember(member, event, "after reaching the warning threshold")
            "kick" -> kickMember(member, event, "after reaching the warning threshold")
            "timeout" -> timeoutMember(
                member,
                event,
                word.timeoutMinutes ?: DEFAULT_TIMEOUT_MINUTES,
                "after reaching the warning threshold"
            )
        }
    }

    private suspend fun sendInstantBanAlert(word: Word, event: MessageReceivedEvent) {
        val guild = event.guild ?: return
        sendAlert {
            command(InstantBan::class)
            severity(if (word.severeAlert) Alerts.Severity.IMPORTANT else Alerts.Severity.NORMAL)
            message("InstantBan triggered `${word.name}` in ${event.channel.asMention} by ${event.author.asTag}.")
            additionalInfo {
                put("Action", word.action)
                put("UserId", event.author.id)
                put("Message", event.message.contentDisplay.take(180))
            }
            guild(guild)
        }
    }

    private fun Word.toDisplayString(): String {
        val details = mutableListOf<String>()
        timeoutMinutes?.let { details += "timeout=${it}m" }
        warnThreshold?.let { threshold ->
            details += "warn-threshold=$threshold"
            onMaxWarn?.let { details += "on-max=$it" }
        }
        if (alert) {
            details += if (severeAlert) "alert=important" else "alert"
        }
        val extras = if (details.isEmpty()) "" else " • ${details.joinToString(", ")}"
        return "`${name} (${action})$extras`"
    }
}
