package com.lost_cities.lan.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lost_cities.lan.ai.LostCitiesSoloAi
import com.lost_cities.lan.data.CardArtPack
import com.lost_cities.lan.data.LostCitiesPackRepository
import com.lost_cities.lan.data.MatchMetadataStore
import com.lost_cities.lan.data.NameGenerator
import com.lost_cities.lan.data.SettingsRepository
import com.lost_cities.lan.model.ClientSession
import com.lost_cities.lan.model.GameRules
import com.lost_cities.lan.model.InviteListItem
import com.lost_cities.lan.model.LostCitiesDeckCard
import com.lost_cities.lan.model.LostCitiesDeckManifest
import com.lost_cities.lan.model.LostCitiesExpeditionScoreBreakdown
import com.lost_cities.lan.model.LostCitiesMatchState
import com.lost_cities.lan.model.LostCitiesScoring
import com.lost_cities.lan.model.LostCitiesSuitConfig
import com.lost_cities.lan.model.LostCitiesTurnPhase
import com.lost_cities.lan.model.MatchState
import com.lost_cities.lan.model.MatchStatus
import com.lost_cities.lan.model.PeerEndpoint
import com.lost_cities.lan.model.PeerTransport
import com.lost_cities.lan.model.PingResponse
import com.lost_cities.lan.model.PollResponse
import com.lost_cities.lan.network.BluetoothClient
import com.lost_cities.lan.network.BluetoothHostServer
import com.lost_cities.lan.network.BluetoothScanner
import com.lost_cities.lan.network.HostGameManager
import com.lost_cities.lan.network.LanClient
import com.lost_cities.lan.network.LanHostServer
import com.lost_cities.lan.network.LanScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

enum class AppTab {
    LOBBY,
    MATCH,
}

enum class LobbyTransport {
    LAN,
    BLUETOOTH,
    SOLO,
}

data class BluetoothDiscoveredPeer(
    val address: String,
    val name: String,
    val ping: PingResponse? = null,
    val bonded: Boolean,
    val isSelf: Boolean,
) {
    val reachable: Boolean
        get() = ping != null
}

data class LobbyInvite(
    val id: String,
    val fromName: String,
    val transport: PeerTransport,
    val hostAddress: String,
    val hostPort: Int = 0,
    val createdAtEpochMs: Long,
    val status: String,
    val rules: GameRules = GameRules(),
)

data class AiHelpSuggestion(
    val action: String,
    val cardId: String? = null,
    val suit: String? = null,
)

