package io.github.araujojordan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import io.github.araujojordan.cache.ReflowLru
import io.github.araujojordan.cache.UseMemoryCacheWithoutKey
import io.github.araujojordan.model.Resulting
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.io.IOException

@OptIn(DelicateCoroutinesApi::class)
object Rexecute {
    val queue = ArrayDeque<RexecuteJob>()
    init {
        GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                val job = queue.removeFirstOrNull() ?: run {
                    delay(RETRY_DELAY)
                    continue
                }
                try {
                    val result = job.block()
                    ReflowLru.put(result, job.key)
                } catch (e: Throwable) {
                    if (job.shouldRetry(e)) {
                        delay(RETRY_DELAY)
                        queue.add(job.copy(retries = job.retries + 1))
                    } else {
                        job.onFailure(e)
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
    Rexecute.queue.indexOfFirst { it.key == key }.let { index -> if (index >= 0) Rexecute.queue.removeAt(index) }
    Rexecute.queue.add(job)
    emitAll(ReflowLru.getAsFlow<T>(key).mapNotNull { it?.let { Resulting.content(it) } }) // Listen for the result
}.flowOn(dispatcher)

data class RexecuteJob(
    val key: String,
    val retries: Long,
    val shouldRetry: (Throwable) -> Boolean,
    val onFailure: (Throwable) -> Unit,
    val block:  suspend () -> Any,
)
