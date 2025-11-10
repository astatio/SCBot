package commands

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import database
import helpers.*
import interfaces.appendRelevantReplacementsInfo
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import logger
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.utils.FileUpload
import java.util.Locale.getDefault

data class CustomCommandData(
    val guildId: Long,
    val trigger: String, // The command trigger (e.g., "!fart")
    val response: String?, // The message to send. Can be nullable, in case it's just for aliasing.
    val aliasCommand: String? = null // Optional: The built-in command to execute (e.g., "!ban")
)

object CustomCommands {
    private val customCommandsCollection = database.getCollection<CustomCommandData>("customCommands")

    // Maximum number of custom commands per guild to prevent abuse
    private const val MAX_RECOMMENDED_COMMANDS_PER_GUILD = 1000 // An arbitrary limit, can be adjusted

    // Maximum length for command response to prevent abuse
    private const val MAX_RESPONSE_LENGTH = 4000 // The actual limit for bots

    // List of built-in commands that can be used as aliases
    // This list should be kept in sync with the commands in GuildCommandsImpl.processBuiltInCommand
    private val builtInCommands = listOf(
        "!help", "!kick", "!ban", "!unban", "!timeout", "!m", "!untimeout", "!um",
        "!avatar", "!a", "!eval", "!rank", "!leaderboard", "!flush", "!rc", "!g", "!talk", "!jobs",
        "!serverinfo", "!si" // Added these from GuildCommandsImpl
    )

    // Function to find a custom command by trigger
    suspend fun findCommand(trigger: String, guildId: Long): CustomCommandData? {
        return customCommandsCollection.find(
            and(
                eq(CustomCommandData::guildId.name, guildId),
                eq(CustomCommandData::trigger.name, trigger.lowercase(getDefault()))
            )
        ).firstOrNull()
    }

    // Check if a command exists (either built-in or custom)
    suspend fun commandExists(trigger: String, guildId: Long): Boolean {
        // Check if it's a built-in command
        if (builtInCommands.contains(trigger)) {
            return true
        }

        // Check if it's a custom command
        return findCommand(trigger, guildId) != null
    }

    // Detect potential command loops
    suspend fun detectLoop(
        trigger: String, aliasCommand: String, guildId: Long, visited: MutableSet<String> = mutableSetOf(),
        commandCache: MutableMap<String, CustomCommandData?> = mutableMapOf()
    ): Boolean {
        // If we've already visited this command in this chain, we have a loop
        if (visited.contains(trigger)) {
            logger.info { "Loop detected in command chain: ${visited.joinToString(" -> ")} -> $trigger" }
            return true
        }

        // Add this command to the visited set
        visited.add(trigger)

        // If the alias is the same as any command in the chain, we have a loop
        if (visited.contains(aliasCommand)) {
            logger.info { "Loop detected in command chain: ${visited.joinToString(" -> ")} -> $aliasCommand" }
            return true
        }

        // If the alias is a built-in command, no loop possible
        if (builtInCommands.contains(aliasCommand)) {
            return false
        }

        // Check if the alias is a custom command (using cache to avoid redundant DB queries)
        val aliasedCommand = if (commandCache.containsKey(aliasCommand)) {
            commandCache[aliasCommand]
        } else {
            val cmd = findCommand(aliasCommand, guildId)
            commandCache[aliasCommand] = cmd
            cmd
        }

        if (aliasedCommand?.aliasCommand != null) {
            // Recursively check if the aliased command's alias creates a loop
            return detectLoop(aliasCommand, aliasedCommand.aliasCommand, guildId, visited, commandCache)
        }

        // No loop detected
        return false
    }

