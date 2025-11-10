
import dev.minn.jda.ktx.messages.Embed
import helpers.*
import kotlinx.serialization.json.*
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.utils.messages.MessageEditData
import java.net.URI
import javax.imageio.ImageIO

private fun responseToEmbed(response: JsonElement) = Embed {


    title = response.jsonObject["Title"]?.jsonPrimitive?.content ?: "No title found"

    if (!response.jsonObject["Poster"]?.jsonPrimitive?.content.isNullOrEmpty()) {
        thumbnail = response.jsonObject["Poster"]?.jsonPrimitive?.content
        color = dominantColorHex(
            ImageIO.read(
                thumbnail?.let { URI(it).toURL() }
            )
        )
    }
    //add Year, Type and IMDB ID
    if (!response.jsonObject["Year"]?.jsonPrimitive?.content.isNullOrEmpty()) {
        field {
            name = "Year"
            value = "${response.jsonObject["Year"]?.jsonPrimitive?.content}"
            inline = true
        }
    }
    if (!response.jsonObject["Type"]?.jsonPrimitive?.content.isNullOrEmpty()) {
        field {
            name = "Type"
            value = "${response.jsonObject["Type"]?.jsonPrimitive?.content}"
            inline = true
        }
    }
    if (!response.jsonObject["Metascore"]?.jsonPrimitive?.content.isNullOrEmpty()) {
        field {
            name = "Ratings"
            value =
                "${response.jsonObject["Metascore"]?.jsonPrimitive?.content} (Metacritic)"
            inline = true
        }
    }
    if (!response.jsonObject["Plot"]?.jsonPrimitive?.content.isNullOrEmpty()) {
        field {
            name = "Plot"
            value = response.jsonObject["Plot"]?.jsonPrimitive?.content?.let { checkAndReduce(it) }
                ?: "No summary found"
        }
    }
    if (!response.jsonObject["Runtime"]?.jsonPrimitive?.content.isNullOrEmpty()) {
        field {
            name = "Runtime"
            value = response.jsonObject["Runtime"]?.jsonPrimitive?.content ?: "No runtime found"
            inline = true
        }
    }
    if (!response.jsonObject["Season"]?.jsonPrimitive?.content.isNullOrEmpty()) {
        field {
            name = "Seasons"
            value = response.jsonObject["Season"]?.jsonPrimitive?.content ?: "No seasons found"
            inline = true
        }
    }
    if (!response.jsonObject["Episode"]?.jsonPrimitive?.content.isNullOrEmpty()) {
        field {
            name = "Episodes"
            value = response.jsonObject["Episode"]?.jsonPrimitive?.content ?: "No episodes found"
            inline = true
        }
    }
    if (!response.jsonObject["Released"]?.jsonPrimitive?.content.isNullOrEmpty()) {
        field {
            name = "Released"
            value =
                response.jsonObject["Released"]?.jsonPrimitive?.content ?: "No release date found"
            inline = true
        }
    }
    if (!response.jsonObject["Rated"]?.jsonPrimitive?.content.isNullOrEmpty()) {
        field {
            name = "Age Rating"
            value = response.jsonObject["Rated"]?.jsonPrimitive?.content ?: "No age rating found"
            inline = true
        }
    }
    if (!response.jsonObject["BoxOffice"]?.jsonPrimitive?.content.isNullOrEmpty()) {
        field {
            name = "Box Office"
            value =
                response.jsonObject["BoxOffice"]?.jsonPrimitive?.content ?: "No box office found"
            inline = true
        }
    }
    if (!response.jsonObject["Director"]?.jsonPrimitive?.content.isNullOrEmpty()) {
        field {
            name = "Director"
            value = response.jsonObject["Director"]?.jsonPrimitive?.content ?: "No director found"
            inline = true
        }
    }
    if (!response.jsonObject["Awards"]?.jsonPrimitive?.content.isNullOrEmpty()) {
        field {
            name = "Awards"
            value = response.jsonObject["Awards"]?.jsonPrimitive?.content ?: "No awards found"
            inline = true
        }
    }
    footer {
        name = "Provided by OMDb"
    }

}

suspend fun OMDB(event: SlashCommandInteractionEvent, search: String, type: OMDbMediaType?, year: Int?) {
    val response = OMDbWrapper.makeRequest(
        omdbQuery {
            this.apiKey = OMDB_KEY
            this.search = search
            if (type != null)
                this.type = type
            if (year != null)
                this.year = year
        }
    )
    event.hook.editOriginal(
        MessageEditData.fromEmbeds(
            if (response.jsonObject["Response"]?.jsonPrimitive?.booleanOrNull == true) {
                // there's a jsonarray called "Search" which contains the results
                // we only want the first result and i want to use a with block to make it easier to access the values
                responseToEmbed(response)
            } else
                NeoSuperEmbed {
                    this@NeoSuperEmbed.type = SuperEmbed.ResultType.SIMPLE_ERROR
                    text = if (!response.jsonObject["Error"]?.jsonPrimitive?.content.isNullOrEmpty())
                        response.jsonObject["Error"]?.jsonPrimitive?.content ?: "No error found"
                    else
                        "Unknown error. Try again later."
                }
        )
    ).queue()
}
