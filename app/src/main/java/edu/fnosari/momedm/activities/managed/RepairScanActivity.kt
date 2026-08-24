package edu.fnosari.momedm.activities.managed

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import edu.fnosari.momedm.R
import edu.fnosari.momedm.managed.ManagedLinkService
import edu.fnosari.momedm.managed.ManagedSetup
import edu.fnosari.momedm.protocol.PairingPayload
import edu.fnosari.momedm.ui.ManagedThemed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Re-pairs this child device by scanning the parent's pairing code.
 *
 * A child holds the controller id and shared secret it was enrolled with. When a parent reinstalls
 * their app, rotates the secret, or moves to a new phone, that identity no longer matches and the
 * child can never authenticate again — previously only a factory reset could fix it. Scanning the
 * parent's current code writes the new identity and restarts the link.
 *
 * Reached from the parent menu, which offers this action only to someone who entered the parent PIN
 * — or, when no PIN has been set at all, only while the phone is neither in child mode nor locked.
 * The activity itself trusts any code it is handed, so that gate is the whole of the protection: it
 * is what stops a child on a restricted phone re-pointing the device at a controller of their own.
 */
class RepairScanActivity : ComponentActivity() {
    companion object { private const val LOG_TAG = "RepairScanActivity" }

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ManagedThemed(this) {
                var status by remember { mutableStateOf<String?>(null) }
                var done by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    CameraPreview(
                        enabled = !done,
                        onScanned = { text ->
                            if (done) return@CameraPreview
                            val identity = PairingPayload.parse(text)
                            if (identity == null) {
                                // A camera decodes anything held in front of it; say so rather than
                                // failing silently, and keep scanning.
                                status = getString(R.string.repair_not_ours)
                                return@CameraPreview
                            }
                            done = true
                            status = getString(R.string.repair_done)
                            CoroutineScope(Dispatchers.Main).launch {
                                ManagedSetup.prefs(this@RepairScanActivity)
                                    .saveProvisioning(identity.controllerId, identity.secretBase64)
                                Log.d(LOG_TAG, "Re-paired with controller ${identity.controllerId.take(8)}")
                                ManagedLinkService.restart(this@RepairScanActivity)
                                finish()
                            }
                        },
                    )
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            status ?: stringResource(R.string.repair_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    @Composable
    private fun CameraPreview(enabled: Boolean, onScanned: (String) -> Unit) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val view = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    runCatching {
                        val provider = providerFuture.get()
                        val preview = androidx.camera.core.Preview.Builder().build()
                            .also { it.setSurfaceProvider(view.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(analysisExecutor, QrAnalyzer(onScanned)) }
                        provider.unbindAll()
                        if (enabled) provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }.onFailure { Log.w(LOG_TAG, "Camera unavailable: ${it::class.simpleName}") }
                }, ContextCompat.getMainExecutor(ctx))
                view
            },
        )
    }
}

/**
 * Decodes QR codes from camera frames with zxing.
 *
 * Results are handed back on the main thread because the callback touches Compose state. Decoding
 * failures are the normal case — most frames contain no code at all — so they are swallowed rather
 * than logged.
 */
private class QrAnalyzer(private val onScanned: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            val source = PlanarYUVLuminanceSource(
                bytes, plane.rowStride, image.height, 0, 0,
                minOf(plane.rowStride, image.width), image.height, false,
            )
            val result = runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source))) }.getOrNull()
            if (result != null) {
                val text = result.text
                android.os.Handler(android.os.Looper.getMainLooper()).post { onScanned(text) }
            }
        } catch (t: Throwable) {
            // A malformed frame must never take the scanner down.
        } finally {
            reader.reset()
            image.close()
        }
    }
}
