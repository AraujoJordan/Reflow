package io.github.araujojordan

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

actual fun createDatastore(context: Any?): DataStore<Preferences> {
    requireNotNull(context) { "Context is required for Android DataStore creation" }
    return createDatastore {
        (context as Context).cacheDir.resolve(REFLOW_CACHE_PREF).absolutePath
    }
}
