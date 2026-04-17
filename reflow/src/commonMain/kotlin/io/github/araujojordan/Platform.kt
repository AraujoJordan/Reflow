package io.github.araujojordan

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

expect val ioDispatcher: CoroutineDispatcher

internal expect fun persistentGetFlow(key: String): Flow<String?>
internal expect suspend fun persistentSet(key: String, value: String)
