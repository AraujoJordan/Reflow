package io.github.araujojordan

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath

internal const val REFLOW_CACHE_PREF = "reflow.cache.pref"

fun createDatastore(
    producePath: () -> String,
): DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
    produceFile = { producePath().toPath() }
)

expect fun createDatastore(context: Any? = null): DataStore<Preferences>