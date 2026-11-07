package net.pcg.hearth.remote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.pcg.hearth.remote.BuildConfig
import net.pcg.hearth.remote.data.AndroidJellyfinStore
import net.pcg.hearth.remote.protocol.HttpGuideFetch
import net.pcg.hearth.remote.protocol.HttpJellyfinTransport
import net.pcg.hearth.remote.protocol.JellyfinRemote
import net.pcg.hearth.remote.protocol.OsakaGuide
import net.pcg.hearth.remote.protocol.SportsnetFeed
import net.pcg.hearth.remote.protocol.SportsnetGuide
import net.pcg.hearth.remote.protocol.TvConnection
import net.pcg.hearth.remote.ui.components.VersionLabel
import net.pcg.hearth.remote.ui.screens.*
import java.io.File

@Composable
fun HearthRemoteApp() {
    val context = LocalContext.current
    val tvConnection = remember { TvConnection(context) }
    val guide = remember {
        OsakaGuide(
            directory = File(context.filesDir, "epg"),
            fetch = HttpGuideFetch()
        )
    }
    val highlights = remember {
        SportsnetGuide(
            directory = File(context.filesDir, "youtube"),
            channel = HttpGuideFetch(SportsnetFeed.CHANNEL_FEED_URL),
            playlist = HttpGuideFetch(SportsnetFeed.PLAYLIST_FEED_URL)
        )
    }
    val jellyfin = remember {
        JellyfinRemote(
            store = AndroidJellyfinStore(context),
            transport = HttpJellyfinTransport(),
            version = BuildConfig.VERSION_NAME,
            log = { line -> tvConnection.note(line) }
        )
    }
    DisposableEffect(guide, highlights) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        guide.start(scope)
        highlights.start(scope)
        onDispose {
            guide.stop()
            highlights.stop()
            scope.cancel()
        }
    }
    val viewModel: MainViewModel = viewModel { MainViewModel(tvConnection) }
    
    val isPaired by viewModel.isPaired.collectAsState()
    val pairingState by viewModel.pairingState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val closeReason by viewModel.closeReason.collectAsState()
    val sessionLog by viewModel.sessionLog.collectAsState()
    val powerOn by viewModel.powerOn.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()
    
    LaunchedEffect(isPaired) {
        if (isPaired) {
            viewModel.connect()
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !isPaired -> {
                SetupScreen(
                    pairingState = pairingState,
                    onStartPairing = viewModel::startPairing,
                    onSubmitCode = viewModel::submitPairingCode,
                    onCancelPairing = viewModel::cancelPairing
                )
            }
            else -> {
                Scaffold(
                    bottomBar = {
                        NavigationBar(
                            currentScreen = currentScreen,
                            onNavigate = viewModel::navigateTo
                        )
                    }
                ) { paddingValues ->
                    Column(
                        modifier = Modifier
                            .padding(paddingValues)
                            .fillMaxSize()
                    ) {
                        ConnectionStatus(
                            connectionState = connectionState,
                            lastError = lastError,
                            powerOn = powerOn,
                            onTogglePower = viewModel::togglePower,
                            onOpenLog = { viewModel.navigateTo(Screen.SessionLog) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (currentScreen) {
                                Screen.Remote -> RemoteShell(
                                    tvConnection = tvConnection,
                                    guide = guide,
                                    highlights = highlights,
                                    jellyfin = jellyfin,
                                    onOpenSettings = { viewModel.navigateTo(Screen.Settings) }
                                )
                                Screen.Trackpad -> TrackpadScreen(tvConnection = tvConnection)
                                Screen.Apps -> AppsScreen(tvConnection = tvConnection)
                                Screen.Settings -> SettingsScreen(
                                    tvConnection = tvConnection,
                                    jellyfin = jellyfin,
                                    onOpenLog = { viewModel.navigateTo(Screen.SessionLog) },
                                    onForgetPairing = viewModel::forgetPairing
                                )
                                Screen.SessionLog -> SessionLogScreen(
                                    lines = sessionLog,
                                    closeReason = closeReason,
                                    onBack = { viewModel.navigateTo(Screen.Settings) }
                                )
                            }
                        }
                    }
                }
            }
        }
        
        VersionLabel(Modifier.align(Alignment.TopEnd))
    }
}

enum class Screen {
    Remote, Trackpad, Apps, Settings, SessionLog
}
