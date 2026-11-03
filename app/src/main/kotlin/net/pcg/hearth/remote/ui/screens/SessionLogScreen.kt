package net.pcg.hearth.remote.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.pcg.hearth.remote.R
import net.pcg.hearth.remote.protocol.RemoteProtocol
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SessionLogScreen(
    lines: List<RemoteProtocol.SessionLogEntry>,
    closeReason: String?,
    onBack: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.lastIndex)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.session_log_back))
        }
        Text(
            text = stringResource(R.string.session_log_title),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (closeReason.isNullOrBlank()) {
                stringResource(R.string.session_log_close_none)
            } else {
                stringResource(R.string.session_log_close_reason, closeReason)
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (closeReason.isNullOrBlank()) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.session_log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                items(lines) { line ->
                    Text(
                        text = "${formatLogTime(line.timeMs)} ${line.direction} ${line.text}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private val logClock: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

private fun formatLogTime(timeMs: Long): String = logClock.format(Instant.ofEpochMilli(timeMs))
