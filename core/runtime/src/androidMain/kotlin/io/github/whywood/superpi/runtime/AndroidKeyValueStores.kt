package io.github.whywood.superpi.runtime

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private val Context.modelDataStore by preferencesDataStore(name = "model_config")
private val Context.workspaceStore by preferencesDataStore(name = "workspaces")
private val Context.sessionMetaStore by preferencesDataStore(name = "session_meta")

fun ModelConfigStore(context: Context): ModelConfigStore =
    ModelConfigStore(DataStoreKeyValueStore(context.applicationContext.modelDataStore))

fun WorkspaceStore(context: Context): WorkspaceStore =
    WorkspaceStore(DataStoreKeyValueStore(context.applicationContext.workspaceStore))

fun SessionMetaStore(context: Context): SessionMetaStore =
    SessionMetaStore(DataStoreKeyValueStore(context.applicationContext.sessionMetaStore))
