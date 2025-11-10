package commands

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import commands.filters.FilterCommons.censor
import core.ModLog
import database
import dev.minn.jda.ktx.messages.MessageCreateBuilder
import helpers.NeoSuperEmbed
import helpers.SuperEmbed
import helpers.checkAndReduce
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent

object Tagging {

    // Meant to ping 1 or more people (these people are meant to be moderators) when the tagged member sends a message

    data class TaggedMember(
        val id: Long,
        val toPingIds: List<Long>,
        val guildId: Long
    )

    data class TaggingSettings(
        val toggle: Boolean, // default: false
        val channelId: Long?,
        val guildId: Long
    )

    val taggedMemberCollection = database.getCollection<TaggedMember>("taggedMember")
    val taggingSettingsCollection = database.getCollection<TaggingSettings>("taggingSettings")

    suspend fun getTaggingSettingsStatus(guildId: Long) =
        taggingSettingsCollection.find(eq(TaggingSettings::guildId.name, guildId)).firstOrNull()?.toggle ?: false

    // [x] /tagging toggle - toggles "Tagging" on/off
    // [x] /tagging channel - sets the channel where the bot will send the tagged member's messages
    // [x] /tagging tag [member] - "tags" a member
    // [x] /tagging untag [member] - "untags" a member

    suspend fun switch(event: SlashCommandInteractionEvent, mode: Boolean) {
        val taggingSettings = taggingSettingsCollection.findOneAndUpdate(
            filter = eq(TaggingSettings::guildId.name, event.guild!!.idLong),
            update = Updates.combine(
                Updates.setOnInsert(TaggingSettings::guildId.name, event.guild!!.idLong),
                Updates.set(TaggingSettings::toggle.name, mode)
            ),
            FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
        )

        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "**Tagging** is now ${if (mode) "ON" else "OFF"}" +
                    if (taggingSettings?.channelId == null) "\nUse `/tagging channel` to set the channel where the bot will send the tagged member's messages" else ""
        }).queue()
    }

    suspend fun setChannel(event: SlashCommandInteractionEvent, channel: GuildChannel) {
        if (!channel.type.isMessage) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "Channel must be a message capable channel."
            }).queue()
            return
        }
        taggingSettingsCollection.updateOne(
            filter = eq(TaggingSettings::guildId.name, event.guild!!.idLong),
            update = Updates.combine(
                Updates.setOnInsert(TaggingSettings::guildId.name, event.guild!!.idLong),
                Updates.setOnInsert(TaggingSettings::toggle.name, false),
                Updates.set(TaggingSettings::channelId.name, channel.idLong)
            ),
            UpdateOptions().upsert(true)
        )

        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "Tagging channel set to ${channel.asMention}"
        }).queue()
    }

    suspend fun list(event: SlashCommandInteractionEvent) {
        val taggingSettings =
            taggingSettingsCollection.find(eq(TaggingSettings::guildId.name, event.guild!!.idLong)).firstOrNull()
        if (taggingSettings == null || !taggingSettings.toggle) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "Tagging is not enabled in this server. Enable and try again."
            }).queue()
            return
        }
        val taggedMembers = taggedMemberCollection.find(
            eq(
                TaggedMember::guildId.name,
                event.guild!!.idLong
            )
        ).toList()
        if (taggedMembers.isEmpty()) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "No one is tagged."
            }).queue()
            return
        }
        val taggedMembersString = taggedMembers.joinToString("\n") {
            "**${it.id} (<@${it.id}>)** - ${it.toPingIds.joinToString(" ") { id -> "<@$id>" }}"
        }
        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "Tagged members:```\n$taggedMembersString```"
        }).queue()
    }

    suspend fun tag(event: SlashCommandInteractionEvent, member: User) {
        val taggingSettings =
            taggingSettingsCollection.find(eq(TaggingSettings::guildId.name, event.guild!!.idLong)).firstOrNull()
        if (taggingSettings == null || !taggingSettings.toggle) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "Tagging is not enabled in this server. Enable and try again."
            }).queue()
            return
        }
        if (taggingSettings.channelId == null) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "Tagging channel not set. Set it using `/tagging channel`"
            }).queue()
            return
        }
        if (member.isBot) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "You can't tag bots."
            }).queue()
            return
        }

        taggedMemberCollection.updateOne(
            filter =
                and(
                    eq(TaggedMember::guildId.name, event.guild!!.idLong),
                    eq(TaggedMember::id.name, member.idLong)
                ),
            update = Updates.combine(
                Updates.setOnInsert(TaggedMember::id.name, member.idLong),
                Updates.setOnInsert(TaggedMember::guildId.name, event.guild!!.idLong),
                Updates.push(TaggedMember::toPingIds.name, event.user.idLong)
            ),
            UpdateOptions().upsert(true)
        )

        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text =
                "Tagged ${member.asMention}. You will be pinged in <#${taggingSettings.channelId}> when they send a message."
        }).queue()
    }

    suspend fun untag(event: SlashCommandInteractionEvent, member: User) {
        val taggingSettings =
            taggingSettingsCollection.find(eq(TaggingSettings::guildId.name, event.guild!!.idLong)).firstOrNull()
        if (taggingSettings == null || !taggingSettings.toggle) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "Tagging is not enabled in this server. Enable and try again."
            }).queue()
            return
        }

        val updatedTaggedMember = taggedMemberCollection.findOneAndUpdate(
            filter = and(
                eq(TaggedMember::guildId.name, event.guild!!.idLong),
                eq(TaggedMember::id.name, member.idLong),
                eq(TaggedMember::toPingIds.name, event.user.idLong)
            ),
            update = Updates.pull(TaggedMember::toPingIds.name, event.user.idLong),
            options = FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
        )

        if (updatedTaggedMember == null) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "You hadn't tagged ${member.asMention}, or they weren't tagged at all."
            }).queue()
            return
        }

        if (updatedTaggedMember.toPingIds.isEmpty()) {
            taggedMemberCollection.deleteOne(
                and(
                    eq(TaggedMember::guildId.name, event.guild!!.idLong),
                    eq(TaggedMember::id.name, member.idLong)
                )
            )
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Untagged ${member.asMention} for you. Since no one else was tagging them, the tag entry was removed."
            }).queue()
        } else {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Untagged ${member.asMention} for you."
            }).queue()
        }
    }

    suspend fun onMessageReceived(event: MessageReceivedEvent) {
        if (!event.isFromGuild) return
        if (event.author.isBot) return
        val taggedMember = taggedMemberCollection.find(
                and(
                    eq(TaggedMember::guildId.name, event.guild.idLong),
                    eq(TaggedMember::id.name, event.author.idLong)
                )
        ).firstOrNull() ?: return

        val channel = event.guild.getTextChannelById(
            taggingSettingsCollection.find(
                eq(
                    TaggingSettings::guildId.name,
                    event.guild.idLong
                )
            ).firstOrNull()?.channelId ?: return
        ) ?: return

        val messageCreated = MessageCreateBuilder {
            content =
                "${taggedMember.toPingIds.joinToString(" ") { id -> "<@$id>" }} the tagged member ${event.author.asMention} sent a message"
            embeds += ModLog.embedBuilder(event.message) {
                    title = "Message Sent"
                color = 0x008100
                    description =
                        "**${event.author.asMention} - ${event.author.name}** sent a [message](${event.message.jumpUrl}) in ${event.channel.asMention}"
                    field {
                        name = "Message"
                        value = checkAndReduce(censor(event.message.contentDisplay))
                    }
                }
        }.build()
        channel.sendMessage(messageCreated).queue()
    }
}
