package filters

import com.ibm.icu.text.Transliterator
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import commands.filters.FilterCommons
import database
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.messages.Embed
import helpers.NeoSuperEmbed
import helpers.SuperEmbed
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import logger
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.user.update.UserUpdateNameEvent
import java.text.Normalizer
import java.util.*

object FilterPing {

    data class FilterSettings(
        val guildId: Long,
        val channelId: Long? = null,
        val enabled: Boolean = false,
        val whitelist: Whitelist = Whitelist()
    )

    data class Whitelist(
        val users: List<Long> = emptyList(),
        val roles: List<Long> = emptyList(),
        val phrases: List<String> = emptyList()
    )

    data class FilterEntry(
        val guildId: Long,
        val phrase: String,
        val pingUsers: List<Long> = emptyList()
    )

    private val settingsCollection = database.getCollection<FilterSettings>("filterSettings")
    private val filtersCollection = database.getCollection<FilterEntry>("filters")

    suspend fun handleCommand(event: SlashCommandInteractionEvent) {
        when (event.subcommandName) {
            "toggle" -> toggle(event)
            "channel" -> setChannel(event, event.getOption("channel")!!.asChannel.asGuildMessageChannel())
            "add" -> addFilter(event, event.getOption("phrase")!!.asString, event.getOption("users")?.asString)
            "remove" -> removeFilter(event, event.getOption("phrase")!!.asString)
            "whitelist" -> handleWhitelist(event)
            "list" -> listFilters(event)
            else -> event.reply("Unknown command").await()
        }
    }

