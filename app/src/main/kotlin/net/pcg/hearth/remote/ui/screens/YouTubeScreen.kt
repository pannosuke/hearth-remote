package net.pcg.hearth.remote.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.pcg.hearth.remote.R
import net.pcg.hearth.remote.protocol.GuideStatus
import net.pcg.hearth.remote.protocol.HighlightVideo
import net.pcg.hearth.remote.protocol.SportsnetFeed
import net.pcg.hearth.remote.protocol.SportsnetGuide
import net.pcg.hearth.remote.protocol.TvConnection

/**
 * Shown only while the TV foreground package is YouTube.
 * The chip is Eric's account. It does not switch profiles.
 */
@Composable
internal fun YouTubeScreen(
    tvConnection: TvConnection,
    highlights: SportsnetGuide
) {
    val tuner by tvConnection.tunerStatus.collectAsState()
    val feed by highlights.state.collectAsState()
    val view = LocalView.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        tvConnection.refreshTuner()
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val videos = highlights.visible(nowMs)
    fun tap(action: () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        action()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(
                    text = stringResource(R.string.youtube_profile_name),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.youtube_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.youtube_profile_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (videos.isEmpty()) {
            Text(
                text = emptyMessage(feed.status),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(videos, key = { it.videoId }) { video ->
                    HighlightCard(video) {
                        tap { tvConnection.launchApp(video.watchUrl) }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { tap { tvConnection.sendRewind() } },
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Icon(Icons.Default.FastRewind, stringResource(R.string.btn_rewind))
            }
            FilledTonalButton(
                onClick = { tap { tvConnection.sendPlayPause() } },
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Icon(Icons.Default.PlayArrow, stringResource(R.string.btn_play_pause))
            }
            FilledTonalButton(
                onClick = { tap { tvConnection.sendFastForward() } },
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Icon(Icons.Default.FastForward, stringResource(R.string.btn_fast_forward))
            }
            FilledTonalButton(
                onClick = { tap { tvConnection.sendNext() } },
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.btn_next))
            }
        }

        Spacer(Modifier.height(8.dp))

        FilledTonalButton(
            onClick = { tap { tvConnection.sendCaptions() } },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Default.ClosedCaption, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.btn_captions))
        }

        Spacer(Modifier.height(8.dp))

        VolumePresetRow(current = tuner.volume, onSelect = tvConnection::setVolume)

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LargeIconButton(
                onClick = { tap { tvConnection.sendVolumeDown() } },
                modifier = Modifier.weight(1f).height(72.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeDown,
                    stringResource(R.string.btn_volume_down),
                    Modifier.size(32.dp)
                )
            }
            LargeIconButton(
                onClick = { tap { tvConnection.sendVolumeUp() } },
                modifier = Modifier.weight(1f).height(72.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    stringResource(R.string.btn_volume_up),
                    Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun emptyMessage(status: GuideStatus): String {
    return when (status) {
        GuideStatus.Loading -> stringResource(R.string.youtube_loading)
        GuideStatus.Unavailable -> stringResource(R.string.youtube_unavailable)
        GuideStatus.Ready -> stringResource(R.string.youtube_empty)
    }
}

@Composable
private fun HighlightCard(video: HighlightVideo, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.youtube_published,
                    SportsnetFeed.formatPublished(video.publishedMs)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
