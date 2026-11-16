package net.pcg.hearth.remote.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pcg.hearth.remote.R
import net.pcg.hearth.remote.protocol.ExplainController
import net.pcg.hearth.remote.protocol.ExplainPhase
import net.pcg.hearth.remote.protocol.jpegForUpload
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One rear-camera shot, held only in memory, then uploaded. The photo is not
 * written to the gallery, MediaStore, or a cache file, and nothing is scanned.
 */
@Composable
internal fun SnapScreenButton(snap: ExplainController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val phase by snap.state.collectAsState()
    var showCamera by remember { mutableStateOf(false) }
    var cameraNote by remember { mutableStateOf<Int?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraNote = null
            showCamera = true
        } else {
            cameraNote = R.string.snap_camera_needed
        }
    }
    Column(modifier) {
    Button(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            cameraNote = null
            val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (allowed) showCamera = true else permission.launch(Manifest.permission.CAMERA)
        },
        enabled = phase != ExplainPhase.Requesting && !showCamera,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    ) {
        Text(
            text = stringResource(R.string.btn_snap_screen),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
    val upload = explainStatusText(phase)
    val note = cameraNote?.let { stringResource(it) }
    val message = if (phase == ExplainPhase.Idle) note else upload
    if (message != null) {
        val failed = phase == ExplainPhase.NeedsUpdate || phase == ExplainPhase.Failed || phase == ExplainPhase.Idle
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    }
    if (showCamera) {
        Dialog(
            onDismissRequest = { showCamera = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            RearCameraCapture(
                onCancel = { showCamera = false },
                onFailed = {
                    showCamera = false
                    cameraNote = R.string.snap_camera_failed
                },
                onPhoto = { jpeg ->
                    showCamera = false
                    cameraNote = null
                    snap.upload(jpeg)
                }
            )
        }
    }
}

@Composable
private fun RearCameraCapture(
    onCancel: () -> Unit,
    onFailed: () -> Unit,
    onPhoto: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
            .build()
    }
    val busy = remember { AtomicBoolean(false) }
    var shutterEnabled by remember { mutableStateOf(true) }
    var bound by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        onDispose {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { future.get().unbindAll() }
            }, mainExecutor)
        }
    }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            update = { view ->
                if (bound) return@AndroidView
                bound = true
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    val provider = runCatching { future.get() }.getOrElse {
                        onFailed()
                        return@addListener
                    }
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(view.surfaceProvider)
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture
                        )
                    }.onFailure { onFailed() }
                }, mainExecutor)
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.pairing_cancel))
            }
            FilledTonalButton(
                onClick = {
                    if (!busy.compareAndSet(false, true)) return@FilledTonalButton
                    shutterEnabled = false
                    imageCapture.takePicture(
                        mainExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val rotation = image.imageInfo.rotationDegrees
                                val bytes = readJpeg(image)
                                if (bytes == null) {
                                    busy.set(false)
                                    onFailed()
                                    return
                                }
                                scope.launch {
                                    val jpeg = try {
                                        withContext(Dispatchers.Default) { jpegForUpload(bytes, rotation) }
                                    } catch (_: Exception) {
                                        null
                                    }
                                    if (jpeg == null) onFailed() else onPhoto(jpeg)
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                busy.set(false)
                                onFailed()
                            }
                        }
                    )
                },
                enabled = shutterEnabled,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(Icons.Filled.PhotoCamera, stringResource(R.string.snap_shutter))
            }
        }
    }
}

/** Copies the JPEG out of the camera buffer and closes it. No file is created. */
private fun readJpeg(image: ImageProxy): ByteArray? {
    return try {
        if (image.format != ImageFormat.JPEG || image.planes.isEmpty()) return null
        val buffer = image.planes[0].buffer
        buffer.rewind()
        ByteArray(buffer.remaining()).also { buffer.get(it) }.takeIf { it.isNotEmpty() }
    } finally {
        image.close()
    }
}

@Composable
internal fun explainStatusText(phase: ExplainPhase): String? = when (phase) {
    ExplainPhase.Idle -> null
    ExplainPhase.Requesting -> stringResource(R.string.explain_requesting)
    ExplainPhase.Sent -> stringResource(R.string.explain_sent)
    ExplainPhase.NeedsUpdate -> stringResource(R.string.explain_update_hearth)
    ExplainPhase.Failed -> stringResource(R.string.explain_error)
}
