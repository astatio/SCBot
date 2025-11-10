package helpers

import kotlinx.coroutines.*
import logger
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

class JobProducer(
    private val f: suspend () -> Unit,
    val interval: Duration,
    val initialDelay: Long?) {
    suspend operator fun invoke() {
        f()
    }
}

class JobProducerScheduler(val service: JobProducer) : CoroutineScope {
    private val job = Job()
    private val singleThreadExecutor = Executors.newSingleThreadExecutor()
    // Initialize the dispatcher once
    private val dispatcher = singleThreadExecutor.asCoroutineDispatcher()


    private val errorHandler = CoroutineExceptionHandler { _, exception ->
        logger.error(exception) { "Uncaught exception in a scheduled job" }
    }

    override val coroutineContext: CoroutineContext
        get() = job + dispatcher + errorHandler


    fun stop() {
        job.cancel()
        singleThreadExecutor.shutdown()
    }

    fun start() = launch {
        service.initialDelay?.let {
            delay(it)
        }
        while (isActive) {
            try {
                service()
            } catch (e: Exception) {
                logger.error(e) { "Exception caught in scheduled job" }
            }
            delay(service.interval)
        }
        logger.info { "Scheduled job coroutine done" }
    }
}