package commands

import com.facebook.ktfmt.cli.Main
import com.facebook.ktfmt.format.Formatter
import com.facebook.ktfmt.format.ParseError
import dev.minn.jda.ktx.coroutines.await
import helpers.Hastebin
import helpers.NeoSuperEmbed
import helpers.SuperEmbed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.slf4j.LoggerFactory
import owners
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

val defaultImports = listOf(
    "kotlinx.coroutines.async",
    "dev.minn.jda.ktx.coroutines.await",
    "dev.minn.jda.ktx.events.await",
    "net.dv8tion.jda.api.managers.*",
    "net.dv8tion.jda.api.entities.*",
    "net.dv8tion.jda.api.*",
    "net.dv8tion.jda.api.utils.*",
    "net.dv8tion.jda.api.utils.data.*",
    "net.dv8tion.jda.internal.entities.*",
    "net.dv8tion.jda.internal.requests.*",
    "net.dv8tion.jda.api.requests.*",
    "java.io.*",
    "java.math.*",
    "java.util.*",
    "java.util.concurrent.*",
    "java.time.*"
)

/**
 * DISCLAIMER:
 * The code in this file is mostly based on [Bean](https://github.com/Xirado/Bean).
 * At the time of writing, the original code can be found at the following directory inside the given repository: Bean/src/main/java/at/xirado/bean/command/commands/EvalCommand.kt
 * Please refer to the original repository for more information about the original code.
 */


suspend fun eval(event: MessageReceivedEvent) {
    if (event.author.idLong !in owners) return
    EvalCommand.executeCommand(event)
}

object EvalCommand {

    val engine: ScriptEngine = ScriptEngineManager().getEngineByName("kotlin")

    suspend fun executeCommand(
        event: MessageReceivedEvent,
        context: String = event.message.contentRaw.substringAfter(" ")
    ) {
        val message = event.message

        if (context == "!eval") {
            event.guild.owner!!.idLong
            message.replyEmbeds(
                NeoSuperEmbed {
                    type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = "Error: missing arguments!"
                }
            ).mentionRepliedUser(false).queue()
            return
        }

        val raw = if (context.startsWith("```") && context.endsWith("```")) {
            context.substring(context.indexOf("\n"), context.length - 3)
        } else {
            context
        }.trim()

        val bindings = mapOf(
            "scope" to CoroutineScope(Dispatchers.Default),
            "channel" to event.channel,
            "guild" to event.guild,
            "jda" to event.jda,
            "user" to event.author,
            "author" to event.author,
            "member" to event.member!!,
            "api" to event.jda,
            "event" to event,
            "bot" to event.jda.selfUser,
            "selfUser" to event.jda.selfUser,
            "selfMember" to event.guild.selfMember,
            "log" to LoggerFactory.getLogger(Main::class.java),
        )

        bindings.forEach { (t, u) -> engine.put(t, u) }

        val formatted = try {
            parse(raw, defaultImports)
        } catch (ex: ParseError) {
            val error = ex.toString()
            message.reply("An error occurred while formatting the code!" + if (error.length > 1000) "" else "\n```\n$error```")
                .mentionRepliedUser(false)
                .setComponents(
                    ActionRow.of(
                        generateError(ex.toString()),
                        generateSource(raw),
                        deleteMessageButton()
                    )
                )
                .queue()
            return
        }

        val response = try {
            (engine.eval(formatted) as Deferred<*>).await()
        } catch (ex: Exception) {
            val error = ex.toString()
            message.reply("An error occurred while running the Kotlin script!" + if (error.length > 1000) "" else "\n```\n$error```")
                .mentionRepliedUser(false)
                .setComponents(
                    ActionRow.of(
                        generateError(ex.toString()),
                        generateSource(formatted),
                        deleteMessageButton()
                    )
                )
                .queue()
            return
        }

        if (response is Unit) {
            message.reply("Code executed without errors")
                .setComponents(
                    ActionRow.of(
                        generateSource(formatted),
                        deleteMessageButton()
                    )
                )
                .queue()
            return
        }

        val responseString = response.toString()

        if (responseString.length > 1993) {
            message.reply("⚠ Result was too long. Please get it from the helpers.Hastebin!")
                .setComponents(
                    ActionRow.of(
                        generateSource(formatted),
                        generateResult(responseString),
                        deleteMessageButton()
                    )
                )
                .mentionRepliedUser(false)
                .queue()
        } else {
            message.reply("```\n${response}```")
                .setComponents(
                    ActionRow.of(
                        generateSource(formatted),
                        generateResult(responseString),
                        deleteMessageButton()
                    )
                )
                .mentionRepliedUser(false)
                .await()
        }
    }
}


private fun parse(input: String, imports: List<String>): String {
    val split = if (input.startsWith("```") && input.endsWith("```")) {
        input.substring(input.indexOf("\n"), input.length - 3).split("\n")
    } else {
        input.split("\n")
    }

    val toEval = mutableListOf<String>()

    val completeImports = mutableListOf<String>()
    completeImports.addAll(imports)

    split.forEach {
        if (it.startsWith("import ")) {
            val import = it.substring(7)
            completeImports.add(import)
            return@forEach
        }
        toEval.add(it)
    }

    val sb = StringBuilder()

    completeImports.forEach { sb.append("import $it\n") }

    sb.append("\n")

    sb.append("scope.async {\n")
    toEval.filter { it.isNotBlank() }.forEach { sb.append("$it\n") }
    sb.append("}")

    return Formatter.format(sb.toString(), removeUnusedImports = true)
}

private suspend fun generateError(content: String): Button {
    val link = runCatching { Hastebin.post(content, false) }
        .getOrNull()

    return if (link != null) errorLinkButton(link, "Error") else errorLinkButton(
        "https://nop.link",
        "Error"
    ).asDisabled()
}

private suspend fun generateSource(content: String): Button {
    val link = runCatching { Hastebin.post(content, false, "kt") }
        .getOrNull()

    return if (link != null) sourceLinkButton(link, "Source-Code") else sourceLinkButton(
        "https://nop.link",
        "Source-Code"
    ).asDisabled()
}

private suspend fun generateResult(content: String): Button {
    val link = runCatching { Hastebin.post(content, false) }
        .getOrNull()

    return if (link != null) resultLinkButton(link, "Result") else resultLinkButton(
        "https://nop.link",
        "Result"
    ).asDisabled()
}

private fun resultLinkButton(url: String, label: String) =
    Button.link(url, label).withEmoji(Emoji.fromFormatted("\uD83D\uDEE0"))

private fun deleteMessageButton() =
    Button.danger("deletemsg", "Delete").withEmoji(Emoji.fromFormatted("\uD83D\uDDD1"))

private fun errorLinkButton(url: String, label: String) =
    Button.link(url, label).withEmoji(Emoji.fromCustom("error", 943524725487968298, false))

private fun sourceLinkButton(url: String, label: String) =
    Button.link(url, label).withEmoji(Emoji.fromFormatted("\uD83D\uDCDD"))
