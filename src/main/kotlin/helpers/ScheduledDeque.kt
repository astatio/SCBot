package helpers

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.coroutines.CoroutineContext

// Extend Deque to add an element and delete it after 15 seconds using a scheduled executor.
class ScheduledDeque<T> : CoroutineScope {
    val parentContext = Dispatchers.Default
    val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = parentContext + job

    val deque = ConcurrentLinkedDeque<T>()


    /**
     * Add an element to the deque of type T and delete it after a delay.
     *
     * @param t
     * @param delay in seconds
     */
    fun addScheduled(t: T, delay: Long) {
        deque.add(t)
        launch {
            delay(delay * 1000)  // delay is in seconds, but delay() takes milliseconds
            deque.removeFirstOccurrence(t)
        }
    }
}