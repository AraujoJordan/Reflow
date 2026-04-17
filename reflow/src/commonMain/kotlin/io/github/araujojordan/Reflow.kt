package io.github.araujojordan

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import io.github.araujojordan.cache.CacheSource
import io.github.araujojordan.model.Resulting
import io.github.araujojordan.reflow.generated.resources.Res
import io.github.araujojordan.reflow.generated.resources.generic_error
import io.github.araujojordan.reflow.generated.resources.retry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.io.IOException
import org.jetbrains.compose.resources.stringResource

/**
 * A wrapper class for managing data fetching states (loading, success, error) with support for refreshing.
 *
 * This class encapsulates a [StateFlow] of [Resulting] and provides a mechanism to trigger refreshes.
 * It is typically created using the [reflow] extension functions on [ViewModel].
 *
 * @param T The type of data being fetched.
 * @property refreshes A [MutableSharedFlow] used to trigger refresh events.
 * @property stateFlow The underlying [StateFlow] holding the current state of the data fetch.
 */
class Reflow<T> internal constructor(
    private val refreshes: MutableSharedFlow<Unit>,
    val stateFlow: StateFlow<Resulting<T>>,
) {
    /**
     * A Composable property that collects the [stateFlow] as a State.
     * Useful for observing the state within a Composable function.
     */
    val state @Composable get() = stateFlow.collectAsState()

    /**
     * Triggers a refresh of the data.
     * This will cause the data to be re-fetched (and potentially show a loading state depending on configuration).
     */
    fun refresh() = refreshes.tryEmit(value = Unit)
}

/**
 * A Composable that displays content based on the [Reflow] state, handling transitions, loading, and error states automatically.
 *
 * @param T The type of data being displayed.
 * @param reflow The [Reflow] instance managing the data state.
 * @param transitionSpec The animation spec for transitions between states (loading, success, error). Defaults to fade in/out.
 * @param contentAlignment The alignment of the content within the container.
 * @param label A label for the animation.
 * @param contentKey A key to identify the content for animation purposes.
 * @param modifier The modifier to be applied to the container.
 * @param onLoading A Composable block to display when the state is [Resulting.Loading]. Defaults to a [CircularProgressIndicator].
 * @param onError A Composable block to display when the state is [Resulting.Failure]. Defaults to a generic error message with a retry button.
 * @param content The main content to display when the state is [Resulting.Success]. Receives the fetched data [T] as a parameter.
 */
@Composable
fun <T> ReflowContent(
    reflow: Reflow<T>,
    transitionSpec: AnimatedContentTransitionScope<Resulting<T>>.() -> ContentTransform = {
        fadeIn().togetherWith(fadeOut())
    },
    contentAlignment: Alignment = Alignment.Center,
    label: String = "ReflowContent",
    contentKey: (targetState: Resulting<T>) -> Any? = { it },
    modifier: Modifier = Modifier,
    onLoading: @Composable AnimatedContentScope.() -> Unit = {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    },
    onError: @Composable AnimatedContentScope.(Throwable, () -> Unit) -> Unit = { _, retry ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = stringResource(Res.string.generic_error))
            Button(onClick = retry) {
                Text(text = stringResource(Res.string.retry))
            }
        }
    },
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val state by reflow.stateFlow.collectAsState()
    AnimatedContent(
        modifier = modifier,
        transitionSpec = transitionSpec,
        contentAlignment = contentAlignment,
        label = label,
        contentKey = contentKey,
        targetState = state,
    ) {
        it.foldUi(
            onLoading = { onLoading() },
            onSuccess = { value -> content(value) },
            onFailure = { throwable -> onError(throwable, reflow::refresh) }
        )
    }
}

/**
 * Creates a [Reflow] that executes a suspend function [fetch] to retrieve data.
 *
 * @param T The type of data to fetch.
 * @param cacheSource The caching strategy to use. Defaults to [CacheSource.None].
 * @param dispatcher The coroutine dispatcher to use for the fetch operation. Defaults to [ioDispatcher].
 * @param initial The initial state of the Reflow. Defaults to [Resulting.loading].
 * @param shouldLoadingOnRefresh Whether to emit a loading state when refreshing. Defaults to true.
 * @param maxRetries The maximum number of retry attempts for failed fetches. Defaults to [MAX_RETRIES].
 * @param retryDelay The delay between retry attempts in milliseconds. Defaults to [RETRY_DELAY].
 * @param shouldRetry A predicate to determine if a retry should be attempted based on the exception. Defaults to retrying on [IOException].
 * @param fetch The suspend function that performs the data fetching.
 * @return A [Reflow] instance managing the data flow.
 */
