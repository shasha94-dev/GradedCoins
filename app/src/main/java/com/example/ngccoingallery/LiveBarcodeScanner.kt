package com.example.ngccoingallery

import android.annotation.SuppressLint
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.FocusMeteringAction
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
import java.util.concurrent.TimeUnit


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

    val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
            BarcodeFormat.CODABAR, BarcodeFormat.ITF, BarcodeFormat.QR_CODE
        )
    )
    val out = linkedSetOf<String>()

    fun otsu(src: ByteArray): ByteArray {
        val histogram = IntArray(256)
        src.forEach { histogram[it.toInt() and 0xff]++ }
        val total = src.size
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * histogram[i]
        var sumB = 0L
        var wB = 0
        var best = -1.0
        var threshold = 150
        for (t in 0..255) {
            wB += histogram[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * histogram[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        return ByteArray(src.size) { i -> if ((src[i].toInt() and 0xff) > threshold) 0xff.toByte() else 0x00 }
    }

    fun crop(src: ByteArray, left: Int, top: Int, cw: Int, ch: Int): ByteArray {
        val result = ByteArray(cw * ch)
        for (row in 0 until ch) {
            System.arraycopy(src, (top + row) * w + left, result, row * cw, cw)
        }
        return result
    }

    // Nearest-neighbour enlargement preserves the hard vertical edges of a 1-D barcode.
    fun scale(src: ByteArray, sw: Int, sh: Int, factor: Int): Pair<ByteArray, Pair<Int, Int>> {
        val dw = sw * factor
        val dh = sh * factor
        val dst = ByteArray(dw * dh)
        for (yy in 0 until dh) {
            val sy = yy / factor
            for (xx in 0 until dw) dst[yy * dw + xx] = src[sy * sw + xx / factor]
        }
        return dst to (dw to dh)
    }

    // Add a real white quiet zone around PCGS bars. Colored holder labels and nearby text
    // otherwise touch the barcode crop and cause many 1-D readers to reject it.
    fun padWhite(src: ByteArray, sw: Int, sh: Int): Pair<ByteArray, Pair<Int, Int>> {
        val px = (sw / 12).coerceAtLeast(16)
        val py = (sh / 5).coerceAtLeast(8)
        val dw = sw + px * 2
        val dh = sh + py * 2
        val dst = ByteArray(dw * dh) { 0xff.toByte() }
        for (row in 0 until sh) System.arraycopy(src, row * sw, dst, (row + py) * dw + px, sw)
        return dst to (dw to dh)
    }

    fun decodeLocal(bytes: ByteArray, lw: Int, lh: Int) {
        if (lw < 40 || lh < 16) return
        val base = PlanarYUVLuminanceSource(bytes, lw, lh, 0, 0, lw, lh, false)
        for (source in listOf(base, InvertedLuminanceSource(base))) {
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

    fun processRegion(left: Int, top: Int, cw: Int, ch: Int) {
        if (left < 0 || top < 0 || cw < 40 || ch < 16 || left + cw > w || top + ch > h) return
        val raw = crop(y, left, top, cw, ch)
        val bw = otsu(raw)
        // Original crop helps when thresholding removes faint bars.
        decodeLocal(raw, cw, ch)
        decodeLocal(bw, cw, ch)
        for (factor in listOf(2, 4)) {
            val (big, dims) = scale(bw, cw, ch, factor)
            val (padded, pdims) = padWhite(big, dims.first, dims.second)
            decodeLocal(padded, pdims.first, pdims.second)
        }

        // PCGS labels often have a blue/grey background and extremely short bars.
        // Build synthetic, tall 1-D barcodes from several averaged scan lines. This
        // removes most label texture/text while preserving the vertical bar widths.
        if (cw >= 120 && ch >= 20) {
            val sampleYs = intArrayOf(ch / 4, ch * 3 / 8, ch / 2, ch * 5 / 8, ch * 3 / 4)
            for (centerY in sampleYs) {
                for (halfHeight in intArrayOf(2, 4, 7)) {
                    val y0 = (centerY - halfHeight).coerceAtLeast(0)
                    val y1 = (centerY + halfHeight).coerceAtMost(ch - 1)
                    val signal = ByteArray(cw)
                    for (x in 0 until cw) {
                        var sum = 0
                        var count = 0
                        for (yy in y0..y1) {
                            sum += raw[yy * cw + x].toInt() and 0xff
                            count++
                        }
                        signal[x] = (sum / count).toByte()
                    }
                    val lineBw = otsu(signal)
                    val syntheticH = 96
                    val synthetic = ByteArray(cw * syntheticH)
                    for (yy in 0 until syntheticH) {
                        System.arraycopy(lineBw, 0, synthetic, yy * cw, cw)
                    }
                    val (bigLine, lineDims) = scale(synthetic, cw, syntheticH, 3)
                    val (paddedLine, paddedDims) = padWhite(bigLine, lineDims.first, lineDims.second)
                    decodeLocal(paddedLine, paddedDims.first, paddedDims.second)
                }
            }
        }
    }

    // Whole frame plus overlapping horizontal bands. In portrait CameraX the Y plane may
    // be sensor-rotated, so also try vertical bands; TRY_HARDER handles the final rotation.
    processRegion(0, 0, w, h)
    val bandH = (h / 4).coerceAtLeast(24)
    for (i in 0..6) {
        val top = ((h - bandH) * i / 6).coerceAtLeast(0)
        processRegion(w / 20, top, w * 18 / 20, bandH)
    }
    val bandW = (w / 4).coerceAtLeast(24)
    for (i in 0..6) {
        val left = ((w - bandW) * i / 6).coerceAtLeast(0)
        processRegion(left, h / 20, bandW, h * 18 / 20)
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
    var status by remember { mutableStateOf("Move closer and align only the barcode inside the box") }

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
                // PCGS 1-D bars are very short. Request a high-resolution analysis stream
                // so each narrow bar has enough pixels for ML Kit/ZXing to resolve it.
                .setTargetResolution(Size(1920, 1080))
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
                val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                // A moderate optical/digital zoom makes the PCGS barcode occupy substantially
                // more pixels while still leaving enough room to align it in the guide box.
                val maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                camera.cameraControl.setZoomRatio(minOf(2.0f, maxZoom))
                // Force autofocus/exposure onto the barcode guide instead of the slab/coin.
                // This matters for PCGS because the bars are only a few pixels high.
                try {
                    val point = previewView.meteringPointFactory.createPoint(
                        previewView.width.coerceAtLeast(1) / 2f,
                        previewView.height.coerceAtLeast(1) / 2f
                    )
                    val focusAction = FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                    camera.cameraControl.startFocusAndMetering(focusAction)
                } catch (_: Exception) { }
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
            Modifier.align(Alignment.Center).fillMaxWidth(0.92f).height(96.dp)
                .background(Color.Transparent)
        ) {
            // Four thin white edges make a barcode aiming box without hiding the preview.
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).background(Color.White))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(Color.White))
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.006f).height(96.dp).background(Color.White))
            Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.006f).height(96.dp).background(Color.White))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(status, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Text("For PCGS, fill the narrow box with the barcode. The same valid barcode must be read 3 times.", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
        }
    }
}
