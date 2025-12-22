package io.github.araujojordan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.araujojordan.reflow.generated.resources.Res
import io.github.araujojordan.reflow.generated.resources.generic_error
import io.github.araujojordan.reflow.generated.resources.retry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.io.IOException
import org.jetbrains.compose.resources.stringResource

const val RETRY_DELAY: Long = 2000L // Milliseconds
const val MAX_RETRIES: Int = 3

@Composable
fun <T> ReflowBox(
    reflow: Reflow<T>,
    modifier: Modifier = Modifier,
    onLoading: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    },
    onError: @Composable (Throwable, () -> Unit) -> Unit = { _, retry ->
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = stringResource(Res.string.generic_error))
            Button(onClick = retry) {
                Text(text = stringResource(Res.string.retry))
            }
        }
    },
    content: @Composable (T) -> Unit,
) = Box(modifier = modifier) {
    val state by reflow.stateFlow.collectAsState()
    state.foldUi(
        onLoading = { onLoading() },
        onSuccess = { content(it) },
        onFailure = { onError(it, reflow::refresh) }
    )
}

class Reflow<T> internal constructor(
    private val refreshes: MutableSharedFlow<Unit>,
    val stateFlow: StateFlow<Resulting<T>>,
) {
    val state @Composable get() = stateFlow.collectAsState()
    fun refresh() = refreshes.tryEmit(value = Unit)
}

fun <T> ViewModel.reflow(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    initial: Resulting<T> = Resulting.loading(),
    shouldLoadingOnRefresh: Boolean = true,
    maxRetries: Int = MAX_RETRIES,
    retryDelay: Long = RETRY_DELAY,
    shouldRetry: (Throwable) -> Boolean = { it is IOException },
    fetch: suspend () -> T,
): Reflow<T> {
    val reflowIn = reflowIn(
        scope = viewModelScope,
        dispatcher = dispatcher,
        initial = initial,
        shouldLoadingOnRefresh = shouldLoadingOnRefresh,
        maxRetries = maxRetries,
        retryDelay = retryDelay,
        shouldRetry = shouldRetry,
        fetchFlow = suspend { fetch() }.asFlow(),
    )
    return reflowIn
}

fun <T> ViewModel.reflow(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    initial: Resulting<T> = Resulting.loading(),
    shouldLoadingOnRefresh: Boolean = true,
    maxRetries: Int = MAX_RETRIES,
    retryDelay: Long = RETRY_DELAY,
    shouldRetry: (Throwable) -> Boolean = { it is IOException },
    fetchFlow: Flow<T>,
): Reflow<T> = reflowIn(
    scope = viewModelScope,
    dispatcher = dispatcher,
    initial = initial,
    shouldLoadingOnRefresh = shouldLoadingOnRefresh,
    maxRetries = maxRetries,
    retryDelay = retryDelay,
    shouldRetry = shouldRetry,
    fetchFlow = fetchFlow,
)

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> reflowIn(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    initial: Resulting<T> = Resulting.loading<T>(),
    shouldLoadingOnRefresh: Boolean = true,
    maxRetries: Int = MAX_RETRIES,
    retryDelay: Long = RETRY_DELAY,
    shouldRetry: (Throwable) -> Boolean = { it is IOException },
    fetchFlow: Flow<T>,
): Reflow<T> {
    val refreshes = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).also { it.tryEmit(value = Unit) }

    var isFirstEmission = true
    val state = refreshes.flatMapLatest {
        flow {
            emitAll(
                fetchFlow
                    .distinctUntilChanged()
                    .map { Resulting.content(it) }
            )
        }.retryWhen { cause, attempt ->
            val canRetry = (attempt + 1) < maxRetries && shouldRetry(cause)
            if (canRetry) delay(timeMillis = retryDelay) else emit(value = Resulting.failure(cause))
            canRetry
        }.onStart {
            if (isFirstEmission) {
                if (initial.isLoading) emit(value = Resulting.loading())
                isFirstEmission = false
            } else if (shouldLoadingOnRefresh) {
                emit(value = Resulting.loading())
            }
        }.catch { emit(value = Resulting.failure(it)) }
    }.stateIn(scope, started = SharingStarted.Lazily, initialValue = initial)

    return Reflow(refreshes = refreshes, stateFlow = state)
}

