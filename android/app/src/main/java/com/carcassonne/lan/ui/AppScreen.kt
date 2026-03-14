package com.carcassonne.lan.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carcassonne.lan.data.CardArtPack
import com.carcassonne.lan.model.PeerTransport
import com.carcassonne.lan.model.LostCitiesTurnPhase
import com.carcassonne.lan.model.MatchStatus
import com.carcassonne.lan.network.LanScanner
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val darkStone = Color(0xFF2A211A)
private val darkStoneMid = Color(0xFF403127)
private val darkStoneLight = Color(0xFF584335)
private val darkStonePatch = Color(0xFF73604E)
private val boardBorder = Brush.linearGradient(listOf(Color(0xFF90745A), Color(0xFF5A4635), Color(0xFF927556)))
private val warmText = Color(0xFFF1E5D1)
private val accent = Color(0xFFD7A75C)
private val actionHighlight = Color(0xFF39D7A3)
private val drawHighlight = Color(0xFFF0BD52)
private val selectedHighlight = Color(0xFFE14B40)
private val turnHighlight = Color(0xFFE0B15B)
private val promptGreen = Color(0xFF5CFF9F)
private val promptRed = Color(0xFFFF6B6B)
private val forecastTint = Color(0xFFE4CB78)
private val classicCardBackAsset = "lost_cities/cards/png/card_back.png"
private val v2CardBackAsset = "lost_cities_v2/cards/png/card_back.png"
private val v3CardBackAsset = "lost_cities_v3/cards/png/card_back.png"
private const val appBackgroundAsset = "lost_cities_v2/backgrounds/dark_stone_portrait_1600x2560.png"

private enum class HandCardHint {
    NONE,
    PLAYABLE,
    SAFE_DISCARD,
    PLAYABLE_AND_SAFE_DISCARD,
}

private data class CardCollectionOverlayState(
    val title: String,
    val cards: List<String>,
)

private enum class BluetoothUiAction {
    NONE,
    SCAN,
    DISCOVERABLE,
}

@Composable
fun LostCitiesAppRoot(vm: AppViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val activeInvite = state.activeInvite
    val incomingRematchRequester = state.match
        ?.takeIf { state.connected && it.status == MatchStatus.FINISHED }
        ?.rematchOfferedByPlayer
        ?.takeIf { requester -> requester != state.session?.player }
    val incomingRematchName = incomingRematchRequester?.let { requester ->
        state.match?.players?.get(requester)?.name ?: "Opponent"
    } ?: "Opponent"
    var topChromeVisible by rememberSaveable { mutableStateOf(true) }
    var pendingBluetoothAction by remember { mutableStateOf(BluetoothUiAction.NONE) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val safeTopPadding = with(density) {
        WindowInsets.statusBars.getTop(this).toDp()
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        vm.refreshBluetoothAvailability()
        if (state.lobbyTransport == LobbyTransport.BLUETOOTH) {
            vm.refreshBluetoothPeers(forceDiscovery = false)
        }
    }
    val discoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        vm.refreshBluetoothAvailability()
    }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        vm.refreshBluetoothAvailability()
        val granted = grants.values.all { it }
        if (!granted) {
            pendingBluetoothAction = BluetoothUiAction.NONE
            return@rememberLauncherForActivityResult
        }
        when (pendingBluetoothAction) {
            BluetoothUiAction.SCAN -> vm.refreshBluetoothPeers(forceDiscovery = true)
            BluetoothUiAction.DISCOVERABLE -> {
                discoverableLauncher.launch(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300),
                )
            }

            BluetoothUiAction.NONE -> Unit
        }
        pendingBluetoothAction = BluetoothUiAction.NONE
    }

    fun requestBluetoothAction(action: BluetoothUiAction) {
        val permissions = when (action) {
            BluetoothUiAction.SCAN -> bluetoothScanPermissions()
            BluetoothUiAction.DISCOVERABLE -> bluetoothDiscoverablePermissions()
            BluetoothUiAction.NONE -> emptyArray()
        }
        val hasPermissions = permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (hasPermissions) {
            when (action) {
                BluetoothUiAction.SCAN -> vm.refreshBluetoothPeers(forceDiscovery = true)
                BluetoothUiAction.DISCOVERABLE -> {
                    discoverableLauncher.launch(
                        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300),
                    )
                }

                BluetoothUiAction.NONE -> Unit
            }
            return
        }
        pendingBluetoothAction = action
        bluetoothPermissionLauncher.launch(permissions)
    }

    LaunchedEffect(Unit) {
        vm.refreshBluetoothAvailability()
    }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = darkStone,
            surface = darkStoneLight,
            onSurface = warmText,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(darkStone)) {
            BackgroundAssetImage(
                path = appBackgroundAsset,
                modifier = Modifier.fillMaxSize(),
            )
            val hideTopChromeInMatch = state.tab == AppTab.MATCH
            val showTopChrome = !hideTopChromeInMatch || topChromeVisible
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                    .padding(top = safeTopPadding + 40.dp),
            ) {
                if (showTopChrome) {
                    AppTopTabs(
                        selected = state.tab,
                        onSelect = vm::selectTab,
                    )
                    TopChromeControls(
                        cardArtPack = state.cardArtPack,
                        onUpdateCardArtPack = vm::updateCardArtPack,
                    )
                }
                when (state.tab) {
                    AppTab.LOBBY -> LobbyScreen(
                        state = state,
                        onSelectLobbyTransport = vm::selectLobbyTransport,
                        onUpdatePlayerName = vm::updatePlayerName,
                        onUpdateServerPort = vm::updateServerPort,
                        onTogglePurple = vm::togglePurple,
                        onRefreshDiscovered = { vm.refreshDiscoveredHosts() },
                        onInviteDiscovered = vm::sendInviteToDiscoveredHost,
                        onRefreshBluetooth = { requestBluetoothAction(BluetoothUiAction.SCAN) },
                        onEnableBluetooth = {
                            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        },
                        onMakeBluetoothVisible = {
                            requestBluetoothAction(BluetoothUiAction.DISCOVERABLE)
                        },
                        onInviteBluetooth = vm::sendInviteToBluetoothPeer,
                        onRefreshBluetoothState = vm::refreshBluetoothAvailability,
                        onDisconnect = vm::disconnect,
                    )

                    AppTab.MATCH -> MatchScreen(
                        state = state,
                        vm = vm,
                        showTopBar = showTopChrome,
                    )
                }
                if (showTopChrome) {
                    HorizontalDivider(color = warmText.copy(alpha = 0.3f))
                    Text(
                        text = state.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = warmText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = (-2).dp)
                    .zIndex(2f),
            ) {
                Button(
                    onClick = { topChromeVisible = !topChromeVisible },
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF353535),
                        contentColor = warmText,
                    ),
                    modifier = Modifier.size(36.dp),
                ) {
                    Text(
                        text = if (topChromeVisible && showTopChrome) "▴" else "▾",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (activeInvite != null) {
                InviteDialog(
                    invite = activeInvite,
                    onAccept = { vm.acceptInvite(activeInvite) },
                    onDeny = { vm.denyInvite(activeInvite) },
                    onDismiss = vm::dismissActiveInvite,
                )
            } else if (incomingRematchRequester != null) {
                RematchDialog(
                    requesterName = incomingRematchName,
                    onAccept = vm::acceptRematch,
                    onDeny = vm::denyRematch,
                )
            }
        }
    }
}

