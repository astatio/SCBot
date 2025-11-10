package helpers

import com.mongodb.client.model.Filters
import database
import dev.minn.jda.ktx.messages.EmbedBuilder
import helpers.BookHandler.bookErrorEmbed
import interfaces.BookRouter
import kotlinx.coroutines.flow.firstOrNull
import logger
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import java.time.Instant

object BookHandler {

    // This is a map of all the registered book routers.
    // The book routers need to provide a function to route and their supported books - which are the componentId prefixes.
    val registeredBookRouters = mutableListOf<BookRouter>()

    fun registerBookRouters(vararg bookRouter: BookRouter) {
        registeredBookRouters.addAll(bookRouter)
    }

    ///typicall component id: book-<bookName>-<pageName>
    //this regex will get the bookname
    val bookRegex = Regex("book-(\\w+)-(.+)")

    private val bookErrorEmbed = NeoSuperEmbed {
        type = SuperEmbed.ResultType.SIMPLE_ERROR
        text = "Oops! An error occurred while trying to create the embed."
    }

    /**
     * Sends a error embed message made specifically for "book" errors.
     * Alternatively, to get the embed only using the [bookErrorEmbed] object.
     */
    private fun MessageChannelUnion.sendErrorEmbed(): MessageCreateAction {
        return this.sendMessageEmbeds(
            bookErrorEmbed
        )
    }

    data class BookData(
        val componentId: String, // This will be the componentId prefix. Ex.: rpconf-1 in book-rpconf1-Instructions for the "Role Picker Configuration" embed.
        val title: String, // This will be the title of the embed. To not be confused with the title of the pages.
        val pages: List<Page>, // These are the pages that will be shown in this embed after a button interaction
    )

    //todo: This is for a future implementation of Custom Commands. It serves no purpose for now.
    data class CustomBookData(
        val componentId: Long,
        val title: String, // This will be the title of the embed. To not be confused with the title of the pages
        val pages: List<Page>, // These are the pages that will be shown in this embed
        val guildId: Long // This is the guildId
    )

    val bookDataCollection = database.getCollection<BookData>("bookEmbedData")

    data class Page(
        val title: String, // This is the title of the page. It will be the same as the button label.
        // This will be the componentId suffix, after the bookData.componentId and a hyphen in between them.
        // Ex.: rpconf-1-instructions for the "Role Picker Configuration" embed with the "Instructions" page
        val paragraphs: List<Pair<String, String>>
    )

    /**
     * Get the [Page] according to the wanted page title (the same as the button label)
     */
    fun List<Page>.getPage(
        titlePage: String
    ): Page? {
        return this.firstOrNull {
            it.title == titlePage
        }
    }

    /**
     * Creates an embed with buttons to navigate between pages.
     * Each page is a button of its own.
     * It will show the default page when the embed is created.
     * The order of the pages doesn't matter, as the buttons will be used to navigate between them.
     *
     * @param defaultPageTitle The title of the page that will be shown when the embed is created.
     * @param bookId The equivalent to [BookData.componentId]. It will be used to identify this "book" of pages.
     */
    suspend fun createEmbed(
        channel: MessageChannelUnion,
        bookId: String,
        defaultPageTitle: String,
        exclusiveUserId: Long // Exclusivity works by checking the user ID in the embed footer everytime a button is clicked
    ): MessageCreateData {
        // Before trying to make an embed, we need to get the data from the database
        val bookData = bookDataCollection.find(
            Filters.eq(BookData::componentId.name, bookId)
        ).firstOrNull() ?: return run {
            logger.error { "The data for the embed with componentId $bookId is not in the database." }
            dev.minn.jda.ktx.messages.MessageCreateBuilder {
                embed {
                    NeoSuperEmbed {
                        type = SuperEmbed.ResultType.SIMPLE_ERROR
                        text = "Oops! An error occurred while trying to create the embed."
                    }
                }
            }.build()
        }

        val embed = turnTo(bookId, defaultPageTitle, exclusiveUserId, channel.asGuildMessageChannel().guild.iconUrl, bookData)
        val buttons = mutableListOf<Button>()
        bookData.pages.forEach {
            buttons.add(
                Button.secondary(
                    "book-$bookId-${it.title}", // Ex.: book-rpconf1-Instructions. capitalization doesn't matter
                    it.title // label
                )
            )
        }
        return dev.minn.jda.ktx.messages.MessageCreateBuilder {
            embeds += embed
            components += createButtons(buttons)
        }.build()
    }


