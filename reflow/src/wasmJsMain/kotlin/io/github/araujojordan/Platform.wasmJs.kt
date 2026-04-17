package io.github.araujojordan

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

private val store = mutableMapOf<String, MutableStateFlow<String?>>()

internal actual fun persistentGetFlow(key: String): Flow<String?> =
    store.getOrPut(key) { MutableStateFlow(null) }

internal actual suspend fun persistentSet(key: String, value: String) {
    store.getOrPut(key) { MutableStateFlow(null) }.value = value
}