@Composable
private fun BackgroundAssetImage(
    path: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            CardBitmapCache.load(context, listOf(path))?.asImageBitmap()
        }
    }

    bitmap?.let { image ->
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun AppTopTabs(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppPill(
            label = "Lobby",
            active = selected == AppTab.LOBBY,
            onClick = { onSelect(AppTab.LOBBY) },
        )
        AppPill(
            label = "Match",
            active = selected == AppTab.MATCH,
            onClick = { onSelect(AppTab.MATCH) },
        )
    }
}

@Composable
private fun TopChromeControls(
    cardArtPack: CardArtPack,
    onUpdateCardArtPack: (CardArtPack) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = darkStoneMid.copy(alpha = 0.88f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Card design",
                color = warmText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Serif,
            )
            AppPill(
                label = "v1",
                active = cardArtPack == CardArtPack.CLASSIC,
                onClick = { onUpdateCardArtPack(CardArtPack.CLASSIC) },
            )
            AppPill(
                label = "v2",
                active = cardArtPack == CardArtPack.V2,
                onClick = { onUpdateCardArtPack(CardArtPack.V2) },
            )
            AppPill(
                label = "v3",
                active = cardArtPack == CardArtPack.V3,
                onClick = { onUpdateCardArtPack(CardArtPack.V3) },
            )
        }
    }
}

@Composable
private fun AppPill(label: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .border(
                2.dp,
                brush = boardBorder,
                shape = RoundedCornerShape(22.dp),
            )
            .background(
                brush = if (active) {
                    Brush.horizontalGradient(listOf(Color(0xFF4A4A4A), Color(0xFF3C3C3C)))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xFF3C3C3C), Color(0xFF2F2F2F)))
                },
                shape = RoundedCornerShape(22.dp),
            ),
    ) {
        Text(
            text = label,
            color = warmText,
            style = TextStyle(fontFamily = FontFamily.Serif, shadow = Shadow(color = Color.Black, blurRadius = 0.2f)),
        )
    }
}

@Composable
private fun LobbyScreen(
    state: AppUiState,
    onSelectLobbyTransport: (LobbyTransport) -> Unit,
    onUpdatePlayerName: (String) -> Unit,
    onUpdateServerPort: (String) -> Unit,
    onTogglePurple: (Boolean) -> Unit,
    onRefreshDiscovered: () -> Unit,
    onInviteDiscovered: (LanScanner.DiscoveredHost) -> Unit,
    onRefreshBluetooth: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onMakeBluetoothVisible: () -> Unit,
    onInviteBluetooth: (BluetoothDiscoveredPeer) -> Unit,
    onRefreshBluetoothState: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var playerNameInput by remember(state.playerName) { mutableStateOf(state.playerName) }
    var portInput by remember(state.serverPort) { mutableStateOf(state.serverPort.toString()) }

    LaunchedEffect(state.playerName) { playerNameInput = state.playerName }
    LaunchedEffect(state.serverPort) { portInput = state.serverPort.toString() }
    LaunchedEffect(state.lobbyTransport) {
        if (state.lobbyTransport == LobbyTransport.BLUETOOTH) {
            onRefreshBluetoothState()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Lost Cities · Nearby Lobby",
            style = MaterialTheme.typography.headlineSmall,
            color = accent,
            fontFamily = FontFamily.Serif,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppPill(
                label = "LAN",
                active = state.lobbyTransport == LobbyTransport.LAN,
                onClick = { onSelectLobbyTransport(LobbyTransport.LAN) },
            )
            AppPill(
                label = "Bluetooth",
                active = state.lobbyTransport == LobbyTransport.BLUETOOTH,
                onClick = { onSelectLobbyTransport(LobbyTransport.BLUETOOTH) },
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.cardColors(containerColor = darkStoneMid),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedFieldRow(
                    value = playerNameInput,
                    onChange = {
                        playerNameInput = it
                        onUpdatePlayerName(it)
                    },
                    label = "Player name",
                    readOnly = state.connected,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                if (state.lobbyTransport == LobbyTransport.LAN) {
                    OutlinedFieldRow(
                        value = portInput,
                        onChange = { raw ->
                            val sanitized = raw.filter { it.isDigit() }.take(5)
                            portInput = sanitized
                            onUpdateServerPort(sanitized)
                        },
                        label = "Port",
                        readOnly = state.connected,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (state.lobbyTransport) {
                        LobbyTransport.LAN -> {
                            OutlinedButton(
                                onClick = onRefreshDiscovered,
                                enabled = !state.scanning,
                            ) {
                                Text(if (state.scanning) "Scanning..." else "Scan LAN")
                            }
                        }

                        LobbyTransport.BLUETOOTH -> {
                            if (!state.bluetoothEnabled) {
                                OutlinedButton(onClick = onEnableBluetooth) {
                                    Text("Enable Bluetooth")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onRefreshBluetooth,
                                    enabled = !state.bluetoothScanning,
                                ) {
                                    Text(if (state.bluetoothScanning) "Scanning..." else "Scan Bluetooth")
                                }
                                OutlinedButton(onClick = onMakeBluetoothVisible) {
                                    Text("Make visible")
                                }
                            }
                        }
                    }
                    if (state.scanning || state.bluetoothScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Purple variant", color = warmText)
            Switch(
                checked = state.usePurple,
                onCheckedChange = onTogglePurple,
                enabled = !state.connected,
            )
        }

        when (state.lobbyTransport) {
            LobbyTransport.LAN -> {
                val localIpsText = if (state.localAddresses.isEmpty()) "No local IPv4 detected" else state.localAddresses.joinToString()
                Text("This device: $localIpsText", style = MaterialTheme.typography.bodySmall, color = warmText)

                if (state.discoveredHosts.isEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = darkStoneMid),
                    ) {
                        Text(
                            text = "No peers found on this LAN yet.",
                            modifier = Modifier.padding(12.dp),
                            color = warmText,
                        )
                    }
                } else {
                    val availablePeers = state.discoveredHosts.filter { !it.isSelf }
                    if (availablePeers.isEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(12.dp)),
                            colors = CardDefaults.cardColors(containerColor = darkStoneMid),
                        ) {
                            Text(
                                text = "No other peers found on this LAN yet.",
                                modifier = Modifier.padding(12.dp),
                                color = warmText,
                            )
                        }
                    } else {
                        Text(
                            text = "Players on local network",
                            color = warmText,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Serif,
                        )
                        availablePeers.forEach { host ->
                            DiscoveredHostCard(
                                host = host,
                                onInvite = { onInviteDiscovered(host) },
                            )
                        }
                    }
                }
            }

            LobbyTransport.BLUETOOTH -> {
                val bluetoothLabel = when {
                    !state.bluetoothSupported -> "Bluetooth is not available on this device or emulator."
                    !state.bluetoothEnabled -> "Bluetooth is off. Enable it to scan and receive invites."
                    else -> "Bluetooth ready. Scan nearby devices and make this phone visible when needed."
                }
                Text(
                    text = bluetoothLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = warmText,
                )

                if (!state.bluetoothSupported) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = darkStoneMid),
                    ) {
                        Text(
                            text = "Bluetooth play is unavailable here.",
                            modifier = Modifier.padding(12.dp),
                            color = warmText,
                        )
                    }
                } else if (state.bluetoothPeers.isEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = darkStoneMid),
                    ) {
                        Text(
                            text = "No Lost Cities Bluetooth peers found yet.",
                            modifier = Modifier.padding(12.dp),
                            color = warmText,
                        )
                    }
                } else {
                    val availablePeers = state.bluetoothPeers.filter { !it.isSelf }
                    if (availablePeers.isEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(12.dp)),
                            colors = CardDefaults.cardColors(containerColor = darkStoneMid),
                        ) {
                            Text(
                                text = "No other Lost Cities Bluetooth peers found yet.",
                                modifier = Modifier.padding(12.dp),
                                color = warmText,
                            )
                        }
                    } else {
                        Text(
                            text = "Bluetooth peers",
                            color = warmText,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Serif,
                        )
                        availablePeers.forEach { peer ->
                            BluetoothPeerCard(
                                peer = peer,
                                onInvite = { onInviteBluetooth(peer) },
                            )
                        }
                    }
                }
            }
        }

        if (state.connected) {
            Button(onClick = onDisconnect) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun BluetoothPeerCard(
    peer: BluetoothDiscoveredPeer,
    onInvite: () -> Unit,
) {
    val open = peer.ping.openSlots > 0
    val playersText = if (peer.ping.players.isEmpty()) {
        "no players"
    } else {
        peer.ping.players.joinToString(prefix = "players: ") {
            "P${it.player} ${it.name}"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = darkStoneMid),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peer.name,
                        color = warmText,
                        fontFamily = FontFamily.Serif,
                    )
                    Text(
                        text = peer.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = warmText,
                    )
                    Text(
                        text = "Status: ${peer.ping.matchStatus.name.lowercase().replaceFirstChar { c -> if (c.isLowerCase()) c.uppercaseChar() else c }} · $playersText",
                        style = MaterialTheme.typography.bodySmall,
                        color = warmText,
                    )
                    if (peer.bonded) {
                        Text(
                            text = "Paired device",
                            style = MaterialTheme.typography.bodySmall,
                            color = accent,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    OutlinedButton(
                        onClick = onInvite,
                        enabled = open,
                    ) {
                        Text("Invite")
                    }
                }
            }
            if (peer.ping.matchStatus == MatchStatus.ACTIVE) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = darkStoneMid),
                ) {
                    Text(
                        text = "Game in progress",
                        modifier = Modifier.padding(top = 6.dp),
                        color = warmText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutlinedFieldRow(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    readOnly: Boolean,
    modifier: Modifier,
    singleLine: Boolean,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(label, color = warmText) },
        enabled = !readOnly,
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = warmText),
    )
}

