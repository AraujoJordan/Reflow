package io.github.araujojordan.cache

import androidx.collection.LruCache
import kotlinx.coroutines.flow.*

internal object ReflowLru {
    private val cache = LruCache<String, Any>(512 * 1024 * 1024)  // 512MiB
    private val _events = MutableSharedFlow<String>()

    fun <T : Any> getAsFlow(key: String): Flow<T?> = merge(
            _events.filter { it == key },
            flowOf(key), // Initial emission
        )
        .map { get<T>(key) }
        .distinctUntilChanged()

    @Suppress("UNCHECKED_CAST")
    private fun <T: Any> get(key: String): T? = cache[key] as? T

    fun put(value: Any?, key: String? = value?.let { it::class.qualifiedName }) {
        if (key == null || value == null) return
        cache.put(key, value)
        _events.tryEmit(key)
    }

    val size: Int get() = cache.size()

    fun updateCacheCapacity(newCapacity: Int) = cache.resize(newCapacity)
    fun clear() { cache.evictAll() }
}