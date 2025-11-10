import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.interactions.commands.*
import interfaces.SlashCommandsUpdates
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions

object SlashCommandsUpdatesImpl : SlashCommandsUpdates {
    override suspend fun start(jda: JDA) {
        jda.updateCommands {
            // message("Report") - This is meant to be a DigitalSparks feature
            user("Rank")
            user("User info")

            slash("alerts", "Settings for Alerts. Sends a message to a channel when an issue occurs with the bot") {
                restrict(guild = true, Permission.ADMINISTRATOR)
                subcommand("switch", "Switch the alerts system ON/OFF") {
                    option<String>("mode", "Switch it ON or OFF", required = true) {
                        choice("on", "true")
                        choice("off", "false")
                    }
                }
                subcommand("channel", "Sets the channel where the bot will send the alerts") {
                    option<GuildChannel>("channel", "The channel to set", required = true)
                }
                subcommand("add", "Adds a role to the list of roles that will be mentioned on important alerts") {
                    option<Role>("role", "The role to add to the list", required = true)
                }
                subcommand(
                    "remove",
                    "Removes a role from the list of roles that will be mentioned on important alerts"
                ) {
                    option<Role>("role", "The role to remove from the list", required = true)
                }
                subcommand("status", "Gets the status of the alerts system")
            }
            slash(
                "nametracker",
                "Settings for Name Tracker. Tracks changes in names, nicknames and global display names."
            ) {
                subcommand("switch", "Switch the nametracker ON/OFF") {
                    restrict(guild = true, Permission.ADMINISTRATOR)
                    option<String>("mode", "Switch it ON or OFF", required = true) {
                        choice("on", "true")
                        choice("off", "false")
                    }
                }
                subcommand("clear", "Clears the nametracker history of a member") {
                    restrict(guild = true, Permission.ADMINISTRATOR)
                    option<User>("user", "The user to clear the history of", required = true)
                }
                subcommand(
                    "clearself",
                    "Clears the nametracker history of the user who executed. Affects names and global display names."
                )
                subcommand(
                    "flush",
                    "Flushes the nametracker history of all members that ever joined the server. Affects only nicknames."
                ) {
                    restrict(guild = true, Permission.ADMINISTRATOR)
                }
            }
            slash("tagging", "Settings for Tagging. Pings moderators when a tagged member sends a message.") {
                restrict(guild = true, Permission.ADMINISTRATOR)
                subcommand("switch", "Switch the tagging ON/OFF") {
                    option<String>("mode", "Switch it ON or OFF", required = true) {
                        choice("on", "true")
                        choice("off", "false")
                    }
                }
                subcommand("channel", "Sets the channel where the bot will send the tagged member's messages") {
                    option<GuildChannel>("channel", "The channel to set", required = true)
                }
                subcommand("list", "Lists all tagged members")
                subcommand("tag", "Tags a member") {
                    option<User>("member", "The member to tag", required = true)
                }
                subcommand("untag", "Untags a member") {
                    option<User>("member", "The member to untag", required = true)
                }
            }

            //TODO: Consider deprecating the following command SpamDetector if it's not used much
            slash(
                "spamdetector",
                "Settings for Spam Detector. Spamming in a specific order and time interval soft-bans them."
            ) {
                restrict(guild = true, Permission.ADMINISTRATOR)
                subcommand("add", "Add a channel to the list") {
                    option<GuildChannel>("channel", "The channel to add to the list", required = true)
                }
                subcommand("remove", "Remove a channel from the list") {
                    option<GuildChannel>("channel", "The channel to remove from the list", required = true)
                }
                subcommand("list", "List all channels in the list")
                subcommand("change", "Changes the position of a channel in the list") {
                    option<GuildChannel>("channel", "The channel to change the position of", required = true)
                    option<Int>("position", "The new position of the channel", required = true)
                }
                subcommand("flush", "Flush the list of channels")
            }
            slash("help", "Shows the help menu")
            slash("ping", "Shows the latency (in ms) between Discord and $BOT_NAME")
            slash("info", "Shows information about $BOT_NAME")
            slash("uptime", "Returns $BOT_NAME uptime")
            slash("serverinfo", "Show important information about this server") {
                restrict(guild = true)
            }
            slash("userinfo", "Show important information about a user") {
                subcommand("self", "Shows information about yourself")
                subcommand("user", "The user to show information about") {
                    option<User>("user", "The user to show information about", required = true)
                }
            }
            slash("modlog", "Settings for the modlog") {
                restrict(guild = true, Permission.ADMINISTRATOR)
                subcommand("switch", "Switch modlog ON/OFF") {
                    option<String>("mode", "Switch it ON or OFF", required = true) {
                        choice("on", "true")
                        choice("off", "false")
                    }
                }
                subcommand("switchlsr", "Switch modlog 'Last Time Roles' feature ON/OFF") {
                    option<String>("mode", "Switch it ON or OFF", required = true) {
                        choice("on", "true")
                        choice("off", "false")
                    }
                }
                group("set", "Sets the modlog channel") {
                    subcommand("channel1", "Sets the primary modlog channel") {
                        option<GuildChannel>(
                            "channel",
                            "Channel for joins, leaves, kicks, bans and unbans",
                            required = true
                        )
                    }
                    subcommand("channel2", "Sets the secondary modlog channel") {
                        option<GuildChannel>(
                            "channel",
                            "Channel for other logs such as message actions",
                            required = true
                        )
                    }
                }
            }
            slash(
                "barrage",
                "Allows/blocks text commands from being executed in certain channels. By default, on block mode."
            ) {
                restrict(guild = true, Permission.ADMINISTRATOR)
                subcommand("add", "Add a channel to the list") {
                    option<GuildChannel>("channel", "The channel to add to the list", required = true)
                }
                subcommand("remove", "Remove a channel from the list") {
                    option<GuildChannel>("channel", "The channel to remove from the list", required = true)
                }
                subcommand("release", "Release all channels from the list")
                subcommand("list", "List all channels in the list")
                subcommand("status", "Get the status of the barrage system")
                subcommand("switch", "Switch between block and allow mode") {
                    option<String>("mode", "The mode to switch to. Either 'block' or 'allow'", required = true) {
                        choice("allow", "true") // true = allow mode
                        choice("block", "false") // false = block mode
                    }
                }
            }
            slash(
                "instantban",
                "A word filter that will instantly punish a user if they say a word in the list"
            ) {
                restrict(guild = true, Permission.ADMINISTRATOR)
                subcommand("add", "Add a word to the list. Default action: ban") {
                    option<String>("word", "The word to add to the list", required = true)
                    option<String>(
                        "action",
                        "How the user should be punished when the word is detected",
                        required = false
                    ) {
                        choice("ban", "ban")
                        choice("kick", "kick")
                        choice("timeout", "timeout")
                        choice("warn", "warn")
                    }
                    option<Int>(
                        "timeout",
                        "Timeout duration in minutes (used for timeout actions)",
                        required = false
                    )
                    option<Int>(
                        "warnthreshold",
                        "Warn threshold before applying the on-max-warn action",
                        required = false
                    )
                    option<String>(
                        "onmaxwarn",
                        "Action to take when the warn threshold is reached",
                        required = false
                    ) {
                        choice("none", "none")
                        choice("ban", "ban")
                        choice("kick", "kick")
                        choice("timeout", "timeout")
                    }
                    option<Boolean>(
                        "alert",
                        "Send an alert when the word is triggered",
                        required = false
                    )
                    option<Boolean>(
                        "severealert",
                        "If alerting, ping important alert roles",
                        required = false
                    )
                }
                subcommand("addmany", "Add many words to the list. Default action: ban") {
                    option<String>("words", "The words to add to the list separated by a whitespace", required = true)
                }
                subcommand("remove", "Remove a word from the list") {
                    option<String>("word", "The word to remove from the list", required = true, autocomplete = true)
                }
                subcommand("list", "List all words and send them through DMs")
                subcommand("change", "Get the status of the instantban system") {
                    option<String>("word", "The word to change the action of", required = true, autocomplete = true)
                    option<String>(
                        "action",
                        "How the user should be punished when the word is detected",
                        required = true
                    ) {
                        choice("ban", "ban")
                        choice("kick", "kick")
                        choice("timeout", "timeout")
                        choice("warn", "warn")
                    }
                    option<Int>(
                        "timeout",
                        "Timeout duration in minutes (used for timeout actions)",
                        required = false
                    )
                    option<Int>(
                        "warnthreshold",
                        "Warn threshold before applying the on-max-warn action",
                        required = false
                    )
                    option<String>(
                        "onmaxwarn",
                        "Action to take when the warn threshold is reached",
                        required = false
                    ) {
                        choice("none", "none")
                        choice("ban", "ban")
                        choice("kick", "kick")
                        choice("timeout", "timeout")
                    }
                    option<Boolean>(
                        "alert",
                        "Send an alert when the word is triggered",
                        required = false
                    )
                    option<Boolean>(
                        "severealert",
                        "If alerting, ping important alert roles",
                        required = false
                    )
                }
                subcommand("flush", "Flush the list of words")
            }
            slash("antiscam", "Protect the guild from known scam links using the Discord AntiScam dataset") {
                restrict(guild = true, Permission.ADMINISTRATOR)
                subcommand("switch", "Enable or disable AntiScam scanning") {
                    option<String>("mode", "Switch it ON or OFF", required = true) {
                        choice("on", "true")
                        choice("off", "false")
                    }
                }
                subcommand("status", "Show the current AntiScam configuration and dataset stats")
                subcommand("refresh", "Force a refresh of the AntiScam dataset from the upstream source")
                group("exclude", "Manage AntiScam channel exclusions") {
                    subcommand("add", "Exclude a channel from AntiScam scanning") {
                        option<GuildChannel>("channel", "Channel to exclude", required = true)
                    }
                    subcommand("remove", "Remove a channel from the AntiScam exclusion list") {
                        option<GuildChannel>("channel", "Channel to remove", required = true)
                    }
                    subcommand("clear", "Clear the AntiScam exclusion list")
                }
            }
            slash("customcommand", "Manage custom text commands") {
                restrict(guild = true, Permission.ADMINISTRATOR)
                subcommand("add", "Add a new custom command") {
                    option<String>("trigger", "The command trigger (e.g., !hello)", required = true)
                    option<String>("response", "The message the bot should send", required = false)
                    option<String>("aliascommand", "Optional: Another command to execute (e.g., !ban)", required = false)
                }
                subcommand("remove", "Remove a custom command") {
                    option<String>("trigger", "The command trigger to remove", required = true, autocomplete = true)
                }
                subcommand("list", "List all custom commands for this server")
            }
            slash("imdb", "Get information about a movie, series or episode") {
                option<String>("search", "The title of the media content to search for", required = true)
                option<String>("type", "The type of media content to search for") {
                    choice("movie", "movie")
                    choice("series", "series")
                    choice("episode", "episode")
                }
                option<Int>("year", "The media release year")
            }
            slash("experiments", "Get the list of currently available experiments")
            /*            slash("clear", "Clear messages from this channel or from a user") {
                restrict(guild = true, Permission.ADMINISTRATOR)
                option<Int>("amount", "The amount of messages to delete (Default: 100)", required = false)
            }*/
            slash("rolepicker", "Settings for the rolepicker and its sessions") {
                restrict(guild = true, Permission.ADMINISTRATOR)
                subcommand("create", "Create a new rolepicker session") {
                    option<GuildChannel>(
                        "channel",
                        "The channel where users will be able to pick roles",
                        required = true
                    )
                }
                subcommand("list", "List all rolepicker channels and their status")
                subcommand("info", "Get info about the rolepicker system")
            }
            slash("ticket", "Settings for the ticket system") {
                restrict(guild = true, Permission.MANAGE_ROLES)
                group("create", "Create a ticket") {
                    subcommand("classic", "Create a new ticket in a classic way") {
                        option<String>("subject", "The subject/reason for the ticket", required = true)
                        option<String>("description", "Additional details about the ticket")
                    }
                    subcommand("onbehalf", "Create a ticket on behalf of another user") {
                        option<User>("user", "The user to create the ticket for", required = true)
                        option<GuildChannel>("channel", "The ticketing channel to send the ticket to", required = true)
                        // option<String>("subject", "The subject/reason for the ticket")
                        option<String>("description", "Additional details about the ticket")
                    }
                }
                subcommand("assign", "Assign a ticket to a staff member") {
                    option<User>("user", "The staff member to assign the ticket to", required = true)
                }
                group("add", "Add to the ticket system") {
                    subcommand("alertrole", "Add a role to be mentioned when a ticket is created") {
                        option<Role>("role", "The role to add", required = true)
                    }
                    subcommand("alertchannel", "Add a channel where the bot will send a notification to") {
                        option<GuildChannel>("channel", "The channel to add", required = true)
                    }
                    subcommand("alertuser", "Add a user to be mentioned when a ticket is created") {
                        option<User>("user", "The user to add", required = true)
                    }
                    subcommand("ticketingchannel", "Add a channel to turn it into a ticketing channel") {
                        option<GuildChannel>("channel", "The ticketing channel to add", required = true)
                    }
                }
                group("remove", "Remove from the ticket system") {
                    subcommand(
                        "alertrole",
                        "Remove a role from the list of roles to be mentioned when a ticket is created"
                    ) {
                        option<Role>("role", "The role to remove", required = true)
                    }
                    subcommand(
                        "alertchannel",
                        "Remove a channel from the list of channels where the bot will send a notification to"
                    ) {
                        option<GuildChannel>("channel", "The channel to remove", required = true)
                    }
                    subcommand(
                        "alertuser",
                        "Remove a user from the list of users to be mentioned when a ticket is created"
                    ) {
                        option<User>("user", "The user to remove", required = true)
                    }
                    subcommand("ticketingchannel", "Remove a channel from the list of ticketing channels") {
                        option<GuildChannel>("channel", "The ticketing channel to remove", required = true)
                    }
                }
            }
            slash("welcome", "Settings for the welcome system") {
                restrict(guild = true, Permission.ADMINISTRATOR)
                group("firsttimers", "Settings for the firsttimers welcome system") {
                    subcommand("channel", "Set the channel where the firsttimers welcome message will be sent") {
                        option<GuildChannel>("channel", "The channel to set", required = true)
                    }
                    subcommand("role", "Set the role to be given to firsttimers") {
                        option<Role>("role", "The role to set", required = true)
                    }
                    subcommand("message", "Set the message to be sent to firsttimers") {
                        option<String>("message", "The message to set", required = true)
                    }
                }
                group("back", "Settings for the returning welcome system") {
                    subcommand("channel", "Set the channel where the returning welcome message will be sent") {
                        option<GuildChannel>("channel", "The channel to set", required = true)
                    }
                    subcommand("role", "Set the role to be given to returning members") {
                        option<Role>("role", "The role to set", required = true)
                    }
                    subcommand("message", "Set the message to be sent to returning members") {
                        option<String>("message", "The message to set", required = true)
                    }
                }
                subcommand("minlevel", "Set the minimum level to receive the welcome message") {
                    option<Int>("level", "The minimum level to set", required = true)
                }
                subcommand("switch", "Switch the welcome system ON/OFF") {
                    option<String>("mode", "Switch it ON or OFF", required = true) {
                        choice("on", "true")
                        choice("off", "false")
                    }
                }
                subcommand("status", "Get the status of the welcome system")
            }
            slash("ephemeralrole", "Settings for the ephemeral role system") {
                restrict(guild = true, Permission.ADMINISTRATOR)
                subcommand("add", "Add a new ephemeral role") {
                    option<Role>("role", "The role to add", required = true)
                    option<Int>("duration", "The duration in minutes for which the role will be active", required = true)
                }
                subcommand("remove", "Remove an ephemeral role") {
                    option<Role>("role", "The role to remove", required = true)
                }
                subcommand("removemissing", "Perform an automatic cleanup of missing ephemeral roles")
                subcommand("list", "List all ephemeral roles")
                subcommand("flush", "Flush all ephemeral roles")
            }

            /*
            // ... Below are some command ideas

                    .addCommands(helpers.commandprocessor.CommandData("purge", "Removes messages from this channel")
                        .addOption(OptionType.INTEGER, "ammount", "Ammount to delete (Default: 100)", false))

                    .addCommands(helpers.commandprocessor.CommandData("antiraid",
                        "Blocks write message permission in every single channel for everyone except moderators"))
                    .addCommands(helpers.commandprocessor.CommandData("antilink", "Blocks messages with links from untrusted members")
                        .addOption(OptionType.ROLE,
                            "role",
                            "Trusted members role that allows them to send links. Leave empty to enable/disable antilink.",
                            false))
                    .addCommands(helpers.commandprocessor.CommandData("antispam",
                        "Deletes and warns members when they send X messages with the same content in a row. Applies to text only")
                        .addOption(OptionType.ROLE, "limit", "Max equal messages allowed limit"))

                    .addCommands(helpers.commandprocessor.CommandData("announcement", "Announces and publishes in a server's announcements channel")
                        .addOption(OptionType.STRING, "channel", "Target announcement channel", true)
                        .addOption(OptionType.STRING, "content", "Content to be announced", true))

                    .addCommands(helpers.commandprocessor.CommandData("boost",
                        "Ativa/desativa o envio de uma mensagem personalizada por impulso no servidor")
                        .addOption(OptionType.CHANNEL,
                            "canal",
                            "Canal para onde as mensagens personalizadas deverão ser enviadas. Necessário para ativar.")
                        .addOption(OptionType.STRING,
                            "mensagem",
                            "Mensagem personalizada a ser enviada. Necessário para ativar."))


                    .addCommands(helpers.commandprocessor.CommandData("modrole", "Comandos sobre cargo de moderadores")
                        .addSubcommands(SubcommandData("verificar",
                            "Retorna informação sobre o atual cargo de moderadores numa mensagem temporária"))
                        .addSubcommands(SubcommandData("definir", "Define cargo de moderadores")
                            .addOption(OptionType.ROLE, "cargo", "Cargo de moderadores", true)))*/


        }.await()

        jda.getGuildById(SONIC_THE_HEDGEHOG)?.upsertCommand("entry", "Settings for entry system") {
            restrict(guild = true, Permission.MANAGE_SERVER)
            group("controller", "Controls whether to give Mime or Returning Member role on entry") {
                subcommand("switch", "Switch controller on/off") {
                    option<String>("mode", "Switch it ON or OFF", required = true) {
                        choice("on", "true")
                        choice("off", "false")
                    }
                }
            }
            group("mime", "Gives Member role to users who have Mime for more than 1 day. Runs every 20 minutes.") {
                subcommand("switch", "Switch the mime check on/off") {
                    option<String>("mode", "Switch it ON or OFF", required = true) {
                        choice("on", "true")
                        choice("off", "false")
                    }
                }
                subcommand("flush", "Flushes all Mime's and gives them the Member role")
            }
            group(
                "return",
                "Gives Member role to users who have Returning Member for more than 5 minutes. Runs every 5 minutes."
            ) {
                subcommand("switch", "Switch the return check on/off") {
                    option<String>("mode", "Switch it ON or OFF", required = true) {
                        choice("on", "true")
                        choice("off", "false")
                    }
                }
            }
        }?.await()
        jda.getGuildById(SONIC_THE_HEDGEHOG)?.upsertCommand("rank", "Settings for rank system") {
            restrict(guild = true, Permission.ADMINISTRATOR)
            subcommand("switch", "Switch rank system on/off") {
                option<String>("mode", "Switch it ON or OFF", required = true) {
                    choice("on", "true")
                    choice("off", "false")
                }
            }
            subcommand("switchmessage", "Switch rank messages on/off (does not affect special rank messages)") {
                option<String>("mode", "Switch it ON or OFF", required = true) {
                    choice("on", "true")
                    choice("off", "false")
                }
            }
            group("happyhour", "Set happy hour settings") {
                subcommand("start", "Start happy hour") {
                    option<Int>(
                        "percentage",
                        "The percentage of XP to give during this happy hour. Between 1 and 1000.",
                        required = true
                    )
                }
                subcommand("stop", "Stop happy hour")
            }
            group("set", "Set rank system settings") {
                subcommand("xp", "Control XP system") {
                    option<Int>(
                        "amount",
                        "The amount of XP gained per message. Default: 10 | Max: 1000",
                        required = true
                    )
                    option<Int>(
                        "cooldown",
                        "The cooldown in seconds between messages. Default: 30 | Max: 120",
                        required = true
                    )
                }
                subcommand("message", "Set the message that is sent when a user gains a new rank") {
                    option<String>(
                        "message",
                        "The message to send. Please check /docs for more info.",
                        required = true
                    )
                }
            }
            group("add", "Add to the rank system") {
                subcommand("specialrank", "Set a special rank that will trigger special actions") {
                    option<Int>("rank", "The rank to set the message for.", required = true)
                    option<String>(
                        "message",
                        "The message to send. Leave blank to not send a message. Please check /docs for more info.",
                        required = false
                    )
                    option<Role>(
                        "role",
                        "The role to give to the user. Leave blank to not give a role.",
                        required = false
                    )
                }
                subcommand("ignorerole", "Add a role to the list of roles where xp is not gained.") {
                    option<Role>("role", "The role to add to the list", required = true)
                }
                subcommand("ignorechannel", "Add a channel to the list of channels where xp is not gained.") {
                    option<GuildChannel>("channel", "The channel to add to the list", required = true)
                }
            }
            group("remove", "Remove from rank system") {
                subcommand("specialrank", "Remove a special rank that will trigger special actions") {
                    option<Int>("rank", "The rank to be removed from the list", required = true)
                }
                subcommand("ignorerole", "Remove a role from the list of roles where xp is not gained.") {
                    option<Role>("role", "The role to remove from the list", required = true)
                }
                subcommand("ignorechannel", "Remove a channel from the list of channels where xp is not gained.") {
                    option<GuildChannel>("channel", "The channel to remove from the list", required = true)
                }
            }
            group("get", "Get rank system settings") {
                subcommand("xp", "Get XP system settings.")
                subcommand("message", "Get the message that is sent when a user gains a new rank.")
                subcommand("specialrank", "Get the special ranks that trigger special actions.")
                subcommand("ignorerole", "Get the list of roles where xp is not gained.")
                subcommand("ignorechannel", "Get the list of channels where xp is not gained.")
                subcommand(
                    "userinfo",
                    "Get all rank information for a user (even if they're not in the server anymore)."
                ) {
                    option<String>("userid", "The user ID to get the info from.", required = true)
                }
            }
            group("give", "Give a member a certain rank or XP") {
                subcommand("rank", "Give a member a certain rank (it will override any XP they have)") {
                    option<Int>("level", "The rank level to give the user.", required = true)
                    option<User>("user", "The user to give the rank to.", required = true)
                }
                subcommand("xp", "Give a member XP (it will add)") {
                    option<Long>("xp", "The amount of XP to give the user.", required = true)
                    option<User>("user", "The user to give XP to.", required = true)
                }
                subcommand("xpoverride", "Give - and override - a member XP") {
                    option<Long>("xp", "The amount of XP to set the user to.", required = true)
                    option<User>("user", "The user to give XP to.", required = true)
                }
            }
        }?.await()
    }
}
