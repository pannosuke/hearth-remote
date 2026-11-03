package net.pcg.hearth.remote.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.pcg.hearth.remote.BuildConfig
import net.pcg.hearth.remote.R
import net.pcg.hearth.remote.data.SettingsRepository
import net.pcg.hearth.remote.protocol.JellyfinProblem
import net.pcg.hearth.remote.protocol.JellyfinRemote
import net.pcg.hearth.remote.protocol.TvConnection

@Composable
internal fun SettingsScreen(
    tvConnection: TvConnection,
    jellyfin: JellyfinRemote,
    onOpenLog: () -> Unit,
    onForgetPairing: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val tvHost = settingsRepository.getTvHost() ?: settingsRepository.getDefaultTvHost()
    
    var showForgetDialog by remember { mutableStateOf(false) }
    var subtitleMacro by remember { mutableStateOf(settingsRepository.getSubtitleMacro()) }
    val jellyfinState by jellyfin.state.collectAsState()
    val scope = rememberCoroutineScope()
    var jellyfinUrl by remember { mutableStateOf(jellyfinState.serverUrl) }
    var jellyfinUser by remember { mutableStateOf("") }
    var jellyfinPassword by remember { mutableStateOf("") }
    var jellyfinBusy by remember { mutableStateOf(false) }
    
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

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
        // TV Address
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_tv_address)) },
            supportingContent = { Text(tvHost) },
            leadingContent = {
                Icon(Icons.Default.Tv, contentDescription = null)
            }
        )
        
        HorizontalDivider()

        Text(
            text = stringResource(R.string.jellyfin_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = jellyfinUrl,
            onValueChange = { jellyfinUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_jellyfin_url)) },
            placeholder = { Text(stringResource(R.string.settings_jellyfin_url_hint)) },
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = jellyfinUser,
            onValueChange = { jellyfinUser = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_jellyfin_username)) },
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = jellyfinPassword,
            onValueChange = { jellyfinPassword = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_jellyfin_password)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
        if (jellyfinState.signedIn && jellyfinState.userName != null) {
            Text(
                text = stringResource(R.string.settings_jellyfin_signed_in, jellyfinState.userName!!),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        val jellyfinMessage = when (jellyfinState.problem) {
            JellyfinProblem.SignInFailed -> stringResource(R.string.settings_jellyfin_failed)
            JellyfinProblem.Unauthorized -> stringResource(R.string.settings_jellyfin_unauthorized)
            else -> null
        }
        if (jellyfinMessage != null) {
            Text(
                text = jellyfinMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    jellyfinBusy = true
                    val password = jellyfinPassword
                    scope.launch {
                        try {
                            jellyfin.signIn(jellyfinUrl, jellyfinUser, password)
                            if (jellyfin.state.value.signedIn) jellyfinPassword = ""
                        } finally {
                            jellyfinBusy = false
                        }
                    }
                },
                enabled = !jellyfinBusy && jellyfinUrl.isNotBlank() && jellyfinUser.isNotBlank()
            ) {
                Text(stringResource(R.string.settings_jellyfin_sign_in))
            }
            if (jellyfinState.signedIn) {
                TextButton(onClick = { scope.launch { jellyfin.signOut() } }) {
                    Text(stringResource(R.string.settings_jellyfin_sign_out))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()

        TextButton(
            onClick = onOpenLog,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.BugReport, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_connection_log))
        }

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

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = subtitleMacro,
            onValueChange = { value ->
                subtitleMacro = value
                settingsRepository.setSubtitleMacro(value)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_subtitle_macro)) },
            supportingText = { Text(stringResource(R.string.settings_subtitle_macro_hint)) },
            minLines = 3
        )
        }

        Spacer(modifier = Modifier.height(16.dp))
        
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
