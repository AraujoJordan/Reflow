package com.araujojordan.reflow

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
import com.araujojordan.reflow.reflow.generated.resources.Res
import com.araujojordan.reflow.reflow.generated.resources.generic_error
import com.araujojordan.reflow.reflow.generated.resources.retry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.stringResource

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
    companion object {
        const val RETRY_DELAY: Long = 2000L // Seconds
        const val MAX_RETRIES: Int = 3
    }

    val state @Composable get() = stateFlow.collectAsState()
    fun refresh() = refreshes.tryEmit(value = Unit)
}

fun <T> ViewModel.reflow(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    fetchPolicy: FetchPolicy<T> = FetchPolicy.NetworkOnly(),
    initial: Resulting<T> = Resulting.loading(),
    shouldLoadingOnRefresh: Boolean = true,
    fetch: suspend () -> T,
): Reflow<T> {
    val reflowIn = reflowIn(
        scope = viewModelScope,
        dispatcher = dispatcher,
        fetchPolicy = fetchPolicy,
        initial = initial,
        shouldLoadingOnRefresh = shouldLoadingOnRefresh,
        fetchFlow = suspend { fetch() }.asFlow(),
    )
    return reflowIn
}

fun <T> ViewModel.reflow(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    fetchPolicy: FetchPolicy<T> = FetchPolicy.NetworkOnly(),
    initial: Resulting<T> = Resulting.loading(),
    shouldLoadingOnRefresh: Boolean = true,
    fetchFlow: Flow<T>,
): Reflow<T> = reflowIn(
    scope = viewModelScope,
    dispatcher = dispatcher,
    fetchPolicy = fetchPolicy,
    initial = initial,
    shouldLoadingOnRefresh = shouldLoadingOnRefresh,
    fetchFlow = fetchFlow,
)

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> reflowIn(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    fetchPolicy: FetchPolicy<T> = FetchPolicy.NetworkOnly(),
    initial: Resulting<T> = Resulting.loading<T>(),
    shouldLoadingOnRefresh: Boolean = true,
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
                flow = when (fetchPolicy) {
                    is FetchPolicy.NetworkOnly -> fetchFlow.distinctUntilChanged()
                    is FetchPolicy.CacheOnly -> fetchPolicy.onRetrieve.distinctUntilChanged()
                    is FetchPolicy.CacheAndNetwork -> merge(
                        fetchPolicy.onRetrieve.distinctUntilChanged(),
                        fetchFlow
                            .onEach { scope.launch(dispatcher) { fetchPolicy.onStore(it) } }
                            .distinctUntilChanged(),
                    )
                }.map {
                    Resulting.content(it)
                }
            )
        }.retryWhen { cause, attempt ->
            val canRetry = (attempt + 1) < fetchPolicy.maxRetries && fetchPolicy.shouldRetry(cause)
            if (canRetry) delay(timeMillis = fetchPolicy.retryDelay) else emit(value = Resulting.failure(cause))
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

