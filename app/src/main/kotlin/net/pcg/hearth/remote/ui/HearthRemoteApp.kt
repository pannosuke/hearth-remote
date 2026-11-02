package net.pcg.hearth.remote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import net.pcg.hearth.remote.protocol.TvConnection
import net.pcg.hearth.remote.ui.screens.*

@Composable
fun HearthRemoteApp() {
    val context = LocalContext.current
    val tvConnection = remember { TvConnection(context) }
    val viewModel: MainViewModel = viewModel { MainViewModel(tvConnection) }
    
    val isPaired by viewModel.isPaired.collectAsState()
    val pairingState by viewModel.pairingState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()
    
    LaunchedEffect(isPaired) {
        if (isPaired) {
            viewModel.connect()
        }
    }
    
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
                Box(modifier = Modifier.padding(paddingValues)) {
                    when (currentScreen) {
                        Screen.Trackpad -> TrackpadScreen(
                            tvConnection = tvConnection,
                            connectionState = connectionState
                        )
                        Screen.DPad -> DPadScreen(
                            tvConnection = tvConnection,
                            connectionState = connectionState
                        )
                        Screen.Apps -> AppsScreen(
                            tvConnection = tvConnection
                        )
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

enum class Screen {
    Trackpad, DPad, Apps, Settings
}
