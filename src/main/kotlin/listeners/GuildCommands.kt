package listeners

import EntryController
import SONIC_THE_HEDGEHOG
import commands.*
import commands.CustomCommands
import commands.eval
import commands.ranks.Ranks
import deprecationWarning
import helpers.permissionCheckMessageless
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logger
import mimeJob
import myScope
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import replaceKeywords
import returnJob
import scbothelp
import java.util.Locale.getDefault

object GuildCommandsImpl : GuildCommands {

    // Set to track command execution chain to prevent loops
    private val executionChainThreadLocal = ThreadLocal.withInitial { mutableSetOf<String>() }

    override suspend fun onGuildCommand(event: MessageReceivedEvent) {
        val content = event.message.contentRaw
        val trigger = content.substringBefore(" ")
        val guildId = event.guild.idLong

        // Clear the execution chain for this new command
        executionChainThreadLocal.get().clear()

        // Process the command with loop detection
        processCommand(event, trigger, guildId)
    }

    // Process a command with loop detection
    private suspend fun processCommand(event: MessageReceivedEvent, trigger: String, guildId: Long) {
        // Add this command to the execution chain
        val commandKey = "$trigger:$guildId"
        val executionChain = executionChainThreadLocal.get()

        // Check for loops
        if (executionChain.contains(commandKey)) {
            logger.error { "Command execution loop detected! Chain: ${executionChain.joinToString(" -> ")} -> $trigger" }
            event.channel.sendMessage("⚠️ Command loop detected! Execution stopped to prevent an infinite loop.").queue()
            return
        }

        // Add to execution chain
        executionChain.add(commandKey)

        // Try executing as a custom command first
        val customCommand = CustomCommands.findCommand(trigger, guildId)
        if (customCommand != null) {
            //Replace "\n" with a newline character
            var msgToSend: String = customCommand.response?.replace("\\n", "\n").toString()
            // The msg may exceed the 2000 character limit, so we truncate it if necessary
            if (msgToSend.length > 2000) {
                // this might contain some URL at the end, so we remove the whole URL if it exceeds the limit
                val lastSpaceIndex = msgToSend.lastIndexOf(" ", 1997)
                msgToSend = if (lastSpaceIndex != -1) {
                    msgToSend.substring(0, lastSpaceIndex) + "..."
                } else {
                    msgToSend.substring(0, 1997) + "..."
                }
                // Find the last whitespace. If after it there's "http" we remove the whole URL and replace it with "..."
                val lastWhitespace = msgToSend.lastIndexOf(" ")
                if (lastWhitespace != -1 && msgToSend.indexOf("http", lastWhitespace) != -1) {
                    msgToSend = msgToSend.substring(0, lastWhitespace) + "..."
                }
            }

            // Send the response
            // Call replacekeywords to replace any keywords in the response
            msgToSend = replaceKeywords(
                msgToSend,
                event.author,
                event.guild
            )

            event.channel.sendMessage(msgToSend).queue()

            // Handle alias if exists
            if (customCommand.aliasCommand != null) {
                val aliasCommand = customCommand.aliasCommand
                logger.info { "Executing alias '$aliasCommand' for custom command '$trigger' in guild $guildId" }
                logger.info { "Current execution chain: ${executionChain.joinToString(" -> ")}" }

                // Process the aliased command - could be built-in or another custom command
                processCommand(event, aliasCommand, guildId)
            }
            return // Custom command (and potential alias) handled
        }

        // If not a custom command, proceed with built-in commands
        with(event) {
            processBuiltInCommand(trigger)
        }
    }

    // Extracted function to handle built-in commands
    context(event: MessageReceivedEvent)
    private suspend fun processBuiltInCommand(commandTrigger: String) {
        with(event.guild) {
            when (commandTrigger.lowercase(getDefault())) {
                //For the current time being, the prefix is hardcoded.
                "!serverinfo" -> deprecationWarning("/serverinfo")
                "!si" -> deprecationWarning("/serverinfo")
                "!ui" -> deprecationWarning("/userinfo")
                "!userinfo" -> deprecationWarning("/userinfo")
                "!alias" -> deprecationWarning("/customcommand")
                "!cc" -> deprecationWarning("/customcommand")
                "!names" -> deprecationWarning("/userinfo")
                "!help" -> scbothelp(event)
                "!kick" -> kick(event)
                "!ban" -> ban(event)
                "!unban" -> unban(event)
                "!timeout" -> TimeOut.timeOut(event)
                "!m" -> TimeOut.timeOut(event)
                "!untimeout" -> TimeOut.removeTimeOut(event)
                "!um" -> TimeOut.removeTimeOut(event)
                "!avatar" -> Avatar.getAvatar(event)
                "!a" -> Avatar.getAvatar(event)
                "!eval" -> eval(event)
                // Below are commands exclusive to /r/SonicTheHedgehog
                "!rank" -> {
                    isSonicGuild() || return
                    Ranks.routing(event)
                }
                "!leaderboard" -> {
                    isSonicGuild() || return
                    Ranks.leaderboard(event)
                }

                "!flush" -> {
                    isSonicGuild() || return
                    EntryController.flush(event)
                }

                "!rc" -> {
                    isSonicGuild() || return
                    EntryController.rolecall(event)
                }

                "!g" -> {
                    isSonicGuild() || return
                    EntryController.turnToMember(event)
                }

                "!talk" -> {
                    isSonicGuild() || return
                    EntryController.giveExceptionRole(event)
                }

                "!jobs" -> {
                    isSonicGuild() || return
                    //check the jobs status in mainScoped
                    if (permissionCheckMessageless(event.member!!, Permission.MANAGE_SERVER))
                        myScope.launch {
                            event.channel.sendMessage("MimeJob: ${mimeJob?.isActive ?: false}\nReturnJob: ${returnJob?.isActive ?: false}")
                                .queue()
                        }
                }

                // No default case needed, if no match, nothing happens
                // else -> return // Removed this line as it's implicit
            }
        }
    }
}

context(guild: Guild)
fun isSonicGuild() = guild.idLong == SONIC_THE_HEDGEHOG