package net.pcg.hearth.remote.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.pcg.hearth.remote.BuildConfig

@Composable
fun VersionLabel(modifier: Modifier = Modifier) {
    Text(
        text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        fontSize = 10.sp,
        color = Color.Gray.copy(alpha = 0.6f),
        modifier = modifier
            .statusBarsPadding()
            .padding(8.dp)
    )
}