@Composable
private fun DiscoveredHostCard(
    host: LanScanner.DiscoveredHost,
    onInvite: () -> Unit,
) {
    val open = host.ping.openSlots > 0
    val playersText = if (host.ping.players.isEmpty()) {
        "no players"
    } else {
        host.ping.players.joinToString(prefix = "players: ") {
            "P${it.player} ${it.name}"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = darkStoneMid),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${host.address}:${host.port}",
                        color = warmText,
                        fontFamily = FontFamily.Serif,
                    )
                    Text(host.ping.hostName, style = MaterialTheme.typography.bodySmall, color = warmText)
                    Text(
                        text = "Status: ${host.ping.matchStatus.name.lowercase().replaceFirstChar { c -> if (c.isLowerCase()) c.uppercaseChar() else c }} · $playersText",
                        style = MaterialTheme.typography.bodySmall,
                        color = warmText,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    OutlinedButton(
                        onClick = onInvite,
                        enabled = open,
                    ) {
                        Text("Invite")
                    }
                }
            }
            if (host.ping.matchStatus == MatchStatus.ACTIVE) {
                Text(
                    text = "Game in progress",
                    color = warmText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun InviteDialog(
    invite: LobbyInvite,
    onAccept: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Game Invitation",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                color = warmText,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val sourceText = when (invite.transport) {
                    PeerTransport.LAN -> "${invite.hostAddress}:${invite.hostPort}"
                    PeerTransport.BLUETOOTH -> "Bluetooth (${invite.hostAddress})"
                }
                Text(
                    text = "${invite.fromName} invites you to a Lost Cities round from $sourceText.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = warmText,
                )
                Text(
                    text = "Rules: purple suit ${if (invite.rules.usePurple) "enabled" else "disabled"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = warmText,
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text("Accept")
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text("Deny")
            }
        },
        containerColor = darkStoneMid,
    )
}

@Composable
private fun RematchDialog(
    requesterName: String,
    onAccept: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                text = "Rematch Request",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                color = warmText,
            )
        },
        text = {
            Text(
                text = "$requesterName wants to start another round immediately.",
                style = MaterialTheme.typography.bodyMedium,
                color = warmText,
            )
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text("Accept")
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text("Deny")
            }
        },
        containerColor = darkStoneMid,
    )
}

