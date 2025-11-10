package commands

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.set
import database
import dev.minn.jda.ktx.coroutines.await
import helpers.*
import kotlinx.coroutines.flow.firstOrNull
import logger
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

object SpamDetector {

    data class SpamDetectorSettings(
        val list: List<Long>, // List of channels to track. In order.
        val guildId: Long
    )
    // This one doesn't need a create() companion
    // because there's only a list that's always going to be changed with each command.
    // This is a rare case where we don't need the companion.


    private val spamDetectorCollection = database.getCollection<SpamDetectorSettings>("spamDetectorSettings")

    // [x] /spamdetector add <channel> - adds one or more channels to the list.
    // [x] /spamdetector remove <channel> - removes one or more channels from the list.
    // [x] /spamdetector list - lists all the channels in their respective order.
    // [x] /spamdetector change <channel> <position> - changes the order of the channel in the list.
    // [x] /spamdetector flush - flushes the entire list of channels. Needs confirmation. This turns it off.

    @OptIn(ExperimentalTime::class)
    private class UserState {
        var startTime = Clock.System.now()
        var endTime = Clock.System.now()
        var expectedChannelIndex = 0
        var isCompleted = false
    }

    private val userState = ConcurrentHashMap<Long, UserState>()


    val channelCache = mutableMapOf<Long, Cache<List<Long>>>()

    suspend fun fetchChannels(guildId: Long) =
        spamDetectorCollection.find(eq(SpamDetectorSettings::guildId.name, guildId)).firstOrNull()?.list ?: emptyList()

