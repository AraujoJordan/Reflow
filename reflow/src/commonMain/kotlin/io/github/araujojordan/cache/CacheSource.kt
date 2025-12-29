package io.github.araujojordan.cache

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.map
import kotlinx.serialization.*
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Defines the persistence strategy for cached data within a [io.github.araujojordan.Reflow] or [io.github.araujojordan.ReflowPaginated] block.
 */
sealed interface CacheSource<T> {

    /**
     * Data is lost when the ViewModel process that holds the reflow/reflowPaginated is terminated.
     */
    class None<T> : CacheSource<T>

    /**
     * Represents a cache source that allows storing and retrieving data.
     * Implementations of this interface provide mechanisms to persist data,
     * such as in-memory or on-disk storage.
     */
    interface Store<T> : CacheSource<T> {
        val data: Flow<T?>
        suspend fun store(value: T)
    }


    /**
     * Stores data in the Least Recently Used (LRU) memory-based cache that will survive screens/VM terminations.
     * This cache source is useful for caching data that is not critical to the user experience, such as search results,
     * or data that is not critical to the application's functionality.
     */
    class Memory<T : Any>(val key: String) : Store<T> {
        companion object {
            @UseMemoryCacheWithoutKey
            inline operator fun <reified T : Any> invoke() = Memory<T>(T::class.qualifiedName.orEmpty())
        }

        override val data: Flow<T?> = ReflowLru.getAsFlow(key)
        override suspend fun store(value: T) = ReflowLru.put(value, key)
        fun updateCacheCapacity(capacity: Int) = ReflowLru.updateCacheCapacity(capacity)
        fun clear() = ReflowLru.clear()
    }

    /**
     * A [Store] implementation that persists data to non-volatile storage using DataStore+Protobuf.
     * Data saved here survives application restarts and device reboots.
     *
     * Note: The type [T] must be serializable (marked with [Serializable])
     * for this cache source to function correctly.
     */
    @OptIn(ExperimentalSerializationApi::class)
    class Disk<T : Any>(
        val key: String,
        private val serializer: KSerializer<T>,
    ) : Store<T> {
        companion object {
            @UseMemoryCacheWithoutKey
            inline operator fun <reified T : Any> invoke(): Store<T> {
                return Disk(key = T::class.qualifiedName.orEmpty())
            }

            inline operator fun <reified T : Any> invoke(key: String): Store<T> {
                return Disk(
                    key = key,
                    serializer = try {
                        serializer<T>()
                    } catch (e: SerializationException) {
                        Logger.e(
                            tag = "Reflow",
                            messageString = "To use CacheSource.Disk, the generic <T> type passed must be annotated with @Serializable (kotlinx.serialization.Serializable).",
                            throwable = e
                        )
                        return Memory(key) // Fallback to CacheSource.Memory when missing @Serializable annotation
                    }
                )
            }
        }

        private val dataStoreKey = stringPreferencesKey(key)

        @OptIn(ExperimentalCoroutinesApi::class)
        override val data: Flow<T?> = ReflowLru.getAsFlow<T>(key).flatMapMerge {
            ReflowDatastore.datastore.data.map { preferences ->
                preferences[dataStoreKey]?.let { encodedValue ->
                    ProtoBuf.decodeFromHexString(serializer, encodedValue)
                }
            }
        }

        override suspend fun store(value: T) {
            ReflowLru.put(value, key)
            ReflowDatastore.datastore.edit { preferences ->
                preferences[dataStoreKey] = ProtoBuf.encodeToHexString(serializer, value)
            }
        }
    }
}

