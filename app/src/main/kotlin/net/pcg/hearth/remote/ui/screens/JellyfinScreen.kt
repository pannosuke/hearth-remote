package net.pcg.hearth.remote.ui.screens

import android.graphics.BitmapFactory
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pcg.hearth.remote.R
import net.pcg.hearth.remote.protocol.JellyfinApi
import net.pcg.hearth.remote.protocol.JellyfinNowPlaying
import net.pcg.hearth.remote.protocol.JellyfinProblem
import net.pcg.hearth.remote.protocol.JellyfinRemote
import net.pcg.hearth.remote.protocol.JellyfinSession
import net.pcg.hearth.remote.protocol.JellyfinUiState
import net.pcg.hearth.remote.protocol.JellyfinUpNext
import net.pcg.hearth.remote.protocol.SubtitleTrack
import net.pcg.hearth.remote.protocol.TvConnection

@Composable
internal fun JellyfinScreen(
    tvConnection: TvConnection,
    jellyfin: JellyfinRemote,
    onOpenSettings: () -> Unit
) {
    val ui by jellyfin.state.collectAsState()
    val tuner by tvConnection.tunerStatus.collectAsState()
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            jellyfin.refresh()
            tvConnection.refreshTuner()
            delay(4_000)
        }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    fun tap(action: suspend () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        scope.launch { action() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.jellyfin_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )

        if (!ui.signedIn) {
            SignedOutCard(ui, onOpenSettings)
        } else if (!ui.loaded) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        } else {
            if (ui.sessions.size > 1) {
                SessionPicker(ui.sessions, ui.selectedSessionId) { deviceId ->
                    tap { jellyfin.selectSession(deviceId) }
                }
            }
            if (ui.sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.jellyfin_no_session),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else if (ui.selectedSessionId != null) {
                NowPlayingHeader(jellyfin, ui, nowMs)
                TransportRow(
                    paused = ui.nowPlaying?.paused != false,
                    canSeek = ui.nowPlaying?.positionTicks != null,
                    onSeek = { delta ->
                        val item = ui.nowPlaying
                        val shown = if (item == null) null else JellyfinApi.shownTicks(item, nowMs, ui.observedAtMs)
                        val target = JellyfinApi.seekTicks(shown, delta, item?.runtimeTicks)
                        if (target != null) tap { jellyfin.seekTo(target) }
                    },
                    onPlayPause = { tap { jellyfin.playPause() } }
                )
                if (ui.nowPlaying != null) {
                    SubtitleRow(ui.nowPlaying!!) { track ->
                        tap { jellyfin.setSubtitle(track) }
                    }
                }
                VolumeRow(
                    volume = tuner.volume,
                    muted = tuner.muted,
                    onUp = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        tvConnection.nudgeVolume(1)
                    },
                    onDown = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        tvConnection.nudgeVolume(-1)
                    },
                    onMute = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        tvConnection.toggleTunerMute()
                    }
                )
            }
            if (ui.problem == JellyfinProblem.RequestFailed) {
                Text(
                    text = stringResource(R.string.jellyfin_request_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (ui.upNext.isNotEmpty() && ui.selectedSessionId != null) {
                Text(
                    text = stringResource(R.string.jellyfin_up_next),
                    style = MaterialTheme.typography.titleMedium
                )
                ui.upNext.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { item ->
                            UpNextCard(
                                jellyfin = jellyfin,
                                item = item,
                                modifier = Modifier.weight(1f),
                                onClick = { tap { jellyfin.play(item.itemId) } }
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SignedOutCard(ui: JellyfinUiState, onOpenSettings: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = when (ui.problem) {
                    JellyfinProblem.Unauthorized -> stringResource(R.string.settings_jellyfin_unauthorized)
                    JellyfinProblem.SignInFailed -> stringResource(R.string.settings_jellyfin_failed)
                    else -> stringResource(R.string.jellyfin_sign_in_needed)
                },
                style = MaterialTheme.typography.bodyLarge
            )
            FilledTonalButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.jellyfin_open_settings))
            }
        }
    }
}

