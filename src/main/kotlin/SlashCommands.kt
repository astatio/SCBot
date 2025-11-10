import commands.*
import commands.core.Alerts
import commands.ranks.Ranks
import core.ModLog
import filters.InstantBan
import helpers.NeoSuperEmbed
import helpers.OMDbMediaType
import helpers.SuperEmbed
import helpers.toWrapper
import interfaces.SlashCommands
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent


object SlashCommandsImpl : SlashCommands {

    override suspend fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {

        //A lot of the commands that use channels dont actually use "MessageChannels" but just TextChannels.
        // This might need to be changed.
        when (event.fullCommandName) {
            "alerts switch" -> {
                event.deferReply().queue()
                Alerts.switch(event, event.getOption("mode")!!.asString.toBooleanStrict())
            }

            "alerts channel" -> {
                event.deferReply().queue()
                Alerts.setChannel(event, event.getOption("channel")!!.asChannel as GuildChannel)
            }

            "alerts add" -> {
                event.deferReply().queue()
                Alerts.addRole(event, event.getOption("role")!!.asRole)
            }

            "alerts remove" -> {
                event.deferReply().queue()
                Alerts.removeRole(event, event.getOption("role")!!.asRole)
            }

            "alerts status" -> {
                event.deferReply().queue()
                Alerts.status(event)
            }

            "nametracker switch" -> {
                event.deferReply().queue()
                NameTracker.switch(event, event.getOption("mode")!!.asString.toBooleanStrict())
            }

            "nametracker clear" -> {
                event.deferReply().queue()
                NameTracker.clear(event, event.getOption("member")!!.asUser)
            }

            "nametracker clearself" -> {
                event.deferReply().queue()
                NameTracker.clearSelf(event)
            }

            "nametracker flush" -> {
                event.deferReply().queue()
                NameTracker.flush(event)
            }

            "tagging switch" -> {
                event.deferReply().queue()
                Tagging.switch(event, event.getOption("mode")!!.asString.toBooleanStrict())
            }

            "tagging channel" -> {
                event.deferReply().queue()
                Tagging.setChannel(event, event.getOption("channel")!!.asChannel as GuildChannel)
            }

            "tagging list" -> {
                event.deferReply().queue()
                Tagging.list(event)
            }

            "tagging tag" -> {
                event.deferReply().queue()
                Tagging.tag(event, event.getOption("member")!!.asUser)
            }

            "tagging untag" -> {
                event.deferReply().queue()
                Tagging.untag(event, event.getOption("member")!!.asUser)
            }


            "spamdetector add" -> {
                event.deferReply().queue()
                SpamDetector.addChannel(event, event.getOption("channel")!!.asChannel)
            }

            "spamdetector remove" -> {
                event.deferReply().queue()
                SpamDetector.removeChannel(event, event.getOption("channel")!!.asChannel)
            }

            "spamdetector list" -> {
                event.deferReply().queue()
                SpamDetector.listChannels(event)
            }

            "spamdetector change" -> {
                event.deferReply().queue()
                SpamDetector.changeChannel(
                    event,
                    event.getOption("channel")!!.asChannel,
                    event.getOption("position")!!.asInt
                )
            }

            "spamdetector flush" -> {
                event.deferReply().queue()
                SpamDetector.flush(event)
            }

            "help" -> CommandsGeneral.help(event)
            "ping" -> CommandsGeneral.ping(event)
            "info" -> CommandsGeneral.info(event)
            "uptime" -> CommandsGeneral.uptime(event)
            "serverinfo" -> CommandsGeneral.serverinfo(event)
            "userinfo self" -> {
                event.deferReply().queue()
                UserInfo.userinfoUser(event, event.user)
            }

            "userinfo user" -> {
                event.deferReply().queue()
                UserInfo.userinfoUser(event, event.getOption("user")!!.asUser)
            }

            //Rank base commands
            "rank switch" -> {
                event.deferReply().queue()
                Ranks.switch(event, event.getOption("mode")!!.asString.toBooleanStrict())
            }

            "rank switchmessage" -> {
                event.deferReply().queue()
                Ranks.switchLUPMessage(event, event.getOption("mode")!!.asString.toBooleanStrict())
            }

            "rank happyhour start" -> {
                event.deferReply().queue()
                Ranks.startHappyhour(
                    event,
                    event.getOption("percentage")!!.asInt,
                )
            }

            "rank happyhour stop" -> {
                event.deferReply().queue()
                Ranks.stopHappyhour(event)
            }

            "rank set xp" -> {
                event.deferReply().queue()
                Ranks.setXPGain(event, event.getOption("amount")!!.asInt, event.getOption("cooldown")!!.asInt)
            }

            "rank set message" -> {
                event.deferReply().queue()
                Ranks.setLevelUpMessage(event, event.getOption("message")!!.asString)
            }

            "rank add specialrank" -> {
                event.deferReply().queue()
                if (event.getOption("role")?.asRole == null) {
                    if (event.getOption("message")?.asString.isNullOrBlank()) {
                        event.hook.editOriginalEmbeds(
                            NeoSuperEmbed {
                                type = SuperEmbed.ResultType.SIMPLE_ERROR
                                text = "If you don't want to give a role, you must provide a message."
                            }
                        ).queue()
                        return
                    }
                    Ranks.addSpecialRankRoleless(
                        event,
                        event.getOption("rank")!!.asInt,
                        event.getOption("message")!!.asString
                    )
                } else {
                    Ranks.addSpecialRankRole(
                        event,
                        event.getOption("rank")!!.asInt,
                        event.getOption("message")?.asString,
                        event.getOption("role")!!.asRole
                    )
                }
            }

            "rank add ignorerole" -> {
                event.deferReply().queue()
                Ranks.addIgnoreRole(event, event.getOption("role")!!.asRole)
            }

            "rank add ignorechannel" -> {
                event.deferReply().queue()
                Ranks.addIgnoreChannel(
                    event,
                    event.getOption("channel")!!.asChannel
                )
            }

            "rank remove specialrank" -> {
                event.deferReply().queue()
                Ranks.removeSpecialRank(event, event.getOption("rank")!!.asInt)
            }

            "rank remove ignorerole" -> {
                event.deferReply().queue()
                Ranks.removeIgnoreRole(event, event.getOption("role")!!.asRole)
            }

            "rank remove ignorechannel" -> {
                event.deferReply().queue()
                Ranks.removeIgnoreChannel(
                    event,
                    event.getOption("channel")!!.asChannel
                )
            }

            "rank get xp" -> {
                event.deferReply().queue()
                Ranks.getXP(event)
            }

            "rank get message" -> {
                event.deferReply().queue()
                Ranks.getMessage(event)
            }

            "rank get specialrank" -> {
                event.deferReply().queue()
                Ranks.getSpecialRank(event)
            }

            "rank get ignorerole" -> {
                event.deferReply().queue()
                Ranks.getIgnoreRole(event)
            }

            "rank get ignorechannel" -> {
                event.deferReply().queue()
                Ranks.getIgnoreChannel(event)
            }

            "rank get userinfo" -> {
                event.deferReply().queue()
                Ranks.getUserInfo(event, event.getOption("userid")!!.asLong)
            }

            "rank give rank" -> {
                event.deferReply().queue()
                Ranks.giveRankLevel(
                    event,
                    event.getOption("level")!!.asInt,
                    event.getOption("user")!!.asUser
                )
            }

            "rank give xp" -> {
                event.deferReply().queue()
                Ranks.giveXP(
                    event,
                    event.getOption("xp")!!.asLong,
                    event.getOption("user")!!.asUser
                )
            }

            "rank give xpoverride" -> {
                event.deferReply().queue()
                Ranks.setUserXP(
                    event,
                    event.getOption("xp")!!.asLong,
                    event.getOption("user")!!.asUser
                )
            }

            "modlog switch" -> {
                event.deferReply().queue()
                ModLog.switch(event, event.getOption("mode")!!.asString.toBooleanStrict())
            }

            "modlog switchlsr" -> {
                event.deferReply().queue()
                ModLog.switchLT(event, event.getOption("mode")!!.asString.toBooleanStrict())
            }

            "modlog set channel1" -> {
                event.deferReply().queue()
                ModLog.setChannel(
                    1,
                    event,
                    event.getOption("channel")!!.asChannel,
                )
            }

            "modlog set channel2" -> {
                event.deferReply().queue()
                ModLog.setChannel(
                    2,
                    event,
                    event.getOption("channel")!!.asChannel,
                )
            }

            "barrage add" -> {
                event.deferReply().queue()
                Barrage.addChannel(
                    event,
                    event.getOption("channel")!!.asChannel as MessageChannel
                )
            }

            "barrage remove" -> {
                event.deferReply().queue()
                Barrage.removeChannel(
                    event,
                    event.getOption("channel")!!.asChannel as MessageChannel
                )
            }

            "barrage release" -> {
                event.deferReply().queue()
                Barrage.release(event)
            }

            "barrage list" -> {
                event.deferReply().queue()
                Barrage.listChannels(event)
            }

            "barrage status" -> {
                event.deferReply().queue()
                Barrage.status(event)
            }

            "barrage switch" -> {
                event.deferReply().queue()
                Barrage.switchMode(event, event.getOption("mode")!!.asString.toBooleanStrict())
            }

            "entry controller switch" -> {
                event.deferReply().queue()
                EntryController.switch(event, event.getOption("mode")!!.asString.toBooleanStrict())
            }

            "entry mime switch" -> {
                event.deferReply().queue()
                EntryController.mimeCheckSwitch(event, event.getOption("mode")!!.asString.toBooleanStrict())
            }

            "entry mime flush" -> {
                event.deferReply().queue()
                EntryController.flush(event)
            }

            "entry return switch" -> {
                event.deferReply().queue()
                EntryController.returnCheckSwitch(event, event.getOption("mode")!!.asString.toBooleanStrict())
            }

            "instantban add" -> {
                event.deferReply().queue()
                InstantBan.addWord(
                    event,
                    event.getOption("word")!!.asString,
                    event.getOption("action")?.asString ?: "ban",
                    event.getOption("timeout")?.asLong,
                    event.getOption("warnthreshold")?.asInt,
                    event.getOption("onmaxwarn")?.asString,
                    event.getOption("alert")?.asBoolean,
                    event.getOption("severealert")?.asBoolean
                )
            }

            "instantban addmany" -> {
                event.deferReply().queue()
                InstantBan.addMany(
                    event,
                    event.getOption("words")!!.asString
                )
            }

            "instantban remove" -> {
                event.deferReply().queue()
                InstantBan.removeWord(
                    event,
                    event.getOption("word")!!.asString
                )
            }

            "instantban list" -> {
                event.deferReply().queue()
                InstantBan.listWords(
                    event
                )
            }

            "instantban change" -> {
                event.deferReply().queue()
                InstantBan.changeWord(
                    event,
                    event.getOption("word")!!.asString,
                    event.getOption("action")!!.asString,
                    event.getOption("timeout")?.asLong,
                    event.getOption("warnthreshold")?.asInt,
                    event.getOption("onmaxwarn")?.asString,
                    event.getOption("alert")?.asBoolean,
                    event.getOption("severealert")?.asBoolean
                )
            }

            "instantban flush" -> {
                event.deferReply().queue()
                InstantBan.flush(
                    event
                )
            }

            "imdb" -> {
                event.deferReply().queue()
                OMDB(
                    event,
                    event.getOption("search")!!.asString,
                    event.getOption("type")?.asString?.let {
                        OMDbMediaType.fromValue(it)
                    },
                    event.getOption("year")?.asInt
                )
            }

            "ticket create classic" -> {
                event.deferReply().queue()
                Ticketing.createTicketClassic(
                    event,
                    event.getOption("subject")!!.asString,
                    event.getOption("description")?.asString,
                )
            }

            "ticket create onbehalf" -> {
                event.deferReply().queue()
                Ticketing.createTicketOnBehalf(
                    event.toWrapper(),
                    event.getOption("user")!!.asMember!!,
                    event.member!!,
                    event.getOption("channel")!!.asChannel as TextChannel
                )
            }

            "ticket assign" -> {
                event.deferReply().queue()
                Ticketing.assignTicket(
                    event,
                    event.getOption("user")!!.asUser
                )
            }

            "ticket add alertrole" -> {
                event.deferReply().queue()
                Ticketing.addAlertRole(
                    event,
                    event.getOption("role")!!.asRole
                )
            }

            "ticket add alertchannel" -> {
                event.deferReply().queue()
                Ticketing.addAlertChannel(
                    event,
                    event.getOption("channel")!!.asChannel as TextChannel
                )
            }

            "ticket add alertuser" -> {
                event.deferReply().queue()
                Ticketing.addAlertUser(
                    event,
                    event.getOption("user")!!.asUser
                )
            }

            "ticket add ticketingchannel" -> {
                event.deferReply().queue()
                Ticketing.addTicketingChannel(
                    event,
                    event.getOption("channel")!!.asChannel as TextChannel
                )
            }

            "ticket remove alertrole" -> {
                event.deferReply().queue()
                Ticketing.removeAlertRole(
                    event,
                    event.getOption("role")!!.asRole
                )
            }

            "ticket remove alertchannel" -> {
                event.deferReply().queue()
                Ticketing.removeAlertChannel(
                    event,
                    event.getOption("channel")!!.asChannel as TextChannel
                )
            }

            "ticket remove alertuser" -> {
                event.deferReply().queue()
                Ticketing.removeAlertUser(
                    event,
                    event.getOption("user")!!.asUser
                )
            }

            "ticket remove ticketingchannel" -> {
                event.deferReply().queue()
                Ticketing.removeTicketingChannel(
                    event,
                    event.getOption("channel")!!.asChannel as TextChannel
                )
            }

            "ticketing set default" -> {
                event.deferReply().queue()
                //Ticketing.setDefaultTicketingChannel(event, event.getOption("channel")!!.asChannel as TextChannel)
            }

            "welcome firsttimers channel" -> {
                event.deferReply().queue()
                WelcomePack.setWelcomeFirstChannel(
                    event,
                    event.getOption("channel")!!.asChannel as TextChannel
                )
            }

            "welcome firsttimers role" -> {
                event.deferReply().queue()
                WelcomePack.setWelcomeFirstRole(
                    event,
                    event.getOption("role")!!.asRole
                )
            }

            "welcome firsttimers message" -> {
                event.deferReply().queue()
                WelcomePack.setWelcomeFirstMessage(
                    event,
                    event.getOption("message")!!.asString
                )
            }

            "welcome back channel" -> {
                event.deferReply().queue()
                WelcomePack.setWelcomeBackChannel(
                    event,
                    event.getOption("channel")!!.asChannel as TextChannel
                )
            }

            "welcome back role" -> {
                event.deferReply().queue()
                WelcomePack.setWelcomeBackRole(
                    event,
                    event.getOption("role")!!.asRole
                )
            }

            "welcome back message" -> {
                event.deferReply().queue()
                WelcomePack.setWelcomeBackMessage(
                    event,
                    event.getOption("message")!!.asString
                )
            }

            "welcome minlevel" -> {
                event.deferReply().queue()
                WelcomePack.setMinLevel(
                    event,
                    event.getOption("level")!!.asInt
                )
            }

            "welcome switch" -> {
                event.deferReply().queue()
                WelcomePack.switch(event, event.getOption("mode")!!.asString.toBooleanStrict())
            }

            "welcome status" -> {
                event.deferReply().queue()
                WelcomePack.status(event)
            }
            // the /ephemeralrole commands
            "ephemeralrole add" -> {
                event.deferReply().queue()
                EphemeralRole.add(
                    event,
                    event.getOption("role")!!.asRole,
                    event.getOption("duration")!!.asLong,
                )
            }

            "ephemeralrole remove" -> {
                event.deferReply().queue()
                EphemeralRole.remove(event, event.getOption("role")!!.asRole)
            }

            "ephemeralrole removemissing" -> {
                event.deferReply().queue()
                EphemeralRole.removeMissing(event)
            }

            "ephemeralrole list" -> {
                event.deferReply().queue()
                EphemeralRole.list(event)
            }

            "ephemeralrole flush" -> {
                event.deferReply().queue()
                EphemeralRole.flush(event)
            }

            "customcommand add" -> {
                event.deferReply().queue()
                CustomCommands.add(event)
            }

            "customcommand remove" -> {
                CustomCommands.remove(event)
            }

            "customcommand list" -> {
                CustomCommands.list(event)
            }
        }
    }

