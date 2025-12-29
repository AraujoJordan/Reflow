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

@OptIn(DelicateCoroutinesApi::class)
internal object Rexecute {
    private val channel = Channel<RexecuteJob>(Channel.UNLIMITED)
    private val pendingJobsMutex = Mutex()
    private val pendingJobs = mutableMapOf<String, RexecuteJob>()
    suspend fun clear() = pendingJobsMutex.withLock { pendingJobs.clear() }
    suspend fun activeJobCount(): Int = pendingJobsMutex.withLock { pendingJobs.size }
    suspend fun peekKey(): String? = pendingJobsMutex.withLock { pendingJobs.keys.firstOrNull() }

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

internal data class RexecuteJob(
    val key: String,
    val retries: Long,
    val shouldRetry: (Throwable) -> Boolean,
    val onFailure: (Throwable) -> Unit,
    val block:  suspend () -> Any,
)
