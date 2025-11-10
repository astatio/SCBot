package commands.core

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.*
import database
import dev.minn.jda.ktx.messages.EmbedBuilder
import dev.minn.jda.ktx.messages.MessageCreateBuilder
import helpers.NeoSuperEmbed
import helpers.SuperEmbed
import kotlinx.coroutines.flow.firstOrNull
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import kotlin.reflect.KClass

suspend fun sendAlert(buildAlert: AlertBuilder.() -> Unit): AlertAction {
    val alert = AlertBuilder().apply(buildAlert).build()
    alert.sendAlert()
    return AlertAction()
}

class AlertAction {
    suspend fun andThen(action: suspend () -> Unit) {
        action()
    }
}

class AlertBuilder {
    var command: KClass<*>? = null
    var severity: Alerts.Severity = Alerts.Severity.NORMAL
    lateinit var message: String
    lateinit var guild: Guild
    var additionalInfo: Map<String, String>? = null

    fun command(commandKClass: KClass<*>) = apply { this.command = commandKClass }
    fun severity(severity: Alerts.Severity) = apply { this.severity = severity }
    fun message(message: String) = apply { this.message = message }
    fun additionalInfo(builder: MutableMap<String, String>.() -> Unit) = apply {
        this.additionalInfo = mutableMapOf<String, String>().apply(builder)
    }

    fun guild(guild: Guild) = apply { this.guild = guild }

    fun build() = Alerts.Alert(
        commandKClass = command,
        severity = Alerts.Severity.NORMAL,
        message = message,
        guild = guild,
        additionalInfo = additionalInfo
    )
}


object Alerts {

    enum class Severity(val value: Int) {
        IMPORTANT(0),
        NORMAL(1)
    }

    data class AlertSettings(
        val status: Boolean, //by default, this is false
        val channelId: Long?, //channel ID where the alerts will be sent
        val importantRoleIds: List<Long>, //role IDs that will be pinged when an important alert is sent
        val guildId: Long
    )

    val alertSettingsCollection = database.getCollection<AlertSettings>("alertSettings")
    //todo: there are no slash commands to activate or set up alerts. Needs to be done.

    suspend fun getAlertSettings(guildId: Long): AlertSettings? {
        return alertSettingsCollection.find(eq(AlertSettings::guildId.name, guildId)).firstOrNull()
    }

    //- [X] /alerts toggle
    //- [x] /alerts channel [channel]
    //- [x] /alerts add [role]
    //- [x] /alerts remove [role]
    //- [x] /alerts status

