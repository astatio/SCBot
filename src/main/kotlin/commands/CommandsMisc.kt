package commands

import helpers.NeoSuperEmbed
import helpers.SuperEmbed
import helpers.throwEmbed
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter


object CommandsMisc : ListenerAdapter() {

    fun shutdown(event: MessageReceivedEvent) {
        event.channel.sendMessage("Shutting down...").queue()
        println("Shut down requested by ${event.author.name} (ID: ${event.author.id})")
        event.jda.awaitShutdown()
    }

    fun getGuilds(event: MessageReceivedEvent) {
        val guildNames = event.jda.guilds.map { it.name + " `ID: ${it.id}`" }
        event.channel.sendMessage("The bot is currently in **" + event.jda.guilds.size + "** guilds.\nThe guild names are:\n" + guildNames.joinToString(
            ", ")).queue()
    }

    fun leaveGuild(guildID: String?, event: MessageReceivedEvent) {
        if (guildID.isNullOrEmpty()) {
            event.channel.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "Please provide a valid guild ID"
                }
            ).queue()
        } else {
            val guild = event.jda.getGuildById(guildID)!!
            val guildName = guild.name
            guild.leave().flatMap {
                event.channel.sendMessageEmbeds(
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SUCCESS
                        text = "Left guild `$guildName`"
                    })
            }.onErrorFlatMap {
                event.channel.sendMessageEmbeds(
                    throwEmbed(it, "Could not leave guild `$guildName`")
                )
            }
        }
    }

}

