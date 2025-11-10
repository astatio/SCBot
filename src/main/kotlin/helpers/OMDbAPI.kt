package helpers

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import mjson

enum class OMDbMediaType(val value: String) {
    MOVIE("movie"),
    SERIES("series"),
    EPISODE("episode");

    companion object {
        fun fromValue(value: String) = entries.firstOrNull { it.value == value }
    }
}


class OMDbQueryBuilder {

    private val baseUrl = "http://www.omdbapi.com/"
    private val parameters = mutableMapOf<String, String>()
    var apiKey: String? = null
        set(value) {
            field = value
            parameters["apikey"] = value!!
        }
    var search: String? = null
        set(value) {
            field = value
            parameters["t"] = value!!
        }
    var type: OMDbMediaType? = null
        set(value) {
            field = value
            parameters["type"] = value!!.value
        }
    var year: Int? = null
        set(value) {
            field = value
            parameters["y"] = value.toString()
        }

    /**
     * Builds the query URL based on the set parameters.
     *
     * @return The query URL.
     * @throws IllegalStateException If [apiKey] or [search] were not set
     */
    fun build(): String {
        if (search == null || apiKey == null)
            throw IllegalStateException("Apikey and search need to be set. Neither of them can be null.")
        val queryString =
            parameters.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        return "$baseUrl?$queryString"
    }
}

fun omdbQuery(block: OMDbQueryBuilder.() -> Unit): OMDbQueryBuilder {
    return OMDbQueryBuilder().apply(block)
}

object OMDbWrapper {

    val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                }
                )
            }
        }
    }


    /**
     * Makes a request to the OMDb API using the specified query builder.
     *
     * @param queryBuilder The query builder for the request.
     * @return The response from the OMDb API as a JSON string.
     */
    suspend fun makeRequest(queryBuilder: OMDbQueryBuilder): JsonElement {
        val response = client.get(queryBuilder.build())
        return omdbResponse(response.bodyAsText())
    }

}


private fun omdbResponse(response: String) = mjson.parseToJsonElement(response)

@Serializable
data class Error(
    val code: String,
    val message: String
)

@Serializable
data class Movie(
    val title: String,
    val rated: String?,
    val released: String?,
    val runtime: String?,
    val director: String?,
    val plot: String?,
    val awards: String?,
    val poster: String?,
    val ratings: List<Rating>?,
    val boxOffice: String?,
    val response: Boolean
) {
    init {
        require(response) { "Response is not 'true'. This means the response is an Error." }
    }
}

@Serializable
data class Rating(
    val source: String,
    val value: String
)

//Example response from API for a movie:
/*
{
    "Title": "Avatar",
    "Year": "2009",
    "Rated": "PG-13",
    "Released": "18 Dec 2009",
    "Runtime": "162 min",
    "Genre": "Action, Adventure, Fantasy",
    "Director": "James Cameron",
    "Writer": "James Cameron",
    "Actors": "Sam Worthington, Zoe Saldana, Sigourney Weaver",
    "Plot": "A paraplegic Marine dispatched to the moon Pandora on a unique mission becomes torn between following his orders and protecting the world he feels is his home.",
    "Language": "English, Spanish",
    "Country": "United States",
    "Awards": "Won 3 Oscars. 89 wins & 131 nominations total",
    "Poster": "https://m.media-amazon.com/images/M/MV5BNjA3NGExZDktNDlhZC00NjYyLTgwNmUtZWUzMDYwMTZjZWUyXkEyXkFqcGdeQXVyMTU1MDM3NDk0._V1_SX300.jpg",
    "Ratings": [
    {
        "Source": "Internet Movie Database",
        "Value": "7.8/10"
    },
    {
        "Source": "Rotten Tomatoes",
        "Value": "82%"
    },
    {
        "Source": "Metacritic",
        "Value": "83/100"
    }
    ],
    "Metascore": "83",
    "imdbRating": "7.8",
    "imdbVotes": "1,247,508",
    "imdbID": "tt0499549",
    "Type": "movie",
    "DVD": "22 Apr 2010",
    "BoxOffice": "$785,221,649",
    "Production": "N/A",
    "Website": "N/A",
    "Response": "True"
}*/