    suspend fun switch(event: SlashCommandInteractionEvent, switch: Boolean) {
        val guildId = event.guild!!.idLong
        alertSettingsCollection.updateOne(
            filter = eq(AlertSettings::guildId.name, event.guild?.idLong),
            update = combine(
                set(AlertSettings::status.name, switch),
                setOnInsert(AlertSettings::channelId.name, null),
                setOnInsert(AlertSettings::importantRoleIds.name, emptyList<Long>()),
                setOnInsert(AlertSettings::guildId.name, guildId)
            ),
            UpdateOptions().upsert(true)
        )

        val status = if (switch) "enabled" else "disabled"
        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "**Alerts** are now $status"
            }
        ).queue()
    }

    suspend fun setChannel(event: SlashCommandInteractionEvent, channelId: GuildChannel) {
        val guildId = event.guild!!.idLong
        if (!channelId.type.isMessage) {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "The channel you provided is not a message channel."
                }
            ).queue()
            return
        }

        alertSettingsCollection.updateOne(
            filter = eq(AlertSettings::guildId.name, event.guild?.idLong),
            update = combine(
                set(AlertSettings::channelId.name, channelId.idLong),
                setOnInsert(AlertSettings::status.name, false),
                setOnInsert(AlertSettings::importantRoleIds.name, emptyList<Long>()),
                setOnInsert(AlertSettings::guildId.name, guildId)
            ),
            UpdateOptions().upsert(true)
        )

        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Alerts channel set to <#$channelId>"
            }
        ).queue()
    }

    suspend fun addRole(event: SlashCommandInteractionEvent, role: Role) {
        val guildId = event.guild!!.idLong

        alertSettingsCollection.updateOne(
            filter = eq(AlertSettings::guildId.name, event.guild?.idLong),
            update = combine(
                addToSet(AlertSettings::importantRoleIds.name, role.idLong),
                setOnInsert(AlertSettings::status.name, false),
                setOnInsert(AlertSettings::channelId.name, null),
                setOnInsert(AlertSettings::guildId.name, guildId)
            ),
            UpdateOptions().upsert(true)
        )

        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Added <@&${role.idLong}> to the list of roles that will be pinged when an important alert is sent."
            }
        ).queue()
    }

    suspend fun removeRole(event: SlashCommandInteractionEvent, role: Role) {
        val guildId = event.guild!!.idLong

        alertSettingsCollection.updateOne(
            filter = eq(AlertSettings::guildId.name, event.guild?.idLong),
            update = combine(
                pull(AlertSettings::importantRoleIds.name, role.idLong),
                setOnInsert(AlertSettings::status.name, false),
                setOnInsert(AlertSettings::channelId.name, null),
                setOnInsert(AlertSettings::guildId.name, guildId)
            ),
            UpdateOptions().upsert(true)
        )

        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Removed <@&${role.idLong}> from the list of roles that will be pinged when an important alert is sent."
            }
        ).queue()
    }

    suspend fun status(event: SlashCommandInteractionEvent) {
        val guildId = event.guild!!.idLong
        val alertSettings = getAlertSettings(guildId)
        if (alertSettings == null) {
            event.hook.sendMessageEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "Alerts are not set up in this server. Please set up alerts first."
                }
            ).queue()
            return
        }

        //check here if the alert channel is valid and that the important roles are valid
        roleCheck(
            this::class,
            alertSettings.importantRoleIds,
            event.guild!!,
            "important roles"
        ).onInvalid {
            it.invalidList?.let { invalidsList ->
                alertSettingsCollection.updateOne(
                    filter = eq(AlertSettings::guildId.name, event.guild?.idLong),
                    update = combine(
                        pullAll(AlertSettings::importantRoleIds.name, invalidsList)
                    ),
                    UpdateOptions().upsert(true)
                )
            }
            it.message += "I removed the invalid role IDs from the list of important roles."
        }

        alertSettings.channelId?.let {
            messageChannelCheck(
                this::class,
                listOf(it),
                event.guild!!,
                "channel"
            ).onInvalid { alert ->
                alertSettingsCollection.updateOne(
                    filter = eq(AlertSettings::guildId.name, event.guild?.idLong),
                    update = combine(
                        set(AlertSettings::channelId.name, null)
                    ),
                    UpdateOptions().upsert(true)
                )
                alert.message += "I removed the invalid channel ID. Please note that alerts will now be sent to the community updates channel (if it exists). If you want to change this, please set a new alerts channel."
            }
        }

        event.hook.sendMessageEmbeds(
            NeoSuperEmbed {
                type = SuperEmbed.ResultType.SUCCESS
                text = "Alerts are currently ${if (alertSettings.status) "enabled" else "disabled"}" +
                        if (alertSettings.channelId != null) "\nAlerts channel: ${"<#${alertSettings.channelId}>"}"
                        else { "" } +
                        if (alertSettings.importantRoleIds.isNotEmpty()) "\nImportant roles: ${alertSettings.importantRoleIds.joinToString(" ") { "<@&$it>" }}"
                        else { "" }
            }
        ).queue()
    }


    /**
     * Checks if the role IDs are valid and exist in the guild. If not, it returns a CheckResult.Failure with an Alert,
     * otherwise it returns a CheckResult.Success
     *
     * @return CheckResult
     */
    fun roleCheck(commandKlass: KClass<*>, roleIds: List<Long>, guild: Guild, functionality: String): CheckResult {
        //check if the role exists in the guild
        val invalidsList = mutableListOf<Long>()
        roleIds.forEach { roleId ->
            if (guild.getRoleById(roleId) == null) {
                invalidsList.add(roleId)
            }
        }
        if (invalidsList.isNotEmpty()) {
            return CheckResult.Invalid(
                Alert(
                    commandKClass = commandKlass,
                    message = "I found `${invalidsList.count()}` role IDs that are no longer valid inserted in `$functionality`.\n",
                    guild = guild,
                    invalidList = invalidsList,
                    severity = Severity.NORMAL,
                    additionalInfo = mapOf("Invalid role IDs" to invalidsList.joinToString(", ") { "`$it`" })
                )
            )
        }
        return CheckResult.Success
    }

    /**
     * Checks if the message channel IDs are valid and exist in the guild. If not, it returns a CheckResult.Failure with an Alert,
     * otherwise it returns a CheckResult.Success
     *
     * @return CheckResult
     */
    fun messageChannelCheck(
        commandKlass: KClass<*>,
        channelIds: List<Long>,
        guild: Guild,
        functionality: String
    ): CheckResult {
        //check if the channels are valid message channels and they exist in the guild
        val invalidsList = mutableListOf<Long>()
        channelIds.forEach { channelId ->
            if (guild.getGuildChannelById(channelId) == null) {
                invalidsList.add(channelId)
            } else {
                //if it isn't of type message, then its invalid
                if (!guild.getGuildChannelById(channelId)!!.type.isMessage)
                    invalidsList.add(channelId)
            }
        }
        if (invalidsList.isNotEmpty()) {
            return CheckResult.Invalid(
                Alert(
                    commandKClass = commandKlass,
                    message = "I found `${invalidsList.count()}` channel IDs that are no longer valid inserted in `$functionality`.\n",
                    guild = guild,
                    invalidList = invalidsList,
                    severity = Severity.NORMAL,
                    additionalInfo = mapOf("Invalid channel IDs" to invalidsList.joinToString(", ") { "`$it`" })
                )
            )
        }
        return CheckResult.Success
    }


    sealed class CheckResult {
        data object Success : CheckResult()
        data class Invalid(val alert: Alert) : CheckResult()
    }


    class Alert(
        var message: String,
        val guild: Guild,
        val invalidList: List<*>? = null,
        val commandKClass: KClass<*>? = null,
        var severity: Severity,
        val additionalInfo: Map<String, String>? = null
    ) {
        private val alertAsEmbed by lazy {
            EmbedBuilder {
                title = commandKClass?.simpleName ?: "Alert"
                description = message
                additionalInfo?.let {
                    field {
                        name = "Additional Info"
                        value = it.map { (key, value) -> "**$key**: `$value`" }.joinToString("\n")
                        inline = false
                    }
                }
                color = 0xF58220
            }
        }

        suspend fun sendAlert() {
            alertSettingsCollection.find(
                and(
                    eq(AlertSettings::status.name, true),
                    eq(AlertSettings::guildId.name, guild.idLong)
                )
            ).firstOrNull()?.let { alert ->
                //send the alert
                alert.channelId?.let {
                    (guild.getGuildChannelById(it) ?: guild.communityUpdatesChannel ?: guild.defaultChannel) as MessageChannel
                }?.sendMessage(
                    MessageCreateBuilder {
                        if (severity == Severity.IMPORTANT) {
                            content = alert.importantRoleIds.joinToString(" ") { "<@&$it>" }
                        }
                        embed { alertAsEmbed }
                    }.build()
                )?.queue()
            }
        }
    }


    /**
     * Sends an alert if the check fails, by default. You can override this behavior by passing in an action.
     * Please check each check's documentation to see what's the alert message.
     * An alert is always sent, you can just add more action **before** it is sent.
     *
     * If the AlertsSettings channel is invalid, it will send to the community updates channel.
     * If that doesn't exist, it will send to the default channel.
     *
     * @param action the action to be taken if the check fails. By default, it sends an alert.
     */
    suspend inline fun CheckResult.onInvalid(
        crossinline action: suspend (alert: Alert) -> Unit
    ) {
        if (this is CheckResult.Invalid) {
            action(alert)
            alert.sendAlert()
        }
    }



}