    override suspend fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent) {
        //TODO: the commands "ticketing create onbehalf" should have autocomplete for the channel option
        // "ticketing set default" should have autocomplete for the channel option as well


        when (event.fullCommandName) {
            "instantban remove" -> InstantBan.handleAutoComplete(event)
            "instantban change" -> InstantBan.handleAutoComplete(event)
            "customcommand remove" -> CustomCommands.handleAutoComplete(event)
        }
    }

    /*
    // LEGACY CODE - KEPT FOR REFERENCE
    // This code has been replaced by the new implementation in CustomCommands.kt and other files.
    // The old CommandsCustom implementation has been moved to archived/CommandsCustom.kt
    // and should not be used anymore.
    //
    // DO NOT UNCOMMENT OR USE THIS CODE!
    //
    // For custom commands, use the new implementation:
    // - CustomCommands.add() - To add a custom command
    // - CustomCommands.remove() - To remove a custom command
    // - CustomCommands.list() - To list all custom commands
    //
    // The command execution logic has been moved to GuildCommandsImpl.kt
    */

    override suspend fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        if (event.componentId == "assignable-roles") {
            val roleIds: List<String> = event.values
            val invalidRoleIds = mutableListOf<String>()
            val validRoleIds = mutableListOf<String>()
            roleIds.forEach {
                val role = event.guild?.getRoleById(it)
                if (role != null) {
                    event.guild!!.addRoleToMember(event.member!!.user, role).queue()
                    validRoleIds.add(role.name)
                } else {
                    invalidRoleIds.add(it)
                }
            }
            val choosenRoles = "${event.user.asMention} you chose: ```${validRoleIds.joinToString("\n")}```"
            if (invalidRoleIds.isNotEmpty()) {
                event.reply("$choosenRoles\nI could not find the following role IDs: ${invalidRoleIds.joinToString(" ")}")
                    .queue()
            } else {
                event.reply(choosenRoles).queue()
            }
        }
    }
}
