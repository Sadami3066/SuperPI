package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.KeyValueStore
import io.github.whywood.superpi.runtime.SecretStore
import io.github.whywood.superpi.runtime.putString
import io.github.whywood.superpi.runtime.stringValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class RelayStore(
    private val store: KeyValueStore,
    private val secrets: SecretStore,
) : RelayAccountStore {
    override val account: Flow<RelayAccount?> = store.entries
        .map { values -> RelayConfigCodec.decode(values.stringValue(ACCOUNT_KEY)) }
        .distinctUntilChanged()

    val allowRemote: Flow<Boolean> = store.entries
        .map { values -> values.stringValue(ALLOW_REMOTE_KEY) == "true" }
        .distinctUntilChanged()

    override suspend fun read(): RelayAccount? = account.first()

    suspend fun readAllowRemote(): Boolean = allowRemote.first()

    suspend fun readDevices(): List<RelayDevice> {
        val raw = store.readAll().stringValue(DEVICES_KEY) ?: return emptyList()
        return RelayFrames.parseDeviceList(raw).orEmpty()
    }

    suspend fun saveDevices(devices: List<RelayDevice>) {
        store.edit { values ->
            values.putString(DEVICES_KEY, RelayFrames.encodeDevices(devices))
        }
    }

    suspend fun setAllowRemote(value: Boolean) {
        store.edit { values ->
            values.putString(ALLOW_REMOTE_KEY, if (value) "true" else "false")
        }
    }

    override suspend fun token(): String? = secrets.read(TOKEN_PREFIX, TOKEN_ID)

    override suspend fun save(account: RelayAccount, token: String) {
        secrets.save(TOKEN_PREFIX, TOKEN_ID, token)
        store.edit { values ->
            values.putString(ACCOUNT_KEY, RelayConfigCodec.encode(account))
        }
    }

    override suspend fun clear() {
        secrets.save(TOKEN_PREFIX, TOKEN_ID, null)
        store.edit { values ->
            values.remove(ACCOUNT_KEY)
            values.remove(DEVICES_KEY)
        }
    }

    companion object {
        private const val ACCOUNT_KEY = "account"
        private const val ALLOW_REMOTE_KEY = "allowRemote"
        private const val DEVICES_KEY = "devices"
        private const val TOKEN_PREFIX = "relay"
        private const val TOKEN_ID = "token"
    }
}
