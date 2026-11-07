package net.pcg.hearth.remote.ui.screens

import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.pcg.hearth.remote.R
import net.pcg.hearth.remote.protocol.Foreground
import net.pcg.hearth.remote.protocol.GuideState
import net.pcg.hearth.remote.protocol.JellyfinRemote
import net.pcg.hearth.remote.protocol.GuideStatus
import net.pcg.hearth.remote.protocol.NowAndNext
import net.pcg.hearth.remote.protocol.OsakaChannels
import net.pcg.hearth.remote.protocol.OsakaGuide
import net.pcg.hearth.remote.protocol.OverlayController
import net.pcg.hearth.remote.protocol.SharpIpCommands
import net.pcg.hearth.remote.protocol.SportsnetGuide
import net.pcg.hearth.remote.protocol.TerrestrialChannel
import net.pcg.hearth.remote.protocol.TvConnection
import net.pcg.hearth.remote.protocol.XmltvGuide

@Composable
internal fun RemoteShell(
    tvConnection: TvConnection,
    guide: OsakaGuide,
    highlights: SportsnetGuide,
    jellyfin: JellyfinRemote,
    overlays: OverlayController,
    onOpenSettings: () -> Unit
) {
    val packageName by tvConnection.currentAppPackage.collectAsState()
    val tuner by tvConnection.tunerStatus.collectAsState()
    val terrestrial = Foreground.isTerrestrial(packageName, tuner.channel)

    LaunchedEffect(packageName.isNullOrBlank(), terrestrial) {
        if (packageName.isNullOrBlank() || terrestrial) {
            while (isActive) {
                tvConnection.refreshTuner()
                delay(4000)
            }
        }
    }

    when {
        terrestrial -> TerrestrialScreen(tvConnection, guide, overlays)
        Foreground.isJellyfin(packageName) -> JellyfinScreen(tvConnection, jellyfin, onOpenSettings)
        Foreground.isYouTube(packageName) -> YouTubeScreen(tvConnection, highlights)
        else -> GenericRemoteScreen(tvConnection, packageName)
    }
}

@Composable
private fun TerrestrialScreen(
    tvConnection: TvConnection,
    guide: OsakaGuide,
    overlays: OverlayController
) {
    val tuner by tvConnection.tunerStatus.collectAsState()
    val guideState by guide.state.collectAsState()
    val subtitleRunning by tvConnection.subtitleMacroRunning.collectAsState()
    val view = LocalView.current
    val context = LocalContext.current
    val code = OsakaChannels.channelReading(tuner.channel)
    val currentButton = OsakaChannels.buttonNumber(code)
    val station = currentButton?.let { OsakaChannels.find(it) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(15_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.live_tv_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = station?.let { channelName(it) } ?: code ?: stringResource(R.string.channel_unknown),
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (code != null) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TemperaturePillToggle(overlays)
        }
        if (currentButton != null) {
            ProgrammeBlock(
                channel = station,
                slot = guide.nowAndNext(currentButton, nowMs),
                guideState = guideState,
                nowMs = nowMs,
                prominent = true
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LargeIconButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.nudgeVolume(-1)
                },
                modifier = Modifier.weight(1f).height(96.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeDown, stringResource(R.string.btn_volume_down), Modifier.size(40.dp))
            }
            Text(
                text = tuner.volume?.toString() ?: "—",
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(84.dp)
            )
            LargeIconButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.nudgeVolume(1)
                },
                modifier = Modifier.weight(1f).height(96.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.btn_volume_up), Modifier.size(40.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        VolumePresetRow(current = tuner.volume, onSelect = tvConnection::setVolume)

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val muted = tuner.muted
            FilledTonalButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.toggleTunerMute()
                },
                modifier = Modifier.weight(1f).height(64.dp),
                colors = if (muted == true) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                }
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(
                    when (muted) {
                        true -> stringResource(R.string.mute_on)
                        false -> stringResource(R.string.mute_off)
                        null -> stringResource(R.string.btn_volume_mute)
                    },
                    maxLines = 1
                )
            }
            FilledTonalButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.stepChannel(true)
                },
                modifier = Modifier.weight(1f).height(64.dp)
            ) {
                Text(stringResource(R.string.btn_channel_up))
            }
            FilledTonalButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tvConnection.stepChannel(false)
                },
                modifier = Modifier.weight(1f).height(64.dp)
            ) {
                Text(stringResource(R.string.btn_channel_down))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (!tvConnection.startSubtitleMacro(true)) return@OutlinedButton
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    Toast.makeText(context, context.getString(R.string.toast_subtitles_on), Toast.LENGTH_SHORT).show()
                },
                enabled = !subtitleRunning,
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Icon(Icons.Default.ClosedCaption, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.btn_subtitles_on), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = {
                    if (!tvConnection.startSubtitleMacro(false)) return@OutlinedButton
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    Toast.makeText(context, context.getString(R.string.toast_subtitles_off), Toast.LENGTH_SHORT).show()
                },
                enabled = !subtitleRunning,
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Icon(Icons.Default.ClosedCaptionDisabled, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.btn_subtitles_off), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(OsakaChannels.buttons, key = { it.button }) { channel ->
                ChannelCard(
                    channel = channel,
                    selected = channel.button == currentButton,
                    slot = guide.nowAndNext(channel.button, nowMs),
                    guideState = guideState,
                    nowMs = nowMs,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        tvConnection.selectChannel(channel.button)
                    }
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(
    channel: TerrestrialChannel,
    selected: Boolean,
    slot: NowAndNext,
    guideState: GuideState,
    nowMs: Long,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 168.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(channelName(channel), style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(
                text = stringResource(R.string.channel_button, channel.button),
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(6.dp))
            ProgrammeBlock(
                channel = channel,
                slot = slot,
                guideState = guideState,
                nowMs = nowMs,
                prominent = false,
                selected = selected
            )
        }
    }
}

