package com.carcassonne.lan.data

import android.content.Context
import com.carcassonne.lan.model.MatchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class MatchMetadataStore(private val context: Context) {
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    private val hostFile = File(context.filesDir, "host_match_snapshot.json")

    suspend fun saveHost(match: MatchState) = withContext(Dispatchers.IO) {
        hostFile.writeText(json.encodeToString(MatchState.serializer(), match))
    }

    suspend fun loadHost(): MatchState? = withContext(Dispatchers.IO) {
        if (!hostFile.exists()) return@withContext null
        runCatching {
            json.decodeFromString(MatchState.serializer(), hostFile.readText())
        }.getOrNull()
    }

    suspend fun saveClient(sessionKey: String, match: MatchState) = withContext(Dispatchers.IO) {
        val f = clientFile(sessionKey)
        f.parentFile?.mkdirs()
        f.writeText(json.encodeToString(MatchState.serializer(), match))
    }

    suspend fun loadClient(sessionKey: String): MatchState? = withContext(Dispatchers.IO) {
        val f = clientFile(sessionKey)
        if (!f.exists()) return@withContext null
        runCatching {
            json.decodeFromString(MatchState.serializer(), f.readText())
        }.getOrNull()
    }

    private fun clientFile(sessionKey: String): File {
        val safe = sessionKey.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(context.filesDir, "client_snapshots/$safe.json")
    }
}