@Composable
private fun SessionPicker(
    sessions: List<JellyfinSession>,
    selectedId: String?,
    onSelect: (String) -> Unit
) {
    Text(stringResource(R.string.jellyfin_pick_session), style = MaterialTheme.typography.titleSmall)
    sessions.forEach { session ->
        val selected = session.id == selectedId
        if (selected) {
            Button(
                onClick = { onSelect(session.deviceId) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(sessionLabel(session), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            FilledTonalButton(
                onClick = { onSelect(session.deviceId) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(sessionLabel(session), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun NowPlayingHeader(jellyfin: JellyfinRemote, ui: JellyfinUiState, nowMs: Long) {
    val item = ui.nowPlaying
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Poster(
                jellyfin = jellyfin,
                itemId = item?.imageItemId,
                tag = item?.imageTag,
                modifier = Modifier.size(width = 112.dp, height = 148.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item == null) {
                    Text(stringResource(R.string.jellyfin_nothing_playing), style = MaterialTheme.typography.titleLarge)
                } else {
                    Text(
                        text = item.headline(),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val detail = item.detail()
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val position = JellyfinApi.shownTicks(item, nowMs, ui.observedAtMs)
                    val fraction = JellyfinApi.fraction(position, item.runtimeTicks)
                    if (position != null && item.runtimeTicks != null) {
                        Text(
                            text = "${JellyfinApi.formatTicks(position)} / ${JellyfinApi.formatTicks(item.runtimeTicks)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportRow(
    paused: Boolean,
    canSeek: Boolean,
    onSeek: (Int) -> Unit,
    onPlayPause: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = { onSeek(-10) },
            enabled = canSeek,
            modifier = Modifier.weight(1f).height(72.dp)
        ) {
            Text(stringResource(R.string.jellyfin_back_10))
        }
        Button(onClick = onPlayPause, modifier = Modifier.weight(1f).height(72.dp)) {
            Icon(
                imageVector = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = stringResource(R.string.btn_play_pause),
                modifier = Modifier.size(36.dp)
            )
        }
        FilledTonalButton(
            onClick = { onSeek(30) },
            enabled = canSeek,
            modifier = Modifier.weight(1f).height(72.dp)
        ) {
            Text(stringResource(R.string.jellyfin_forward_30))
        }
    }
}

@Composable
private fun SubtitleRow(item: JellyfinNowPlaying, onPick: (SubtitleTrack) -> Unit) {
    val japanese = JellyfinApi.matchingSubtitle(item.streams, SubtitleTrack.Japanese)
    val english = JellyfinApi.matchingSubtitle(item.streams, SubtitleTrack.English)
    val current = item.subtitleIndex
    Text(stringResource(R.string.jellyfin_subtitles), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SubtitleButton(
            label = stringResource(R.string.jellyfin_sub_ja),
            enabled = japanese != null,
            selected = japanese != null && current == japanese,
            modifier = Modifier.weight(1f),
            onClick = { onPick(SubtitleTrack.Japanese) }
        )
        SubtitleButton(
            label = stringResource(R.string.jellyfin_sub_en),
            enabled = english != null,
            selected = english != null && current == english,
            modifier = Modifier.weight(1f),
            onClick = { onPick(SubtitleTrack.English) }
        )
        SubtitleButton(
            label = stringResource(R.string.jellyfin_sub_off),
            enabled = true,
            selected = current == null || current < 0,
            modifier = Modifier.weight(1f),
            onClick = { onPick(SubtitleTrack.Off) }
        )
    }
}

@Composable
private fun SubtitleButton(
    label: String,
    enabled: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp)) {
            Text(label, maxLines = 1)
        }
    } else {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp)) {
            Text(label, maxLines = 1)
        }
    }
}

@Composable
private fun UpNextCard(
    jellyfin: JellyfinRemote,
    item: JellyfinUpNext,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.height(132.dp)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Poster(
                jellyfin = jellyfin,
                itemId = item.itemId,
                tag = item.imageTag,
                modifier = Modifier.fillMaxWidth().height(64.dp)
            )
            Text(item.headline(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = item.detail()
            if (detail.isNotBlank() && detail != item.headline()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun Poster(
    jellyfin: JellyfinRemote,
    itemId: String?,
    tag: String?,
    modifier: Modifier
) {
    var image by remember(itemId, tag) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(itemId, tag) {
        image = null
        if (itemId.isNullOrBlank()) return@LaunchedEffect
        val bytes = withContext(Dispatchers.IO) { jellyfin.poster(itemId, tag) } ?: return@LaunchedEffect
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@LaunchedEffect
        image = bitmap.asImageBitmap()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val current = image
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun VolumeRow(
    volume: Int?,
    muted: Boolean?,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onMute: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = onUp, modifier = Modifier.weight(1f).height(80.dp), shape = RoundedCornerShape(24.dp)) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.btn_volume_up), Modifier.size(36.dp))
        }
        Text(
            text = volume?.toString() ?: "—",
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp)
        )
        Button(onClick = onDown, modifier = Modifier.weight(1f).height(80.dp), shape = RoundedCornerShape(24.dp)) {
            Icon(Icons.AutoMirrored.Filled.VolumeDown, stringResource(R.string.btn_volume_down), Modifier.size(36.dp))
        }
    }
    FilledTonalButton(
        onClick = onMute,
        modifier = Modifier.fillMaxWidth().height(56.dp),
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
        Spacer(Modifier.width(8.dp))
        Text(
            when (muted) {
                true -> stringResource(R.string.mute_on)
                false -> stringResource(R.string.mute_off)
                null -> stringResource(R.string.btn_volume_mute)
            }
        )
    }
}

private fun sessionLabel(session: JellyfinSession): String {
    val device = session.deviceName.ifBlank { session.client }
    val client = session.client
    return if (client.isBlank() || client == device) device else "$device · $client"
}
