package helpers

import dev.minn.jda.ktx.events.getDefaultScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object ReusableScope {

    val vScope = virtualThreadScope("Reusable-VirtualThread-Scope")

    /**
     * Creates a [CoroutineScope] with virtual threads, uses [getDefaultScope] under the hood.
     *
     * @param name         The base name of the coroutines
     * @param job          The parent job used for coroutines which can be used to cancel all children, uses [SupervisorJob] by default
     * @param errorHandler The [CoroutineExceptionHandler] used for handling uncaught exceptions,
     * uses a logging handler which cancels the parent job on [Error] by default
     * @param context      Any additional context to add to the scope, uses [EmptyCoroutineContext] by default
     */
    fun virtualThreadScope(
        name: String,
        job: Job? = null,
        errorHandler: CoroutineExceptionHandler? = null,
        context: CoroutineContext = EmptyCoroutineContext
    ): CoroutineScope {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        return getDefaultScope(pool = executor, context = CoroutineName(name) + context, job = job, errorHandler = errorHandler)
    }

    /**
     * Creates a [CoroutineScope] with incremental thread naming, uses [getDefaultScope] under the hood.
     *
     * @param coroutineName The name of the coroutines
     * @param executor      The executor running the coroutines
     * @param job           The parent job used for coroutines which can be used to cancel all children, uses [SupervisorJob] by default
     * @param errorHandler  The [CoroutineExceptionHandler] used for handling uncaught exceptions,
     * uses a logging handler which cancels the parent job on [Error] by default
     * @param context       Any additional context to add to the scope, uses [EmptyCoroutineContext] by default
     */
    fun namedDefaultScope(
        coroutineName: String,
        executor: Executor,
        job: Job? = null,
        errorHandler: CoroutineExceptionHandler? = null,
        context: CoroutineContext = EmptyCoroutineContext
    ): CoroutineScope {
        return getDefaultScope(pool = executor, context = CoroutineName(coroutineName) + context, job = job, errorHandler = errorHandler)
    }

}