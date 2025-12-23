package io.github.araujojordan

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.*
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Defines the persistence strategy for cached data within a [Reflow] block.
 */
sealed interface CacheSource<T> {
    /**
     * Stores data in a volatile memory-based cache.
     * Data is lost when the ViewModel process that holds the reflow is terminated.
     */
    class Memory<T> : CacheSource<T>

    /**
     * Persists data to non-volatile storage using DataStore.
     * Data survives application restarts and device reboots.
     * Note that T needs to be Serializable for this cache source to work
     */
    @OptIn(ExperimentalSerializationApi::class)
    class Disk<T>(
        name: String,
        private val serializer: KSerializer<T>,
    ) : CacheSource<T> {
        companion object {
            inline operator fun <reified T> invoke(
                name: String = T::class.qualifiedName.orEmpty(),
            ): CacheSource<T> {
                return Disk(
                    name = name,
                    serializer = try {
                        serializer<T>()
                    } catch (e: SerializationException) {
                        Logger.e(
                            tag = "Reflow",
                            messageString = "To use CacheSource.Disk, the type passed on '$name' reflow must be @Serializable (kotlinx.serialization.Serializable).",
                            throwable = e
                        )
                        return Memory<T>() // Fallback to Memory
                    }
                )
            }
        }

        private val key = stringPreferencesKey(name)
        private val dataStore: DataStore<Preferences> by lazy { ReflowCacheFactory.datastore }

        val data: Flow<T?> = dataStore.data.map { preferences ->
            preferences[key]?.let { encodedValue ->
                ProtoBuf.decodeFromHexString(serializer, encodedValue)
            }
        }

        suspend fun store(value: T) {
            dataStore.edit { preferences ->
                preferences[key] = ProtoBuf.encodeToHexString(serializer, value)
            }
        }
    }
}