fun <T> ViewModel.reflow(
    cacheSource: CacheSource<T> = CacheSource.None(),
    dispatcher: CoroutineDispatcher = ioDispatcher,
    initial: Resulting<T> = Resulting.loading(),
    shouldLoadingOnRefresh: Boolean = true,
    maxRetries: Int = MAX_RETRIES,
    retryDelay: Long = RETRY_DELAY,
    shouldRetry: (Throwable) -> Boolean = { it is IOException },
    fetch: suspend () -> T,
): Reflow<T> = reflowIn(
    scope = viewModelScope,
    dispatcher = dispatcher,
    initial = initial,
    shouldLoadingOnRefresh = shouldLoadingOnRefresh,
    cacheSource = cacheSource,
    maxRetries = maxRetries,
    retryDelay = retryDelay,
    shouldRetry = shouldRetry,
    fetchFlow = flow { emit(fetch()) }
)

/**
 * Creates a [Reflow] from an existing [Flow].
 *
 * @param T The type of data to fetch.
 * @param cacheSource The caching strategy to use. Defaults to [CacheSource.None].
 * @param dispatcher The coroutine dispatcher to use for the flow collection. Defaults to [ioDispatcher].
 * @param initial The initial state of the Reflow. Defaults to [Resulting.loading].
 * @param shouldLoadingOnRefresh Whether to emit a loading state when refreshing. Defaults to true.
 * @param maxRetries The maximum number of retry attempts for failed flow collections. Defaults to [MAX_RETRIES].
 * @param retryDelay The delay between retry attempts in milliseconds. Defaults to [RETRY_DELAY].
 * @param shouldRetry A predicate to determine if a retry should be attempted based on the exception. Defaults to retrying on [IOException].
 * @param fetchFlow The source [Flow] providing the data.
 * @return A [Reflow] instance managing the data flow.
 */
fun <T> ViewModel.reflow(
    cacheSource: CacheSource<T> = CacheSource.None(),
    dispatcher: CoroutineDispatcher = ioDispatcher,
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
    cacheSource = cacheSource,
    maxRetries = maxRetries,
    retryDelay = retryDelay,
    shouldRetry = shouldRetry,
    fetchFlow = fetchFlow,
)

/**
 * Internal helper to create a [Reflow] within a specific [CoroutineScope].
 *
 * @param T The type of data to fetch.
 * @param scope The [CoroutineScope] in which the Reflow will operate.
 * @param dispatcher The [CoroutineDispatcher] for operations.
 * @param initial The initial [Resulting] state.
 * @param shouldLoadingOnRefresh Whether to show loading on refresh.
 * @param cacheSource The [CacheSource] strategy.
 * @param maxRetries Maximum retry attempts.
 * @param retryDelay Delay between retries.
 * @param shouldRetry Predicate for retrying.
 * @param fetchFlow The source [Flow] of data.
 * @return A [Reflow] instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> reflowIn(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = ioDispatcher,
    initial: Resulting<T> = Resulting.loading<T>(),
    shouldLoadingOnRefresh: Boolean = true,
    cacheSource: CacheSource<T> = CacheSource.None(),
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

    val cached = when (cacheSource) {
        is CacheSource.Store<T> -> cacheSource.data.map { it?.let { Resulting.content<T>(it) } ?: Resulting.loading() }
        is CacheSource.None -> flowOf(Resulting.loading())
    }.filter { !(it.isLoading && !isFirstEmission && !shouldLoadingOnRefresh) }.flowOn(dispatcher)

    val fetched = fetchFlow
        .map {
            when (cacheSource) {
                is CacheSource.Store<T> -> scope.launch(ioDispatcher) { cacheSource.store(it) }
                is CacheSource.None -> {}
            }
            Resulting.content(it)
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
        }.catch {
            emit(value = Resulting.failure(it))
        }.flowOn(dispatcher)

    return Reflow(
        refreshes = refreshes,
        stateFlow = refreshes
            .flatMapLatest { merge(fetched, cached) }
            .flowOn(dispatcher)
            .stateIn(scope, started = SharingStarted.Lazily, initialValue = initial),
    )
}

const val RETRY_DELAY: Long = 2_000L // Milliseconds
const val MAX_RETRIES: Int = 3

