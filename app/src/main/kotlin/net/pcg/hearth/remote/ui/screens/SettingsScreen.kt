package net.pcg.hearth.remote.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pcg.hearth.remote.BuildConfig
import net.pcg.hearth.remote.R
import net.pcg.hearth.remote.data.SettingsRepository
import net.pcg.hearth.remote.protocol.TvConnection

@Composable
fun SettingsScreen(
    tvConnection: TvConnection,
    onForgetPairing: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val tvHost = settingsRepository.getTvHost() ?: settingsRepository.getDefaultTvHost()
    
    var showForgetDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // TV Address
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_tv_address)) },
            supportingContent = { Text(tvHost) },
            leadingContent = {
                Icon(Icons.Default.Tv, contentDescription = null)
            }
        )
        
        HorizontalDivider()
        
        // Forget Pairing
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_forget_pairing)) },
            leadingContent = {
                Icon(
                    Icons.Default.LinkOff, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            modifier = Modifier.let { modifier ->
                modifier
            }
        )
        
        // Make it clickable
        TextButton(
            onClick = { showForgetDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.LinkOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_forget_pairing),
                color = MaterialTheme.colorScheme.error
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // About
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.settings_about),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    
    if (showForgetDialog) {
        AlertDialog(
            onDismissRequest = { showForgetDialog = false },
            title = { Text(stringResource(R.string.settings_forget_pairing)) },
            text = { Text("Are you sure you want to forget the TV pairing? You will need to pair again.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForgetDialog = false
                        onForgetPairing()
                    }
                ) {
                    Text("Forget", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