    suspend fun add(event: SlashCommandInteractionEvent) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) return
        val guildId = event.guild!!.idLong

        val trigger = event.getOption("trigger")!!.asString
        val response = event.getOption("response")?.asString
        var alias = event.getOption("aliascommand")?.asString

        // Validate that either response or aliascommand is provided
        if ((response == null || response.isEmpty()) && (alias == null || alias.isEmpty())) {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "You must provide either a `response` or an `aliascommand` (or both)."
                }
            ).setEphemeral(true).queue()
            return
        }

        // If an alias is provided, ensure it starts with "!"
        if (alias != null && !alias.startsWith("!")) {
            alias = "!$alias"
        }

        // Validate response length if provided
        if (response != null && response.length > MAX_RESPONSE_LENGTH) {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "Response is too long. Maximum length is $MAX_RESPONSE_LENGTH characters."
                }
            ).setEphemeral(true).queue()
            return
        }

        // Check if command already exists
        val existing = findCommand(trigger, guildId)
        if (existing != null) {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "A custom command with the trigger `$trigger` already exists."
                }
            ).setEphemeral(true).queue()
            return
        }

        // Check if guild has reached the command limit
        val commandCount = customCommandsCollection.countDocuments(
            eq(CustomCommandData::guildId.name, guildId)
        )
        if (commandCount >= MAX_RECOMMENDED_COMMANDS_PER_GUILD) {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.ALERT
                    text =
                        "This server has reached the maximum recommended limit of $MAX_RECOMMENDED_COMMANDS_PER_GUILD custom commands. " +
                                "Please consider removing unused commands."
                }
            ).queue()
        }

        // Validate alias if provided
        if (alias != null) {
            // Check if alias target exists
            if (!commandExists(alias, guildId)) {
                event.hook.sendMessageEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "The alias target `$alias` does not exist as a command."
                    }
                ).setEphemeral(true).queue()
                return
            }

            // Check for potential loops
            val commandCache = mutableMapOf<String, CustomCommandData?>()
            if (detectLoop(trigger, alias, guildId, mutableSetOf(), commandCache)) {
                event.hook.sendMessageEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text =
                            "Adding this command would create a command execution loop. Please choose a different alias."
                    }
                ).setEphemeral(true).queue()
                return
            }
        }

        val newData = CustomCommandData(
            guildId = guildId,
            trigger = trigger.lowercase(getDefault()),
            response = response,
            aliasCommand = alias?.lowercase()
        )

        try {
            customCommandsCollection.insertOne(newData)
            var successMessageText = "Custom command `$trigger` added successfully."
            if (alias != null) {
                successMessageText += "\nThis command will also execute: `$alias`"
            }
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = if (response != null) {
                        "$successMessageText\n" +
                                appendRelevantReplacementsInfo(response, event.toWrapper())
                    } else {
                        successMessageText
                    }
                }
            ).queue()
        } catch (e: Exception) {
            logger.error(e) { "Failed to add custom command '$trigger' in guild $guildId: ${e.message}" }
            event.hook.sendMessageEmbeds(throwEmbed(e, "Failed to add custom command.")).queue()
        }
    }

    suspend fun remove(event: SlashCommandInteractionEvent) {
        event.deferReply().queue()
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) return

        val trigger = event.getOption("trigger")!!.asString
        val guildId = event.guild!!.idLong

        logger.info { "Attempting to remove custom command '$trigger' from guild $guildId" }

        // Check if the command exists first to provide better logging
        val existingCommand = findCommand(trigger, guildId)
        if (existingCommand == null) {
            // Does it have the "!" prefix? If it doesn't, check for a prefixed version
            // If it does, check for a non-prefixed version
            val similarFound: String? = if (!checkForSimilar(trigger, guildId).isNullOrBlank()) {
                " Did you mean `${checkForSimilar(trigger, guildId)}`?"
            } else {
                ""
            }

            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "Custom command `$trigger` not found.$similarFound"
                }
            ).setEphemeral(true).queue()
            return
        }

        // Log details about the command being removed

        try {
            val result = customCommandsCollection.deleteOne(
                and(
                    eq(CustomCommandData::guildId.name, guildId),
                    eq(CustomCommandData::trigger.name, trigger.lowercase())
                )
            )

            if (result.deletedCount > 0) {
                event.hook.sendMessageEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SUCCESS
                        text = "Custom command `$trigger` removed successfully."
                    }
                ).queue()
            } else {
                // This should not happen since we checked existence above, but just in case
                logger.error { "Failed to remove custom command '$trigger' from guild $guildId (no documents deleted)" }
                event.hook.sendMessageEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "Custom command `$trigger` not found."
                    }
                ).setEphemeral(true).queue()
            }
        } catch (e: Exception) {
            logger.error(e) { "Exception while removing custom command '$trigger' from guild $guildId: ${e.message}" }
            event.hook.sendMessageEmbeds(throwEmbed(e, "Failed to remove custom command.")).queue()
        }
    }

    suspend fun checkForSimilar(trigger: String, guildId: Long): String? {
        // Does it have the "!" prefix? If it doesn't, check for a prefixed version
        // If it does, check for a non-prefixed version
        val alternativeTrigger = if (trigger.startsWith("!")) {
            trigger.removePrefix("!")
        } else {
            "!$trigger"
        }
        return findCommand(alternativeTrigger, guildId)?.trigger
    }

    suspend fun list(event: SlashCommandInteractionEvent) {
        event.deferReply().queue()

        try {
            val guildId = event.guild!!.idLong // Store for logging and filename
            val commands = customCommandsCollection.find(eq(CustomCommandData::guildId.name, guildId)).toList()

            if (commands.isEmpty()) {
                event.hook.sendMessageEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE
                        text = "No custom commands found for this server."
                    }
                ).queue()
                return
            }

            val jsonPrettyPrinter = Json { prettyPrint = true }

            val jsonElements = commands.map { command ->
                buildJsonObject {
                    put("guildId", command.guildId.toString())
                    put("trigger", command.trigger)
                    command.response?.let { response -> put("response", response) }
                    command.aliasCommand?.let { alias -> put("aliasCommand", alias) }
                }
            }
            val jsonArray = JsonArray(jsonElements)

            val jsonString = jsonPrettyPrinter.encodeToString(jsonArray)

            val byteArray = jsonString.toByteArray(Charsets.UTF_8)
            val fileUpload = FileUpload.fromData(byteArray, "custom_commands_${guildId}.json")

            event.hook.sendMessage("Here are the custom commands for this server as a JSON file:")
                .addFiles(fileUpload)
                .queue()

        } catch (e: Exception) {
            val guildIdForError = event.guild?.idLong ?: "unknown"
            logger.error(e) { "Failed to list or export custom commands for guild $guildIdForError: ${e.message}" }
            event.hook.sendMessageEmbeds(throwEmbed(e, "Failed to list or export custom commands.")).queue()
        }
    }

    suspend fun handleAutoComplete(event: CommandAutoCompleteInteractionEvent) {
        if (event.focusedOption.name == "trigger") {
            val guildId = event.guild?.idLong ?: run {
                logger.error { "Autocomplete failed - guild is null" }
                event.replyChoices(emptyList()).queue()
                return
            }

            val currentInput = event.focusedOption.value.lowercase()

            try {
                val commands = customCommandsCollection.find(
                    eq(CustomCommandData::guildId.name, guildId)
                ).toList()

                val suggestions = commands
                    .map { it.trigger }
                    .filter { it.lowercase().startsWith(currentInput) }
                    .sorted() // Sort alphabetically for better user experience
                    .take(25) // Discord limit
                    .map { Command.Choice(it, it) }

                event.replyChoices(suggestions).queue()
            } catch (e: Exception) {
                // More detailed error logging
                logger.error(e) { "Autocomplete failed for guild $guildId with input '$currentInput': ${e.message}" }
                event.replyChoices(emptyList()).queue()
            }
        } else if (event.focusedOption.name == "aliascommand") {
            // Provide autocomplete for alias commands too
            val currentInput = event.focusedOption.value.lowercase()

            val suggestions = builtInCommands
                .filter { it.lowercase().startsWith(currentInput) }
                .sorted()
                .take(25)
                .map { Command.Choice(it, it) }

            event.replyChoices(suggestions).queue()
        }
    }
}