    suspend fun addChannel(event: SlashCommandInteractionEvent, channel: GuildChannel) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val guildId = event.guild!!.idLong
        try {
            val settings =
                spamDetectorCollection.find(eq(SpamDetectorSettings::guildId.name, guildId)).firstOrNull() ?: SpamDetectorSettings(
                    emptyList(),
                    guildId
                )
            if (settings.list.contains(channel.idLong)) {
                // Channel is already in the list.
                // Insert your preferred way of notifying the admin here.
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "That channel is already in the list. Its position is `#${
                            settings.list.indexOf(
                                channel.idLong
                            ) + 1
                        }`"
                    }).queue()
                return
            }
            val newList = settings.list.toMutableList()
            newList.add(channel.idLong)
            spamDetectorCollection.updateOne(
                eq(SpamDetectorSettings::guildId.name, guildId),
                set(SpamDetectorSettings::list.name, newList),
                UpdateOptions().upsert(true)
            )
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "Added the channel to the list."
                }).queue()

            // Update the cache
            channelCache[guildId]?.forceUpdate { fetchChannels(guildId) }
        } catch (e: Throwable) {
            // Handle errors.
            e.printStackTrace()
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "An error occurred while adding the channel to the list."
                }
            ).queue()
        }
    }

    suspend fun removeChannel(event: SlashCommandInteractionEvent, channel: GuildChannel) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val guildId = event.guild!!.idLong
        try {
            val settings =
                spamDetectorCollection.find(eq(SpamDetectorSettings::guildId.name, guildId)).firstOrNull() ?: SpamDetectorSettings(
                    emptyList(),
                    guildId
                )
            if (!settings.list.contains(channel.idLong)) {
                // Channel is not in the list.
                event.hook.editOriginal("That channel isn't in the list.").queue()
                return
            }
            val newList = settings.list.toMutableList()
            newList.remove(channel.idLong)
            spamDetectorCollection.updateOne(
                eq(SpamDetectorSettings::guildId.name, guildId),
                set(SpamDetectorSettings::list.name, newList)
            )

            // Update the cache
            channelCache[guildId]?.forceUpdate { fetchChannels(guildId) }
        } catch (e: Throwable) {
            // Handle errors.
            // Insert your preferred error handling here.
            e.printStackTrace()
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "An error occurred while removing the channel from the list."
                }
            ).queue()
        }
    }

    suspend fun listChannels(event: SlashCommandInteractionEvent) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val guildId = event.guild!!.idLong
        try {
            val settings =
                spamDetectorCollection.find(eq(SpamDetectorSettings::guildId.name, guildId)).firstOrNull() ?: SpamDetectorSettings(
                    emptyList(),
                    guildId
                )
            if (settings.list.isEmpty()) {
                // List is empty.
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE
                        text = "The list is empty."
                    }).queue()
                return
            }
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE
                    text = "Here's the list of channels in order:\n${settings.list.joinToString("\n") { "<#$it>" }}"
                }
            ).queue()
        } catch (e: Throwable) {
            e.printStackTrace()
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "An error occurred while listing the channels."
                }
            ).queue()
        }
    }

    suspend fun changeChannel(event: SlashCommandInteractionEvent, channel: GuildChannel, newPosition: Int) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val guildId = event.guild!!.idLong
        try {
            val settings =
                spamDetectorCollection.find(eq(SpamDetectorSettings::guildId.name, guildId)).firstOrNull() ?: SpamDetectorSettings(
                    emptyList(),
                    guildId
                )

            if (!settings.list.contains(channel.idLong)) {
                // Channel is not in the list.
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "That channel isn't in the list."
                    }).queue()
                return
            }
            if (newPosition < 1 || newPosition > settings.list.size) {
                // Position is invalid.
                event.hook.editOriginalEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "That position is invalid. It needs to be between `1` and `${settings.list.size}`."
                    }
                ).queue()
                return
            }
            val oldList = settings.list
            val newList = oldList.filter { it != channel.idLong }.toMutableList()
            newList.add(newPosition - 1, channel.idLong)
            spamDetectorCollection.updateOne(
                eq(SpamDetectorSettings::guildId.name, guildId),
                set(SpamDetectorSettings::list.name, newList)
            )
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text = "Changed the channel's position to `#$newPosition`."
                }
            ).queue()

            // Update the cache
            channelCache[guildId]?.forceUpdate { fetchChannels(guildId) }
        } catch (e: Throwable) {
            // Handle errors.
            e.printStackTrace()
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "An error occurred while changing the channel's position."
                }
            ).queue()
        }
    }

    suspend fun flush(event: SlashCommandInteractionEvent) {
        if (!permissionCheck(event.toWrapper(), Permission.ADMINISTRATOR)) {
            return
        }
        val guildId = event.guild!!.idLong
        try {
            spamDetectorCollection.updateOne(
                eq(SpamDetectorSettings::guildId.name, guildId),
                set(SpamDetectorSettings::list.name, emptyList<Long>())
            )
            event.hook.editOriginal("The list has been flushed.").queue()

            // Update the cache
            channelCache[guildId]?.forceUpdate { fetchChannels(guildId) }
        } catch (e: Throwable) {
            // Handle errors.
            e.printStackTrace()
            event.hook.editOriginalEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "An error occurred while changing the channel's position."
                }
            ).queue()
        }
    }


    @OptIn(ExperimentalTime::class)
    suspend fun onMessageReceived(event: MessageReceivedEvent) {
        if (!channelCache.containsKey(event.guild.idLong)) {
            //this means that the guild is not in the cache
            // check the database
            val channels = fetchChannels(event.guild.idLong)
            if (channels.isEmpty()) {
                // we want to add it to the cache with empty list
                channelCache[event.guild.idLong] = Cache()
            }
        }
        val channelIDs: List<Long>? = channelCache[event.guild.idLong]?.request { fetchChannels(event.guild.idLong) }
        if (channelIDs == null)
            logger.throwing(IllegalStateException("ChannelIDs should not be null at this point"))
        if (!channelIDs!!.contains(event.channel.idLong) &&
            !userState.containsKey(event.message.author.idLong)
        ) return
        //If the channel is not in the list of channels, and the user is not in the userState, then return
        //if the user is in the userState, we want to continue. if they sent a message not in the list of channels, we want to forgive them.
        val state =
            userState.getOrPut(event.message.author.idLong) { UserState() } // Get the user's state, or create a new one if they don't have one yet.

        if (event.channel.idLong == channelIDs[state.expectedChannelIndex]) {
            if (state.expectedChannelIndex == 0) {
                state.startTime = Clock.System.now()
            }
            state.expectedChannelIndex++
            if (state.expectedChannelIndex == channelCache.size) {
                state.endTime = Clock.System.now()
                state.isCompleted = true
            }
        } else {
            // Forgive the user if they send a message out of order or in an unmonitored channel
            userState.remove(event.author.idLong)
        }

        if (isUserSpamming(event.author.idLong)) {
            //theyre spamming
            //take action
            event.channel.sendMessage("${event.member?.nickname} was removed on suspicion of being compromised by a spam bot. They  will be free to rejoin at their own convenience when they regain their account.")
                .queue()
            spamBotSoftBan(event.member!!)
            userState.remove(event.author.idLong)
        }
    }

    @OptIn(ExperimentalTime::class)
    fun isUserSpamming(userID: Long): Boolean {
        val state = userState[userID] ?: return false
        if (!state.isCompleted) return false

        val duration = state.endTime - state.startTime
        return duration.inWholeSeconds < 15
    }


    // helpers.MessageCache keeps track of all messages. I can then sort of use this to check if a user is spamming.
    // Example, if a user send a message in the first channel of the list, a countdown starts.
    // time elapsed is only checked when a message in the last channel of the list is sent.
    // If the time elapsed is less than 15s between the message in the first channel and the message in the last channel, then the user is spamming.
    // if the person sent a message in a channel out of order, then the countdown is reset and they're "forgiven"
    // if after the countdown starts, the user sends a message in a channel that is not in the list, then the countdown is reset and they're "forgiven"


    private fun spamBotMessage(serverName: String, inviteLink: String) =
        "We detected a potential spam bot hijacking your account to spam scam links and have removed your account " +
                "from `$serverName`. You are free to rejoin using $inviteLink when you regain full control of your account." +
                "\nAs a note: you will want to change your password if you haven't already. It's recommended to have 2 Factor Authentication enabled." +
                "\nThe following domains are the official Discord domains: `discord.com`, `discordapp.com`, `discord.gg`, `discordstatus.com`" +
                "Do **not trust** any other domains claiming to be Discord. Opening a suspicious website is enough to get your account hijacked without even entering your password." +
                "\nRemember: **NEVER** give your password to anyone, even if they claim to be a Discord staff member."


    /**
     * If a user sends 1 message to a set of channels in a specific order they get spotted by this function.
     * Softbans the user and deletes 5 minutes worth of messages. The user is warned in DMs about the event (if possible).
     * The user is immediately unbanned after the softban.
     *
     * @param event
     */
    suspend fun spamBotSoftBan(member: Member) {
        try {
            member.user.openPrivateChannel().await().sendMessage(
                spamBotMessage(
                    member.guild.name,
                    member.guild.vanityUrl.toString()
                )
            ).await()
        } catch (e: Exception) {
            // BotAlerts should be here. but we don't have that yet...
        }
        try {
            member.guild.ban(member.user, 5, TimeUnit.MINUTES).reason("Spam bot detected (soft-ban)").await()
        } catch (e: Exception) {
            // BotAlerts should be here. but we don't have that yet...
            e.printStackTrace()
        }
        try {
            member.guild.unban(member.user).reason("Spam bot detected (soft-ban)").await()
        } catch (e: Exception) {
            // BotAlerts should be here. but we don't have that yet...
            e.printStackTrace()
        }
    }
}