@Composable
private fun MatchScreen(
    state: AppUiState,
    vm: AppViewModel,
    showTopBar: Boolean,
) {
    val session = state.session
    val match = state.match
    if (session == null || match == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text(
                text = "No active round. Invite a peer from the lobby.",
                style = MaterialTheme.typography.titleMedium,
                color = warmText,
            )
        }
        return
    }

    val activeMatch = match
    val player = session.player
    val opponent = if (player == 1) 2 else 1
    val playerName = activeMatch.players[player]?.name ?: "You"
    val opponentName = activeMatch.players[opponent]?.name ?: "Opponent"
    val phase = if (activeMatch.status == MatchStatus.ACTIVE) activeMatch.lostCities.phase else null
    val finalTurnsRemaining = activeMatch.lostCities.finalTurnsRemaining
    val legalPlaySuits = vm.legalPlaySuitsForSelectedCard().map { it.lowercase() }.toSet()
    val canPlay = state.canAct && activeMatch.status == MatchStatus.ACTIVE && phase == LostCitiesTurnPhase.PLAY
    val canDraw = state.canAct && activeMatch.status == MatchStatus.ACTIVE && phase == LostCitiesTurnPhase.DRAW
    val myTurn = activeMatch.status == MatchStatus.ACTIVE && activeMatch.lostCities.turnPlayer == player
    val isFinished = activeMatch.status == MatchStatus.FINISHED
    val hand = vm.sortedHandForPlayer(player)
    val selectedCardSuit = vm.cardSuit(state.selectedCardId)
    val playableCardIds = if (canPlay && myTurn) hand.filter(vm::canPlayHandCard).toSet() else emptySet()
    val safeDiscardCardIds = if (canPlay && myTurn) hand.filter(vm::canSafelyDiscardHandCard).toSet() else emptySet()
    val alwaysDeckProjection = vm.projectedRemainingPlaysForLocalPlayer(maximizeLocalTurns = false)
    val aggressiveProjection = vm.projectedRemainingPlaysForLocalPlayer(maximizeLocalTurns = true)
    var finishedDetailsExpanded by rememberSaveable(activeMatch.id, isFinished) { mutableStateOf(isFinished) }
    var cardCollectionOverlay by remember { mutableStateOf<CardCollectionOverlayState?>(null) }
    val showMatchHeader = showTopBar && !isFinished

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        val boardWeight = if (showMatchHeader) 2.8f else 3.2f
        val handWeight = if (showMatchHeader) 1.9f else 2.2f

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showMatchHeader) {
                MatchHeader(
                    playerName = playerName,
                    player = player,
                    opponentName = opponentName,
                    opponent = opponent,
                    score = vm.scoreOf(player),
                    opponentScore = vm.scoreOf(opponent),
                    phase = phase,
                    finalTurnsRemaining = finalTurnsRemaining,
                    myTurn = myTurn,
                )
            }

            BoardArea(
                modifier = Modifier.weight(boardWeight),
                vm = vm,
                player = player,
                opponent = opponent,
                match = activeMatch,
                legalPlaySuits = legalPlaySuits,
                canPlay = canPlay,
                canDraw = canDraw,
                myTurn = myTurn,
                canUndo = vm.canUndoPendingMove(),
                selectedCardSuit = selectedCardSuit,
                alwaysDeckProjection = alwaysDeckProjection,
                aggressiveProjection = aggressiveProjection,
                finishedDetailsExpanded = finishedDetailsExpanded,
                onToggleFinishedDetails = {
                    if (isFinished) {
                        finishedDetailsExpanded = !finishedDetailsExpanded
                    }
                },
                reviewEnabled = isFinished && !finishedDetailsExpanded,
                onReviewDiscard = { suitId, cards ->
                    cardCollectionOverlay = CardCollectionOverlayState(
                        title = "${vm.suitLabel(suitId)} discard",
                        cards = cards,
                    )
                },
                onReviewExpedition = { reviewedPlayer, suitId, cards ->
                    val ownerLabel = if (reviewedPlayer == player) {
                        "Your"
                    } else {
                        opponentName
                    }
                    cardCollectionOverlay = CardCollectionOverlayState(
                        title = "$ownerLabel ${vm.suitLabel(suitId)} expedition",
                        cards = cards,
                    )
                },
                onPlayToSuit = vm::placeSelectedCardToSuit,
                onUndo = vm::undoPendingMove,
            )

            HandSection(
                modifier = Modifier.weight(handWeight),
                cards = hand,
                cardPath = vm::cardPath,
                selectedCardId = state.selectedCardId,
                canPlay = canPlay,
                isYourTurn = myTurn,
                turnHintCards = state.turnHintCards,
                newlyAcquiredCardIds = state.newlyAcquiredCardIds,
                playableCardIds = playableCardIds,
                safeDiscardCardIds = safeDiscardCardIds,
                onSelect = vm::selectCard,
            )
        }

        if (isFinished && !finishedDetailsExpanded) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(28.dp)
                    .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(14.dp))
                    .background(color = darkStonePatch, shape = RoundedCornerShape(14.dp))
                    .clickable {
                        cardCollectionOverlay = CardCollectionOverlayState(
                            title = "$opponentName final hand",
                            cards = vm.sortedHandForPlayer(opponent),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "\u25C0",
                    color = warmText,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                )
            }
        }

        if (isFinished) {
            MatchFinishedOverlay(
                vm = vm,
                player = player,
                opponent = opponent,
                playerName = playerName,
                opponentName = opponentName,
                expanded = finishedDetailsExpanded,
                onCollapse = { finishedDetailsExpanded = false },
                onRematch = vm::requestRematch,
            )
        }

        cardCollectionOverlay?.let { overlay ->
            CardCollectionOverlay(
                title = overlay.title,
                cards = overlay.cards,
                cardPath = vm::cardPath,
                onClose = { cardCollectionOverlay = null },
            )
        }
    }
}

@Composable
private fun MatchHeader(
    playerName: String,
    player: Int,
    opponentName: String,
    opponent: Int,
    score: Int,
    opponentScore: Int,
    phase: com.carcassonne.lan.model.LostCitiesTurnPhase?,
    finalTurnsRemaining: Int,
    myTurn: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = darkStoneMid),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "P$player · $playerName vs P$opponent · $opponentName",
                style = MaterialTheme.typography.titleSmall,
                color = warmText,
                fontFamily = FontFamily.Serif,
            )
            Text("Your score: $score", color = warmText)
            Text("Opponent score: $opponentScore", color = warmText)
            if (finalTurnsRemaining > 0) {
                Text(
                    text = if (myTurn) {
                        "Final turn: play one last card. No draw."
                    } else {
                        "Opponent final turn: one last card, no draw."
                    },
                    color = accent,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Serif,
                )
            } else if (phase != null) {
                Text(
                    text = when (phase) {
                        LostCitiesTurnPhase.PLAY -> if (myTurn) "Your turn: play or discard one card" else "Opponent is choosing a card"
                        LostCitiesTurnPhase.DRAW -> if (myTurn) "Your turn: draw from deck or discard" else "Opponent is drawing"
                    },
                    color = accent,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Serif,
                )
            }
        }
    }
}

