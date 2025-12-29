package io.github.araujojordan.cache

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.files.SystemTemporaryDirectory
import okio.Path.Companion.toPath

internal object ReflowDatastore {
    val datastore = PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            val tempDirPath = SystemTemporaryDirectory.toString().toPath()
            tempDirPath.resolve("reflow.cache.preferences_pb")
        },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    )
}