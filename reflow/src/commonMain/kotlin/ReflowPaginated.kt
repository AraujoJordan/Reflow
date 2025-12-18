package com.araujojordan.reflow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.haan.resultat.Resultat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException

data class PaginatedState<T>(
    val items: List<T>,
    val isLoadingMore: Boolean = false,
    val hasMorePages: Boolean = true,
)

sealed class PaginationKey {
    data class Page(val value: Int) : PaginationKey()
    data class Cursor(val value: String) : PaginationKey()
}

sealed class PaginatedFetchPolicy<T>(
    open val maxRetries: Int = Reflow.MAX_RETRIES,
    open val retryDelay: Long = Reflow.RETRY_DELAY,
    open val shouldRetry: (Throwable) -> Boolean = { it is IOException },
) {
    data class NetworkOnly<T>(
        override val maxRetries: Int = Reflow.MAX_RETRIES,
        override val retryDelay: Long = Reflow.RETRY_DELAY,
        override val shouldRetry: (Throwable) -> Boolean = { it is IOException },
    ) : PaginatedFetchPolicy<T>(maxRetries, retryDelay, shouldRetry)

    data class CacheAndNetwork<T>(
        val onStore: suspend (PaginationKey, List<T>) -> Unit,
        val onRetrieve: suspend (PaginationKey) -> List<T>?,
        override val maxRetries: Int = Reflow.MAX_RETRIES,
        override val retryDelay: Long = Reflow.RETRY_DELAY,
        override val shouldRetry: (Throwable) -> Boolean = { it is IOException },
    ) : PaginatedFetchPolicy<T>(maxRetries, retryDelay, shouldRetry)
}

class ReflowPaginated<T> internal constructor(
    private val refreshTrigger: MutableSharedFlow<Unit>,
    private val loadMoreTrigger: MutableSharedFlow<Unit>,
    val stateFlow: StateFlow<Resultat<PaginatedState<T>>>,
) {
    val state @Composable get() = stateFlow.collectAsState()

    val hasMorePages: Boolean
        get() = (stateFlow.value as? Resultat.Success)?.value?.hasMorePages ?: true

    val isLoadingMore: Boolean
        get() = (stateFlow.value as? Resultat.Success)?.value?.isLoadingMore ?: false

    fun refresh() = refreshTrigger.tryEmit(Unit)

    fun loadMore() = loadMoreTrigger.tryEmit(Unit)
}

fun <T> ViewModel.reflowPaginated(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    pageSize: Int = 10,
    initialPage: PaginationKey = PaginationKey.Page(1),
    initial: Resultat<PaginatedState<T>> = Resultat.loading(),
    shouldLoadingOnRefresh: Boolean = true,
    fetchPolicy: PaginatedFetchPolicy<T> = PaginatedFetchPolicy.NetworkOnly(),
    fetch: suspend (pageKey: PaginationKey, pageSize: Int) -> List<T>,
): ReflowPaginated<T> = reflowPaginatedIn(
    scope = viewModelScope,
    dispatcher = dispatcher,
    pageSize = pageSize,
    initialPage = initialPage,
    initial = initial,
    shouldLoadingOnRefresh = shouldLoadingOnRefresh,
    fetchPolicy = fetchPolicy,
    fetch = fetch,
)

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> reflowPaginatedIn(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    pageSize: Int = 10,
    initialPage: PaginationKey = PaginationKey.Page(1),
    initial: Resultat<PaginatedState<T>> = Resultat.loading(),
    shouldLoadingOnRefresh: Boolean = true,
    fetchPolicy: PaginatedFetchPolicy<T> = PaginatedFetchPolicy.NetworkOnly(),
    fetch: suspend (pageKey: PaginationKey, pageSize: Int) -> List<T>,
): ReflowPaginated<T> {
    val refreshTrigger = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).also { it.tryEmit(Unit) }

    val loadMoreTrigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    var isFirstEmission = true

    return ReflowPaginated(
        refreshTrigger = refreshTrigger,
        loadMoreTrigger = loadMoreTrigger,
        stateFlow = refreshTrigger.flatMapLatest {
            val mutex = Mutex()
            var currentPageKey: PaginationKey? = initialPage
            val accumulatedItems = mutableListOf<T>()
            var hasMorePages = true

            suspend fun fetchPage(): PaginatedState<T> {
                val pageKey = currentPageKey
                    ?: return PaginatedState(accumulatedItems.toList(), false, false)

                val cachedItems = when (fetchPolicy) {
                    is PaginatedFetchPolicy.CacheAndNetwork -> fetchPolicy.onRetrieve(pageKey)
                    is PaginatedFetchPolicy.NetworkOnly -> null
                }

                if (cachedItems != null) {
                    accumulatedItems.addAll(cachedItems)
                }

                val fetchedItems = fetch(pageKey, pageSize)

                if (fetchPolicy is PaginatedFetchPolicy.CacheAndNetwork) {
                    scope.launch(dispatcher) { fetchPolicy.onStore(pageKey, fetchedItems) }
                }

                if (cachedItems == null) {
                    accumulatedItems.addAll(fetchedItems)
                } else if (cachedItems != fetchedItems) {
                    val startIndex = accumulatedItems.size - cachedItems.size
                    if (startIndex >= 0) {
                        for (i in fetchedItems.indices) {
                            if (startIndex + i < accumulatedItems.size) {
                                accumulatedItems[startIndex + i] = fetchedItems[i]
                            } else {
                                accumulatedItems.add(fetchedItems[i])
                            }
                        }
                    }
                }

                hasMorePages = fetchedItems.size >= pageSize
                currentPageKey = if (hasMorePages) nextPage(pageKey) else null

                return PaginatedState(accumulatedItems.toList(), false, hasMorePages)
            }

            flow {
                emit(fetchPage())

                loadMoreTrigger.collect {
                    if (hasMorePages) {
                        mutex.withLock {
                            emit(fetchPage())
                        }
                    }
                }
            }.retryWhen { cause, attempt ->
                val canRetry = (attempt + 1) < fetchPolicy.maxRetries && fetchPolicy.shouldRetry(cause)
                if (canRetry) delay(fetchPolicy.retryDelay)
                canRetry
            }.map { Resultat.success(it) }
                .onStart {
                    if (isFirstEmission) {
                        if (initial.isLoading) emit(Resultat.loading())
                        isFirstEmission = false
                    } else if (shouldLoadingOnRefresh) {
                        emit(Resultat.loading())
                    }
                }
                .catch { emit(Resultat.failure(it)) }
        }.stateIn(
            scope = scope,
            started = SharingStarted.Lazily,
            initialValue = initial,
        ),
    )
}

private fun nextPage(current: PaginationKey): PaginationKey {
    return when (current) {
        is PaginationKey.Page -> PaginationKey.Page(current.value + 1)
        is PaginationKey.Cursor -> current
    }
}
