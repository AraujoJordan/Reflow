package io.github.araujojordan

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromHexString
import kotlinx.serialization.encodeToHexString
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.serializer

/**
 * Defines the persistence strategy for cached data within a [Reflow] block.
 */
sealed interface CacheSource<T> {
    /**
     * Stores data in a volatile memory-based cache.
     * Data is lost when the ViewModel process that holds the reflow is terminated.
     */
    class Memory<T>: CacheSource<T>

    /**
     * Persists data to non-volatile storage using DataStore.
     * Data survives application restarts and device reboots.
     */
    @OptIn(ExperimentalSerializationApi::class)
    class Disk<T>(
        name: String,
        private val serializer: KSerializer<T>,
        private val dataStore: DataStore<Preferences>,
    ) : CacheSource<T> {

        private val key = stringPreferencesKey(name)

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

        companion object {
            inline operator fun <reified T> invoke(
                context: Any? = null,
                name: String = T::class.simpleName.orEmpty(),
                dataStore: DataStore<Preferences> = createDatastore(context)
            ): Disk<T> = Disk(name, serializer<T>(), dataStore)
        }
    }
}