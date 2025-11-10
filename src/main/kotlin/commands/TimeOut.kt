package commands

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.set
import database
import dev.minn.jda.ktx.coroutines.await
import helpers.*
import kotlinx.coroutines.flow.firstOrNull
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.utils.TimeFormat
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit


object TimeOut {

    data class TimeOutData(
        val default: String, // By default, is 10m. This is the value that will be used if no time is specified.
        val guildId: Long
    )

    val timeoutCollection = database.getCollection<TimeOutData>("timeOutData")

    suspend fun timeOut(event: MessageReceivedEvent) {
        if (!permissionCheck(event.toWrapper(), Permission.MODERATE_MEMBERS)) {
            return
        }
        //todo: this could be improved with commandMemberValidation() and commandManyMembersValidation()
        val parts = event.message.contentRaw.split(" ")
        if (event.message.contentDisplay.substringAfter(" ").startsWith("default")) {
            setDefault(event)
            return
        }
        if (event.message.mentions.members.isEmpty()) { //If it only has "!timeout" or has no members mentioned.
            event.channel.sendMessageEmbeds(NeoSuperCommand {
                triggers = arrayOf("!timeout", "!m", "!um")
                name = "TimeOut"
                description =
                    "Requires moderate members permission.\nDefault: 10 minutes (Can be changed)\nLimit: 28 days\n\nUse **!untimeout** or **!um** to end a timeout early.\n" + "You can end timeouts from multiple members at once."
                subcommands {
                    put("@user [quantity]s/m/h/d", "Mute a member for a specific amount of time")
                    put(
                        "default [time]",
                        "Change the default time for timeouts. This help menu doesn't reflect changes made to the default time."
                    )
                }
            }).queue()
            return
        }

        // Due to a bug it is not possible to implement the line below. This is what WAS and IS intended.
        // var messageToSend = "Done. Muted for ${prettyTimeDuration}\nUntil ${TimeFormat.DATE_TIME_LONG.format(theTime.time)}")
        var timeInTimeFormat: Instant
        var prettyTimeDuration: String

        if (parts.size > 2) {
            val dateContent = event.message.contentRaw.substringAfterLast(" ")
            //example: !timeout [user] 2m
            //needs to support s, m, h, d, w
            runCatching {
                val parsedTime = TimeUtil().parse(dateContent).toMillisecond
                timeInTimeFormat = Instant.now().plusMillis(parsedTime)
                prettyTimeDuration = formatDuration(parsedTime, TimeUnit.MILLISECONDS)
                event.message.mentions.members[0].timeoutFor(Duration.ofMillis(parsedTime)).await()
                event.channel.sendMessageEmbeds(NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text =
                        "Done. Muted until ${TimeFormat.DATE_TIME_LONG.format(timeInTimeFormat)}. Muted for $prettyTimeDuration"
                }).queue()
            }.onFailure {
                event.message.replyEmbeds(
                    throwEmbed(it, "An error occurred when trying to mute the member.")
                ).queue()
            }

        } else {
            val guildId = event.guild.idLong
            val defaultValue =
                timeoutCollection.find(eq(TimeOutData::guildId.name, guildId)).firstOrNull()?.default ?: "10m"

            val parsedTime = TimeUtil().parse(defaultValue).toMillisecond
            val timeInTimeFormat = Instant.now().plusMillis(parsedTime)
            val prettyTimeDuration = formatDuration(parsedTime, TimeUnit.MILLISECONDS)
            runCatching {
                event.message.mentions.members[0].timeoutFor(Duration.ofMillis(parsedTime)).await()
                event.channel.sendMessageEmbeds(NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SUCCESS
                    text =
                        "Done. Muted until ${TimeFormat.DATE_TIME_LONG.format(timeInTimeFormat)}. Muted for $prettyTimeDuration"
                }).queue()
            }.onFailure {
                event.message.replyEmbeds(
                    throwEmbed(it, "An error occurred when trying to mute the member.")
                ).queue()
            }
        }
    }

    fun removeTimeOut(event: MessageReceivedEvent) {
        val parts = event.message.contentRaw.split(" ")
        if (!permissionCheck(event.toWrapper(), Permission.MODERATE_MEMBERS)) {
            return
        }
        if (parts.size == 2) {
            kotlin.runCatching {
                event.message.mentions.members.forEach {
                    it.removeTimeout().queue() //If the member is not in the server this will cause an error.
                }
            }
            val thisEmbed = NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Done. Removed timeouts for ${event.message.mentions.members.size} member(s)."
            }
            event.channel.sendMessageEmbeds(thisEmbed).queue()
        } else {
            val thisEmbed = NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "Please mention a member to remove the timeout from."
            }
            event.channel.sendMessageEmbeds(thisEmbed).queue()
            return
        }
    }

    private suspend fun setDefault(event: MessageReceivedEvent) {
        val newDefault = event.message.contentDisplay.substringAfter("default").trim()
        try {
            //This is purely to know if its written properly: a number followed by 's','m','h' or 'd'
            TimeUtil().parse(newDefault).toString()
        } catch (t: Throwable) {
            event.message.replyEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text =
                    "The time you entered is not valid. Please enter a number followed by either `s`, `m`, `h` or `d`"
            }).queue()
            return
        }

        val guildId = event.guild.idLong

        // Fetch existing record from MongoDB using KMongo
        val existingTimeoutData = timeoutCollection.find(
            eq(TimeOutData::guildId.name, guildId)
        ).firstOrNull()

        if (existingTimeoutData == null) {
            // No record exists, insert a new one
            val newTimeoutData = TimeOutData(default = newDefault, guildId = guildId)
            timeoutCollection.insertOne(newTimeoutData)
        } else {
            // Update the existing record
            timeoutCollection.updateOne(
                eq(TimeOutData::guildId.name, guildId),
                update = set(TimeOutData::default.name, newDefault),
                UpdateOptions().upsert(false)
            )
        }
    }


}
