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
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


private fun decodeHighContrastBarcode(proxy: androidx.camera.core.ImageProxy): List<String> {
    val plane = proxy.planes.firstOrNull() ?: return emptyList()
    val w = proxy.width
    val h = proxy.height
    val buffer = plane.buffer.duplicate()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val y = ByteArray(w * h)
    for (row in 0 until h) {
        val rowStart = row * rowStride
        for (col in 0 until w) {
            val index = rowStart + col * pixelStride
            if (index < buffer.limit()) y[row * w + col] = buffer.get(index)
        }
    }

    // PCGS labels are often gray/blue rather than white. Create hard black/white
    // luminance versions so the 1-D bars have a clean white background.
    val histogram = IntArray(256)
    y.forEach { histogram[it.toInt() and 0xff]++ }
    var total = w * h
    var sum = 0L
    for (i in 0..255) sum += i.toLong() * histogram[i]
    var sumB = 0L; var wB = 0; var best = -1.0; var threshold = 150
    for (t in 0..255) {
        wB += histogram[t]; if (wB == 0) continue
        val wF = total - wB; if (wF == 0) break
        sumB += t.toLong() * histogram[t]
        val mB = sumB.toDouble() / wB
        val mF = (sum - sumB).toDouble() / wF
        val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
        if (between > best) { best = between; threshold = t }
    }
    val binary = ByteArray(y.size) { i -> if ((y[i].toInt() and 0xff) > threshold) 0xff.toByte() else 0x00 }

    val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
            BarcodeFormat.CODABAR, BarcodeFormat.ITF, BarcodeFormat.QR_CODE
        )
    )
    val out = linkedSetOf<String>()
    fun attempt(bytes: ByteArray, left: Int, top: Int, width: Int, height: Int) {
        if (width < 40 || height < 20) return
        val src = PlanarYUVLuminanceSource(bytes, w, h, left, top, width, height, false)
        for (source in listOf(src, InvertedLuminanceSource(src))) {
            for (hybrid in listOf(true, false)) {
                try {
                    val reader = MultiFormatReader().apply { setHints(hints) }
                    val bin = if (hybrid) HybridBinarizer(source) else GlobalHistogramBinarizer(source)
                    val value = reader.decodeWithState(BinaryBitmap(bin)).text
                    if (value.isNotBlank()) out += value
                } catch (_: Exception) { }
            }
        }
    }
    val variants = listOf(y, binary)
    for (bytes in variants) {
        attempt(bytes, 0, 0, w, h)
        // Thin horizontal bands are especially useful for the short PCGS 1-D barcode.
        for (i in 0 until 6) {
            val top = (h * i / 8).coerceAtMost(h - 1)
            val bh = (h / 3).coerceAtMost(h - top)
            attempt(bytes, 0, top, w, bh)
        }
        // Central area removes holder edges and colored surroundings.
        attempt(bytes, w / 10, h / 8, w * 8 / 10, h * 6 / 8)
    }
    return out.toList()
}

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
                            // ML Kit sometimes misses PCGS bars because the holder label is not
                            // white. Retry the same camera frame with thresholded ZXing passes.
                            for (raw in decodeHighContrastBarcode(proxy)) {
                                parsed = parser.parseDecodedBarcode(raw)
                                if (parsed != null) break
                            }
                        }
                        if (parsed == null) {
                            lastKey = ""
                            consecutive = 0
                            status = "Scanning (enhanced contrast)..."
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
