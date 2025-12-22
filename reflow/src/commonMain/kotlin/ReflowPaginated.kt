package io.github.araujojordan.reflow

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.*
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Snackbar
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.araujojordan.reflow.generated.resources.Res
import io.github.araujojordan.reflow.generated.resources.generic_error
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import org.jetbrains.compose.resources.stringResource

data class PaginatedState<T>(
    val items: List<T>,
    val isLoadingMore: Boolean = false,
    val hasMorePages: Boolean = true,
)

sealed class Page(open val pageSize: Int = 10) {
    data class Number(val value: Int = 0, override val pageSize: Int = 10) : Page(pageSize)
    data class Cursor(val value: String, override val pageSize: Int = 10) : Page(pageSize)
}

@Composable
fun <T> LazyColumnPaginated(
    paginatedFlow: ReflowPaginated<T>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    key: ((item: T) -> Any)? = null,
    onLoading: LazyListScope.() -> Unit = {
        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    },
    onError: LazyListScope.(Throwable) -> Unit = {
        item {
            Snackbar(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = Color.Red,
                contentColor = Color.White,
            ) {
                Text(
                    text = stringResource(Res.string.generic_error),
                    color = Color.White
                )
            }
        }
    },
    content: @Composable LazyItemScope.(item: T) -> Unit
) {
    val list by paginatedFlow.stateFlow.collectAsState()
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
    ) {
        if (list.isFailure) {
            onError(list.exceptionOrNull()!!)
        }
        items(
            items = list.getOrNull()?.items.orEmpty(),
            key = key,
            itemContent = content,
        )
        if (list.isLoading) {
            item {
                if(!paginatedFlow.hasMorePages) {
                    paginatedFlow.loadMore()
                }
            }
            onLoading()
        }
    }
}

class ReflowPaginated<T> internal constructor(
    private val refreshTrigger: MutableSharedFlow<Unit>,
    private val loadMoreTrigger: MutableSharedFlow<Unit>,
    val stateFlow: StateFlow<Resulting<PaginatedState<T>>>,
) {

    val hasMorePages: Boolean
        get() = stateFlow.value.getOrNull()?.hasMorePages ?: true

    val isLoadingMore: Boolean
        get() = stateFlow.value.getOrNull()?.isLoadingMore ?: false

    fun refresh() = refreshTrigger.tryEmit(Unit)
    fun loadMore() = loadMoreTrigger.tryEmit(Unit)
}

fun <T> ViewModel.reflowPaginated(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialPage: Page.Number = Page.Number(),
    initial: Resulting<PaginatedState<T>> = Resulting.loading(),
    shouldLoadingOnRefresh: Boolean = true,
    maxRetries: Int = MAX_RETRIES,
    retryDelay: Long = RETRY_DELAY,
    shouldRetry: (Throwable) -> Boolean = { it is IOException },
    fetch: suspend (pageKey: Page.Number) -> List<T>,
): ReflowPaginated<T> = reflowPaginatedIn(
    scope = viewModelScope,
    dispatcher = dispatcher,
    initialPage = initialPage,
    initial = initial,
    shouldLoadingOnRefresh = shouldLoadingOnRefresh,
    maxRetries = maxRetries,
    retryDelay = retryDelay,
    shouldRetry = shouldRetry,
    fetch = { fetch.invoke(it as Page.Number) },
)

fun <T> ViewModel.reflowPaginated(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialPage: Page.Cursor,
    initial: Resulting<PaginatedState<T>> = Resulting.loading(),
    shouldLoadingOnRefresh: Boolean = true,
    maxRetries: Int = MAX_RETRIES,
    retryDelay: Long = RETRY_DELAY,
    shouldRetry: (Throwable) -> Boolean = { it is IOException },
    fetch: suspend (pageKey: Page) -> List<T>,
): ReflowPaginated<T> = reflowPaginatedIn(
    scope = viewModelScope,
    dispatcher = dispatcher,
    initialPage = initialPage,
    initial = initial,
    shouldLoadingOnRefresh = shouldLoadingOnRefresh,
    maxRetries = maxRetries,
    retryDelay = retryDelay,
    shouldRetry = shouldRetry,
    fetch = { fetch.invoke(it as Page.Cursor) },
)

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> reflowPaginatedIn(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialPage: Page = Page.Number(0),
    initial: Resulting<PaginatedState<T>> = Resulting.loading(),
    shouldLoadingOnRefresh: Boolean = true,
    maxRetries: Int = MAX_RETRIES,
    retryDelay: Long = RETRY_DELAY,
    shouldRetry: (Throwable) -> Boolean = { it is IOException },
    fetch: suspend (pageKey: Page) -> List<T>,
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
            var currentPageKey: Page? = initialPage
            val accumulatedItems = mutableListOf<T>()
            var hasMorePages = true

            suspend fun fetchPage(): PaginatedState<T> {
                val pageKey = currentPageKey
                    ?: return PaginatedState(accumulatedItems.toList(), false, false)

                val pageItems = fetch(pageKey)
                accumulatedItems.addAll(pageItems)
                hasMorePages = pageItems.size >= pageKey.pageSize

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
                val canRetry = (attempt + 1) < maxRetries && shouldRetry(cause)
                if (canRetry) delay(retryDelay)
                canRetry
            }.map { Resulting.content(it) }
                .onStart {
                    if (isFirstEmission) {
                        if (initial.isLoading) emit(Resulting.loading())
                        isFirstEmission = false
                    } else if (shouldLoadingOnRefresh) {
                        emit(Resulting.loading())
                    }
                }
                .catch { emit(Resulting.failure(it)) }
        }.stateIn(
            scope = scope,
            started = SharingStarted.Lazily,
            initialValue = initial,
        ),
    )
}

private fun nextPage(current: Page): Page {
    return when (current) {
        is Page.Number -> Page.Number(current.value + 1)
        is Page.Cursor -> current
    }
}
