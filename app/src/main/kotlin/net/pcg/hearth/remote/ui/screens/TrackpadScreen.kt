package net.pcg.hearth.remote.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pcg.hearth.remote.R
import net.pcg.hearth.remote.protocol.RemoteProtocol
import net.pcg.hearth.remote.protocol.TvConnection
import kotlin.math.abs

@Composable
fun TrackpadScreen(
    tvConnection: TvConnection
) {
    val view = LocalView.current
    var lastDragX by remember { mutableFloatStateOf(0f) }
    var lastDragY by remember { mutableFloatStateOf(0f) }
    
    val swipeThreshold = 50f
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Power button row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            RemoteButton(
                icon = Icons.Default.PowerSettingsNew,
                contentDescription = stringResource(R.string.btn_power),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.togglePower()
                },
                tint = MaterialTheme.colorScheme.error
            )
            
            RemoteButton(
                icon = Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = stringResource(R.string.btn_volume_mute),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.sendVolumeMute()
                }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Trackpad area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            tvConnection.sendDpadCenter()
                        },
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            tvConnection.sendMenu()
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            lastDragX = 0f
                            lastDragY = 0f
                        },
                        onDrag = { _, dragAmount ->
                            lastDragX += dragAmount.x
                            lastDragY += dragAmount.y
                            
                            when {
                                lastDragX > swipeThreshold -> {
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    tvConnection.sendDpadRight()
                                    lastDragX = 0f
                                    lastDragY = 0f
                                }
                                lastDragX < -swipeThreshold -> {
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    tvConnection.sendDpadLeft()
                                    lastDragX = 0f
                                    lastDragY = 0f
                                }
                                lastDragY > swipeThreshold -> {
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    tvConnection.sendDpadDown()
                                    lastDragX = 0f
                                    lastDragY = 0f
                                }
                                lastDragY < -swipeThreshold -> {
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    tvConnection.sendDpadUp()
                                    lastDragX = 0f
                                    lastDragY = 0f
                                }
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.a11y_trackpad),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RemoteButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.btn_back),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.sendBack()
                }
            )
            
            RemoteButton(
                icon = Icons.Default.Home,
                contentDescription = stringResource(R.string.btn_home),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.sendHome()
                }
            )
            
            RemoteButton(
                icon = Icons.Default.Menu,
                contentDescription = stringResource(R.string.btn_menu),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.sendMenu()
                }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Play/Pause button
        FilledIconButton(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                tvConnection.sendPlayPause()
            },
            modifier = Modifier.size(80.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.btn_play_pause),
                modifier = Modifier.size(48.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Volume and Channel controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RemoteButton(
                    icon = Icons.AutoMirrored.Filled.VolumeDown,
                    contentDescription = stringResource(R.string.btn_volume_down),
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        tvConnection.sendVolumeDown()
                    }
                )
                RemoteButton(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(R.string.btn_volume_up),
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        tvConnection.sendVolumeUp()
                    }
                )
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RemoteButton(
                    icon = Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.btn_channel_up),
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        tvConnection.sendChannelUp()
                    }
                )
                Text(
                    text = stringResource(R.string.btn_channel_mark),
                    style = MaterialTheme.typography.labelSmall
                )
                RemoteButton(
                    icon = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.btn_channel_down),
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        tvConnection.sendChannelDown()
                    }
                )
            }
        }
    }
}

@Composable
fun ConnectionStatus(
    connectionState: RemoteProtocol.ConnectionState,
    lastError: String? = null,
    powerOn: Boolean? = null,
    onTogglePower: (() -> Unit)? = null,
    onOpenLog: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val (text, color) = when (connectionState) {
        is RemoteProtocol.ConnectionState.Connected ->
            stringResource(R.string.remote_connected) to Color(0xFF4CAF50)
        is RemoteProtocol.ConnectionState.Connecting ->
            stringResource(R.string.remote_reconnecting) to Color(0xFFFFC107)
        is RemoteProtocol.ConnectionState.Disconnected ->
            stringResource(R.string.remote_disconnected) to Color(0xFFF44336)
        is RemoteProtocol.ConnectionState.Error ->
            connectionState.message to Color(0xFFF44336)
    }
    val showLastError = !lastError.isNullOrBlank() &&
        (connectionState !is RemoteProtocol.ConnectionState.Error || connectionState.message != lastError)

    val powerLabel = when (powerOn) {
        true -> stringResource(R.string.power_on)
        false -> stringResource(R.string.power_standby)
        null -> stringResource(R.string.power_unknown)
    }
    val powerTint = when (powerOn) {
        true -> Color(0xFF4CAF50)
        false -> Color(0xFF9E9E9E)
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(modifier = modifier.fillMaxWidth()) {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(horizontal = 64.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showLastError) {
            Text(
                text = stringResource(R.string.remote_last_error, lastError!!),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFF44336),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onOpenLog != null) {
            TextButton(onClick = onOpenLog) {
                Text(stringResource(R.string.settings_connection_log))
            }
        }
    }
        if (onTogglePower != null) {
            Column(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(onClick = onTogglePower) {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = stringResource(R.string.btn_power) + ", " + powerLabel,
                        tint = powerTint
                    )
                }
                Text(
                    text = powerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = powerTint
                )
            }
        }
    }
}

@Composable
fun RemoteButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(28.dp)
        )
    }
}
