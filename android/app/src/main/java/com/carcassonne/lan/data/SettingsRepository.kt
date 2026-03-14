package com.carcassonne.lan.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private const val DATASTORE_NAME = "carcassonne_lan_settings"
private val Context.dataStore by preferencesDataStore(name = DATASTORE_NAME)

private const val DEFAULT_PORT = 18473

enum class CardArtPack {
    CLASSIC,
    V2,
    V3,
}

data class AppSettings(
    val playerName: String,
    val port: Int,
    val serverId: String,
    val cardArtPack: CardArtPack,
    val simplifiedView: Boolean,
    val previewPaneHeightPercent: Int,
)

class SettingsRepository(private val context: Context) {
    private val keyPlayerName = stringPreferencesKey("player_name")
    private val keyPort = intPreferencesKey("lan_port")
    private val keyServerId = stringPreferencesKey("server_id")
    private val keyCardArtPack = stringPreferencesKey("card_art_pack")
    private val keySimplifiedView = booleanPreferencesKey("simplified_view")
    private val keyPreviewPaneHeightPercent = intPreferencesKey("preview_pane_height_percent")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val storedName = prefs[keyPlayerName].orEmpty()
        val safeName = NameGenerator.ensureNumericSuffix(storedName)
        val port = sanitizePort(prefs[keyPort])
        val serverId = prefs[keyServerId].orEmpty()
        val cardArtPack = prefs[keyCardArtPack]
            ?.let(::decodeCardArtPack)
            ?: CardArtPack.CLASSIC
        val simplified = prefs[keySimplifiedView] ?: false
        val previewPaneHeight = sanitizePreviewPaneHeight(prefs[keyPreviewPaneHeightPercent])
        AppSettings(
            playerName = safeName,
            port = port,
            serverId = serverId,
            cardArtPack = cardArtPack,
            simplifiedView = simplified,
            previewPaneHeightPercent = previewPaneHeight,
        )
    }

    suspend fun initializeDefaultsIfNeeded() {
        context.dataStore.edit { prefs ->
            val name = prefs[keyPlayerName]
            if (name.isNullOrBlank()) {
                prefs[keyPlayerName] = NameGenerator.generate()
            } else {
                prefs[keyPlayerName] = NameGenerator.ensureNumericSuffix(name)
            }
            prefs[keyPort] = sanitizePort(prefs[keyPort])
            if (prefs[keyServerId].isNullOrBlank()) {
                prefs[keyServerId] = UUID.randomUUID().toString()
            }
            prefs[keyCardArtPack] = decodeCardArtPack(prefs[keyCardArtPack]).name
            prefs[keySimplifiedView] = prefs[keySimplifiedView] ?: false
            prefs[keyPreviewPaneHeightPercent] = sanitizePreviewPaneHeight(prefs[keyPreviewPaneHeightPercent])
        }
    }

    suspend fun save(
        playerName: String,
        port: Int,
        cardArtPack: CardArtPack,
        simplifiedView: Boolean,
        previewPaneHeightPercent: Int,
    ) {
        val safeName = NameGenerator.ensureNumericSuffix(playerName)
        val safePort = sanitizePort(port)
        val safePreviewPaneHeight = sanitizePreviewPaneHeight(previewPaneHeightPercent)
        context.dataStore.edit { prefs ->
            prefs[keyPlayerName] = safeName
            prefs[keyPort] = safePort
            prefs[keyCardArtPack] = cardArtPack.name
            prefs[keySimplifiedView] = simplifiedView
            prefs[keyPreviewPaneHeightPercent] = safePreviewPaneHeight
        }
    }

    companion object {
        const val FALLBACK_PORT = DEFAULT_PORT

        fun sanitizePort(raw: Int?): Int {
            val value = raw ?: DEFAULT_PORT
            return value.coerceIn(1024, 65535)
        }

        fun sanitizePreviewPaneHeight(raw: Int?): Int {
            val value = raw ?: 15
            return value.coerceIn(6, 35)
        }

        private fun decodeCardArtPack(raw: String?): CardArtPack {
            return CardArtPack.entries.firstOrNull { it.name == raw } ?: CardArtPack.CLASSIC
        }
    }
}