    //todo: This should be the update alternative to the createEmbed function. It should be called after a button interaction.
    /**
     * Flips the book to the desired page, if it exists.
     * This function is meant to be called when a button interaction is received from a "book" embed.
     * All the necessary validation are done inside this function.
     *
     * @param buttonInteractionEvent The button interaction event that triggered this function.
     */
    suspend fun flipBook(
        event: ButtonInteractionEvent, wantedBook: String, wantedPage: String, // This is the button interaction event
    ) {
        //shouldn't this check if the user is the one that can interact with the embed?
        val exclusiveUserId =
            event.message.embeds.firstOrNull()?.footer?.text?.substringAfter("ID: ")?.toLongOrNull()
        val guildIconUrl = event.guild?.iconUrl
        turnTo(wantedBook, wantedPage, exclusiveUserId, guildIconUrl).let {
            event.message.editMessageEmbeds(it).queue()
        }
    }

    // this should be the common function to handle the

    /**
     * This function returns an embed with the wanted page. Can be used to turn to a page or used when creating for the first time.
     *
     * @param wantedBook The book ID that will be shown in the embed. Ex.: rpconf1
     * @param wantedPage The page title that will be shown in the embed. Ex.: Instructions
     * @param userId **For use when creating the embed for the first time.** The user ID that will be shown in the footer of the embed.
     * @param guildIconUrl **For use when creating the embed for the first time.** The guild icon URL that will be shown in the footer of the embed.
     * @param bookData **For use when creating the embed for the first time.** The [BookData] object that will be used to create the embed. Use in order to avoid querying the database again.
     * */
    suspend fun turnTo(wantedBook: String, wantedPage: String, userId: Long?, guildIconUrl: String?, bookData: BookData? = null): MessageEmbed {
        val foundBook = bookData
            ?: (bookDataCollection.find(
                Filters.eq(BookData::componentId.name, wantedBook)
            ).firstOrNull())

        // if the book or the page doesn't exist, we need to send an error message
        if (foundBook == null) {
            logger.error { "The book with the ID $wantedBook and the page with the title $wantedPage is not in the database!" }
            return bookErrorEmbed
        }

        // now lets get the page
        val page = wantedPage.let { foundBook.pages.getPage(it) }
        if (page == null) {
            logger.error { "The book with the ID $wantedBook and the page with the title $wantedPage is not in the database!" }
            return bookErrorEmbed
        }

        // now we can create the embed
        val embedToSend = EmbedBuilder {
            title = foundBook.title
            description = page.title
            color = 0x2D7D46
            if (userId != null) {
                footer {
                    name = "Only interactable by User ID: $userId"
                    iconUrl = guildIconUrl
                }
                timestamp = Instant.now()
            }
            // Show the page with the requested title
            page.paragraphs.forEach { paragraph ->
                field {
                    name = paragraph.first
                    value = paragraph.second
                    inline = false
                }
            }
        }.build()
        return embedToSend
    }


    //todo: maybe it will be necessary to create a function that does the job of updating the embed.
    // theres common code in both createEmbed and flipBook that could be extracted to a function.

    //This function should smartly divide the buttons to make sure there's a max of 5 buttons per row
    private fun createButtons(buttons: List<Button>): MutableList<ActionRow> {
        // We need to divide the buttons into groups of 5. An ActionRow can contain up to 5 buttons.
        // In case Discord ever updates the max number of buttons per row, this will handle it.
        return ActionRow.partitionOf(buttons.toList())
        // This is the list of ActionRow objects that we want to put in the messages
    }
}
