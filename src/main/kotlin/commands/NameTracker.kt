package commands

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import database
import helpers.NeoSuperEmbed
import helpers.SuperEmbed
import helpers.sendConfirmationPrompt
import kotlinx.coroutines.flow.firstOrNull
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.user.update.UserUpdateGlobalNameEvent
import net.dv8tion.jda.api.events.user.update.UserUpdateNameEvent

object NameTracker {

    data class NameHistoryNames(
        val id: Long,
        val pastUsername: List<String>, // limited to 50 entries
        val pastGlobalName: List<String>, // limited to 50 entries
    )

    data class NameHistoryNicknames(
        val id: Long,
        val pastNickname: List<String>, // limited to 50 entries
        val guildId: Long
    )

    data class NameHistorySettings(
        val toggle: Boolean, // default: false
        val guildId: Long
    )

    val nameHistoryNamesCollection = database.getCollection<NameHistoryNames>("nameHistoryNames")
    val nameHistoryNicknamesCollection = database.getCollection<NameHistoryNicknames>("nameHistoryNicknames")
    val nameHistorySettingsCollection = database.getCollection<NameHistorySettings>("nameHistorySettings")

    suspend fun getNameHistoryStatus(guildId: Long) = nameHistorySettingsCollection.find(eq(NameHistorySettings::guildId.name, guildId)).firstOrNull()?.toggle ?: false

    /** Returns a list of the previous nicknames of a user in a specific guild.
     *
     * @return A list of previous nicknames of the user in the specified guild, limited to 50 entries.
     */
    suspend fun getNicknames(userId: Long, guildId: Long): List<String> {
        return nameHistoryNicknamesCollection.find(and(
            eq(NameHistoryNicknames::id.name, userId),
            eq(NameHistoryNicknames::guildId.name, guildId)
        )).firstOrNull()?.pastNickname ?: emptyList()
    }

    /** Returns a list of the previous usernames of a user.
     *
     * @return A list of previous usernames of the user, limited to 50 entries.
     */
    suspend fun getNames(userId: Long): List<String> {
        return nameHistoryNamesCollection.find(
            eq(NameHistoryNames::id.name, userId)
        ).firstOrNull()?.pastUsername ?: emptyList()
    }

    /** Returns a list of the previous global display names of a user.
     *
     * @return A list of previous global display names of the user, limited to 50 entries.
     */
    suspend fun getGlobalNames(userId: Long): List<String> {
        return nameHistoryNamesCollection.find(
            eq(NameHistoryNames::id.name, userId)
        ).firstOrNull()?.pastGlobalName ?: emptyList()
    }

