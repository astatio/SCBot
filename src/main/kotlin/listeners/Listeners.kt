package listeners

import EntryController // Added import
import commands.Barrage
import commands.EphemeralRole
import commands.NameTracker
import commands.SpamPrevention
import commands.Tagging
import filters.InstantBan
import commands.WelcomePack
import commands.ranks.Ranks
import core.ModLog
import dev.minn.jda.ktx.events.listener
import helpers.MessageCache
import interfaces.ButtonInteractions
import interfaces.ModalInteractions
import interfaces.SlashCommands
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.events.guild.GuildAuditLogEntryCreateEvent
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent // Added import
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent // Added import
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.MessageUpdateEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent
import net.dv8tion.jda.api.events.user.update.UserUpdateGlobalNameEvent
import net.dv8tion.jda.api.events.user.update.UserUpdateNameEvent

object Listeners {
    private lateinit var slashCommands: SlashCommands
    private lateinit var guildCommands: GuildCommands
    private lateinit var buttonInteractions: ButtonInteractions


    fun start(jda: JDA, slshCommands: SlashCommands, gldCommands: GuildCommands, bttnInteractions: ButtonInteractions) {
        slashCommands = slshCommands
        guildCommands = gldCommands
        buttonInteractions = bttnInteractions

        jda.listener<MessageReceivedEvent> {
            context(it) {
                // checks if the message comes from an allowed channel
                if (it.author.isBot) {
                    return@listener
                }
                if (it.isFromType(ChannelType.PRIVATE)) {
                    onPrivateCommand()
                    return@listener // Don't process guild commands if it's private
                }
                // Guild-based listeners below
                if (it.isFromGuild) {
                    if (Barrage.canContinue(it)) {
                        // CustomCommands.execute is now called within GuildCommandsImpl
                        guildCommands.onGuildCommand(it)
                    }
                    // Other guild message listeners that should always run
                    MessageCache.onMessageReceived(it)
                    InstantBan.findWords(it)
                    Ranks.onMessageReceived(it)
                    SpamPrevention.onMessageReceived(it)
                    Tagging.onMessageReceived(it) // Added Tagging listener
                }
            }
        }

        jda.listener<GuildMemberJoinEvent> {
            Ranks.onMemberJoin(it)
        }
        jda.listener<SlashCommandInteractionEvent> {
            slashCommands.onSlashCommandInteraction(it)
        }
        jda.listener<CommandAutoCompleteInteractionEvent> {
            slashCommands.onCommandAutoCompleteInteraction(it)
        }
        jda.listener<UserContextInteractionEvent> {
            ContextMenus.onUserContextInteractionEvent(it)
        }
        jda.listener<StringSelectInteractionEvent> {
            slashCommands.onStringSelectInteraction(it)
        }
        jda.listener<ButtonInteractionEvent> {
            buttonInteractions.onButtonInteraction(it)
        }
        jda.listener<MessageDeleteEvent> {
            ModLog.onMessageDelete(it)
        }
        jda.listener<MessageBulkDeleteEvent> {
            ModLog.onMessageBulkDelete(it)
        }
        jda.listener<GuildMemberRemoveEvent> {
            ModLog.onGuildMemberRemove(it)
        }
        jda.listener<GuildMemberJoinEvent> {
            ModLog.onGuildMemberJoin(it)
        }
        jda.listener<GuildMemberUpdateNicknameEvent> {
            ModLog.onGuildMemberUpdateNickname(it)
        }
        jda.listener<MessageUpdateEvent> {
            ModLog.onMessageUpdate(it)
        }
        jda.listener<GuildAuditLogEntryCreateEvent> {
            ModLog.onGuildAuditLogEntryCreate(it)
        }
        jda.listener<MessageReactionAddEvent> {
            ModLog.onMessageReactionAdd(it)
        }
        jda.listener<MessageReactionRemoveEvent> {
            ModLog.onMessageReactionRemove(it)
        }
        jda.listener<GuildMemberJoinEvent>{
            WelcomePack.decideWelcomeType(it)
        }
        jda.listener<GuildMemberUpdateNicknameEvent> {
            NameTracker.onGuildMemberUpdateNickname(it)
        }
        jda.listener<UserUpdateGlobalNameEvent> {
            NameTracker.onUserUpdateGlobalName(it)
        }
        jda.listener<UserUpdateNameEvent> {
            NameTracker.onUserUpdateName(it)
        }
        jda.listener<EntitySelectInteractionEvent> {
            onEntitySelectInteraction(it)
        }

        // Listeners moved from Main.kt
        jda.listener<GuildMemberJoinEvent> {
            EntryController.runEntryController(it)
        }

        jda.listener<GuildMemberRoleAddEvent> {
            EntryController.onGuildMemberRoleAdd(it)
        }

        jda.listener<GuildMemberRoleAddEvent> {
            EphemeralRole.onGuildMemberRoleAddEvent(it)
        }

        jda.listener<GuildAuditLogEntryCreateEvent> {
            EntryController.onGuildAuditLogEntryCreate(it)
        }

        jda.listener<GuildUnbanEvent> {
            EntryController.onGuildUnban(it)
        }

/*        jda.listener<GuildMemberJoinEvent> {
            FilterPing.onGuildMemberJoin(it)
        }
        jda.listener<MessageReceivedEvent> {
            if (it.isFromGuild)
                FilterPing.onMessageReceived(it)
        }*/
/*        jda.listener<GuildMemberUpdateNicknameEvent> {
            FilterPing.onGuildMemberUpdateNickname(it)
        }*/
/*
        jda.listener<UserUpdateNameEvent> {
            FilterPing.onUserUpdateName(it)
        }
*/

    }
}
