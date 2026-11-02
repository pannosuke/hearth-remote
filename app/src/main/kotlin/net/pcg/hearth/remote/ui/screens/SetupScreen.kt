package net.pcg.hearth.remote.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.pcg.hearth.remote.R
import net.pcg.hearth.remote.protocol.TvConnection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    pairingState: TvConnection.PairingState,
    onStartPairing: (String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancelPairing: () -> Unit
) {
    var ipAddress by remember { mutableStateOf("192.168.10.105") }
    var pairingCode by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Tv,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        when (pairingState) {
            is TvConnection.PairingState.Idle,
            is TvConnection.PairingState.Error -> {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text(stringResource(R.string.setup_ip_label)) },
                    placeholder = { Text(stringResource(R.string.setup_ip_hint)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onStartPairing(ipAddress) }
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                if (pairingState is TvConnection.PairingState.Error) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = pairingState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = { onStartPairing(ipAddress) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.setup_connect))
                }
            }
            
            is TvConnection.PairingState.Connecting -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.setup_connecting))
            }
            
            is TvConnection.PairingState.WaitingForCode -> {
                Text(
                    text = stringResource(R.string.pairing_title),
                    style = MaterialTheme.typography.titleLarge
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = stringResource(R.string.pairing_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { 
                        if (it.length <= 6 && it.all { c -> c.isDigit() || c.uppercaseChar() in 'A'..'F' }) {
                            pairingCode = it.uppercase()
                        }
                    },
                    label = { Text("Code") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { 
                            if (pairingCode.length == 6) {
                                onSubmitCode(pairingCode)
                            }
                        }
                    ),
                    singleLine = true,
                    modifier = Modifier.width(200.dp),
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        textAlign = TextAlign.Center
                    )
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(onClick = onCancelPairing) {
                        Text(stringResource(R.string.pairing_cancel))
                    }
                    
                    Button(
                        onClick = { onSubmitCode(pairingCode) },
                        enabled = pairingCode.length == 6
                    ) {
                        Text(stringResource(R.string.pairing_submit))
                    }
                }
            }
            
            is TvConnection.PairingState.Verifying -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.setup_pairing))
            }
            
            is TvConnection.PairingState.Success -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.setup_paired))
            }
        }
    }
}
