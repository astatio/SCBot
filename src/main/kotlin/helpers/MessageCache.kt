package helpers

import Metrics
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.MessageUpdateEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

val messageCacheCaffeine: FIFOCache<String, MessageLite> = FIFOCache(80000)

/*
This commented code was the old cache. The maximum size eviction is not First In First Out - it was LRU (Least Recently Used)
It could be used with just expireAfterWrite(), but that would mean that the cache could grow indefinitely.

val helpers.getMessageCacheCaffeine: Cache<String, helpers.MessageLite> =
    Caffeine.newBuilder().maximumSize(30000) // Acceptable value for a single guild with over 20 very active members at any given moment. Untested if it ever reaches this limit.
        .expireAfterWrite(Duration.ofDays(15)).build()
*/

class MessageLite(
    val id: Long = 0L,
    val authorID: Long,
    val channelID: Long,
    val effectiveAvatarUrl: String,
    val contentDisplay: String,
    val attachmentsURLs: List<String>,
    val stickersUrls: List<String>
)
// store only the following message properties: id, channel, author, contentDisplay, guild, attachments.
// this is to reduce the memory usage of the cache

object MessageCache {

    //custom lighter message objects for the cache
    private fun convertToLite(message: Message) = MessageLite(
        id = message.idLong,
        authorID = message.author.idLong,
        channelID = message.channel.idLong,
        effectiveAvatarUrl = message.author.effectiveAvatarUrl,
        contentDisplay = message.contentDisplay,
        stickersUrls = message.stickers.map {
            it.iconUrl
        },
        attachmentsURLs = message.attachments.map {
            it.url
        })

    //This is the only that gets directly fom the listener. The others are called/handled by ModLogs first.
    fun onMessageReceived(event: MessageReceivedEvent) {
        Metrics.receivedMessageEvents.inc()
        if (!event.isFromGuild) return
        if (event.author.isBot) return
        messageCacheCaffeine.put(event.messageId, convertToLite(event.message))
        Metrics.receivedGuildMessageEvents.inc()
    }

    fun onMessageDeleteEvent(event: MessageDeleteEvent) {
        val cached = messageCacheCaffeine.getIfPresent(event.messageId)
        if (cached != null) {
            messageCacheCaffeine.invalidate(cached.id.toString())
        } else {
            Metrics.uncachedDeletedMessages.inc()
        }
    }

    fun onMessageBulkDeleteEvent(event: MessageBulkDeleteEvent) {
        event.messageIds.forEach {
            messageCacheCaffeine.invalidate(it)
        }
    }

    fun onMessageUpdateEvent(event: MessageUpdateEvent) {
        messageCacheCaffeine.getIfPresent(event.messageId)?.let {
            messageCacheCaffeine.put(event.message.id, convertToLite(event.message))
        }
    }
}

class FIFOCache<K, V>(private val maxSize: Int) {
    private val map = ConcurrentHashMap<K, V>(maxSize)
    private val queue = ConcurrentLinkedDeque<K>()

    @Synchronized
    fun put(key: K, value: V) {
        // If key already exists in the cache, we remove it first to reinsert later at the end of the queue
        map[key]?.let {
            map.remove(key)
            queue.remove(key)
        }

        // If cache is full, we need to evict the oldest item.
        if (queue.size >= maxSize) {
            val oldestKey = queue.poll()
            oldestKey?.let {
                map.remove(it)
            }
        }

        // Add the key to the cache and to the queue
        map[key] = value
        queue.addLast(key)
    }

    fun get(key: K): V? = map[key]

    fun getIfPresent(key: K): V? = map[key]

    // Get the X most recently added items.
    fun getMostRecent(x: Int): List<V?> {
        val mostRecentKeys = queue.descendingIterator().asSequence().take(x).toList()
        return mostRecentKeys.map { map[it] }
    }

    // Get the X most recently added items that match the predicate.
    fun getMostRecentFiltered(x: Int, predicate: (V) -> Boolean) =
        queue.descendingIterator().asSequence()
            .map { map[it] }  // Get values
            .filterNotNull()  // Filter out null values
            .filter(predicate)  // Filter values using predicate
            .take(x)  // Take the first x items
            .toList()

    fun invalidate(key: K) {
        map.remove(key)?.let {
            queue.remove(key)
        }
    }
}