@Composable
private fun ProgrammeBlock(
    channel: TerrestrialChannel?,
    slot: NowAndNext,
    guideState: GuideState,
    nowMs: Long,
    prominent: Boolean,
    selected: Boolean = false
) {
    val muted = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val current = slot.current
    if (current == null) {
        Text(
            text = missingListing(channel, guideState),
            style = if (prominent) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
            color = muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val upcoming = slot.next
        if (upcoming != null) {
            Text(
                text = stringResource(R.string.epg_next, upcoming.title),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }
    Text(
        text = current.title,
        style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        maxLines = if (prominent) 2 else 1,
        overflow = TextOverflow.Ellipsis
    )
    Text(
        text = XmltvGuide.formatRange(current.startMs, current.endMs),
        style = MaterialTheme.typography.bodySmall,
        color = muted,
        maxLines = 1
    )
    val fraction = slot.progress(nowMs)
    if (fraction != null) {
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
            trackColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.24f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
            }
        )
    }
    val upcoming = slot.next
    if (upcoming != null) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.epg_next, upcoming.title),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun missingListing(channel: TerrestrialChannel?, guideState: GuideState): String {
    val hasId = channel?.epgId != null
    return if (hasId && guideState.status == GuideStatus.Unavailable) {
        stringResource(R.string.epg_unavailable)
    } else {
        stringResource(R.string.now_playing_placeholder)
    }
}

@Composable
private fun channelName(channel: TerrestrialChannel): String {
    val res = when (channel.button) {
        10 -> R.string.ch_ytv
        8 -> R.string.ch_ktv
        7 -> R.string.ch_tvo
        6 -> R.string.ch_abc
        1 -> R.string.ch_nhk
        2 -> R.string.ch_nhk_e
        4 -> R.string.ch_mbs
        3 -> R.string.ch_sun
        else -> null
    }
    return if (res == null) channel.name else stringResource(res)
}

@Composable
private fun GenericRemoteScreen(tvConnection: TvConnection, packageName: String?) {
    val tuner by tvConnection.tunerStatus.collectAsState()
    val view = LocalView.current
    LaunchedEffect(Unit) { tvConnection.refreshTuner() }
    fun tap(action: () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        action()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = packageName ?: stringResource(R.string.generic_remote_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .size(280.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = { tap { tvConnection.sendDpadUp() } }, modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(72.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.a11y_dpad_up), Modifier.size(48.dp))
            }
            IconButton(onClick = { tap { tvConnection.sendDpadDown() } }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(72.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.a11y_dpad_down), Modifier.size(48.dp))
            }
            IconButton(onClick = { tap { tvConnection.sendDpadLeft() } }, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(72.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.a11y_dpad_left), Modifier.size(48.dp))
            }
            IconButton(onClick = { tap { tvConnection.sendDpadRight() } }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(72.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.a11y_dpad_right), Modifier.size(48.dp))
            }
            FilledIconButton(
                onClick = { tap { tvConnection.sendDpadCenter() } },
                modifier = Modifier.size(88.dp)
            ) {
                Text(stringResource(R.string.btn_ok), style = MaterialTheme.typography.titleLarge)
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { tap { tvConnection.sendBack() } }, modifier = Modifier.weight(1f).height(64.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_back))
            }
            FilledTonalButton(onClick = { tap { tvConnection.sendHome() } }, modifier = Modifier.weight(1f).height(64.dp)) {
                Icon(Icons.Default.Home, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_home))
            }
        }

        Spacer(Modifier.height(16.dp))

        FilledIconButton(onClick = { tap { tvConnection.sendPlayPause() } }, modifier = Modifier.size(88.dp)) {
            Icon(Icons.Default.PlayArrow, stringResource(R.string.btn_play_pause), Modifier.size(48.dp))
        }

        Spacer(Modifier.weight(1f))

        VolumePresetRow(current = tuner.volume, onSelect = tvConnection::setVolume)

        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LargeIconButton(onClick = { tap { tvConnection.sendVolumeDown() } }, modifier = Modifier.weight(1f).height(80.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeDown, stringResource(R.string.btn_volume_down), Modifier.size(36.dp))
            }
            LargeIconButton(onClick = { tap { tvConnection.sendVolumeUp() } }, modifier = Modifier.weight(1f).height(80.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.btn_volume_up), Modifier.size(36.dp))
            }
        }
    }
}

@Composable
internal fun VolumePresetRow(current: Int?, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SharpIpCommands.PRESET_LEVELS.forEach { level ->
            val selected = current == level
            val description = stringResource(R.string.btn_volume_preset, level)
            FilledTonalButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onSelect(level)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clearAndSetSemantics { contentDescription = description },
                colors = if (selected) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                }
            ) {
                Text(level.toString(), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
internal fun LargeIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(0.dp),
        content = content
    )
}