data class AppUiState(
    val tab: AppTab = AppTab.LOBBY,
    val lobbyTransport: LobbyTransport = LobbyTransport.LAN,
    val playerName: String = NameGenerator.generate(),
    val serverPort: Int = SettingsRepository.FALLBACK_PORT,
    val localServerId: String = "",
    val localAddresses: List<String> = emptyList(),
    val discoveredHosts: List<LanScanner.DiscoveredHost> = emptyList(),
    val bluetoothSupported: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val bluetoothPeers: List<BluetoothDiscoveredPeer> = emptyList(),
    val pendingInvites: List<LobbyInvite> = emptyList(),
    val activeInvite: LobbyInvite? = null,
    val manifest: LostCitiesDeckManifest? = null,
    val cardById: Map<String, LostCitiesDeckCard> = emptyMap(),
    val connected: Boolean = false,
    val session: ClientSession? = null,
    val match: MatchState? = null,
    val statusMessage: String = "Loading resources...",
    val scanning: Boolean = false,
    val bluetoothScanning: Boolean = false,
    val canAct: Boolean = false,
    val selectedCardId: String? = null,
    val cardArtPack: CardArtPack = CardArtPack.CLASSIC,
    val aiHelpEnabled: Boolean = false,
    val usePurple: Boolean = false,
    val newlyAcquiredCardIds: Set<String> = emptySet(),
    val turnHintCards: Boolean = false,
    val rematchPeer: PeerEndpoint? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LostCitiesPackRepository(application)
    private val settingsRepository = SettingsRepository(application)
    private val metadataStore = MatchMetadataStore(application)
    private val lanClient = LanClient()
    private val lanScanner = LanScanner()
    private val bluetoothScanner = BluetoothScanner(application)
    private val bluetoothClient = BluetoothClient(application)

    private var hostManager: HostGameManager? = null
    private var hostServer: LanHostServer? = null
    private var bluetoothHostServer: BluetoothHostServer? = null
    private var scanJob: Job? = null
    private var pollJob: Job? = null
    private var soloAiJob: Job? = null
    private var lastLobbyFullScanElapsedMs: Long = 0L
    private var lastBluetoothRefreshElapsedMs: Long = 0L
    private val scanMutex = Mutex()
    private var soloAiToken: String? = null
    private var soloAiPlayer: Int? = null

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        startBootstrap()
        startScannerLoop()
    }

    private fun startBootstrap() {
        viewModelScope.launch {
            settingsRepository.initializeDefaultsIfNeeded()
            val settings = settingsRepository.settings.first()
            val playerName = settings.playerName.ifBlank { NameGenerator.generate() }
            _uiState.update { current ->
                current.copy(
                    playerName = playerName,
                    serverPort = settings.port,
                    localServerId = settings.serverId,
                    cardArtPack = settings.cardArtPack,
                    aiHelpEnabled = settings.aiHelpEnabled,
                    bluetoothSupported = bluetoothScanner.isSupported(),
                    bluetoothEnabled = bluetoothScanner.isEnabled(),
                    statusMessage = "Ready. Scan and invite a peer.",
                )
            }
            loadManifest()
        }
    }

    private fun loadManifest() {
        viewModelScope.launch {
            val loaded = runCatching { repository.loadDeckManifest() }.getOrNull()
            if (loaded == null) {
                _uiState.update { it.copy(statusMessage = "Could not load the Lost Cities deck.") }
                return@launch
            }

            _uiState.update {
                it.copy(
                    manifest = loaded,
                    cardById = loaded.cards.associateBy { card -> card.id },
                    statusMessage = "Ready. Scan and invite a peer.",
                )
            }
            ensureLocalLobbyServer()
            refreshBluetoothAvailability()
            refreshDiscoveredHosts()
        }
    }

    private fun startScannerLoop() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            while (isActive) {
                if (shouldAutoScan()) {
                    when (_uiState.value.lobbyTransport) {
                        LobbyTransport.LAN -> {
                            val now = SystemClock.elapsedRealtime()
                            val shouldRunFullScan =
                                _uiState.value.discoveredHosts.isEmpty() ||
                                    now - lastLobbyFullScanElapsedMs >= FULL_LOBBY_SCAN_INTERVAL_MS
                            refreshDiscoveredHostsInternal(forceFullScan = shouldRunFullScan)
                            delay(LOBBY_REFRESH_INTERVAL_MS)
                        }

                        LobbyTransport.BLUETOOTH -> {
                            val now = SystemClock.elapsedRealtime()
                            val shouldRefresh = now - lastBluetoothRefreshElapsedMs >= BLUETOOTH_REFRESH_INTERVAL_MS
                            if (shouldRefresh) {
                                refreshBluetoothPeersInternal(forceDiscovery = false)
                            }
                            delay(IDLE_LOOP_INTERVAL_MS)
                        }

                        LobbyTransport.SOLO -> {
                            delay(IDLE_LOOP_INTERVAL_MS)
                        }
                    }
                } else {
                    delay(IDLE_LOOP_INTERVAL_MS)
                }
            }
        }
    }

    private fun shouldAutoScan(): Boolean {
        val state = _uiState.value
        return !state.connected &&
            state.tab == AppTab.LOBBY &&
            state.localServerId.isNotBlank() &&
            state.manifest != null
    }

    private fun ensureLocalLobbyServer(forceRestart: Boolean = false) {
        viewModelScope.launch {
            ensureLocalLobbyServerReady(forceRestart)
        }
    }

    private suspend fun ensureLocalLobbyServerReady(forceRestart: Boolean = false): Boolean {
        val state = _uiState.value
        val manifest = state.manifest ?: return false
        val localServerId = state.localServerId.ifBlank { return false }
        if (!forceRestart && hostServer != null) return true

        if (forceRestart) {
            hostServer?.stop()
            hostServer = null
            bluetoothHostServer?.stop()
            bluetoothHostServer = null
            hostManager = null
        }

        val manager = if (!forceRestart && hostManager != null) {
            hostManager!!.apply {
                updateLobbyHostName(state.playerName)
                configureGameRules(GameRules(usePurple = state.usePurple))
            }
        } else {
            HostGameManager(
                deckManifest = manifest,
                hostName = state.playerName,
                serverId = localServerId,
                initialRules = GameRules(usePurple = state.usePurple),
            )
        }

        val server = LanHostServer(manager, metadataStore)
        return runCatching { server.start(state.serverPort) }
            .onFailure { error ->
                _uiState.update {
                    it.copy(statusMessage = "Could not start local lobby server: ${error.message ?: "error"}")
                }
            }
            .map {
                hostManager = manager
                hostServer = server
                ensureBluetoothLobbyServer()
                true
            }
            .getOrElse { false }
    }

    fun startSoloGame() {
        val state = _uiState.value
        if (state.connected) {
            _uiState.update { it.copy(statusMessage = "Disconnect from the current match before starting solo play.") }
            return
        }
        if (state.manifest == null) {
            _uiState.update { it.copy(statusMessage = "Deck is still loading.") }
            return
        }

        stopPollingLoop()
        soloAiJob?.cancel()
        viewModelScope.launch {
            if (!ensureLocalLobbyServerReady(forceRestart = true)) {
                return@launch
            }
            val manager = hostManager ?: return@launch
            val playerName = _uiState.value.playerName.ifBlank { NameGenerator.generate() }
            val aiName = "Stone Sage 9001"
            val humanJoin = manager.joinOrReconnect(playerName)
            val aiJoin = manager.joinOrReconnect(aiName)
            if (!humanJoin.ok || !aiJoin.ok || humanJoin.token == null || humanJoin.player == null) {
                _uiState.update {
                    it.copy(statusMessage = humanJoin.error ?: aiJoin.error ?: "Could not start the solo round.")
                }
                return@launch
            }

            soloAiToken = aiJoin.token
            soloAiPlayer = aiJoin.player
            val match = manager.snapshot()
            val session = ClientSession(
                transport = PeerTransport.LAN,
                hostAddress = LOCAL_HOST,
                port = _uiState.value.serverPort,
                token = humanJoin.token,
                player = humanJoin.player,
                playerName = playerName,
            )
            _uiState.update {
                it.copy(
                    lobbyTransport = LobbyTransport.SOLO,
                    connected = true,
                    session = session,
                    match = match,
                    canAct = match.status == MatchStatus.ACTIVE && match.lostCities.turnPlayer == session.player,
                    tab = AppTab.MATCH,
                    pendingInvites = emptyList(),
                    activeInvite = null,
                    selectedCardId = null,
                    newlyAcquiredCardIds = emptySet(),
                    turnHintCards = match.status == MatchStatus.ACTIVE &&
                        match.lostCities.turnPlayer == session.player &&
                        match.lostCities.phase == LostCitiesTurnPhase.PLAY,
                    statusMessage = "Solo round started against ${match.players[aiJoin.player ?: 2]?.name ?: aiName}.",
                    rematchPeer = null,
                )
            }
            maybeRunSoloAi(match)
        }
    }

    fun refreshBluetoothAvailability() {
        val supported = bluetoothScanner.isSupported()
        val enabled = bluetoothScanner.isEnabled()
        _uiState.update {
            it.copy(
                bluetoothSupported = supported,
                bluetoothEnabled = enabled,
            )
        }
        if (enabled) {
            ensureBluetoothLobbyServer()
        } else {
            bluetoothHostServer?.stop()
            bluetoothHostServer = null
            bluetoothClient.closeMatchConnection()
        }
    }

    private fun ensureBluetoothLobbyServer() {
        val manager = hostManager ?: return
        if (!bluetoothScanner.isEnabled()) return
        if (bluetoothHostServer != null) return
        viewModelScope.launch {
            val server = BluetoothHostServer(
                context = getApplication(),
                hostGameManager = manager,
                metadataStore = metadataStore,
            )
            runCatching { server.start() }
                .onSuccess {
                    bluetoothHostServer = server
                    _uiState.update {
                        it.copy(
                            bluetoothSupported = bluetoothScanner.isSupported(),
                            bluetoothEnabled = bluetoothScanner.isEnabled(),
                        )
                    }
                }
                .onFailure {
                    bluetoothHostServer?.stop()
                    bluetoothHostServer = null
                }
        }
    }

    private fun restartLocalLobbyServerIfIdle() {
        val state = _uiState.value
        if (state.connected) return
        ensureLocalLobbyServer(forceRestart = true)
    }

    private fun startPollingLoop() {
        _uiState.value.session ?: return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val currentSession = _uiState.value.session ?: break
                val response = runCatching { pollCurrentSession(currentSession) }.getOrNull()
                if (response == null) {
                    _uiState.update { it.copy(statusMessage = "Unable to reach the current match.") }
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                applyPollResponse(currentSession, response)
                if (!response.ok) break

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPollingLoop() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun applyPollResponse(session: ClientSession, response: PollResponse) {
        if (!response.ok) {
            if (session.transport == PeerTransport.BLUETOOTH) {
                bluetoothClient.closeMatchConnection()
            }
            _uiState.update {
                it.copy(
                    connected = false,
                    canAct = false,
                    session = null,
                    match = null,
                    tab = AppTab.LOBBY,
                    statusMessage = response.error ?: "Disconnected from the match.",
                )
            }
            return
        }

        val match = response.match ?: return
        val previousState = _uiState.value
        val previousMatch = previousState.match
        val player = session.player

        val previousHand = previousMatch?.lostCities?.players?.get(player)?.hand.orEmpty()
        val nextHand = match.lostCities.players[player]?.hand.orEmpty()
        val newCards = nextHand.filterNot(previousHand::contains).toSet()

        val selectedCardStillPresent = previousState.selectedCardId?.let(nextHand::contains) == true
        val canActNow = match.status == MatchStatus.ACTIVE && match.lostCities.turnPlayer == player
        val shouldGlowCards = match.status == MatchStatus.ACTIVE &&
            match.lostCities.turnPlayer == player &&
            match.lostCities.phase == LostCitiesTurnPhase.PLAY &&
            (previousMatch == null ||
                previousMatch.lostCities.turnPlayer != player ||
                previousMatch.lostCities.phase != LostCitiesTurnPhase.PLAY)
        val nextSelectedCardId = if (selectedCardStillPresent) previousState.selectedCardId else null
        val nextStatusMessage = match.lastEvent.ifBlank { describeMatch(match, player) }
        val nextTurnHintCards = canActNow && shouldGlowCards

        if (
            previousState.connected &&
            previousMatch?.updatedAtEpochMs == match.updatedAtEpochMs &&
            previousState.canAct == canActNow &&
            previousState.selectedCardId == nextSelectedCardId &&
            previousState.statusMessage == nextStatusMessage &&
            previousState.turnHintCards == nextTurnHintCards &&
            previousState.newlyAcquiredCardIds.isEmpty() &&
            newCards.isEmpty()
        ) {
            return
        }

        _uiState.update {
            it.copy(
                connected = true,
                canAct = canActNow,
                match = match,
                selectedCardId = nextSelectedCardId,
                statusMessage = nextStatusMessage,
                newlyAcquiredCardIds = newCards,
                turnHintCards = nextTurnHintCards,
            )
        }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(tab = tab) }
        if (tab == AppTab.LOBBY && !_uiState.value.connected) {
            refreshNearbyPeers(forceFull = true)
        }
    }

    fun selectLobbyTransport(transport: LobbyTransport) {
        _uiState.update { it.copy(lobbyTransport = transport) }
        if (!_uiState.value.connected) {
            refreshNearbyPeers(forceFull = true)
        }
    }

    fun updatePlayerName(raw: String) {
        val sanitized = raw.take(28)
        _uiState.update { it.copy(playerName = sanitized) }
        viewModelScope.launch {
            settingsRepository.save(
                playerName = sanitized,
                port = _uiState.value.serverPort,
                cardArtPack = _uiState.value.cardArtPack,
                aiHelpEnabled = _uiState.value.aiHelpEnabled,
                simplifiedView = false,
                previewPaneHeightPercent = 15,
            )
            if (!_uiState.value.connected) {
                hostManager?.updateLobbyHostName(sanitized)
            }
        }
    }

    fun updateServerPort(raw: String) {
        val parsed = raw.filter(Char::isDigit).toIntOrNull()?.coerceIn(1024, 65535) ?: return
        _uiState.update { it.copy(serverPort = parsed) }
        viewModelScope.launch {
            settingsRepository.save(
                playerName = _uiState.value.playerName,
                port = parsed,
                cardArtPack = _uiState.value.cardArtPack,
                aiHelpEnabled = _uiState.value.aiHelpEnabled,
                simplifiedView = false,
                previewPaneHeightPercent = 15,
            )
            restartLocalLobbyServerIfIdle()
        }
    }

    fun updateCardArtPack(cardArtPack: CardArtPack) {
        _uiState.update { it.copy(cardArtPack = cardArtPack) }
        viewModelScope.launch {
            settingsRepository.save(
                playerName = _uiState.value.playerName,
                port = _uiState.value.serverPort,
                cardArtPack = cardArtPack,
                aiHelpEnabled = _uiState.value.aiHelpEnabled,
                simplifiedView = false,
                previewPaneHeightPercent = 15,
            )
        }
    }

    fun toggleAiHelp(enabled: Boolean) {
        _uiState.update { it.copy(aiHelpEnabled = enabled) }
        viewModelScope.launch {
            settingsRepository.save(
                playerName = _uiState.value.playerName,
                port = _uiState.value.serverPort,
                cardArtPack = _uiState.value.cardArtPack,
                aiHelpEnabled = enabled,
                simplifiedView = false,
                previewPaneHeightPercent = 15,
            )
        }
    }

    fun togglePurple(enabled: Boolean) {
        _uiState.update { it.copy(usePurple = enabled) }
        val match = _uiState.value.match
        if (match == null || match.status != MatchStatus.ACTIVE) {
            hostManager?.configureGameRules(GameRules(usePurple = enabled))
        }
    }

    fun sendInviteToDiscoveredHost(host: LanScanner.DiscoveredHost) {
        sendInviteToPeer(peerEndpointOf(host))
    }

    fun sendInviteToBluetoothPeer(peer: BluetoothDiscoveredPeer) {
        viewModelScope.launch {
            refreshBluetoothAvailability()
            if (!_uiState.value.bluetoothEnabled) {
                _uiState.update { it.copy(statusMessage = "Enable Bluetooth before inviting this phone.") }
                return@launch
            }

            val deviceLabel = peer.name.ifBlank { peer.address }
            val status = if (peer.bonded) {
                "Connecting to $deviceLabel..."
            } else {
                "Pairing with $deviceLabel..."
            }
            _uiState.update { it.copy(statusMessage = status) }

            val resolvedPing = peer.ping ?: runCatching { bluetoothClient.preparePeer(peer.address) }.getOrNull()
            if (resolvedPing == null) {
                _uiState.update {
                    it.copy(
                        statusMessage = "Could not reach $deviceLabel over Bluetooth. Keep Lost Cities open and make the phone visible.",
                    )
                }
                refreshBluetoothPeersInternal(forceDiscovery = false)
                return@launch
            }

            val resolvedPeer = peer.copy(
                ping = resolvedPing,
                isSelf = resolvedPing.serverId == _uiState.value.localServerId,
            )
            _uiState.update { current ->
                current.copy(
                    bluetoothPeers = current.bluetoothPeers.map { item ->
                        if (item.address == peer.address) resolvedPeer else item
                    },
                )
            }
            sendInviteToPeer(peerEndpointOf(resolvedPeer))
        }
    }

    private fun sendInviteToPeer(target: PeerEndpoint) {
        val state = _uiState.value
        if (state.manifest == null) {
            _uiState.update { it.copy(statusMessage = "Deck is still loading.") }
            return
        }
        if (target.transport == PeerTransport.LAN && isKnownSelfAddress(target.address)) {
            _uiState.update { it.copy(statusMessage = "Cannot invite this device.") }
            return
        }
        if (target.serverId != null && target.serverId == state.localServerId) {
            _uiState.update { it.copy(statusMessage = "Cannot invite this device.") }
            return
        }
        if (state.connected && state.session?.hostAddress != LOCAL_HOST) {
            _uiState.update { it.copy(statusMessage = "Disconnect from the current peer before sending a new invite.") }
            return
        }

        viewModelScope.launch {
            ensureLocalLobbyServer()
            ensureBluetoothLobbyServer()
            val playerName = _uiState.value.playerName.ifBlank { NameGenerator.generate() }
            val session = ensureLocalSession(playerName) ?: return@launch
            val inviteResult = runCatching {
                hostManager?.createInvite(
                    fromName = playerName,
                    targetServerId = target.serverId,
                    inviteRules = GameRules(usePurple = _uiState.value.usePurple),
                )
            }.getOrNull()

            if (inviteResult == null || !inviteResult.ok) {
                _uiState.update { it.copy(statusMessage = inviteResult?.error ?: "Failed to send invite.") }
                return@launch
            }

            hostManager?.snapshot()?.let { snapshot ->
                runCatching { metadataStore.saveHost(snapshot) }
            }
            val waitingMatch = hostManager?.snapshot() ?: return@launch
            _uiState.update {
                it.copy(
                    connected = true,
                    session = session,
                    match = waitingMatch,
                    canAct = false,
                    tab = AppTab.MATCH,
                    statusMessage = "Invite sent to ${labelForPeer(target)}. Waiting for accept or deny.",
                    selectedCardId = null,
                    turnHintCards = false,
                    newlyAcquiredCardIds = emptySet(),
                    rematchPeer = target,
                )
            }
            startPollingLoop()
        }
    }

    private fun ensureLocalSession(playerName: String): ClientSession? {
        val currentState = _uiState.value
        currentState.session?.takeIf { currentState.connected && it.hostAddress == LOCAL_HOST }?.let { existing ->
            return existing
        }

        val manager = hostManager ?: return null
        val join = manager.joinOrReconnect(playerName)
        if (!join.ok || join.token == null || join.player == null || join.match == null) {
            _uiState.update { it.copy(statusMessage = join.error ?: "Could not join the local lobby session.") }
            return null
        }

        return ClientSession(
            transport = PeerTransport.LAN,
            hostAddress = LOCAL_HOST,
            port = _uiState.value.serverPort,
            token = join.token,
            player = join.player,
            playerName = playerName,
        )
    }

    fun acceptInvite(invite: LobbyInvite) {
        viewModelScope.launch {
            val responded = runCatching {
                when (invite.transport) {
                    PeerTransport.LAN -> lanClient.respondInvite(invite.hostAddress, invite.hostPort, invite.id, "accept")
                    PeerTransport.BLUETOOTH -> bluetoothClient.respondInvite(invite.hostAddress, invite.id, "accept")
                }
            }.getOrNull()
            if (responded == null || !responded.ok) {
                _uiState.update { it.copy(statusMessage = responded?.error ?: "Could not accept invite.") }
                return@launch
            }

            when (invite.transport) {
                PeerTransport.LAN -> joinRemoteLanHost(peerEndpointOf(invite))
                PeerTransport.BLUETOOTH -> joinRemoteBluetoothHost(peerEndpointOf(invite))
            }
            _uiState.update {
                it.copy(
                    activeInvite = null,
                    pendingInvites = it.pendingInvites.filterNot { item ->
                        item.id == invite.id &&
                            item.hostAddress == invite.hostAddress &&
                            item.transport == invite.transport
                    },
                    rematchPeer = peerEndpointOf(invite),
                )
            }
        }
    }

    fun denyInvite(invite: LobbyInvite) {
        viewModelScope.launch {
            val responded = runCatching {
                when (invite.transport) {
                    PeerTransport.LAN -> lanClient.respondInvite(invite.hostAddress, invite.hostPort, invite.id, "deny")
                    PeerTransport.BLUETOOTH -> bluetoothClient.respondInvite(invite.hostAddress, invite.id, "deny")
                }
            }.getOrNull()
            _uiState.update {
                it.copy(
                    statusMessage = if (responded?.ok == true) "Invite declined." else responded?.error ?: "Could not decline invite.",
                    activeInvite = if (it.activeInvite?.id == invite.id) null else it.activeInvite,
                    pendingInvites = it.pendingInvites.filterNot { item ->
                        item.id == invite.id &&
                            item.hostAddress == invite.hostAddress &&
                            item.transport == invite.transport
                    },
                )
            }
        }
    }

    fun dismissActiveInvite() {
        _uiState.update { it.copy(activeInvite = null) }
    }

    private fun joinRemoteLanHost(target: PeerEndpoint) {
        val targetAddress = target.address.trim()
        if (targetAddress.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Invite host address is empty.") }
            return
        }

        viewModelScope.launch {
            if (isKnownSelfAddress(targetAddress)) {
                _uiState.update { it.copy(statusMessage = "Cannot join this device.") }
                return@launch
            }

            val playerName = _uiState.value.playerName.ifBlank { NameGenerator.generate() }
            val join = runCatching { lanClient.join(targetAddress, target.port ?: _uiState.value.serverPort, playerName) }.getOrNull()
            if (join == null || !join.ok || join.token == null || join.player == null || join.match == null) {
                _uiState.update { it.copy(statusMessage = join?.error ?: "Could not join the invited peer.") }
                return@launch
            }

            val session = ClientSession(
                transport = PeerTransport.LAN,
                hostAddress = targetAddress,
                port = target.port ?: _uiState.value.serverPort,
                token = join.token,
                player = join.player,
                playerName = playerName,
            )
            applyJoinedRemoteSession(session = session, match = join.match, player = join.player, rematchPeer = target)
            startPollingLoop()
        }
    }

    private fun joinRemoteBluetoothHost(target: PeerEndpoint) {
        val targetAddress = target.address.trim()
        if (targetAddress.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Bluetooth invite source is empty.") }
            return
        }

        viewModelScope.launch {
            refreshBluetoothAvailability()
            if (!_uiState.value.bluetoothEnabled) {
                _uiState.update { it.copy(statusMessage = "Enable Bluetooth before accepting this invite.") }
                return@launch
            }

            val playerName = _uiState.value.playerName.ifBlank { NameGenerator.generate() }
            val join = runCatching { bluetoothClient.join(targetAddress, playerName) }.getOrNull()
            if (join == null || !join.ok || join.token == null || join.player == null || join.match == null) {
                _uiState.update { it.copy(statusMessage = join?.error ?: "Could not join the Bluetooth peer.") }
                return@launch
            }

            val session = ClientSession(
                transport = PeerTransport.BLUETOOTH,
                hostAddress = targetAddress,
                port = 0,
                token = join.token,
                player = join.player,
                playerName = playerName,
            )
            applyJoinedRemoteSession(session = session, match = join.match, player = join.player, rematchPeer = target)
            startPollingLoop()
        }
    }

    private fun applyJoinedRemoteSession(
        session: ClientSession,
        match: MatchState,
        player: Int,
        rematchPeer: PeerEndpoint,
    ) {
        _uiState.update {
            it.copy(
                connected = true,
                session = session,
                match = match,
                canAct = match.status == MatchStatus.ACTIVE && match.lostCities.turnPlayer == player,
                tab = AppTab.MATCH,
                statusMessage = describeMatch(match, player),
                selectedCardId = null,
                turnHintCards = false,
                newlyAcquiredCardIds = emptySet(),
                rematchPeer = rematchPeer,
            )
        }
    }

    fun refreshNearbyPeers(forceFull: Boolean = true) {
        when (_uiState.value.lobbyTransport) {
            LobbyTransport.LAN -> refreshDiscoveredHosts(forceFull)
            LobbyTransport.BLUETOOTH -> refreshBluetoothPeers(forceFull)
            LobbyTransport.SOLO -> Unit
        }
    }

    fun refreshDiscoveredHosts(forceFull: Boolean = true) {
        viewModelScope.launch {
            refreshDiscoveredHostsInternal(forceFullScan = forceFull)
        }
    }

    fun refreshBluetoothPeers(forceDiscovery: Boolean = true) {
        viewModelScope.launch {
            refreshBluetoothPeersInternal(forceDiscovery = forceDiscovery)
        }
    }

    private suspend fun refreshDiscoveredHostsInternal(forceFullScan: Boolean = false) {
        scanMutex.withLock {
            val initialState = _uiState.value
            val shouldRunFullScan = forceFullScan ||
                initialState.discoveredHosts.isEmpty() ||
                initialState.localAddresses.isEmpty()
            if (shouldRunFullScan) {
                _uiState.update { it.copy(scanning = true) }
            }
            try {
                val state = _uiState.value
                val localIps = if (shouldRunFullScan) {
                    withContext(Dispatchers.Default) {
                        lanScanner.localIPv4Addresses().toList().sorted()
                    }
                } else {
                    state.localAddresses
                }
                val visibleHosts = if (shouldRunFullScan) {
                    val rawHosts = withContext(Dispatchers.IO) {
                        runCatching { lanScanner.scan(state.serverPort) }.getOrDefault(emptyList())
                    }
                    lastLobbyFullScanElapsedMs = SystemClock.elapsedRealtime()
                    dedupeVisibleHosts(
                        hosts = rawHosts,
                        localIps = localIps.toSet(),
                        localServerId = state.localServerId,
                    )
                } else {
                    state.discoveredHosts
                }

                val invites = withContext(Dispatchers.IO) {
                    buildList {
                        for (host in visibleHosts) {
                            val response = runCatching {
                                lanClient.inviteList(host.address, host.port, state.localServerId)
                            }.getOrNull() ?: continue
                            response.invites.forEach { item ->
                                add(inviteFromRemote(host, item))
                            }
                        }
                    }
                }

                val latestPendingInvite = invites
                    .filter { it.status.equals("pending", ignoreCase = true) }
                    .maxByOrNull { it.createdAtEpochMs }

                _uiState.update {
                    it.copy(
                        discoveredHosts = visibleHosts,
                        localAddresses = localIps,
                        pendingInvites = invites,
                        activeInvite = if (it.connected) null else latestPendingInvite,
                        scanning = false,
                    )
                }
            } finally {
                _uiState.update { current ->
                    if (current.scanning) current.copy(scanning = false) else current
                }
            }
        }
    }

    private suspend fun refreshBluetoothPeersInternal(forceDiscovery: Boolean = false) {
        scanMutex.withLock {
            refreshBluetoothAvailability()
            val initialState = _uiState.value
            if (!initialState.bluetoothSupported) {
                _uiState.update {
                    it.copy(
                        bluetoothPeers = emptyList(),
                        bluetoothScanning = false,
                    )
                }
                return
            }
            if (!initialState.bluetoothEnabled) {
                _uiState.update {
                    it.copy(
                        bluetoothPeers = emptyList(),
                        bluetoothScanning = false,
                    )
                }
                return
            }

            if (forceDiscovery) {
                _uiState.update { it.copy(bluetoothScanning = true) }
            }
            try {
                val state = _uiState.value
                val rawDevices = if (forceDiscovery) {
                    withContext(Dispatchers.IO) {
                        runCatching { bluetoothScanner.scanNearby() }.getOrElse { emptyList() }
                    }
                } else {
                    val current = state.bluetoothPeers.map { peer ->
                        BluetoothScanner.NearbyDevice(
                            address = peer.address,
                            name = peer.name,
                            bonded = peer.bonded,
                        )
                    }
                    (current + runCatching { bluetoothScanner.bondedDevices() }.getOrDefault(emptyList()))
                        .distinctBy { it.address }
                }

                val visiblePeers = withContext(Dispatchers.IO) {
                    coroutineScope {
                        rawDevices.distinctBy { it.address }
                            .map { device ->
                                async {
                                    val ping = runCatching { bluetoothClient.ping(device.address) }.getOrNull()
                                    BluetoothDiscoveredPeer(
                                        address = device.address,
                                        name = device.name,
                                        ping = ping,
                                        bonded = device.bonded,
                                        isSelf = ping?.serverId == state.localServerId,
                                    )
                                }
                            }
                            .awaitAll()
                    }.sortedWith(
                        compareBy<BluetoothDiscoveredPeer> { !it.bonded }
                            .thenByDescending { it.reachable }
                            .thenBy { it.name.lowercase(Locale.ROOT) }
                            .thenBy { it.address },
                    )
                }
                lastBluetoothRefreshElapsedMs = SystemClock.elapsedRealtime()

                val invites = withContext(Dispatchers.IO) {
                    buildList {
                        for (peer in visiblePeers.filter { it.reachable && !it.isSelf }) {
                            val response = runCatching {
                                bluetoothClient.inviteList(peer.address, state.localServerId)
                            }.getOrNull() ?: continue
                            response.invites.forEach { item ->
                                add(inviteFromBluetoothRemote(peer, item))
                            }
                        }
                    }
                }

                val latestPendingInvite = invites
                    .filter { it.status.equals("pending", ignoreCase = true) }
                    .maxByOrNull { it.createdAtEpochMs }

                _uiState.update {
                    it.copy(
                        bluetoothPeers = visiblePeers,
                        pendingInvites = invites,
                        activeInvite = if (it.connected) null else latestPendingInvite,
                        bluetoothScanning = false,
                    )
                }
            } finally {
                _uiState.update { current ->
                    if (current.bluetoothScanning) current.copy(bluetoothScanning = false) else current
                }
            }
        }
    }

    private fun dedupeVisibleHosts(
        hosts: List<LanScanner.DiscoveredHost>,
        localIps: Set<String>,
        localServerId: String,
    ): List<LanScanner.DiscoveredHost> {
        return hosts
            .filterNot { isSelfDiscoveredHost(it, localIps, localServerId) }
            .sortedWith(
                compareBy<LanScanner.DiscoveredHost> { addressPreference(it.address, localIps) }
                    .thenBy { it.address },
            )
            .distinctBy { host ->
                host.ping.serverId.ifBlank { host.address }
            }
    }

    private fun addressPreference(address: String, localIps: Set<String>): Int {
        val normalized = address.trim()
        if (localIps.any { sameSubnet(it, normalized) }) return 0
        if (normalized == LanScanner.EmulatorGateway) return 1
        return 2
    }

    private fun sameSubnet(left: String, right: String): Boolean {
        val a = left.split('.')
        val b = right.split('.')
        return a.size == 4 && b.size == 4 && a.take(3) == b.take(3)
    }

    private fun inviteFromRemote(host: LanScanner.DiscoveredHost, item: InviteListItem): LobbyInvite {
        return LobbyInvite(
            id = item.id,
            fromName = item.fromName,
            transport = PeerTransport.LAN,
            hostAddress = host.address,
            hostPort = host.port,
            createdAtEpochMs = item.createdAtEpochMs,
            status = item.status,
            rules = item.rules ?: GameRules(),
        )
    }

    private fun inviteFromBluetoothRemote(peer: BluetoothDiscoveredPeer, item: InviteListItem): LobbyInvite {
        return LobbyInvite(
            id = item.id,
            fromName = item.fromName,
            transport = PeerTransport.BLUETOOTH,
            hostAddress = peer.address,
            hostPort = 0,
            createdAtEpochMs = item.createdAtEpochMs,
            status = item.status,
            rules = item.rules ?: GameRules(),
        )
    }

    private fun isSelfDiscoveredHost(
        host: LanScanner.DiscoveredHost,
        localIps: Set<String>,
        localServerId: String,
    ): Boolean {
        if (host.ping.serverId.isNotBlank() && host.ping.serverId == localServerId) {
            return true
        }
        return isKnownSelfAddress(host.address, localIps)
    }

    private fun isKnownSelfAddress(address: String, localIps: Set<String> = _uiState.value.localAddresses.toSet()): Boolean {
        val normalized = address.trim()
        return when (normalized) {
            "", LOCAL_HOST, "localhost" -> true
            else -> normalized in localIps
        }
    }

    private fun peerEndpointOf(host: LanScanner.DiscoveredHost): PeerEndpoint {
        return PeerEndpoint(
            transport = PeerTransport.LAN,
            address = host.address,
            port = host.port,
            displayName = host.ping.hostName,
            serverId = host.ping.serverId,
        )
    }

    private fun peerEndpointOf(peer: BluetoothDiscoveredPeer): PeerEndpoint {
        return PeerEndpoint(
            transport = PeerTransport.BLUETOOTH,
            address = peer.address,
            port = null,
            displayName = peer.name.ifBlank { peer.ping?.hostName.orEmpty() },
            serverId = peer.ping?.serverId,
        )
    }

    private fun peerEndpointOf(invite: LobbyInvite): PeerEndpoint {
        val resolvedServerId = when (invite.transport) {
            PeerTransport.LAN -> _uiState.value.discoveredHosts.firstOrNull { host ->
                host.address == invite.hostAddress && host.port == invite.hostPort
            }?.ping?.serverId

            PeerTransport.BLUETOOTH -> _uiState.value.bluetoothPeers.firstOrNull { peer ->
                peer.address == invite.hostAddress
            }?.ping?.serverId
        }
        return PeerEndpoint(
            transport = invite.transport,
            address = invite.hostAddress,
            port = invite.hostPort.takeIf { it > 0 },
            displayName = invite.fromName,
            serverId = resolvedServerId,
        )
    }

    private fun labelForPeer(peer: PeerEndpoint): String {
        return when (peer.transport) {
            PeerTransport.LAN -> peer.displayName.ifBlank {
                "${peer.address}:${peer.port ?: _uiState.value.serverPort}"
            }

            PeerTransport.BLUETOOTH -> peer.displayName.ifBlank { peer.address }
        }
    }

    private fun resolvePeerEndpoint(peer: PeerEndpoint): PeerEndpoint {
        return when (peer.transport) {
            PeerTransport.LAN -> {
                _uiState.value.discoveredHosts.firstOrNull { host ->
                    host.address == peer.address && (peer.port == null || host.port == peer.port)
                }?.let(::peerEndpointOf) ?: peer
            }

            PeerTransport.BLUETOOTH -> {
                _uiState.value.bluetoothPeers.firstOrNull { candidate ->
                    candidate.address == peer.address
                }?.let(::peerEndpointOf) ?: peer
            }
        }
    }

    private suspend fun leaveSession(session: ClientSession): Boolean {
        return when (session.transport) {
            PeerTransport.LAN -> lanClient.leave(session)
            PeerTransport.BLUETOOTH -> bluetoothClient.leave(session)
        }
    }

    fun selectCard(cardId: String?) {
        val state = _uiState.value
        val session = state.session ?: return
        val match = state.match ?: return
        if (!state.canAct || match.status != MatchStatus.ACTIVE || match.lostCities.phase != LostCitiesTurnPhase.PLAY) {
            return
        }
        if (match.lostCities.players[session.player]?.hand?.contains(cardId) != true) return
        _uiState.update {
            it.copy(
                selectedCardId = if (it.selectedCardId == cardId) null else cardId,
                turnHintCards = false,
            )
        }
    }

    fun legalPlaySuitsForSelectedCard(): Set<String> {
        val state = _uiState.value
        val session = state.session ?: return emptySet()
        val match = state.match ?: return emptySet()
        val selectedCardId = state.selectedCardId ?: return emptySet()
        if (!state.canAct || match.status != MatchStatus.ACTIVE || match.lostCities.phase != LostCitiesTurnPhase.PLAY) {
            return emptySet()
        }

        val playerState = match.lostCities.players[session.player] ?: return emptySet()
        val card = state.cardById[selectedCardId] ?: return emptySet()
        if (!playerState.hand.contains(selectedCardId)) return emptySet()

        val column = playerState.expeditions[card.suit].orEmpty()
        return if (canPlaceInExpedition(card, column, state.cardById)) {
            setOf(card.suit.lowercase(Locale.ROOT))
        } else {
            emptySet()
        }
    }

    private fun canPlaceInExpedition(
        card: LostCitiesDeckCard,
        existingColumn: List<String>,
        cardById: Map<String, LostCitiesDeckCard>,
    ): Boolean {
        val existingRanks = existingColumn.mapNotNull { existingId ->
            cardById[existingId]?.rank
        }
        if (card.rank == null) {
            return existingRanks.isEmpty()
        }
        val maxRank = existingRanks.maxOrNull()
        return maxRank == null || card.rank > maxRank
    }

    fun placeSelectedCardToSuit(suit: String) {
        val state = _uiState.value
        val session = state.session ?: return
        val match = state.match ?: return
        val selectedCard = state.selectedCardId ?: return
        val normalizedSuit = suit.trim().lowercase(Locale.ROOT)

        if (match.status != MatchStatus.ACTIVE || match.lostCities.turnPlayer != session.player) {
            _uiState.update { it.copy(statusMessage = "It is not your turn.") }
            return
        }
        if (match.lostCities.phase != LostCitiesTurnPhase.PLAY) {
            _uiState.update { it.copy(statusMessage = "Choose a draw source to finish the turn.") }
            return
        }
        if (!legalPlaySuitsForSelectedCard().contains(normalizedSuit)) {
            _uiState.update { it.copy(statusMessage = "That expedition is not legal for the selected card.") }
            return
        }

        performAction("play_expedition", selectedCard, normalizedSuit)
    }

    fun discardSelectedCard() {
        val state = _uiState.value
        val session = state.session ?: return
        val match = state.match ?: return
        val selectedCard = state.selectedCardId ?: return

        if (match.status != MatchStatus.ACTIVE || match.lostCities.turnPlayer != session.player) {
            _uiState.update { it.copy(statusMessage = "It is not your turn.") }
            return
        }
        if (match.lostCities.phase != LostCitiesTurnPhase.PLAY) {
            _uiState.update { it.copy(statusMessage = "Choose a draw source to finish the turn.") }
            return
        }

        performAction("discard", selectedCard, null)
    }

    fun drawFromDeck() {
        val state = _uiState.value
        val session = state.session ?: return
        val match = state.match ?: return
        if (match.status != MatchStatus.ACTIVE || match.lostCities.turnPlayer != session.player) {
            _uiState.update { it.copy(statusMessage = "It is not your turn.") }
            return
        }
        if (match.lostCities.phase != LostCitiesTurnPhase.DRAW) {
            _uiState.update { it.copy(statusMessage = "Play or discard a card first.") }
            return
        }
        if (match.lostCities.deck.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "The draw pile is empty.") }
            return
        }

        performAction("draw_deck", "", null)
    }

    fun drawFromDiscard(suit: String) {
        val state = _uiState.value
        val session = state.session ?: return
        val match = state.match ?: return
        if (match.status != MatchStatus.ACTIVE || match.lostCities.turnPlayer != session.player) {
            _uiState.update { it.copy(statusMessage = "It is not your turn.") }
            return
        }
        if (match.lostCities.phase != LostCitiesTurnPhase.DRAW) {
            _uiState.update { it.copy(statusMessage = "Play or discard a card first.") }
            return
        }

        performAction("draw_discard", "", suit.lowercase(Locale.ROOT))
    }

    fun undoPendingMove() {
        val state = _uiState.value
        val session = state.session ?: return
        val match = state.match ?: return
        if (!canUndoPendingMove()) {
            _uiState.update { it.copy(statusMessage = "Undo is only available before drawing.") }
            return
        }
        if (match.lostCities.turnPlayer != session.player) {
            _uiState.update { it.copy(statusMessage = "It is not your turn.") }
            return
        }

        performAction("undo", "", null)
    }

    private suspend fun pollCurrentSession(session: ClientSession): PollResponse {
        return when (session.transport) {
            PeerTransport.LAN -> lanClient.poll(session)
            PeerTransport.BLUETOOTH -> bluetoothClient.poll(session)
        }
    }

    private fun performAction(action: String, cardId: String, suit: String?) {
        val state = _uiState.value
        val session = state.session ?: return
        viewModelScope.launch {
            val response = runCatching {
                when (session.transport) {
                    PeerTransport.LAN -> lanClient.lostCitiesAction(session, action, cardId, suit)
                    PeerTransport.BLUETOOTH -> bluetoothClient.lostCitiesAction(session, action, cardId, suit)
                }
            }.getOrNull()
            if (response == null || !response.ok || response.match == null) {
                _uiState.update { it.copy(statusMessage = response?.error ?: "Action rejected.") }
                return@launch
            }

            val match = response.match
            applyLocalMatchUpdate(match)
            maybeRunSoloAi(match)
        }
    }

    private fun applyLocalMatchUpdate(match: MatchState) {
        val session = _uiState.value.session ?: return
        val oldHand = _uiState.value.match?.lostCities?.players?.get(session.player)?.hand.orEmpty()
        val newHand = match.lostCities.players[session.player]?.hand.orEmpty()
        val acquired = newHand.filterNot(oldHand::contains).toSet()

        _uiState.update {
            it.copy(
                match = match,
                canAct = match.status == MatchStatus.ACTIVE && match.lostCities.turnPlayer == session.player,
                selectedCardId = null,
                newlyAcquiredCardIds = acquired,
                turnHintCards = match.status == MatchStatus.ACTIVE &&
                    match.lostCities.turnPlayer == session.player &&
                    match.lostCities.phase == LostCitiesTurnPhase.PLAY,
                statusMessage = match.lastEvent.ifBlank { describeMatch(match, session.player) },
            )
        }
    }

    private fun maybeRunSoloAi(match: MatchState?) {
        val aiToken = soloAiToken ?: return
        val aiPlayer = soloAiPlayer ?: return
        val session = _uiState.value.session ?: return
        if (_uiState.value.lobbyTransport != LobbyTransport.SOLO || !_uiState.value.connected) return
        if (match == null || match.status != MatchStatus.ACTIVE || match.lostCities.turnPlayer != aiPlayer) return

        soloAiJob?.cancel()
        soloAiJob = viewModelScope.launch {
            delay(280L)
            while (isActive) {
                val currentMatch = hostManager?.snapshot() ?: break
                if (currentMatch.status != MatchStatus.ACTIVE || currentMatch.lostCities.turnPlayer != aiPlayer) {
                    break
                }

                val plan = LostCitiesSoloAi.chooseTurn(
                    match = currentMatch,
                    aiPlayer = aiPlayer,
                    cardById = _uiState.value.cardById,
                    activeSuits = suitOrderIds(),
                    rules = currentMatch.rules,
                )
                if (plan == null) {
                    _uiState.update { it.copy(statusMessage = "Solo AI could not find a legal move.") }
                    break
                }

                var updatedMatch = currentMatch
                for (step in plan.steps) {
                    val response = hostManager?.applyLostCitiesAction(
                        token = aiToken,
                        action = step.action,
                        cardId = step.cardId,
                        suit = step.suit,
                    )
                    if (response == null || !response.ok || response.match == null) {
                        _uiState.update { it.copy(statusMessage = response?.error ?: "Solo AI action failed.") }
                        return@launch
                    }
                    updatedMatch = response.match
                }

                applyLocalMatchUpdate(updatedMatch)
                if (updatedMatch.status != MatchStatus.ACTIVE || updatedMatch.lostCities.turnPlayer != aiPlayer) {
                    break
                }
                if (updatedMatch.lostCities.turnPlayer == session.player) {
                    break
                }
                delay(160L)
            }
        }
    }

    fun canDrawDiscard(suit: String): Boolean {
        val match = _uiState.value.match ?: return false
        if (match.status != MatchStatus.ACTIVE || match.lostCities.phase != LostCitiesTurnPhase.DRAW) return false
        val pile = match.lostCities.discardPiles[suit].orEmpty()
        if (pile.isEmpty()) return false
        return pile.last() != match.lostCities.justDiscardedCardId
    }

    fun canDrawDeck(): Boolean {
        val match = _uiState.value.match ?: return false
        return match.status == MatchStatus.ACTIVE &&
            match.lostCities.phase == LostCitiesTurnPhase.DRAW &&
            match.lostCities.deck.isNotEmpty()
    }

    fun canUndoPendingMove(): Boolean {
        val state = _uiState.value
        val session = state.session ?: return false
        val match = state.match ?: return false
        return state.canAct &&
            match.status == MatchStatus.ACTIVE &&
            match.lostCities.turnPlayer == session.player &&
            match.lostCities.phase == LostCitiesTurnPhase.DRAW &&
            match.lostCities.finalTurnsRemaining == 0
    }

    fun canPlayHandCard(cardId: String): Boolean {
        val state = _uiState.value
        val session = state.session ?: return false
        val match = state.match ?: return false
        if (!state.canAct || match.status != MatchStatus.ACTIVE) return false
        if (match.lostCities.turnPlayer != session.player || match.lostCities.phase != LostCitiesTurnPhase.PLAY) {
            return false
        }

        val playerState = match.lostCities.players[session.player] ?: return false
        if (!playerState.hand.contains(cardId)) return false
        val card = state.cardById[cardId] ?: return false
        val expedition = playerState.expeditions[card.suit].orEmpty()
        return canPlaceInExpedition(card, expedition, state.cardById)
    }

    fun canSafelyDiscardHandCard(cardId: String): Boolean {
        val state = _uiState.value
        val session = state.session ?: return false
        val match = state.match ?: return false
        if (!state.canAct || match.status != MatchStatus.ACTIVE) return false
        if (match.lostCities.turnPlayer != session.player || match.lostCities.phase != LostCitiesTurnPhase.PLAY) {
            return false
        }

        val playerState = match.lostCities.players[session.player] ?: return false
        if (!playerState.hand.contains(cardId)) return false
        val card = state.cardById[cardId] ?: return false
        val opponentState = match.lostCities.players[otherPlayer(session.player)] ?: return false
        val opponentExpedition = opponentState.expeditions[card.suit].orEmpty()
        return !canPlaceInExpedition(card, opponentExpedition, state.cardById)
    }

    fun projectedRemainingPlaysForLocalPlayer(maximizeLocalTurns: Boolean): Int {
        val state = _uiState.value
        val session = state.session ?: return 0
        val match = state.match ?: return 0
        if (match.status != MatchStatus.ACTIVE) return 0

        return simulateRemainingPlays(
            state = match.lostCities,
            targetPlayer = session.player,
            maximizeLocalTurns = maximizeLocalTurns,
        )
    }

    fun suitOrder(): List<LostCitiesSuitConfig> {
        val manifest = _uiState.value.manifest ?: return emptyList()
        return manifest.suits.filter { suit ->
            _uiState.value.usePurple || suit.id.lowercase(Locale.ROOT) != "purple"
        }
    }

    private fun suitOrderIds(): List<String> = suitOrder().map { it.id.lowercase(Locale.ROOT) }

    fun aiHelpSuggestionForCurrentTurn(): AiHelpSuggestion? {
        val state = _uiState.value
        if (!state.aiHelpEnabled || !state.connected || !state.canAct || state.cardById.isEmpty()) {
            return null
        }
        val session = state.session ?: return null
        val match = state.match ?: return null
        if (match.status != MatchStatus.ACTIVE || match.lostCities.turnPlayer != session.player) {
            return null
        }
        val firstStep =
            LostCitiesSoloAi.chooseTurn(
                match = match,
                aiPlayer = session.player,
                cardById = state.cardById,
                activeSuits = suitOrderIds(),
                rules = match.rules,
            )?.steps?.firstOrNull() ?: return null
        val normalizedSuit =
            when (firstStep.action) {
                "play_expedition", "discard" -> firstStep.cardId
                    .takeUnless(String::isBlank)
                    ?.let { state.cardById[it]?.suit }
                else -> firstStep.suit
            }?.lowercase(Locale.ROOT)
        return AiHelpSuggestion(
            action = firstStep.action,
            cardId = firstStep.cardId.takeUnless(String::isBlank),
            suit = normalizedSuit,
        )
    }

    private fun simulateRemainingPlays(
        state: LostCitiesMatchState,
        targetPlayer: Int,
        maximizeLocalTurns: Boolean,
    ): Int {
        var plays = 0
        var turnPlayer = state.turnPlayer
        var phase = state.phase
        var deckCount = state.deck.size
        var finalTurnsRemaining = state.finalTurnsRemaining
        var guard = (deckCount + finalTurnsRemaining + 8) * 6

        while (guard-- > 0) {
            when (phase) {
                LostCitiesTurnPhase.PLAY -> {
                    if (turnPlayer == targetPlayer) {
                        plays += 1
                    }
                    if (finalTurnsRemaining > 0) {
                        finalTurnsRemaining = (finalTurnsRemaining - 1).coerceAtLeast(0)
                        if (finalTurnsRemaining == 0) {
                            break
                        }
                        turnPlayer = otherPlayer(turnPlayer)
                        phase = LostCitiesTurnPhase.PLAY
                    } else {
                        phase = LostCitiesTurnPhase.DRAW
                    }
                }

                LostCitiesTurnPhase.DRAW -> {
                    val consumesDeck = deckCount > 0 && (!maximizeLocalTurns || turnPlayer != targetPlayer)
                    if (consumesDeck) {
                        deckCount -= 1
                        val lastDeckCardDrawn = deckCount == 0
                        turnPlayer = otherPlayer(turnPlayer)
                        phase = LostCitiesTurnPhase.PLAY
                        if (lastDeckCardDrawn) {
                            finalTurnsRemaining = 2
                        }
                    } else {
                        turnPlayer = otherPlayer(turnPlayer)
                        phase = LostCitiesTurnPhase.PLAY
                    }
                }
            }
        }

        return plays
    }

    private fun handForPlayer(player: Int): List<String> {
        val match = _uiState.value.match ?: return emptyList()
        return match.lostCities.players[player]?.hand.orEmpty()
    }

    fun sortedHandForPlayer(player: Int): List<String> {
        val suitIndex = suitOrderIds().mapIndexed { index, suitId -> suitId to index }.toMap()
        return handForPlayer(player).sortedWith(
            compareBy<String> { cardId ->
                val suit = _uiState.value.cardById[cardId]?.suit?.lowercase(Locale.ROOT)
                suitIndex[suit] ?: Int.MAX_VALUE
            }.thenBy { cardId ->
                _uiState.value.cardById[cardId]?.rank ?: -1
            }.thenBy { cardId ->
                _uiState.value.cardById[cardId]?.id ?: cardId
            },
        )
    }

    fun expeditionCards(player: Int, suit: String): List<String> {
        val match = _uiState.value.match ?: return emptyList()
        return match.lostCities.players[player]?.expeditions?.get(suit).orEmpty()
    }

    fun cardPath(cardId: String): String? {
        val card = _uiState.value.cardById[cardId] ?: return null
        val normalized = card.path.trim().trimStart('/').removePrefix("assets/")
        val assetRoot = cardAssetRoot()
        return when {
            normalized.startsWith("$CLASSIC_ASSET_ROOT/") -> assetRoot + normalized.removePrefix(CLASSIC_ASSET_ROOT)
            normalized.startsWith("$V2_ASSET_ROOT/") -> assetRoot + normalized.removePrefix(V2_ASSET_ROOT)
            normalized.startsWith("$V3_ASSET_ROOT/") -> assetRoot + normalized.removePrefix(V3_ASSET_ROOT)
            else -> "$assetRoot/$normalized"
        }
    }

    fun cardBackPath(): String = "${cardAssetRoot()}/cards/png/card_back.png"

    private fun cardAssetRoot(): String {
        return when (_uiState.value.cardArtPack) {
            CardArtPack.CLASSIC -> CLASSIC_ASSET_ROOT
            CardArtPack.V2 -> V2_ASSET_ROOT
            CardArtPack.V3 -> V3_ASSET_ROOT
        }
    }

    fun cardSuit(cardId: String?): String? {
        return cardId?.let { _uiState.value.cardById[it]?.suit?.lowercase(Locale.ROOT) }
    }

    fun cardTopText(cardId: String): String {
        val card = _uiState.value.cardById[cardId] ?: return cardId
        return card.rank?.toString() ?: "X"
    }

    fun expeditionScore(player: Int, suit: String): Int {
        return expeditionScoreBreakdown(player, suit).total
    }

    fun expeditionScoreBreakdown(player: Int, suit: String): LostCitiesExpeditionScoreBreakdown {
        return LostCitiesScoring.expeditionBreakdown(
            cardIds = expeditionCards(player, suit),
            cardById = _uiState.value.cardById,
        )
    }

    fun suitLabel(suit: String): String {
        val manifest = _uiState.value.manifest ?: return suit.replaceFirstChar { char ->
            if (char.isLowerCase()) char.uppercaseChar() else char
        }
        val match = manifest.suits.firstOrNull { it.id == suit }
        return match?.officialColor ?: suit.replaceFirstChar { char ->
            if (char.isLowerCase()) char.uppercaseChar() else char
        }
    }

    fun scoreOf(player: Int): Int {
        return _uiState.value.match?.score?.get(player) ?: 0
    }

    fun requestRematch() {
        val state = _uiState.value
        val match = state.match
        val session = state.session
        if (state.lobbyTransport == LobbyTransport.SOLO && state.connected && match?.status == MatchStatus.FINISHED && session != null) {
            val aiToken = soloAiToken
            if (aiToken == null) {
                _uiState.update { it.copy(statusMessage = "Solo rematch is not available.") }
                return
            }
            viewModelScope.launch {
                val requested = hostManager?.applyLostCitiesAction(session.token, "request_rematch", "", null)
                if (requested == null || !requested.ok) {
                    _uiState.update { it.copy(statusMessage = requested?.error ?: "Could not start the solo rematch.") }
                    return@launch
                }
                val accepted = hostManager?.applyLostCitiesAction(aiToken, "accept_rematch", "", null)
                if (accepted == null || !accepted.ok || accepted.match == null) {
                    _uiState.update { it.copy(statusMessage = accepted?.error ?: "Solo rematch failed.") }
                    return@launch
                }
                applyLocalMatchUpdate(accepted.match)
                maybeRunSoloAi(accepted.match)
            }
            return
        }
        if (state.connected && match?.status == MatchStatus.FINISHED && session != null) {
            performAction("request_rematch", "", null)
            return
        }
        val target = state.rematchPeer
        if (match == null || match.status != MatchStatus.FINISHED) {
            _uiState.update { it.copy(statusMessage = "Rematch is only available after the round ends.") }
            return
        }
        if (target == null) {
            _uiState.update { it.copy(statusMessage = "No opponent endpoint is available for a rematch.") }
            return
        }

        viewModelScope.launch {
            val currentSession = _uiState.value.session
            if (currentSession != null && (currentSession.transport != PeerTransport.LAN || currentSession.hostAddress != LOCAL_HOST)) {
                runCatching { leaveSession(currentSession) }
            }
            stopPollingLoop()
            ensureLocalLobbyServer()
            ensureBluetoothLobbyServer()
            val resolvedTarget = resolvePeerEndpoint(target)
            val playerName = _uiState.value.playerName.ifBlank { NameGenerator.generate() }
            val localSession = ensureLocalSession(playerName) ?: return@launch
            val inviteResult = runCatching {
                hostManager?.createInvite(
                    fromName = playerName,
                    targetServerId = resolvedTarget.serverId,
                    inviteRules = GameRules(usePurple = _uiState.value.usePurple),
                )
            }.getOrNull()
            if (inviteResult == null || !inviteResult.ok) {
                _uiState.update { it.copy(statusMessage = inviteResult?.error ?: "Could not send the rematch invite.") }
                return@launch
            }

            hostManager?.snapshot()?.let { snapshot ->
                runCatching { metadataStore.saveHost(snapshot) }
            }
            val waitingMatch = hostManager?.snapshot() ?: return@launch
            _uiState.update {
                it.copy(
                    connected = true,
                    session = localSession,
                    match = waitingMatch,
                    canAct = false,
                    tab = AppTab.MATCH,
                    selectedCardId = null,
                    newlyAcquiredCardIds = emptySet(),
                    turnHintCards = false,
                    statusMessage = "Rematch invite sent to ${labelForPeer(resolvedTarget)}.",
                    rematchPeer = resolvedTarget,
                )
            }
            startPollingLoop()
        }
    }

    fun acceptRematch() {
        val state = _uiState.value
        val match = state.match ?: return
        if (!state.connected || match.status != MatchStatus.FINISHED) {
            _uiState.update { it.copy(statusMessage = "No rematch offer is active.") }
            return
        }
        performAction("accept_rematch", "", null)
    }

    fun denyRematch() {
        val state = _uiState.value
        val match = state.match ?: return
        if (!state.connected || match.status != MatchStatus.FINISHED) {
            _uiState.update { it.copy(statusMessage = "No rematch offer is active.") }
            return
        }
        performAction("deny_rematch", "", null)
    }

    fun disconnect() {
        stopPollingLoop()
        soloAiJob?.cancel()
        viewModelScope.launch {
            _uiState.value.session?.let { session ->
                runCatching { leaveSession(session) }
            }
            soloAiToken?.let { token ->
                runCatching { hostManager?.removeToken(token) }
            }
            soloAiToken = null
            soloAiPlayer = null
            _uiState.update {
                it.copy(
                    connected = false,
                    canAct = false,
                    session = null,
                    match = null,
                    tab = AppTab.LOBBY,
                    selectedCardId = null,
                    newlyAcquiredCardIds = emptySet(),
                    turnHintCards = false,
                    statusMessage = "Disconnected.",
                )
            }
            refreshNearbyPeers(forceFull = false)
        }
    }

    private fun describeMatch(match: MatchState?, localPlayer: Int): String {
        if (match == null) return "No match data."
        return when (match.status) {
            MatchStatus.WAITING -> when (match.players.size) {
                0 -> "Invite a peer to start."
                1 -> "Waiting for an invited player to join."
                else -> "Waiting to start."
            }

            MatchStatus.ACTIVE -> {
                if (match.lostCities.finalTurnsRemaining > 0) {
                    val turnLabel = if (match.lostCities.turnPlayer == localPlayer) {
                        "Your final turn"
                    } else {
                        "Opponent final turn"
                    }
                    "$turnLabel. Play one last card; no draw."
                } else {
                    val phaseLabel = if (match.lostCities.phase == LostCitiesTurnPhase.PLAY) "play" else "draw"
                    val turnLabel = if (match.lostCities.turnPlayer == localPlayer) "Your turn" else "Opponent turn"
                    "$turnLabel. $phaseLabel phase."
                }
            }

            MatchStatus.FINISHED -> {
                val p1 = match.score[1] ?: 0
                val p2 = match.score[2] ?: 0
                if (p1 == p2) {
                    "Round over. Draw ($p1-$p2)."
                } else {
                    val winner = if (p1 > p2) 1 else 2
                    if (winner == localPlayer) {
                        "Round over. You win ($p1-$p2)."
                    } else {
                        "Round over. Opponent wins ($p2-$p1)."
                    }
                }
            }

            MatchStatus.ABORTED -> "Match aborted."
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPollingLoop()
        scanJob?.cancel()
        hostServer?.stop()
        hostServer = null
        bluetoothHostServer?.stop()
        bluetoothHostServer = null
        bluetoothClient.closeMatchConnection()
    }

    companion object {
        private const val LOCAL_HOST = "127.0.0.1"
        private const val CLASSIC_ASSET_ROOT = "lost_cities"
        private const val V2_ASSET_ROOT = "lost_cities_v2"
        private const val V3_ASSET_ROOT = "lost_cities_v3"
        private const val POLL_INTERVAL_MS = 100L
        private const val LOBBY_REFRESH_INTERVAL_MS = 100L
        private const val FULL_LOBBY_SCAN_INTERVAL_MS = 1_000L
        private const val BLUETOOTH_REFRESH_INTERVAL_MS = 1_000L
        private const val IDLE_LOOP_INTERVAL_MS = 100L

        private fun otherPlayer(player: Int): Int = if (player == 1) 2 else 1
    }
}
