package helpers

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap


// Aligns behaviour with helpers.Cache by expiring entries on access and providing lifecycle controls.
class CacheMap<K, V>
// expirationTime – TTL in milliseconds since last access.
// checkInterval – interval for the background eviction job. Defaults to 1 minute.
    (
    private val expirationTime: Long,
    private val checkInterval: Long = 60_000,
    private val scope: kotlinx.coroutines.CoroutineScope = ReusableScope.vScope
) {
    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()
    private val mutex = Mutex()
    private val cleanerJob = startExpirationChecker()

    private fun startExpirationChecker() = scope.launch {
        while (isActive) {
            delay(checkInterval)
            removeExpiredEntries()
        }
    }

    suspend fun put(key: K, value: V) {
        val now = System.currentTimeMillis()
        mutex.withLock {
            cache[key] = CacheEntry(value, createdAt = now, lastAccessTime = now)
            pruneExpiredLocked(now)
        }
    }

    suspend fun get(key: K): V? {
        val now = System.currentTimeMillis()
        return mutex.withLock {
            cache[key]?.takeUnless { it.isExpired(now, expirationTime) }?.also {
                it.lastAccessTime = now
            }?.value
        }
    }

    suspend fun remove(key: K) {
        mutex.withLock {
            cache.remove(key)
        }
    }

    suspend fun clear() = mutex.withLock { cache.clear() }

    suspend fun snapshot(): Map<K, V> = mutex.withLock { cache.mapValues { it.value.value } }

    fun shutdown() {
        cleanerJob.cancel()
    }

    private suspend fun removeExpiredEntries(referenceTime: Long = System.currentTimeMillis()) {
        mutex.withLock { pruneExpiredLocked(referenceTime) }
    }

    private fun pruneExpiredLocked(referenceTime: Long) {
        cache.entries.removeIf { it.value.isExpired(referenceTime, expirationTime) }
    }

    private data class CacheEntry<V>(
        val value: V,
        val createdAt: Long,
        var lastAccessTime: Long
    ) {
        fun isExpired(referenceTime: Long, ttl: Long): Boolean {
            val lastTouch = maxOf(createdAt, lastAccessTime)
            return referenceTime - lastTouch > ttl
        }
    }
}
