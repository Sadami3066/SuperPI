package io.github.whywood.superpi.runtime

import kotlinx.coroutines.flow.Flow

interface SecretStore {
    fun entries(prefix: String): Flow<Set<String>>

    fun has(prefix: String, providerId: String): Flow<Boolean>

    suspend fun read(prefix: String, providerId: String): String?

    suspend fun readAll(prefix: String): Map<String, String>

    suspend fun save(prefix: String, providerId: String, value: String?)
}
