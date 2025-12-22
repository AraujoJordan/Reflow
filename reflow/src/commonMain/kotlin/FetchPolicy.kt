package com.araujojordan.reflow

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.io.IOException

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