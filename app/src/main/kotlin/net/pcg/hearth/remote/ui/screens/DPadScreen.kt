package net.pcg.hearth.remote.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pcg.hearth.remote.R
import net.pcg.hearth.remote.protocol.RemoteProtocol
import net.pcg.hearth.remote.protocol.TvConnection

@Composable
fun DPadScreen(
    tvConnection: TvConnection,
    connectionState: RemoteProtocol.ConnectionState
) {
    val view = LocalView.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ConnectionStatus(connectionState)
        
        Spacer(modifier = Modifier.height(8.dp))
        
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
                    tvConnection.sendPower()
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
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // D-Pad
        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            // Up
            IconButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.sendDpadUp()
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.a11y_dpad_up),
                    modifier = Modifier.size(40.dp)
                )
            }
            
            // Down
            IconButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.sendDpadDown()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.a11y_dpad_down),
                    modifier = Modifier.size(40.dp)
                )
            }
            
            // Left
            IconButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.sendDpadLeft()
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.a11y_dpad_left),
                    modifier = Modifier.size(40.dp)
                )
            }
            
            // Right
            IconButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.sendDpadRight()
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.a11y_dpad_right),
                    modifier = Modifier.size(40.dp)
                )
            }
            
            // Center (OK)
            FilledIconButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.sendDpadCenter()
                },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = stringResource(R.string.btn_ok),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
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
        
        Spacer(modifier = Modifier.height(24.dp))
        
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
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Volume and Channel controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RemoteButton(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(R.string.btn_volume_up),
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        tvConnection.sendVolumeUp()
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                RemoteButton(
                    icon = Icons.AutoMirrored.Filled.VolumeDown,
                    contentDescription = stringResource(R.string.btn_volume_down),
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        tvConnection.sendVolumeDown()
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
                    text = "CH",
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
