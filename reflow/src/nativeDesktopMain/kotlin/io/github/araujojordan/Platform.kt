package io.github.araujojordan

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.io.files.SystemTemporaryDirectory
import okio.Path.Companion.toPath

private val datastore: DataStore<Preferences> by lazy {
    PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            val tempDirPath = SystemTemporaryDirectory.toString().toPath()
            tempDirPath.resolve("reflow.cache.preferences_pb")
        },
        scope = CoroutineScope(ioDispatcher + SupervisorJob()),
    )
}

internal actual fun persistentGetFlow(key: String): Flow<String?> {
    val prefKey = stringPreferencesKey(key)
    return datastore.data.map { prefs -> prefs[prefKey] }
}

internal actual suspend fun persistentSet(key: String, value: String) {
    val prefKey = stringPreferencesKey(key)
    datastore.edit { prefs -> prefs[prefKey] = value }
}
