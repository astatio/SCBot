package helpers

import BOT_NAME
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object Hastebin {
    private const val BASE = "https://hastebin.de"
    // Create a Ktor HttpClient using the CIO engine
    private val client = HttpClient(CIO)

    // Note: This function must be called from a coroutine because it is suspend.
    suspend fun post(text: String, raw: Boolean, extension: String? = null): String {
        val data = text.toByteArray(Charsets.UTF_8)
        val length = data.size

        // Send a POST request to the hastebin API
        val response: HttpResponse = client.post("$BASE/documents") {
            setBody(data)
            headers {
                append("User-Agent", "$BOT_NAME Discord Bot")
                append("Content-Length", length.toString())
            }
        }

        // Read the response body as text (JSON string)
        val jsonText = response.bodyAsText()

        // Parse the JSON to extract the key.
        // The expected response format is something like: {"key": "someKey"}
        val jsonElement = Json.parseToJsonElement(jsonText)
        val key = jsonElement.jsonObject["key"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Invalid response: missing 'key'")

        // Build the final URL based on the 'raw' flag and optional extension.
        return if (raw) {
            "$BASE/raw/$key"
        } else {
            "$BASE/$key" + (extension?.let { ".$it" } ?: "")
        }
    }
}