    private suspend fun toggle(event: SlashCommandInteractionEvent) {
        updateSettings(event.guild!!.idLong) { currentSettings ->
            currentSettings.copy(enabled = !currentSettings.enabled)
        }
        event.replyEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "FilterPing ${if (!getSettings(event.guild!!.idLong).enabled) "enabled" else "disabled"}"
        }).await()
    }

    private suspend fun setChannel(event: SlashCommandInteractionEvent, channel: GuildChannel) {
        updateSettings(event.guild!!.idLong) { currentSettings ->
            currentSettings.copy(channelId = channel.idLong)
        }
        event.replyEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "Notification channel set to ${channel.asMention}"
        }).await()
    }

    private suspend fun addFilter(event: SlashCommandInteractionEvent, phrase: String, users: String?) {
        val userIds = users?.split(",")?.map { it.trim().toLong() } ?: emptyList()
        filtersCollection.updateOne(
            Filters.and(
                Filters.eq(FilterEntry::guildId.name, event.guild!!.idLong),
                Filters.eq(FilterEntry::phrase.name, phrase)
            ),
            Updates.combine(
                Updates.setOnInsert(FilterEntry::guildId.name, event.guild!!.idLong),
                Updates.addEachToSet(FilterEntry::pingUsers.name, userIds)
            ),
            UpdateOptions().upsert(true)
        )
        event.replyEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "Added filter for '$phrase'"
        }).await()
    }

    private suspend fun removeFilter(event: SlashCommandInteractionEvent, phrase: String) {
        filtersCollection.deleteOne(
            Filters.and(
                Filters.eq(FilterEntry::guildId.name, event.guild!!.idLong),
                Filters.eq(FilterEntry::phrase.name, phrase)
            )
        )
        event.replyEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "Removed filter for '$phrase'"
        }).await()
    }

    private suspend fun listFilters(event: SlashCommandInteractionEvent) {
        val filters = filtersCollection.find(Filters.eq(FilterEntry::guildId.name, event.guild!!.idLong)).toList()
        val response = filters.joinToString("\n") {
            "**${it.phrase}** - Pinging: ${it.pingUsers.joinToString(" ") { "<@$it>" }}"
        }

        val pages = pagify(response) // Reserve space for embed overhead

        if (pages.isEmpty()) {
            event.replyEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "No filters configured"
            }).await()
            return
        }

        event.deferReply().await()

        pages.forEachIndexed { index, page ->
            val embed = Embed {
                title = "Active Filters (Page ${index + 1}/${pages.size})"
                description = if (index == 0) {
                    "Current filter configuration:\n$page"
                } else {
                    page
                }
            }

            if (index == 0) {
                runCatching {
                    event.user.openPrivateChannel().await().sendMessageEmbeds(embed).await()
                }.onSuccess {
                    event.channel.sendMessageEmbeds(
                        NeoSuperEmbed {
                            type = SuperEmbed.ResultType.SIMPLE
                            text = "Check your DMs for the full list"
                        }).queue()
                }.onFailure {
                    event.channel.sendMessageEmbeds(NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "Failed to send DMs with the full list. Do you allow DMs from this server members?"
                    }).queue()
                }
            } else {
                runCatching {
                    event.user.openPrivateChannel().await().sendMessageEmbeds(embed).await()
                }.onSuccess {
                    event.channel.sendMessageEmbeds(
                        NeoSuperEmbed {
                            type = SuperEmbed.ResultType.SIMPLE
                            text = "Check your DMs for the full list"
                        }).queue()
                }.onFailure {
                    event.channel.sendMessageEmbeds(NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "Failed to send DMs with the full list. Do you allow DMs from this server members?"
                    }).queue()
                }
            }
        }
    }

    private suspend fun handleWhitelist(event: SlashCommandInteractionEvent) {
        val type = event.getOption("type")?.asString ?: run {
            event.hook.sendMessage("Missing whitelist type").await()
            return
        }
        val action = event.getOption("action")?.asString ?: run {
            event.hook.sendMessage("Missing action").await()
            return
        }

        when (type.lowercase()) {
            "phrase" -> handlePhraseWhitelist(event, action)
            "user" -> handleUserWhitelist(event, action)
            "role" -> handleRoleWhitelist(event, action)
            else -> event.reply("Invalid whitelist type").await()
        }
    }

    private suspend fun handlePhraseWhitelist(event: SlashCommandInteractionEvent, action: String) {
        val phrases = event.getOption("phrases")?.asString?.split(",")?.map { it.trim().lowercase() } ?: emptyList()
        updateWhitelist(
            event.guild!!.idLong,
            "phrases",
            phrases,
            action
        )
        event.reply("Phrase whitelist updated").await()
    }

    private suspend fun updateWhitelist(guildId: Long, field: String, values: List<String>, action: String) {
        val update = when (action.lowercase()) {
            "add" -> Updates.addEachToSet("whitelist.$field", values)
            "remove" -> Updates.pullAll("whitelist.$field", values)
            else -> return
        }

        settingsCollection.updateOne(
            Filters.eq(FilterSettings::guildId.name, guildId),
            update,
            UpdateOptions().upsert(true)
        )
    }

    private suspend fun handleUserWhitelist(event: SlashCommandInteractionEvent, action: String) {
        // Extract user IDs from command arguments
        val usersInput = event.getOption("users")?.asString ?: ""
        val userIds = usersInput.split(",").mapNotNull { arg ->
            // Extract digits from both mentions and raw IDs
            val idString = arg.trim().filter { it.isDigit() }
            if (idString.isNotEmpty()) {
                try {
                    idString.toLong()
                } catch (e: NumberFormatException) {
                    null
                }
            } else null
        }.filter { userId ->
            // Verify user exists in guild
            event.guild?.getMemberById(userId) != null
        }

        updateWhitelist(
            event.guild!!.idLong,
            "users",
            userIds.map { it.toString() },
            action
        )

        event.reply("User whitelist updated (${userIds.size} valid users processed)").await()
    }

    private suspend fun getSettings(guildId: Long): FilterSettings {
        return settingsCollection.find(Filters.eq(FilterSettings::guildId.name, guildId)).firstOrNull()
            ?: FilterSettings(guildId)
    }

    private suspend fun updateSettings(guildId: Long, updateFn: (FilterSettings) -> FilterSettings) {
        val currentSettings = settingsCollection.find(Filters.eq(FilterSettings::guildId.name, guildId)).firstOrNull()
            ?: FilterSettings(guildId)

        val newSettings = updateFn(currentSettings)

        settingsCollection.updateOne(
            Filters.eq(FilterSettings::guildId.name, guildId),
            Updates.combine(
                Updates.setOnInsert(FilterSettings::guildId.name, guildId),
                Updates.set(FilterSettings::channelId.name, newSettings.channelId),
                Updates.set(FilterSettings::enabled.name, newSettings.enabled),
                Updates.set("whitelist.users", newSettings.whitelist.users),
                Updates.set("whitelist.roles", newSettings.whitelist.roles),
                Updates.set("whitelist.phrases", newSettings.whitelist.phrases)
            ),
            UpdateOptions().upsert(true)
        )
    }

    suspend fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return
        if (event.message.contentDisplay.contains("filterping", ignoreCase = true)) return
        val settings = getSettings(event.guild.idLong)
        if (!settings.enabled || settings.channelId == null) return

        val member = event.member ?: return
        if (isWhitelisted(settings, member)) return

        val content = normalizeText(event.message.contentRaw).lowercase()
        val whitelistCount = settings.whitelist.phrases.sumOf { phrase ->
            phrase.countIn(content) // Implement countIn() as in Python
        }
        val filters = filtersCollection.find(Filters.eq(FilterEntry::guildId.name, event.guild.idLong)).toList()

        filters.forEach { filter ->
            val badCount = content.split(filter.phrase).size - 1
            if (badCount > 0 && whitelistCount < badCount) {
                sendNotification(event.message, filter, settings)
                return
            }
        }
    }

    suspend fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
        val settings = getSettings(event.guild.idLong)
        if (!settings.enabled || settings.channelId == null) return

        val member = event.member
        if (isWhitelisted(settings, member)) return

        val content = normalizeText(member.effectiveName).lowercase()
        val filters = filtersCollection.find(Filters.eq(FilterEntry::guildId.name, event.guild.idLong)).toList()

        if (settings.whitelist.phrases.any { content.contains(it) }) return

        filters.firstOrNull { content.contains(it.phrase) }?.let { filter ->
            val mentions = filter.pingUsers.joinToString(" ") { "<@$it>" }
            val censored = censor(member.effectiveName, listOf(filter.phrase))

            event.guild.getTextChannelById(settings.channelId)?.sendMessage(
                "${member.asMention} joined with filtered username: $censored\n$mentions"
            )?.queue()
        }
    }

    suspend fun onGuildMemberUpdateNickname(event: GuildMemberUpdateNicknameEvent) {
        val guild = event.guild
        val settings = getSettings(guild.idLong)
        if (!settings.enabled || settings.channelId == null) return

        val member = event.member
        if (isWhitelisted(settings, member)) return

        val oldNick = event.oldNickname
        val newNick = event.newNickname
        val content = normalizeText(newNick ?: "").lowercase()

        handleNameChange(
            member = member,
            oldName = oldNick,
            newName = newNick,
            guild = guild,
            settings = settings,
            changeType = "nickname"
        )
    }

    suspend fun onUserUpdateName(event: UserUpdateNameEvent) {
        val user = event.user
        // Check all mutual guilds where the name change might be relevant
        user.mutualGuilds.forEach { guild ->
            val settings = getSettings(guild.idLong)
            if (!settings.enabled || settings.channelId == null) return@forEach

            val member = guild.getMember(user) ?: return@forEach
            if (isWhitelisted(settings, member)) return@forEach

            val oldName = event.oldName
            val newName = event.newName

            handleNameChange(
                member = member,
                oldName = oldName,
                newName = newName,
                guild = guild,
                settings = settings,
                changeType = "username"
            )
        }
    }

    private suspend fun handleNameChange(
        member: Member,
        oldName: String?,
        newName: String?,
        guild: Guild,
        settings: FilterSettings,
        changeType: String
    ) {
        val content = normalizeText(newName ?: return).lowercase()
        val filters = filtersCollection.find(Filters.eq(FilterEntry::guildId.name, guild.idLong)).toList()

        // Check against whitelist phrases
        if (settings.whitelist.phrases.any { normalizeText(it) in normalizeText(content) }) return

        filters.firstOrNull { filter ->
            content.contains(filter.phrase)
        }?.let { matchingFilter ->
            sendNameNotification(
                member = member,
                oldName = oldName,
                newName = newName,
                filter = matchingFilter,
                settings = settings,
                changeType = changeType
            )
        }
    }

    private suspend fun sendNameNotification(
        member: Member,
        oldName: String?,
        newName: String,
        filter: FilterEntry,
        settings: FilterSettings,
        changeType: String
    ) {
        val channel = member.guild.getTextChannelById(settings.channelId!!) ?: return
        val mentions = filter.pingUsers.joinToString(" ") { "<@$it>" }
        val censored = censor(newName, listOf(filter.phrase))

        val msg = when (changeType) {
            "nickname" -> "${member.asMention} changed their nickname: " +
                    "$censored (was: ${oldName ?: "none"})\n$mentions"

            "username" -> "${member.asMention} changed their username: " +
                    "$censored\n$mentions"

            else -> return
        }

        try {
            channel.sendMessage(msg).queue()
        } catch (e: Exception) {
            logger.error(e) { "Failed to send name change notification" }
        }
    }

    private suspend fun sendNotification(message: Message, filter: FilterEntry, settings: FilterSettings) {
        val channel = message.guild.getTextChannelById(settings.channelId!!) ?: return
        val mentions = filter.pingUsers.joinToString(" ") { "<@$it>" }
        val censoredWords = filtersCollection.find(Filters.eq(FilterEntry::guildId.name, message.guild.idLong))
            .toList()
            .map { it.phrase }

        // Censor both the matched phrase and full message
        val censoredPhrase = censor(filter.phrase, censoredWords)
        val censoredContent = censor(message.contentRaw, censoredWords)
        val unicodeDodge = hasUnicodeDodge(message.contentRaw)

        // Build base message
        var msg = "${message.author.asMention} said a filtered phrase in ${message.channel.asMention}: " +
                "$censoredPhrase\nMessage: $censoredContent\n$mentions"

        if (unicodeDodge) {
            msg += "\nUser attempted to dodge filter with unicode"
        }

        // Add message link in embed
        val embed = NeoSuperEmbed {
            type = SuperEmbed.ResultType.ALERT
            description = "[Message Link](${message.jumpUrl})"
        }

        // Check message length and truncate if needed
        if (msg.length > 2000) {
            msg = "${message.author.asMention} said a filtered phrase in ${message.channel.asMention}: " +
                    "$censoredPhrase\n$mentions"
        }

        try {
            channel.sendMessage(msg).addEmbeds(embed).queue()
        } catch (e: Exception) {
            logger.error(e) { "Failed to send filterping notification" }
            // Optionally notify admins here
        }
    }

    private fun hasUnicodeDodge(content: String): Boolean {
        val normalized = Normalizer.normalize(content, Normalizer.Form.NFKC)
        val asciiOnly = normalized.replace(Regex("[^\\p{ASCII}]"), "")
        return asciiOnly != content  // Detect if normalization changed the content
    }

    private fun isWhitelisted(settings: FilterSettings, member: Member): Boolean {
        return member.idLong in settings.whitelist.users ||
                member.roles.any { it.idLong in settings.whitelist.roles } ||
                settings.whitelist.phrases.any { member.effectiveName.contains(it, true) }
    }

    // Add to handleWhitelist
    private suspend fun handleRoleWhitelist(event: SlashCommandInteractionEvent, action: String) {
        val roles = event.getOption("roles")?.asString?.split(",")?.mapNotNull { arg ->
            val roleId = arg.trim().filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toLong()
            roleId?.let { event.guild?.getRoleById(it) } ?: event.guild?.getRolesByName(arg, true)?.firstOrNull()
        }?.map { it.idLong } ?: emptyList()

        updateWhitelist(
            event.guild!!.idLong,
            "roles",
            roles.map { it.toString() },
            action
        )
        event.reply("Role whitelist updated").await()
    }

    private fun censor(
        message: String,
        censoredWords: List<String> = FilterCommons.censoredWords,
        whitelist: List<String> = emptyList()
    ): String {
        val normalized = normalizeText(message).lowercase()
        // Check against whitelist first
        if (whitelist.any { normalized.contains(it) }) return message

        val normalizedMessage = normalizeText(message)
        val msgLower = normalizedMessage.lowercase(Locale.getDefault())
        val censoredRanges = mutableListOf<IntRange>()
        val originalIndices = mapNormalizedIndices(message)

        for (cuss in censoredWords) {
            val normalizedCuss = normalizeText(cuss).lowercase(Locale.getDefault())
            var startIndex = msgLower.indexOf(normalizedCuss)

            while (startIndex != -1) {
                // Map normalized index back to original message indices
                val originalStart = originalIndices.getOrElse(startIndex) { startIndex }
                val originalEnd = originalIndices.getOrElse(startIndex + normalizedCuss.length - 1) {
                    startIndex + normalizedCuss.length - 1
                }

                val censorStart = originalStart + 1 // Skip first character
                var censorEnd = originalEnd - 1 // Skip last character

                // Ensure we don't go out of bounds
                if (censorEnd <= censorStart) censorEnd = censorStart + 1
                if (censorEnd > message.length) censorEnd = message.length

                // Extend censorEnd in original message
                var currentNormalizedIndex = startIndex + normalizedCuss.length
                while (currentNormalizedIndex < normalizedMessage.length) {
                    val nextChar = normalizedMessage.getOrNull(currentNormalizedIndex + 1)
                    if (nextChar != null && nextChar.isLetter()) {
                        censorEnd = originalIndices.getOrElse(currentNormalizedIndex) { currentNormalizedIndex }
                        currentNormalizedIndex++
                    } else {
                        break
                    }
                }

                // Ensure we don't exceed message bounds
                if (censorEnd > message.length) censorEnd = message.length
                if (censorStart < message.length) {
                    censoredRanges.add(censorStart until censorEnd)
                }

                // Find next occurrence
                startIndex = msgLower.indexOf(normalizedCuss, startIndex + 1)
            }
        }

        // Apply censoring to original message
        val chars = message.toCharArray()
        censoredRanges.sortByDescending { it.start }

        for (range in censoredRanges) {
            for (i in range.start until range.endInclusive) {
                if (i in chars.indices) {
                    chars[i] = '-'
                }
            }
        }

        return String(chars)
    }

    private fun normalizeText(text: String): String =
        Transliterator.getInstance("Any-Latin; Latin-ASCII").transliterate(text).lowercase()


    private fun mapNormalizedIndices(original: String): List<Int> {
        val indices = mutableListOf<Int>()
        var originalIndex = 0

        Normalizer.normalize(original, Normalizer.Form.NFD).forEach { c ->
            if (c.isLetter()) {
                indices.add(originalIndex)
                originalIndex++
            } else {
                // For non-letters, maintain index mapping
                indices.add(originalIndex)
                if (!c.isISOControl()) originalIndex++
            }
        }

        return indices
    }

    private fun pagify(text: String, delims: List<String> = listOf("\n"), maxLength: Int = 2000): List<String> {
        val pages = mutableListOf<String>()
        var remaining = text
        while (remaining.length > maxLength) {
            val chunk = remaining.take(maxLength)
            val closestDelim = delims.map { chunk.lastIndexOf(it) }.max()
            val splitIndex = if (closestDelim > 0) closestDelim + 1 else maxLength
            pages.add(remaining.take(splitIndex))
            remaining = remaining.drop(splitIndex)
        }
        pages.add(remaining)
        return pages
    }


    // Extension function to count occurrences
    private fun String.countIn(text: String): Int {
        val regex = Regex(Regex.escape(this), RegexOption.IGNORE_CASE)
        return regex.findAll(text).count()
    }
}