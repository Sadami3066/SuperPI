package io.github.whywood.superpi.data.relay

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.whywood.superpi.runtime.DataStoreKeyValueStore
import io.github.whywood.superpi.runtime.EncryptedSecretStore

private val Context.relayDataStore by preferencesDataStore(name = "relay")

fun RelayStore(context: Context): RelayStore {
    val appContext = context.applicationContext
    return RelayStore(
        store = DataStoreKeyValueStore(appContext.relayDataStore),
        secrets = EncryptedSecretStore(appContext),
    )
}