@Composable
private fun BoardArea(
    modifier: Modifier = Modifier,
    vm: AppViewModel,
    player: Int,
    opponent: Int,
    match: com.carcassonne.lan.model.MatchState,
    legalPlaySuits: Set<String>,
    canPlay: Boolean,
    canDraw: Boolean,
    myTurn: Boolean,
    canUndo: Boolean,
    selectedCardSuit: String?,
    alwaysDeckProjection: Int,
    aggressiveProjection: Int,
    finishedDetailsExpanded: Boolean,
    onToggleFinishedDetails: () -> Unit,
    reviewEnabled: Boolean,
    onReviewDiscard: (String, List<String>) -> Unit,
    onReviewExpedition: (Int, String, List<String>) -> Unit,
    onPlayToSuit: (String) -> Unit,
    onUndo: () -> Unit,
) {
    val suits = vm.suitOrder()
    val deckCount = match.lostCities.deck.size

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BoardAreaSection(modifier = Modifier.weight(0.33f)) {
            ExpeditionRow(
                vm = vm,
                player = opponent,
                suits = suits,
                highlightSuits = emptySet(),
                canPlay = false,
                reviewEnabled = reviewEnabled,
                onReviewExpedition = onReviewExpedition,
                onPlayToSuit = {},
            )
        }

        BoardAreaSection(modifier = Modifier.weight(0.34f)) {
            DrawArea(
                vm = vm,
                cardBackPath = vm.cardBackPath(),
                canDraw = canDraw,
                canPlay = canPlay,
                myTurn = myTurn,
                canUndo = canUndo,
                selectedCardSuit = selectedCardSuit,
                deckCount = deckCount,
                alwaysDeckProjection = alwaysDeckProjection,
                aggressiveProjection = aggressiveProjection,
                finishedDetailsExpanded = finishedDetailsExpanded,
                onToggleFinishedDetails = onToggleFinishedDetails,
                reviewEnabled = reviewEnabled,
                onReviewDiscard = onReviewDiscard,
                suits = suits,
                match = match,
                onUndo = onUndo,
            )
        }

        BoardAreaSection(modifier = Modifier.weight(0.33f)) {
            ExpeditionRow(
                vm = vm,
                player = player,
                suits = suits,
                highlightSuits = legalPlaySuits,
                canPlay = canPlay,
                reviewEnabled = reviewEnabled,
                onReviewExpedition = onReviewExpedition,
                onPlayToSuit = onPlayToSuit,
            )
        }
    }
}

