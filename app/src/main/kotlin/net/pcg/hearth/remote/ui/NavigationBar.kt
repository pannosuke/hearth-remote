package net.pcg.hearth.remote.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.pcg.hearth.remote.R

@Composable
fun NavigationBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit
) {
    NavigationBar {
        NavigationBarItem(
            icon = { Icon(Icons.Default.LiveTv, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_remote)) },
            selected = currentScreen == Screen.Remote,
            onClick = { onNavigate(Screen.Remote) }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.TouchApp, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_trackpad)) },
            selected = currentScreen == Screen.Trackpad,
            onClick = { onNavigate(Screen.Trackpad) }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Apps, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_apps)) },
            selected = currentScreen == Screen.Apps,
            onClick = { onNavigate(Screen.Apps) }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_settings)) },
            selected = currentScreen == Screen.Settings || currentScreen == Screen.SessionLog,
            onClick = { onNavigate(Screen.Settings) }
        )
    }
}
