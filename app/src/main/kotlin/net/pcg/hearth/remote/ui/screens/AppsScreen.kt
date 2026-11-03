package net.pcg.hearth.remote.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pcg.hearth.remote.R
import net.pcg.hearth.remote.protocol.TvConnection

data class AppItem(
    val name: String,
    val icon: ImageVector,
    val color: Color,
    val onLaunch: (TvConnection) -> Unit,
)

@Composable
private fun appShortcuts(): List<AppItem> = listOf(
    AppItem(stringResource(R.string.app_youtube), Icons.Default.PlayCircle, Color(0xFFFF0000)) { it.launchYouTube() },
    AppItem(stringResource(R.string.app_netflix), Icons.Default.Tv, Color(0xFFE50914)) { it.launchNetflix() },
    AppItem(stringResource(R.string.app_disney), Icons.Default.Movie, Color(0xFF113CCF)) { it.launchDisneyPlus() },
    AppItem(stringResource(R.string.app_amazon), Icons.Default.VideoLibrary, Color(0xFF00A8E1)) { it.launchAmazonVideo() },
    AppItem(stringResource(R.string.app_jellyfin), Icons.Default.Theaters, Color(0xFF00A4DC)) { it.launchJellyfin() },
    AppItem(stringResource(R.string.app_tver), Icons.Default.LiveTv, Color(0xFFF15A22)) { it.launchTver() },
    AppItem(stringResource(R.string.app_nhk), Icons.Default.AccountBalance, Color(0xFFE60012)) { it.launchNhk() },
    AppItem(stringResource(R.string.app_hulu), Icons.Default.SmartDisplay, Color(0xFF1CE783)) { it.launchHulu() },
    AppItem(stringResource(R.string.app_live_tv), Icons.Default.SettingsInputAntenna, Color(0xFFB0BEC5)) { it.launchLiveTv() },
)

@Composable
fun AppsScreen(
    tvConnection: TvConnection
) {
    val view = LocalView.current
    val apps = appShortcuts()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.apps_title),
            style = MaterialTheme.typography.headlineSmall
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(apps) { app ->
                AppCard(
                    app = app,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        app.onLaunch(tvConnection)
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Deep Links",
            style = MaterialTheme.typography.titleMedium
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Tap and hold an app button to enter a custom deep link URL.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun AppCard(
    app: AppItem,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = app.icon,
                contentDescription = app.name,
                tint = app.color,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = app.name,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
