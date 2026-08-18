package tools.isekai.cameraclient

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean
import uniffi.isekai_client_ffi.pairingCodeInScan

/**
 * The camera, looking for the QR a camera server displays.
 *
 * Reports only this project's own pairing code. Everything else in front of
 * the lens is ignored and scanning continues, because handing an arbitrary
 * string to the proxy would spend a request to be told it is not a pairing
 * code. What counts is decided by the core (`pairingCodeInScan`), so the app
 * does not carry its own idea of the format -- same design as iOS's
 * `QRScannerView.swift`.
 */
@Composable
fun QrScanScreen(
    onCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // A scan fires once. Barcode results arrive on (almost) every analyzed
    // frame the code is in view, and without this the caller would try to
    // pair a dozen times off one code -- and a code works once.
    val handled = remember { AtomicBoolean(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview =
                        Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                    val analysis =
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                    val scanner = BarcodeScanning.getClient()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null || handled.get()) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                for (barcode in barcodes) {
                                    val raw = barcode.rawValue ?: continue
                                    val code = pairingCodeInScan(raw) ?: continue
                                    if (handled.compareAndSet(false, true)) {
                                        onCode(code)
                                    }
                                    break
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } catch (e: Exception) {
                        // No camera, or binding failed. The preview stays
                        // blank; the pairing code can still be typed in
                        // instead, same fallback iOS offers when camera
                        // access is denied.
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        Text(
            "Point this at the code the camera is showing.",
            color = Color.White,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Button(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            Text("Cancel")
        }
    }
}