@Composable
private fun BoardAreaSection(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(5.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ExpeditionRow(
    vm: AppViewModel,
    player: Int,
    suits: List<com.carcassonne.lan.model.LostCitiesSuitConfig>,
    highlightSuits: Set<String>,
    canPlay: Boolean,
    reviewEnabled: Boolean,
    onReviewExpedition: (Int, String, List<String>) -> Unit,
    onPlayToSuit: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        suits.forEach { suit ->
            val cards = vm.expeditionCards(player, suit.id)
            val isHighlighted = highlightSuits.contains(suit.id.lowercase())
            val expeditionScore = vm.expeditionScore(player, suit.id)
            val highlightFill = if (isHighlighted && canPlay) {
                actionHighlight.copy(alpha = 0.18f)
            } else {
                Color.Transparent
            }
            val reviewTapEnabled = reviewEnabled && cards.isNotEmpty()
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(enabled = (canPlay && isHighlighted) || reviewTapEnabled) {
                        if (canPlay && isHighlighted) {
                            onPlayToSuit(suit.id)
                        } else if (reviewTapEnabled) {
                            onReviewExpedition(player, suit.id, cards)
                        }
                    }
                    .border(
                        width = if (isHighlighted && canPlay) 4.dp else 1.dp,
                        color = if (isHighlighted && canPlay) actionHighlight else Color(0xFF8C7A66),
                        shape = RoundedCornerShape(8.dp),
                    ),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(highlightFill)
                        .padding(horizontal = 3.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = expeditionScore.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = warmText,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    if (cards.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Text(
                                text = "",
                                style = MaterialTheme.typography.bodySmall,
                                color = warmText,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    } else {
                        CardStack(
                            cards = cards,
                            vm = vm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CardStack(cards: List<String>, vm: AppViewModel, modifier: Modifier) {
    if (cards.isEmpty()) return
    val cardScale = 1.08f
    val overlapPercent = 0.204f
    val visible = cards
    BoxWithConstraints(modifier = modifier) {
        val widthLimitedCardW = (maxWidth * 0.98f).coerceIn(52.dp, 158.dp)
        val widthLimitedCardH = widthLimitedCardW * cardScale
        val maxCardHeight = (maxHeight * 0.92f).coerceAtLeast(56.dp)
        val cardH = widthLimitedCardH.coerceAtMost(maxCardHeight)
        val cardW = (cardH / cardScale).coerceAtMost(widthLimitedCardW)
        val overlap = cardH * overlapPercent
        val stackHeight = cardH + overlap * (visible.size - 1)
        val availableHeight = maxHeight
        val scrollState = rememberScrollState()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(stackHeight.coerceAtLeast(availableHeight)),
            ) {
                visible.forEachIndexed { index, cardId ->
                    val offset = overlap * index
                    Card(
                        modifier = Modifier
                            .width(cardW)
                            .height(cardH)
                            .align(Alignment.TopCenter)
                            .offset(y = offset),
                        colors = CardDefaults.cardColors(containerColor = darkStoneLight),
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = Color(0xFF625A4B),
                        ),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CardImage(
                                path = vm.cardPath(cardId),
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(cardH * overlapPercent)
                                    .background(Color(0xAA1F1910)),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Text(
                                    text = vm.cardTopText(cardId),
                                    color = Color(0xFFF8EEDB),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.padding(top = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawArea(
    vm: AppViewModel,
    cardBackPath: String,
    canDraw: Boolean,
    canPlay: Boolean,
    myTurn: Boolean,
    canUndo: Boolean,
    selectedCardSuit: String?,
    deckCount: Int,
    alwaysDeckProjection: Int,
    aggressiveProjection: Int,
    finishedDetailsExpanded: Boolean,
    onToggleFinishedDetails: () -> Unit,
    reviewEnabled: Boolean,
    onReviewDiscard: (String, List<String>) -> Unit,
    suits: List<com.carcassonne.lan.model.LostCitiesSuitConfig>,
    match: com.carcassonne.lan.model.MatchState,
    onUndo: () -> Unit,
) {
    val selectedSuit = selectedCardSuit?.lowercase(Locale.ROOT)
    val spacing = 4.dp
    val pileCount = suits.size.coerceAtLeast(1)
    val deckLandscapeAspect = 640f / 420f
    val portraitCardAspect = 420f / 640f

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val deckWidthByWidth = (maxWidth * 0.276f).coerceIn(106.dp, 188.dp)
        val deckHeightByWidth = (deckWidthByWidth / deckLandscapeAspect).coerceIn(69.dp, 124.dp)
        val deckHeightMaxBySection = (maxHeight * 0.37f).coerceIn(56.dp, 124.dp)
        val deckHeight = deckHeightByWidth.coerceAtMost(deckHeightMaxBySection)
        val deckWidth = deckHeight * deckLandscapeAspect
        val availableDiscardHeight = (maxHeight - deckHeight - spacing).coerceAtLeast(0.dp)
        val discardFooterHeight = (availableDiscardHeight * 0.16f).coerceIn(14.dp, 18.dp)
        val discardHeight = availableDiscardHeight
        val pileWidth = ((maxWidth - spacing * (pileCount - 1)).coerceAtLeast(0.dp) / pileCount)
        val discardPreviewHeight = (discardHeight - discardFooterHeight - 4.dp).coerceAtLeast(28.dp)
        val deckCounterTextSize = (deckHeight.value * 0.54f).sp
        val deckProjectionTextSize = (deckHeight.value * 0.17f).coerceIn(11f, 15f).sp
        val sidePanelWidth = (((maxWidth - deckWidth) / 2f) - 8.dp).coerceAtLeast(84.dp)
        val isFinished = match.status == MatchStatus.FINISHED
        val promptText = when {
            isFinished -> "FINAL\nSCORE"
            !myTurn -> "OPPONENT'S\nTURN"
            canDraw -> "DRAW CARD\nTO FINISH"
            else -> "SELECT CARD\nTO PLAY"
        }
        val promptColor = if (myTurn) promptGreen else promptRed

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(spacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val deckHighlight = canDraw && deckCount > 0
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(deckHeight),
            ) {
                Box(
                    modifier = Modifier
                        .width(sidePanelWidth)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isFinished) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size((deckHeight * 0.52f).coerceIn(28.dp, 42.dp))
                                    .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(18.dp))
                                    .background(color = darkStonePatch, shape = RoundedCornerShape(18.dp))
                                    .clickable(onClick = onToggleFinishedDetails),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (finishedDetailsExpanded) "\u25C0" else "\u25B6",
                                    color = warmText,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                            Text(
                                text = promptText,
                                color = accent,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = (deckHeight.value * 0.18f).sp,
                                ),
                                textAlign = TextAlign.Center,
                                fontFamily = FontFamily.Serif,
                            )
                        }
                    } else {
                        Text(
                            text = promptText,
                            color = promptColor,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                lineHeight = (deckHeight.value * 0.22f).sp,
                            ),
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.Serif,
                        )
                    }
                }
                Card(
                    modifier = Modifier
                        .width(deckWidth)
                        .height(deckHeight)
                        .align(Alignment.Center)
                        .clickable(enabled = deckHighlight, onClick = vm::drawFromDeck)
                        .border(
                            width = if (deckHighlight) 4.dp else 1.dp,
                            color = if (deckHighlight) drawHighlight else Color(0xFF8C7A66),
                        ),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CardImage(
                            path = cardBackPath,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                            rotationDegrees = 90f,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .width(sidePanelWidth)
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            text = deckCount.toString(),
                            color = warmText,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = deckCounterTextSize,
                                fontWeight = FontWeight.Bold,
                            ),
                            lineHeight = deckCounterTextSize,
                        )
                        Text(
                            text = "$alwaysDeckProjection / $aggressiveProjection",
                            color = forecastTint,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = deckProjectionTextSize,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            lineHeight = deckProjectionTextSize,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onUndo,
                        enabled = canUndo,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = darkStone,
                            disabledContainerColor = Color(0xFF3E3E3E),
                            disabledContentColor = Color(0xFF8E8E8E),
                        ),
                        modifier = Modifier.size((deckHeight * 0.72f).coerceIn(36.dp, 52.dp)),
                    ) {
                        Text(
                            text = "\u21BA",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }
            }

            val pileRowTopPadding = 2.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = pileRowTopPadding),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                suits.forEach { suit ->
                    val pile = match.lostCities.discardPiles[suit.id].orEmpty()
                    val topCard = pile.lastOrNull()
                    val suitId = suit.id.lowercase(Locale.ROOT)
                    val enabledForDraw = canDraw && topCard != null && vm.canDrawDiscard(suit.id)
                    val enabledForDiscard = canPlay && selectedSuit == suitId
                    val enabledForReview = reviewEnabled && pile.isNotEmpty()
                    val canTap = enabledForDraw || enabledForDiscard || enabledForReview

                    Card(
                        modifier = Modifier
                            .width(pileWidth)
                            .height(discardHeight)
                            .clickable(enabled = canTap) {
                                if (enabledForDiscard) {
                                    vm.discardSelectedCard()
                                } else if (enabledForDraw) {
                                    vm.drawFromDiscard(suit.id)
                                } else if (enabledForReview) {
                                    onReviewDiscard(suit.id, pile)
                                }
                            }
                            .border(
                                width = if (canTap) 4.dp else 1.dp,
                                color = when {
                                    enabledForDiscard -> selectedHighlight
                                    enabledForDraw -> drawHighlight
                                    else -> Color(0xFF8C7A66)
                                },
                            ),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(discardPreviewHeight)
                                    .clip(RoundedCornerShape(5.dp)),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                if (topCard == null) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("Empty", color = warmText, style = MaterialTheme.typography.bodySmall)
                                    }
                                } else {
                                    CardImage(
                                        path = vm.cardPath(topCard),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(portraitCardAspect),
                                        contentScale = ContentScale.FillWidth,
                                        alignment = Alignment.TopCenter,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(discardFooterHeight),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = pile.size.toString(),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                                    color = warmText,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun suitColor(suit: String): Color {
    return when (suit.lowercase(Locale.ROOT)) {
        "yellow" -> Color(0xFFD8A61B)
        "white" -> Color(0xFFEDE6D4)
        "blue" -> Color(0xFF3C80B8)
        "green" -> Color(0xFF3D8F5C)
        "red" -> Color(0xFFB93B3B)
        "purple" -> Color(0xFF7B4D91)
        else -> Color(0xFF8A7A61)
    }
}

@Composable
private fun HandSection(
    modifier: Modifier = Modifier,
    cards: List<String>,
    cardPath: (String) -> String?,
    selectedCardId: String?,
    canPlay: Boolean,
    isYourTurn: Boolean,
    turnHintCards: Boolean,
    newlyAcquiredCardIds: Set<String>,
    playableCardIds: Set<String>,
    safeDiscardCardIds: Set<String>,
    onSelect: (String) -> Unit,
) {
    if (cards.isEmpty()) {
        Text("No cards in hand.", style = MaterialTheme.typography.bodySmall, color = warmText)
        return
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
    ) {
        val spacing = 4.dp
        val rowCellWidth = ((maxWidth - spacing * 3) / 4).coerceIn(46.dp, 170.dp)
        val cardHeight = (rowCellWidth * 1.62f).coerceIn(88.dp, 210.dp)
        val rows = cards.chunked(4)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { cardId ->
                        val isSelected = selectedCardId == cardId
                        val isNew = newlyAcquiredCardIds.contains(cardId)
                        val shouldGlow = canPlay && (isYourTurn || turnHintCards)
                        val hint = when {
                            playableCardIds.contains(cardId) && safeDiscardCardIds.contains(cardId) -> HandCardHint.PLAYABLE_AND_SAFE_DISCARD
                            playableCardIds.contains(cardId) -> HandCardHint.PLAYABLE
                            safeDiscardCardIds.contains(cardId) -> HandCardHint.SAFE_DISCARD
                            else -> HandCardHint.NONE
                        }
                        val haloColor = when (hint) {
                            HandCardHint.PLAYABLE -> promptGreen
                            HandCardHint.SAFE_DISCARD -> promptRed
                            HandCardHint.PLAYABLE_AND_SAFE_DISCARD -> Color.Transparent
                            HandCardHint.NONE -> when {
                                isSelected -> selectedHighlight
                                isNew -> actionHighlight
                                shouldGlow -> turnHighlight
                                else -> Color(0xFFCFBFA4)
                            }
                        }
                        val haloWidth = when {
                            isSelected -> 4.dp
                            hint != HandCardHint.NONE -> 3.dp
                            isNew -> 3.dp
                            shouldGlow -> 2.dp
                            else -> 1.dp
                        }
                        val haloBg = when {
                            isSelected -> Brush.horizontalGradient(
                                listOf(
                                    selectedHighlight.copy(alpha = 0.30f),
                                    selectedHighlight.copy(alpha = 0.16f),
                                    selectedHighlight.copy(alpha = 0.30f),
                                ),
                            )
                            hint == HandCardHint.PLAYABLE_AND_SAFE_DISCARD -> Brush.horizontalGradient(
                                listOf(
                                    promptGreen.copy(alpha = 0.24f),
                                    promptGreen.copy(alpha = 0.18f),
                                    promptRed.copy(alpha = 0.18f),
                                    promptRed.copy(alpha = 0.24f),
                                ),
                            )
                            hint == HandCardHint.PLAYABLE -> Brush.horizontalGradient(
                                listOf(
                                    promptGreen.copy(alpha = 0.20f),
                                    Color.Transparent,
                                    promptGreen.copy(alpha = 0.20f),
                                ),
                            )
                            hint == HandCardHint.SAFE_DISCARD -> Brush.horizontalGradient(
                                listOf(
                                    promptRed.copy(alpha = 0.20f),
                                    Color.Transparent,
                                    promptRed.copy(alpha = 0.20f),
                                ),
                            )
                            isNew -> Brush.horizontalGradient(
                                listOf(
                                    actionHighlight.copy(alpha = 0.22f),
                                    Color.Transparent,
                                    actionHighlight.copy(alpha = 0.22f),
                                ),
                            )
                            shouldGlow -> Brush.horizontalGradient(
                                listOf(
                                    turnHighlight.copy(alpha = 0.18f),
                                    Color.Transparent,
                                    turnHighlight.copy(alpha = 0.18f),
                                ),
                            )
                            else -> Brush.horizontalGradient(
                                listOf(Color.Transparent, Color.Transparent, Color.Transparent),
                            )
                        }
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(cardHeight)
                                .offset(y = if (isSelected) (-8).dp else 0.dp)
                                .zIndex(if (isSelected) 1f else 0f)
                                .background(brush = haloBg, shape = RoundedCornerShape(8.dp))
                                .handCardHintBorder(
                                    hint = hint,
                                    borderWidth = haloWidth,
                                    borderColor = haloColor,
                                )
                                .clickable(enabled = canPlay) { onSelect(cardId) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) darkStonePatch else darkStoneLight,
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            CardImage(
                                path = cardPath(cardId),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = 1.035f,
                                        scaleY = 1.035f,
                                    ),
                                contentScale = ContentScale.Crop,
                                alignment = Alignment.TopCenter,
                            )
                        }
                    }
                    repeat(4 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun Modifier.handCardHintBorder(
    hint: HandCardHint,
    borderWidth: androidx.compose.ui.unit.Dp,
    borderColor: Color,
): Modifier {
    val shape = RoundedCornerShape(8.dp)
    return if (hint == HandCardHint.PLAYABLE_AND_SAFE_DISCARD) {
        drawWithContent {
            drawContent()
            val strokeWidth = borderWidth.toPx()
            val inset = strokeWidth / 2f
            val dashLength = 14.dp.toPx()
            val gapLength = 8.dp.toPx()
            val cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
            val rectTopLeft = Offset(inset, inset)
            val rectSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawRoundRect(
                color = promptRed,
                topLeft = rectTopLeft,
                size = rectSize,
                cornerRadius = cornerRadius,
                style = Stroke(
                    width = strokeWidth,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(dashLength, gapLength),
                        phase = 0f,
                    ),
                ),
            )
            drawRoundRect(
                color = promptGreen,
                topLeft = rectTopLeft,
                size = rectSize,
                cornerRadius = cornerRadius,
                style = Stroke(
                    width = strokeWidth,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(dashLength, gapLength),
                        phase = dashLength,
                    ),
                ),
            )
        }
    } else {
        border(
            width = borderWidth,
            color = borderColor,
            shape = shape,
        )
    }
}

@Composable
private fun CardImage(
    path: String?,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    rotationDegrees: Float = 0f,
) {
    val context = LocalContext.current
    val candidatePaths = remember(path) {
        val normalized = path?.trim()?.trimStart('/') ?: return@remember emptyList()
        val fileName = normalized.substringAfterLast("/")
        buildList {
            add(normalized)
            if (normalized.startsWith("assets/")) {
                add(normalized.removePrefix("assets/"))
            }
            if (
                !normalized.startsWith("lost_cities/") &&
                !normalized.startsWith("lost_cities_v2/") &&
                !normalized.startsWith("lost_cities_v3/")
            ) {
                add("lost_cities/$normalized")
                add("lost_cities_v2/$normalized")
                add("lost_cities_v3/$normalized")
            }
            if (normalized.startsWith("lost_cities/")) {
                val stripped = normalized.removePrefix("lost_cities/")
                add("lost_cities_v2/$stripped")
                add("lost_cities_v3/$stripped")
            }
            if (normalized.startsWith("lost_cities_v2/")) {
                val stripped = normalized.removePrefix("lost_cities_v2/")
                add("lost_cities/$stripped")
                add("lost_cities_v3/$stripped")
            }
            if (normalized.startsWith("lost_cities_v3/")) {
                val stripped = normalized.removePrefix("lost_cities_v3/")
                add("lost_cities/$stripped")
                add("lost_cities_v2/$stripped")
            }
            add("lost_cities/cards/$fileName")
            add("lost_cities_v2/cards/$fileName")
            add("lost_cities_v3/cards/$fileName")
            add("lost_cities_v2/cards/png/$fileName")
            add("lost_cities_v3/cards/png/$fileName")
            add("cards/png/$fileName")
            add(classicCardBackAsset)
            add(v2CardBackAsset)
            add(v3CardBackAsset)
        }.distinct()
    }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = candidatePaths, key2 = rotationDegrees) {
        value = withContext(Dispatchers.IO) {
            CardBitmapCache.load(context, candidatePaths, rotationDegrees)?.asImageBitmap()
        }
    }
    val resolvedBitmap = bitmap
    if (resolvedBitmap == null) {
        Box(modifier = modifier.background(Color(0xFF4A4A4A)), contentAlignment = Alignment.Center) {
            Text("Card", color = warmText, style = TextStyle(fontFamily = FontFamily.Serif))
        }
    } else {
        Image(
            bitmap = resolvedBitmap,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
            alignment = alignment,
        )
    }
}

private object CardBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(18 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun load(context: Context, candidatePaths: List<String>, rotationDegrees: Float = 0f): Bitmap? {
        candidatePaths.forEach { candidate ->
            val cacheKey = if (rotationDegrees == 0f) candidate else "$candidate#rot$rotationDegrees"
            synchronized(cache) {
                cache.get(cacheKey)
            }?.let { return it }

            val decoded = runCatching {
                context.assets.open(candidate).use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()

            if (decoded != null) {
                val finalBitmap = if (rotationDegrees == 0f) {
                    decoded
                } else {
                    val matrix = Matrix().apply { postRotate(rotationDegrees) }
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                        if (it != decoded) {
                            decoded.recycle()
                        }
                    }
                }
                synchronized(cache) {
                    cache.put(cacheKey, finalBitmap)
                }
                return finalBitmap
            }
        }
        return null
    }
}

private fun bluetoothScanPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun bluetoothDiscoverablePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

@Composable
private fun MatchFinishedOverlay(
    vm: AppViewModel,
    player: Int,
    opponent: Int,
    playerName: String,
    opponentName: String,
    expanded: Boolean,
    onCollapse: () -> Unit,
    onRematch: () -> Unit,
) {
    if (!expanded) return

    val suits = vm.suitOrder()
    val playerTotal = vm.scoreOf(player)
    val opponentTotal = vm.scoreOf(opponent)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB0121212))
            .zIndex(4f),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = darkStone.copy(alpha = 0.98f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(14.dp))
                            .background(color = darkStonePatch, shape = RoundedCornerShape(14.dp))
                            .clickable(onClick = onCollapse),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "\u25C0",
                            color = warmText,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    Text(
                        text = "Final score details",
                        style = MaterialTheme.typography.titleMedium,
                        color = warmText,
                        fontFamily = FontFamily.Serif,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onRematch) {
                        Text("Rematch")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScoreColumn(
                        title = "You ($playerName)",
                        total = playerTotal,
                        suits = suits,
                        vm = vm,
                        player = player,
                        modifier = Modifier.weight(1f),
                    )
                    ScoreColumn(
                        title = "Opponent ($opponentName)",
                        total = opponentTotal,
                        suits = suits,
                        vm = vm,
                        player = opponent,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CardCollectionOverlay(
    title: String,
    cards: List<String>,
    cardPath: (String) -> String?,
    onClose: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC121212))
            .zIndex(5f),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.88f)
                .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = darkStone.copy(alpha = 0.98f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .border(2.dp, brush = boardBorder, shape = RoundedCornerShape(14.dp))
                            .background(color = darkStonePatch, shape = RoundedCornerShape(14.dp))
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "\u25C0",
                            color = warmText,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    Text(
                        text = title,
                        color = warmText,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Serif,
                    )
                }
                if (cards.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No cards.",
                            color = warmText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                    ) {
                        val spacing = 4.dp
                        val rowCellWidth = ((maxWidth - spacing * 3) / 4).coerceIn(46.dp, 170.dp)
                        val cardHeight = (rowCellWidth * 1.62f).coerceIn(88.dp, 210.dp)
                        val rows = cards.chunked(4)

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(spacing),
                        ) {
                            rows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(spacing),
                                ) {
                                    row.forEach { cardId ->
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(cardHeight)
                                                .border(
                                                    width = 1.dp,
                                                    color = Color(0xFFCFBFA4),
                                                    shape = RoundedCornerShape(8.dp),
                                                ),
                                            colors = CardDefaults.cardColors(containerColor = darkStoneLight),
                                            shape = RoundedCornerShape(8.dp),
                                        ) {
                                            CardImage(
                                                path = cardPath(cardId),
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .graphicsLayer(
                                                        scaleX = 1.035f,
                                                        scaleY = 1.035f,
                                                    ),
                                                contentScale = ContentScale.Crop,
                                                alignment = Alignment.TopCenter,
                                            )
                                        }
                                    }
                                    repeat(4 - row.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreColumn(
    title: String,
    total: Int,
    suits: List<com.carcassonne.lan.model.LostCitiesSuitConfig>,
    vm: AppViewModel,
    player: Int,
    modifier: Modifier,
) {
    Card(
        modifier = modifier.border(1.dp, brush = boardBorder, shape = RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = darkStoneMid),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = warmText,
            )
            Text(text = "Total: $total", color = accent)
            suits.forEach { suit ->
                val breakdown = vm.expeditionScoreBreakdown(player, suit.id)
                Text(
                    text = "${vm.suitLabel(suit.id)}: ${breakdown.total}",
                    color = warmText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (breakdown.hasCards) {
                    Text(
                        text = "Points ${breakdown.pointSum} -> base ${breakdown.baseBeforeMultiplier}",
                        color = warmText,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = "Multiplier x${breakdown.multiplier} (${breakdown.wagerCount} wagers) = ${breakdown.multipliedScore}",
                        color = warmText,
                        fontSize = 11.sp,
                    )
                    if (breakdown.bonus > 0) {
                        Text(
                            text = "Bonus +${breakdown.bonus}",
                            color = accent,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}
