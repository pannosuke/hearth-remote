package net.pcg.hearth.remote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.pcg.hearth.remote.protocol.RemoteProtocol
import net.pcg.hearth.remote.protocol.TvConnection

class MainViewModel(
    private val tvConnection: TvConnection
) : ViewModel() {
    
    private val _currentScreen = MutableStateFlow(Screen.Trackpad)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()
    
    val pairingState = tvConnection.pairingState
    val connectionState = tvConnection.connectionState
    val lastError = tvConnection.lastError
    val closeReason = tvConnection.closeReason
    val sessionLog = tvConnection.sessionLog
    
    val isPaired: StateFlow<Boolean> = pairingState
        .map { state ->
            when (state) {
                is TvConnection.PairingState.Success -> true
                else -> tvConnection.isPaired()
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, tvConnection.isPaired())
    
    fun startPairing(host: String) {
        viewModelScope.launch {
            tvConnection.startPairing(host)
        }
    }
    
    fun submitPairingCode(code: String) {
        viewModelScope.launch {
            tvConnection.submitPairingCode(code)
        }
    }
    
    fun cancelPairing() {
        tvConnection.cancelPairing()
    }
    
    fun connect() {
        viewModelScope.launch {
            tvConnection.connect()
        }
    }
    
    fun disconnect() {
        viewModelScope.launch {
            tvConnection.disconnect()
        }
    }
    
    fun forgetPairing() {
        viewModelScope.launch {
            tvConnection.disconnect()
            tvConnection.forgetPairing()
        }
    }
    
    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }
}
