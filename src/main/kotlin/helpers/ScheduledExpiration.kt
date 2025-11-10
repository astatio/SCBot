package helpers

import club.minnced.discord.webhook.send.WebhookMessage
import helpers.ReusableScope.vScope
import kotlinx.coroutines.*
import net.dv8tion.jda.api.components.Component
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.components.buttons.ButtonInteraction
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty
import kotlin.time.Duration

object ScheduledExpiration {

    // A way to track message expirations. This can be used to cancel scheduled expirations when manual expiration is done.
    private val scheduledJobs = mutableMapOf<Long, Job>()

    /**
     * Schedules a message to expire after a certain amount of time.
     *
     * All components of type [ActionRow] will be disabled while others will be removed.
     *
     * The buttons defined expiration time when using [dev.minn.jda.ktx.interactions.components.button] is not taken into account.
     *
     * It's recommended to set the buttons expiration time to the same value as the message expiration time.
     *
     * @param expirationTime The amount of time to wait before expiring the message
     */
    fun Message.expire(expirationTime: Duration) {
        val job = vScope.launch {
            delay(expirationTime.inWholeMilliseconds)
            val editedComps = this@expire.components.mapNotNull {
                if (it.type == Component.Type.ACTION_ROW)
                    it.asActionRow().asDisabled()
                else null
            }
            this@expire.editMessageComponents(editedComps)
            scheduledJobs.remove(this@expire.idLong)
        }
    }


    /**
     * Cancels a scheduled expiration for this message, if one exists, and immediately expires the message.
     *
     * All components of type [ActionRow] will be disabled.
     *
     * The buttons defined expiration time when using [dev.minn.jda.ktx.interactions.components.button] is not taken into account.
     *
     * It's recommended to set the buttons expiration time to the same value as the message expiration time.
     */
    fun Message.expire() {
        scheduledJobs.remove(this.idLong)?.cancel()
        val editedComps = this.components.mapNotNull {
            if (it.type == Component.Type.ACTION_ROW)
                it.asActionRow().asDisabled()
            else null
        }
        this.editMessageComponents(editedComps)
    }

    /**
     * Makes new interactions impossible on the message this button is attached to and edits to show a "Canceled" message.
     *
     * Calls [expire].
     */
    fun ButtonInteraction.cancel() {
        this.hook.editOriginalEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SIMPLE
                text = "Canceled"
            }
        ).setComponents()

        this.hook.retrieveOriginal().queue { msg ->
            msg.cancelScheduledExpiration()
            val editedComps = msg.components.mapNotNull {
                if (it.type == Component.Type.ACTION_ROW)
                    it.asActionRow().asDisabled()
                else null
            }
            msg.editMessageComponents(editedComps).queue()
        }
    }

    /**
     * Cancels a scheduled expiration for this message, if one exists.
     */
    fun Message.cancelScheduledExpiration() = scheduledJobs.remove(this.idLong)?.cancel()

    /**
     * Checks if this message has a scheduled expiration.
     *
     * @return True if the message has a scheduled expiration, false otherwise.
     */
    fun Message.hasScheduledExpiration(): Boolean = scheduledJobs.containsKey(this.idLong)

}