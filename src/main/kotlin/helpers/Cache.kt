package helpers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Cache<T> {
    @Volatile
    private var cache: T? = null
    @Volatile
    private var lastUpdateTime = System.currentTimeMillis()
    private var updatingJob: Deferred<T>? = null
    private val cooldown: Long = 30000 // 30 seconds in milliseconds
    private val mutex = Mutex()

    suspend fun request(updateFunction: suspend () -> T): T {
        val currentTime = System.currentTimeMillis()

        mutex.withLock {
            if (currentTime - lastUpdateTime > cooldown || cache == null) {
                updateCache(updateFunction)
            }
        }
        if (cache == null && updatingJob != null) {
            cache = updatingJob!!.await()
        } else if (updatingJob?.isCompleted == true) {
            cache = updatingJob?.await()
        }
        return cache ?: throw IllegalStateException("Cache is empty")
    }

    suspend fun forceUpdate(updateFunction: suspend () -> T) {
        mutex.withLock {
            updateCache(updateFunction)
        }
    }

    private suspend fun updateCache(updateFunction: suspend () -> T) {
        if (updatingJob == null || updatingJob!!.isCompleted) {
            updatingJob = CoroutineScope(Dispatchers.IO).async {
                updateFunction()
            }
            lastUpdateTime = System.currentTimeMillis()
        }
    }
}
