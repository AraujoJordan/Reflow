package io.github.araujojordan

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.File

actual fun createDatastore(context: Any?): DataStore<Preferences> {
    return createDatastore { File(REFLOW_CACHE_PREF).absolutePath }
}