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

class Reflow<T> internal constructor(
    private val refreshes: MutableSharedFlow<Unit>,
    val stateFlow: StateFlow<Resulting<T>>,
) {
    val state @Composable get() = stateFlow.collectAsState()
    fun refresh() = refreshes.tryEmit(value = Unit)
}

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

fun <T> ViewModel.reflow(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    initial: Resulting<T> = Resulting.loading(),
    shouldLoadingOnRefresh: Boolean = true,
    cacheSource: CacheSource<T> = CacheSource.Memory(),
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

fun <T> ViewModel.reflow(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    initial: Resulting<T> = Resulting.loading(),
    shouldLoadingOnRefresh: Boolean = true,
    cacheSource: CacheSource<T> = CacheSource.Memory(),
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

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> reflowIn(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    initial: Resulting<T> = Resulting.loading<T>(),
    shouldLoadingOnRefresh: Boolean = true,
    cacheSource: CacheSource<T> = CacheSource.Memory(),
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
        is CacheSource.Disk<T> -> cacheSource.data.map { it?.let { Resulting.content<T>(it) } ?: Resulting.loading() }
        is CacheSource.Memory -> flowOf(Resulting.loading())
    }.filter { !(it.isLoading && !isFirstEmission && !shouldLoadingOnRefresh) }.flowOn(dispatcher)

    val fetched = fetchFlow
        .map {
            scope.launch(Dispatchers.IO) { (cacheSource as? CacheSource.Disk<T>)?.store(it) }
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

