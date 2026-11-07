package net.pcg.hearth.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.pcg.hearth.remote.R
import net.pcg.hearth.remote.protocol.OverlayController
import net.pcg.hearth.remote.protocol.OverlayKey

/** Small live-TV temperature toggle. Disabled, with a hint, when Hearth does not answer. */
@Composable
internal fun TemperaturePillToggle(overlays: OverlayController) {
    val state by overlays.state.collectAsState()
    LaunchedEffect(Unit) { overlays.refresh() }
    val enabled = state.available && !state.loading
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconToggleButton(
            checked = state.config?.wxpill == true,
            onCheckedChange = { overlays.set(OverlayKey.WXPILL, it) },
            enabled = enabled,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Filled.DeviceThermostat,
                contentDescription = stringResource(R.string.overlay_wxpill),
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    state.config?.wxpill == true -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (!state.loading && !state.available) {
            Text(
                text = stringResource(R.string.overlay_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.width(96.dp)
            )
        }
    }
}

/** All five Hearth overlay switches. The same unavailable hint as the pill. */
@Composable
internal fun OverlaySettingsSection(overlays: OverlayController) {
    val state by overlays.state.collectAsState()
    LaunchedEffect(Unit) { overlays.refresh() }
    val enabled = state.available && !state.loading
    Text(
        text = stringResource(R.string.overlay_title),
        style = MaterialTheme.typography.titleMedium
    )
    if (!state.loading && !state.available) {
        Text(
            text = stringResource(R.string.overlay_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )
    }
    OverlayKey.ALL.forEach { key ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = stringResource(overlayLabel(key)))
            Switch(
                checked = state.config?.get(key) == true,
                onCheckedChange = { overlays.set(key, it) },
                enabled = enabled
            )
        }
    }
}

private fun overlayLabel(key: String): Int = when (key) {
    OverlayKey.WXPILL -> R.string.overlay_wxpill
    OverlayKey.QUAKE -> R.string.overlay_quake
    OverlayKey.WARN -> R.string.overlay_warn
    OverlayKey.SABRES -> R.string.overlay_sabres
    OverlayKey.WEATHER -> R.string.overlay_weather
    else -> R.string.overlay_title
}
