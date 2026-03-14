package com.carcassonne.lan.data

import android.content.Context
import com.carcassonne.lan.model.LostCitiesDeckManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class LostCitiesPackRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun loadDeckManifest(): LostCitiesDeckManifest = withContext(Dispatchers.IO) {
        val candidates = listOf("lost_cities/deck_manifest.json", "lost_cities/manifests/deck_manifest.json")
        val path = candidates.firstOrNull { path ->
            runCatching { context.assets.open(path).close() }.isSuccess
        } ?: throw IllegalStateException("Missing Lost Cities deck manifest.")

        context.assets.open(path).use { stream ->
            val text = stream.bufferedReader().use { it.readText() }
            json.decodeFromString(LostCitiesDeckManifest.serializer(), text)
        }
    }
}
