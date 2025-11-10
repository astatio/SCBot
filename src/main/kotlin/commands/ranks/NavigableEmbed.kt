package commands.ranks

import dev.minn.jda.ktx.interactions.components.button
import dev.minn.jda.ktx.messages.EmbedBuilder
import dev.minn.jda.ktx.messages.InlineEmbed
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.Delegates
import kotlin.time.Duration.Companion.minutes

class NavigableEmbed {
    private val pageSize = 10 // The max amount of items per page
    private var totalPages by Delegates.notNull<Int>()
    private var title: String = ""

    fun createEmbed(
        embedTitle: String,
        event: MessageChannelUnion,
        items: List<Pair<String, Any>>,
        currentPage: AtomicInteger,
    ): InlineEmbed {
        totalPages = if (items.size % pageSize == 0) items.size / pageSize else (items.size / pageSize) + 1
        return EmbedBuilder {
            title = embedTitle
            color = 0x2D7D46
            footer {
                name = "Page ${currentPage.get()} - ${totalPages} • ${event.asGuildMessageChannel().guild.name}"
                iconUrl = event.asGuildMessageChannel().guild.iconUrl
            }
            timestamp = Instant.now()

            // Create the fields but only for a max of 10 items belonging to the current page
            items.subList((currentPage.get() - 1) * pageSize, currentPage.get() * pageSize).forEach { entry ->
                field {
                    name = entry.first
                    value = entry.second.toString()
                    inline = true
                }
            }
        }.also { this.title = embedTitle }
    }

    data class ButtonCallbacks(
        val first: suspend (ButtonInteractionEvent) -> Unit,
        val back: suspend (ButtonInteractionEvent) -> Unit,
        val next: suspend (ButtonInteractionEvent) -> Unit,
        val last: suspend (ButtonInteractionEvent) -> Unit
    )


    fun createButtonCallbacks(
        event: MessageChannelUnion,
        items: List<Pair<String, Any>>,
        currentPage: AtomicInteger
    ): ButtonCallbacks {

        val first: suspend (ButtonInteractionEvent) -> Unit = {
            if (currentPage.get() != 1) {
                currentPage.set(1)
                it.editMessageEmbeds(
                    createEmbed(
                        title,
                        event,
                        items,
                        currentPage
                    ).build()
                ).queue()
            } else {
                it.reply("You are already on the first page.").setEphemeral(true).queue()
            }
        }

        val back: suspend (ButtonInteractionEvent) -> Unit = {
            if (currentPage.get() > 1) {
                currentPage.decrementAndGet()
                val backPageEmbed = createEmbed(
                    title,
                    event,
                    items,
                    currentPage
                )
                it.editMessageEmbeds(backPageEmbed.build()).queue()
            } else {
                it.reply("You can't go back any further because you're already on the first page").setEphemeral(true)
                    .queue()
            }
        }


        val next: suspend (ButtonInteractionEvent) -> Unit = {
            if (currentPage.get() < totalPages) {
                currentPage.incrementAndGet()
                val nextPageEmbed = createEmbed(
                    title,
                    event,
                    items,
                    currentPage
                )
                it.editMessageEmbeds(nextPageEmbed.build()).queue()
            } else {
                it.reply("You can't go forward any further because you're already on the last page.").setEphemeral(true)
                    .queue()
            }
        }

        val last: suspend (ButtonInteractionEvent) -> Unit = {
            if (currentPage.get() != totalPages) {
                currentPage.set(totalPages)
                val lastPageEmbed = createEmbed(
                    title,
                    event,
                    items,
                    currentPage
                )
                it.editMessageEmbeds(lastPageEmbed.build()).queue()
            } else {
                it.reply("You are already on the last page.").setEphemeral(true).queue()
            }
        }
        return ButtonCallbacks(first, back, next, last)
    }

    fun createButtons(jda: JDA, buttonCallbacks: ButtonCallbacks): List<Button> {
        val first = jda.button(label = "First 10", style = ButtonStyle.SUCCESS, expiration = 5.minutes) {
            buttonCallbacks.first(it)
        }
        val back = jda.button(label = "Back", style = ButtonStyle.SECONDARY, expiration = 5.minutes) {
            buttonCallbacks.back(it)
        }
        val next = jda.button(label = "Next", style = ButtonStyle.SECONDARY, expiration = 5.minutes) {
            buttonCallbacks.next(it)
        }
        val last = jda.button(label = "Last 10", style = ButtonStyle.SUCCESS, expiration = 5.minutes) {
            buttonCallbacks.last(it)
        }

        return listOf(first, back, next, last)
    }

}