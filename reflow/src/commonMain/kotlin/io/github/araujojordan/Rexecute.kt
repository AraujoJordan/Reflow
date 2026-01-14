package io.github.araujojordan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import io.github.araujojordan.cache.ReflowLru
import io.github.araujojordan.cache.UseMemoryCacheWithoutKey
import io.github.araujojordan.model.Resulting
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException

/**
 * An internal singleton object that manages the execution and retrying of jobs.
 *
 * It maintains a queue of pending jobs and handles their execution, deduplication, and retrying
 * upon failure. It uses a [Mutex] to ensure thread safety when accessing the job map.
 */
@OptIn(DelicateCoroutinesApi::class)
internal object Rexecute {
    private val channel = Channel<RexecuteJob>(Channel.UNLIMITED)
    private val pendingJobsMutex = Mutex()
    private val pendingJobs = mutableMapOf<String, RexecuteJob>()
    
    /**
     * Clears all pending jobs.
     */
    suspend fun clear() = pendingJobsMutex.withLock { pendingJobs.clear() }

    /**
     * Returns the number of currently active or pending jobs.
     */
    suspend fun activeJobCount(): Int = pendingJobsMutex.withLock { pendingJobs.size }
    
    /**
     * Returns the key of the first pending job, if any.
     */
    suspend fun peekKey(): String? = pendingJobsMutex.withLock { pendingJobs.keys.firstOrNull() }

    /**
     * Submits a new job to be executed.
     * If a job with the same key already exists, it is overwritten in the map (deduplication logic might handle this before submission or during processing).
     */
    suspend fun submitJob(job: RexecuteJob) {
        pendingJobsMutex.withLock { pendingJobs[job.key] = job }
        channel.send(job)
    }

    init {
        GlobalScope.launch(Dispatchers.IO) {
            for (job in channel) {
                // Check if the job is still the valid one for this key
                val isValid = pendingJobsMutex.withLock { pendingJobs[job.key] === job }
                if (!isValid) continue

                try {
                    val result = job.block()
                    ReflowLru.put(result, job.key)
                    pendingJobsMutex.withLock {
                        if (pendingJobs[job.key] === job) {
                            pendingJobs.remove(job.key)
                        }
                    }
                } catch (e: Throwable) {
                    if (job.shouldRetry(e)) {
                        delay(RETRY_DELAY)
                        val retryJob = job.copy(retries = job.retries + 1)
                        val submitted = pendingJobsMutex.withLock {
                            if (pendingJobs[job.key] === job) {
                                pendingJobs[job.key] = retryJob
                                true
                            } else {
                                false
                            }
                        }

                        if (submitted) {
                            channel.send(retryJob)
                        }
                    } else {
                        job.onFailure(e)
                        pendingJobsMutex.withLock {
                            if (pendingJobs[job.key] === job) {
                                pendingJobs.remove(job.key)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Executes a task with automatic retries and caching, keyed by the return type [T].
 *
 * This extension function uses the qualified name of class [T] as the unique key for the operation.
 * It is useful for operations where there is only one instance of the task per type (e.g., a singleton setting).
 *
 * @param T The return type of the block, used to generate the unique key.
 * @param shouldRetry A predicate to determine if the task should be retried on failure. Defaults to retrying on [IOException].
 * @param onFailure A callback to be invoked when the task fails and will not be retried.
 * @param dispatcher The coroutine dispatcher to use. Defaults to [Dispatchers.IO].
 * @param block The suspend function to execute.
 * @return A [Flow] emitting the [Resulting] state of the operation.
 */
@UseMemoryCacheWithoutKey
inline fun <reified T : Any> ViewModel.rexecute(
    noinline shouldRetry: (Throwable) -> Boolean = { it is IOException },
    noinline onFailure: (Throwable) -> Unit = { Logger.e(tag = "ReActFailure", throwable = it, messageString = "Fail on reAct") },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    noinline block: suspend () -> T,
) = viewModelScope.rexecuteIn(
    key = T::class.qualifiedName.orEmpty(),
    shouldRetry = shouldRetry,
    onFailure = onFailure,
    dispatcher = dispatcher,
    block = block,
)

/**
 * Executes a task with automatic retries and caching, identified by a unique [key].
 *
 * This function handles the execution of a task that should be reliable (e.g., POST requests, mutations).
 * If the task fails with a retryable exception, it is queued for retry in the background.
 * The result is cached in an in-memory LRU cache ([ReflowLru]) and emitted via the returned Flow.
 *
 * @param T The return type of the block.
 * @param key A unique key to identify this operation. Used for deduplication and caching.
 * @param shouldRetry A predicate to determine if the task should be retried on failure. Defaults to retrying on [IOException].
 * @param onFailure A callback to be invoked when the task fails and will not be retried.
 * @param dispatcher The coroutine dispatcher to use. Defaults to [Dispatchers.IO].
 * @param block The suspend function to execute.
 * @return A [Flow] emitting the [Resulting] state of the operation.
 */
fun <T : Any> ViewModel.rexecute(
    key: String,
    shouldRetry: (Throwable) -> Boolean = { it is IOException },
    onFailure: (Throwable) -> Unit = { Logger.e(tag = "ReActFailure", throwable = it, messageString = "Fail on reAct") },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend () -> T,
) = viewModelScope.rexecuteIn(
    key = key,
    shouldRetry = shouldRetry,
    onFailure = onFailure,
    dispatcher = dispatcher,
    block = block,
)

/**
 * Internal helper to execute a task within a specific [CoroutineScope].
 *
 * @param T The return type of the block.
 * @param key The unique key for the operation.
 * @param shouldRetry Predicate for retrying.
 * @param onFailure Callback for final failure.
 * @param dispatcher Dispatcher for the operation.
 * @param block The task to execute.
 * @return A [Flow] of [Resulting].
 */
fun <T : Any> CoroutineScope.rexecuteIn(
    key: String,
    shouldRetry: (Throwable) -> Boolean = { it is IOException },
    onFailure: (Throwable) -> Unit = { Logger.e(tag = "ReActFailure", throwable = it, messageString = "Fail on reAct") },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend () -> T,
) = flow<Resulting<T>> {
    emit(Resulting.loading())

    // Attempt to run it now
    try {
        emit(Resulting.content(block()))
        return@flow
    } catch (e: Throwable) {
        if (!shouldRetry(e)) {
            emit(Resulting.failure(e))
            return@flow
        }
    }

    // Schedule for retry
    val job = RexecuteJob(
        key = key,
        retries = 0L,
        shouldRetry = shouldRetry,
        onFailure = onFailure,
        block = block as suspend () -> Any,
    )
    Rexecute.submitJob(job)
    emitAll(ReflowLru.getAsFlow<T>(key).mapNotNull { it?.let { Resulting.content(it) } }) // Listen for the result
}.flowOn(dispatcher)

/**
 * Represents a job managed by [Rexecute].
 *
 * @property key The unique identifier for the job.
 * @property retries The number of times this job has been retried.
 * @property shouldRetry Predicate to check if the job should be retried on a given error.
 * @property onFailure Callback to execute if the job fails and cannot be retried.
 * @property block The actual task to execute.
 */
internal data class RexecuteJob(
    val key: String,
    val retries: Long,
    val shouldRetry: (Throwable) -> Boolean,
    val onFailure: (Throwable) -> Unit,
    val block:  suspend () -> Any,
)