    suspend fun switch(event: SlashCommandInteractionEvent, mode: Boolean) {
        val guildId = event.guild!!.idLong

        nameHistorySettingsCollection.updateOne(
            filter = eq(NameHistorySettings::guildId.name, guildId),
            update = Updates.combine(
                Updates.setOnInsert(NameHistorySettings::guildId.name, guildId),
                Updates.set(NameHistorySettings::toggle.name, mode)
            ),
            UpdateOptions().upsert(true)
        )

        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "**Name Tracker** is now ${if (mode) "ON" else "OFF"}"
        }).queue()
    }

    suspend fun clear(event: SlashCommandInteractionEvent, user: User) {
        val userNicknames =
            nameHistoryNicknamesCollection.findOneAndDelete(eq(NameHistoryNicknames::id.name, user.idLong))
        if (userNicknames == null) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "No nickname history found for ${user.asMention}"
            }).queue()
            return
        }

        event.hook.sendMessageEmbeds(NeoSuperEmbed {
            type = SuperEmbed.ResultType.SUCCESS
            text = "Cleared nickname history for ${user.asMention}\n"
        }).queue()
    }

    suspend fun clearSelf(event: SlashCommandInteractionEvent) {
        //instead of deleting nicknames, delete from the names collection
        val userNames =
            nameHistoryNamesCollection.find(eq(NameHistoryNames::id.name, event.user.idLong)).firstOrNull()
        if (userNames == null) {
            event.hook.sendMessageEmbeds(NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE_ERROR
                text = "No name nor global display name history found for ${event.user.asMention}"
            }).queue()
            return
        }
        event.sendConfirmationPrompt(
            "Are you sure you want to clear your name and global display name history? This data can help others identify you and is useful for moderation purposes."
        ) {
            nameHistoryNamesCollection.deleteOne(
                eq(NameHistoryNames::id.name, event.user.idLong)
            )
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Cleared name and global display name history for ${event.user.asMention}"
            }
        }
    }

    suspend fun flush(event: SlashCommandInteractionEvent) {
        event.sendConfirmationPrompt(
            "Are you sure you want to flush the nickname history for all members that ever joined the server? This data can help identify members."
        ) {
            nameHistoryNicknamesCollection.deleteMany(
                eq(NameHistoryNicknames::guildId.name, event.guild!!.idLong)
            )
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Flushed nickname history for all members that ever joined the server"
            }
        }
    }


    suspend fun onGuildMemberUpdateNickname(event: GuildMemberUpdateNicknameEvent) {
        // If the user is a bot, ignore. If MemberNameTracker is disabled, ignore
        if (event.member.user.isBot || !getNameHistoryStatus(event.guild.idLong) || (event.oldNickname == null)) return
       val nicknameHistory = nameHistoryNicknamesCollection.findOneAndUpdate(
            filter = and(
                eq(NameHistoryNicknames::id.name, event.member.idLong),
                eq(NameHistoryNicknames::guildId.name, event.guild.idLong)
            ),
            update = Updates.combine(
                Updates.setOnInsert(NameHistoryNicknames::id.name, event.member.idLong),
                Updates.setOnInsert(NameHistoryNicknames::guildId.name, event.guild.idLong),
                Updates.push(NameHistoryNicknames::pastNickname.name, event.oldNickname)
            ),
            options = FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
        ) ?: return
        if(nicknameHistory.pastNickname.count() > 50) {
            nameHistoryNicknamesCollection.updateOne(
                filter = eq(NameHistoryNicknames::id.name, event.member.idLong),
                update = Updates.combine(
                    Updates.setOnInsert(NameHistoryNicknames::id.name, event.member.idLong),
                    Updates.setOnInsert(NameHistoryNicknames::guildId.name, event.guild.idLong),
                    Updates.popFirst(NameHistoryNicknames::pastNickname.name)
                ),
                options = UpdateOptions().upsert(true)
            )
        }
    }

    suspend fun onUserUpdateGlobalName(event: UserUpdateGlobalNameEvent) {
        if (event.oldGlobalName == null || event.user.mutualGuilds.firstOrNull {
            guild -> getNameHistoryStatus(guild.idLong)
        } == null) return
        val nameHistory = nameHistoryNamesCollection.findOneAndUpdate(
            filter = eq(NameHistoryNames::id.name, event.user.idLong),
            update = Updates.combine(
                Updates.setOnInsert(NameHistoryNames::id.name, event.user.idLong),
                Updates.setOnInsert(NameHistoryNames::pastUsername.name, emptyList<String>()),
                Updates.push(NameHistoryNames::pastGlobalName.name, event.oldGlobalName)
            ),
            options = FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
        ) ?: return
        if(nameHistory.pastGlobalName.count() > 50) {
            nameHistoryNamesCollection.updateOne(
                filter = eq(NameHistoryNames::id.name, event.user.idLong),
                update = Updates.popFirst(NameHistoryNames::pastGlobalName.name),
                options = UpdateOptions().upsert(true)
            )
        }
    }

    suspend fun onUserUpdateName(event: UserUpdateNameEvent) {
        if (event.user.mutualGuilds.firstOrNull { guild -> getNameHistoryStatus(guild.idLong) } == null) return
        val nameHistory = nameHistoryNamesCollection.findOneAndUpdate(
            filter = eq(NameHistoryNames::id.name, event.user.idLong),
            update = Updates.combine(
                Updates.setOnInsert(NameHistoryNames::id.name, event.user.idLong),
                Updates.setOnInsert(NameHistoryNames::pastGlobalName.name, emptyList<String>()),
                Updates.push(NameHistoryNames::pastUsername.name, event.oldName)
            ),
            options = FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
        ) ?: return
        if(nameHistory.pastUsername.count() > 50) {
            nameHistoryNamesCollection.updateOne(
                filter = eq(NameHistoryNames::id.name, event.user.idLong),
                update = Updates.popFirst(NameHistoryNames::pastUsername.name),
                options = UpdateOptions().upsert(true)
            )
        }
    }
}