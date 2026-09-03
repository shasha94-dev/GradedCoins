package com.example.ngccoingallery

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun LiveBarcodeScanner(
    parser: NgcScanner,
    onResult: (NgcScanner.Result) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var status by remember { mutableStateOf("Place the barcode inside the box") }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_QR_CODE
            ).build()
        val barcodeScanner = BarcodeScanning.getClient(options)
        val busy = AtomicBoolean(false)
        var lastKey = ""
        var consecutive = 0
        var completed = false

        val listener = Runnable {
            val provider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val mediaImage = proxy.image
                if (completed || mediaImage == null || !busy.compareAndSet(false, true)) {
                    proxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                barcodeScanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        var parsed: NgcScanner.Result? = null
                        for (barcode in barcodes) {
                            val raw = barcode.rawValue ?: continue
                            parsed = parser.parseDecodedBarcode(raw)
                            if (parsed != null) break
                        }
                        if (parsed == null) {
                            lastKey = ""
                            consecutive = 0
                            status = "Scanning..."
                        } else {
                            val r = parsed
                            val key = "${r.service}:${r.certNumber}:${r.coinNumber}"
                            if (key == lastKey) consecutive++ else {
                                lastKey = key
                                consecutive = 1
                            }
                            status = "${r.service} ${r.certNumber}  $consecutive/3"
                            if (consecutive >= 3 && !completed) {
                                completed = true
                                onResult(r)
                            }
                        }
                    }
                    .addOnCompleteListener {
                        busy.set(false)
                        proxy.close()
                    }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                status = "Camera error: ${e.message ?: "unknown error"}"
            }
        }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            completed = true
            try { cameraProviderFuture.get().unbindAll() } catch (_: Exception) {}
            barcodeScanner.close()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth(0.92f).height(150.dp)
                .background(Color.Transparent)
        ) {
            // Four thin white edges make a barcode aiming box without hiding the preview.
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).background(Color.White))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(Color.White))
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.006f).height(150.dp).background(Color.White))
            Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.006f).height(150.dp).background(Color.White))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(status, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Text("The same valid barcode must be read 3 times.", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
        }
    }
}
