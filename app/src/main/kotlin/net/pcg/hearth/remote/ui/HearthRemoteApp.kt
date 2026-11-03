package net.pcg.hearth.remote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import net.pcg.hearth.remote.protocol.TvConnection
import net.pcg.hearth.remote.ui.components.VersionLabel
import net.pcg.hearth.remote.ui.screens.*

@Composable
fun HearthRemoteApp() {
    val context = LocalContext.current
    val tvConnection = remember { TvConnection(context) }
    val viewModel: MainViewModel = viewModel { MainViewModel(tvConnection) }
    
    val isPaired by viewModel.isPaired.collectAsState()
    val pairingState by viewModel.pairingState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
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
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (currentScreen) {
                                Screen.Trackpad -> TrackpadScreen(tvConnection = tvConnection)
                                Screen.DPad -> DPadScreen(tvConnection = tvConnection)
                                Screen.Apps -> AppsScreen(tvConnection = tvConnection)
                                Screen.Settings -> SettingsScreen(
                                    tvConnection = tvConnection,
                                    onForgetPairing = viewModel::forgetPairing
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
    Trackpad, DPad, Apps, Settings
}
