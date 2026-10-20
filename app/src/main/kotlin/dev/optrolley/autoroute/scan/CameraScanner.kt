package dev.optrolley.autoroute.scan

import android.os.SystemClock
import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.optrolley.core.Barcode
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executors

/**
 * Where scanned codes come from. The app uses [CameraScanner]; tests supply
 * their own, since there is no camera under Robolectric.
 */
fun interface Scanner {
    /** Shows the viewfinder; calls [onCode] on the main thread for each code accepted while [active]. */
    @Composable
    fun Viewfinder(active: Boolean, onCode: (Barcode) -> Unit, modifier: Modifier)
}

/**
 * The back camera, decoded on a thread of its own. Each frame is closed as
 * soon as it has been read; nothing from the camera is kept (O47).
 */
object CameraScanner : Scanner {
    @Composable
    override fun Viewfinder(active: Boolean, onCode: (Barcode) -> Unit, modifier: Modifier) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current
        val isActive by rememberUpdatedState(active)
        val deliver by rememberUpdatedState(onCode)
        var surface by remember { mutableStateOf<SurfaceRequest?>(null) }

        LaunchedEffect(lifecycle) {
            val provider = ProcessCameraProvider.awaitInstance(context)
            val preview = Preview.Builder().build().apply { setSurfaceProvider { surface = it } }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // Enough pixels across an EAN-13's bars at arm's length, without
                // making each frame slow to decode.
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .build()
            val thread = Executors.newSingleThreadExecutor()
            val main = ContextCompat.getMainExecutor(context)
            val decoder = BarcodeDecoder()
            val gate = ScanGate()
            analysis.setAnalyzer(thread) { frame ->
                frame.use {
                    if (!isActive) return@use
                    val plane = it.planes[0]
                    val buffer = plane.buffer
                    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                    val code = decoder.decode(bytes, it.width, it.height, plane.rowStride) ?: return@use
                    if (gate.accept(code, SystemClock.elapsedRealtime())) {
                        main.execute { if (isActive) deliver(code) }
                    }
                }
            }
            try {
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                awaitCancellation()
            } finally {
                provider.unbind(preview, analysis)
                analysis.clearAnalyzer()
                thread.shutdown()
            }
        }

        surface?.let { CameraXViewfinder(surfaceRequest = it, modifier = modifier) }
    }
}
