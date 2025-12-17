package com.araujojordan.reflow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.haan.resultat.Resultat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.io.IOException

class Reflow<T> internal constructor(
    private val refreshes: MutableSharedFlow<Unit>,
    val stateFlow: StateFlow<Resultat<T>>,
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
    initial: Resultat<T> = Resultat.loading(),
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
    initial: Resultat<T> = Resultat.loading(),
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
    initial: Resultat<T> = Resultat.loading(),
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
                    Resultat.success(value = it)
                }
            )
        }.retryWhen { cause, attempt ->
            val canRetry = (attempt + 1) < fetchPolicy.maxRetries && fetchPolicy.shouldRetry(cause)
            if (canRetry) delay(timeMillis = fetchPolicy.retryDelay) else emit(value = Resultat.failure(exception = cause))
            canRetry
        }.onStart {
            if (isFirstEmission) {
                if (initial.isLoading) emit(value = Resultat.loading())
                isFirstEmission = false
            } else if (shouldLoadingOnRefresh) {
                emit(value = Resultat.loading())
            }
        }.catch { emit(value = Resultat.failure(exception = it)) }
    }.stateIn(scope, started = SharingStarted.Lazily, initialValue = initial)

    return Reflow(refreshes = refreshes, stateFlow = state)
}

sealed class FetchPolicy<T>(
    open val maxRetries: Int = Reflow.MAX_RETRIES,
    open val retryDelay: Long = Reflow.RETRY_DELAY,
    open val shouldRetry: (Throwable) -> Boolean = { it is IOException },
) {

    data class NetworkOnly<T>(
        override val maxRetries: Int = 3,
        override val retryDelay: Long = Reflow.RETRY_DELAY,
        override val shouldRetry: (Throwable) -> Boolean = { it is IOException },
    ) : FetchPolicy<T>(maxRetries, retryDelay, shouldRetry)

    data class CacheOnly<T>(
        val onRetrieve: Flow<T>,
        override val maxRetries: Int = 3,
        override val retryDelay: Long = Reflow.RETRY_DELAY,
        override val shouldRetry: (Throwable) -> Boolean = { it is IOException },
    ) : FetchPolicy<T>(maxRetries, retryDelay, shouldRetry) {
        constructor(
            onRetrieveCallback: suspend () -> T,
            maxRetries: Int = 3,
            retryDelay: Long = Reflow.RETRY_DELAY,
            shouldRetry: (Throwable) -> Boolean = { it is IOException },
        ) : this(
            onRetrieve = suspend { onRetrieveCallback() }.asFlow(),
            maxRetries = maxRetries,
            retryDelay = retryDelay,
            shouldRetry = shouldRetry,
        )
    }

    data class CacheAndNetwork<T>(
        val onStore: suspend (T) -> Unit,
        val onRetrieve: Flow<T>,
        override val maxRetries: Int = 3,
        override val retryDelay: Long = Reflow.RETRY_DELAY,
        override val shouldRetry: (Throwable) -> Boolean = { it is IOException },
    ) : FetchPolicy<T>(maxRetries, retryDelay, shouldRetry) {
        constructor(
            onStore: suspend (T) -> Unit,
            onRetrieve: suspend () -> T,
            maxRetries: Int = 3,
            retryDelay: Long = Reflow.RETRY_DELAY,
            shouldRetry: (Throwable) -> Boolean = { it is IOException },
        ) : this(
            onStore = onStore,
            onRetrieve = suspend { onRetrieve() }.asFlow(),
            maxRetries = maxRetries,
            retryDelay = retryDelay,
            shouldRetry = shouldRetry,
        )
    }
}
