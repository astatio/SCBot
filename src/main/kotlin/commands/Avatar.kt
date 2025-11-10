package commands

import dev.minn.jda.ktx.coroutines.await
import helpers.commandMemberValidation
import helpers.throwEmbed
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.message.MessageReceivedEvent

object Avatar {

    suspend fun getAvatar(event: MessageReceivedEvent) {
        commandMemberValidation(event,
            ifMentioned = Avatar::getAvatarMember,
            ifUserID = Avatar::getAvatarUserID,
            ifNoMentionNorID =
            suspend {
                getAvatarMember(event, event.member!!)
            }
        )
    }

    suspend fun getAvatarUserID(event: MessageReceivedEvent, user: String) {
        try {
            val actualUser = event.jda.getUserById(user)!!
            event.message.reply("${actualUser.name}'s avatar: ${actualUser.effectiveAvatarUrl + "?size=2048"}").await()
        } catch (e: Throwable) {
            event.message.replyEmbeds(
                throwEmbed(e, "Invalid UserID")
            ).await()
        }
    }

    suspend fun getAvatarMember(event: MessageReceivedEvent, member: Member) {
        //member.effectiveAvatarUrl will show the perguild avatar if the member has one.
        //In case it has, the below 'if' will show the users regular avatar too.
        val memberTag = member.user.name
        val msg: String = if (member.avatarUrl != null) {
            "${memberTag}'s server avatar: ${member.effectiveAvatarUrl + "?size=2048"}\n" +
                    "${memberTag}'s avatar: ${member.user.effectiveAvatarUrl + "?size=2048"}"
        } else {
            "${memberTag}'s avatar: ${member.effectiveAvatarUrl + "?size=2048"}\n"
        }
        event.message.reply(msg).await()
    }
}