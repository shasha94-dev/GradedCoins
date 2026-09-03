package com.example.ngccoingallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.BarcodeFormat
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

class NgcScanner(private val context: Context) {
    data class Result(
        val coinNumber: String,
        val certNumber: String,
        val grade: String,
        val url: String,
        val service: String = "NGC",
        val rawBarcode: String = ""
    )

    /** Parse a raw value which was actually decoded from a barcode reader.
     * NGC is accepted only when its strict payload validates; otherwise PCGS
     * uses the final 8 digits and then 7 digits.
     */
    fun parseDecodedBarcode(raw: String): Result? =
        (parseBarcode(raw) ?: parsePcgsBarcode(raw))?.copy(rawBarcode = raw)

    /** Force a service when the automatic classification was wrong. */
    fun parseAsService(raw: String, service: String): Result? = when (service.uppercase()) {
        "NGC" -> parseBarcode(raw)?.copy(rawBarcode = raw)
        "PCGS" -> parsePcgsBarcode(raw)?.copy(rawBarcode = raw)
        else -> null
    }

    fun parseManualBarcode(raw: String): Result? {
        // Manual entry: prefer NGC only when the entered value itself matches
        // the strict 20-digit NGC payload. Otherwise fall back to PCGS.
        val digits = raw.filter { it.isDigit() }
        if (digits.length == 20) {
            parseNgc20(digits)?.let { return it.copy(rawBarcode = raw) }
        }
        return parsePcgsBarcode(raw)?.copy(rawBarcode = raw)
    }

    suspend fun scanImage(uri: Uri): Result? {
        val original = loadBitmap(uri) ?: return null
        val variants = buildVariants(original)

        try {
            // Reader 1: Google ML Kit. Try several crops/scales because NGC's 1D barcode
            // can occupy a small part of a full slab photo.
            val pcgsCandidates = mutableListOf<String>()
            val mlKit = BarcodeScanning.getClient()
            try {
                for (bitmap in variants) {
                    val barcodes = mlKit.process(InputImage.fromBitmap(bitmap, 0)).await()
                    for (barcode in barcodes) {
                        val raw = barcode.rawValue.orEmpty()
                        parseBarcode(raw)?.let { return it }
                        if (raw.isNotBlank()) pcgsCandidates += raw
                    }
                }
            } finally {
                mlKit.close()
            }

            // Reader 2: ZXing. It uses a different decoder/binarization pipeline from
            // ML Kit, so it can recover some 1D barcodes which ML Kit misses.
            for (bitmap in variants) {
                decodeWithZxing(bitmap)?.let { raw ->
                    parseBarcode(raw)?.let { return it }
                    pcgsCandidates += raw
                }
            }
            // PCGS barcodes do not use the NGC 20-digit layout. Their certificate
            // number is at the end of the payload: try the last 8 digits, then 7.
            for (raw in pcgsCandidates) parsePcgsBarcode(raw)?.let { return it }
            return null
        } finally {
            variants.drop(1).forEach { if (!it.isRecycled) it.recycle() }
            if (!original.isRecycled) original.recycle()
        }
    }

    private fun buildVariants(original: Bitmap): List<Bitmap> {
        val result = ArrayList<Bitmap>()
        result += original

        // Broad label crops for both NGC and PCGS slabs.
        result += cropAndScale(original, 0.00f, 0.00f, 1.00f, 0.55f, 2.0f)
        result += cropAndScale(original, 0.03f, 0.08f, 0.94f, 0.42f, 3.0f)

        // PCGS barcodes are often extremely short vertically. Sweep thin horizontal
        // bands through the upper half instead of assuming one fixed barcode height.
        val tops = listOf(0.18f, 0.24f, 0.30f, 0.36f, 0.42f)
        for (top in tops) {
            result += cropAndScale(original, 0.04f, top, 0.92f, 0.12f, 4.0f)
            result += cropAndScale(original, 0.04f, top, 0.92f, 0.18f, 3.0f)
        }

        // Add high-contrast copies of every crop. Keep the original versions too:
        // different readers often prefer different preprocessing.
        val base = result.drop(1).toList()
        for (bitmap in base) result += enhanceContrast(bitmap)
        return result
    }

    private fun cropAndScale(
        source: Bitmap,
        leftFraction: Float,
        topFraction: Float,
        widthFraction: Float,
        heightFraction: Float,
        scale: Float
    ): Bitmap {
        val x = (source.width * leftFraction).toInt().coerceIn(0, source.width - 1)
        val y = (source.height * topFraction).toInt().coerceIn(0, source.height - 1)
        val width = (source.width * widthFraction).toInt().coerceAtMost(source.width - x).coerceAtLeast(1)
        val height = (source.height * heightFraction).toInt().coerceAtMost(source.height - y).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(source, x, y, width, height)
        if (scale == 1f) return cropped

        val matrix = Matrix().apply { postScale(scale, scale) }
        val scaled = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    private fun enhanceContrast(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert to high-contrast grayscale while retaining enough midtones for
        // anti-aliased barcode edges.
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            var gray = (r * 30 + g * 59 + b * 11) / 100
            gray = ((gray - 128) * 1.8f + 128).toInt().coerceIn(0, 255)
            pixels[i] = (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun decodeWithZxing(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val normal = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)

        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39,
                BarcodeFormat.CODE_93,
                BarcodeFormat.CODABAR,
                BarcodeFormat.ITF
            )
        )

        // Try normal and inverted luminance with both ZXing binarizers. PCGS label
        // photographs can have weak bars and uneven illumination, so these passes
        // are intentionally independent.
        val sources = listOf(normal, InvertedLuminanceSource(normal))
        for (source in sources) {
            for (hybrid in listOf(true, false)) {
                val reader = MultiFormatReader().apply { setHints(hints) }
                try {
                    val binarizer = if (hybrid) HybridBinarizer(source)
                                    else GlobalHistogramBinarizer(source)
                    return reader.decodeWithState(BinaryBitmap(binarizer)).text
                } catch (_: Exception) {
                    // Try the next independent pass.
                } finally {
                    reader.reset()
                }
            }
        }
        return null
    }

    /** Scan the machine-readable code from a downloaded NGC OBV image and return
     * its six-digit coin number. NGC slab images may contain either a QR code or
     * a 1D barcode, so both ML Kit and ZXing are used with multiple crops/scales.
     * If expectedCert is supplied, a code belonging to another slab is ignored.
     */
    suspend fun scanNgcCoinNumberFromFile(path: String, expectedCert: String? = null): String? {
        val original = android.graphics.BitmapFactory.decodeFile(path) ?: return null
        val variants = mutableListOf<Bitmap>()
        variants += original

        // Website slab images vary by generation. Try the whole image, broad top/bottom
        // label areas, and left/right halves. Upscale each crop because the code can be tiny.
        fun addCrop(left: Float, top: Float, width: Float, height: Float, scale: Float) {
            try { variants += cropAndScale(original, left, top, width, height, scale) } catch (_: Exception) { }
        }
        addCrop(0f, 0f, 1f, .55f, 2f)
        addCrop(0f, .45f, 1f, .55f, 2f)
        addCrop(0f, 0f, .62f, 1f, 2.5f)
        addCrop(.38f, 0f, .62f, 1f, 2.5f)
        addCrop(.05f, .05f, .90f, .40f, 3f)
        addCrop(.05f, .55f, .90f, .40f, 3f)

        // Contrast-enhanced copies help 1D bars on reflective holders.
        for (b in variants.drop(1).toList()) {
            try { variants += enhanceContrast(b) } catch (_: Exception) { }
        }

        fun accept(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val parsed = parseBarcode(raw) ?: return null
            if (parsed.service != "NGC" || parsed.coinNumber.isBlank()) return null
            if (expectedCert != null && parsed.certNumber != expectedCert) return null
            return parsed.coinNumber
        }

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_ITF
            )
            .build()
        val client = BarcodeScanning.getClient(options)
        try {
            // Reader 1: ML Kit.
            for (bitmap in variants) {
                val codes = try { client.process(InputImage.fromBitmap(bitmap, 0)).await() }
                            catch (_: Exception) { emptyList() }
                for (code in codes) accept(code.rawValue)?.let { return it }
            }

            // Reader 2: ZXing, independent from ML Kit.
            for (bitmap in variants) {
                decodeWithZxingNgc(bitmap)?.let { raw -> accept(raw)?.let { return it } }
            }
            return null
        } finally {
            client.close()
            variants.distinctBy { System.identityHashCode(it) }.forEach {
                if (it !== original && !it.isRecycled) it.recycle()
            }
            if (!original.isRecycled) original.recycle()
        }
    }

    private fun decodeWithZxingNgc(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val normal = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
                BarcodeFormat.CODE_93, BarcodeFormat.CODABAR, BarcodeFormat.ITF
            )
        )
        for (source in listOf(normal, InvertedLuminanceSource(normal))) {
            for (hybrid in listOf(true, false)) {
                val reader = MultiFormatReader().apply { setHints(hints) }
                try {
                    val binarizer = if (hybrid) HybridBinarizer(source) else GlobalHistogramBinarizer(source)
                    return reader.decodeWithState(BinaryBitmap(binarizer)).text
                } catch (_: Exception) {
                } finally { reader.reset() }
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } else {
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (_: Exception) {
            null
        }
    }

    data class NgcCoinNumberDebug(val coinNumber: String?, val summary: String)

    /** Diagnostic version of the downloaded-OBV scan. It records every distinct raw
     * value returned by ML Kit and ZXing before NGC parsing/certificate validation. */
    suspend fun debugNgcCoinNumberFromFile(path: String, expectedCert: String): NgcCoinNumberDebug {
        val original = android.graphics.BitmapFactory.decodeFile(path)
            ?: return NgcCoinNumberDebug(null, "NGC scan: OBV image could not be decoded")
        val variants = mutableListOf<Bitmap>()
        variants += original
        fun addCrop(left: Float, top: Float, width: Float, height: Float, scale: Float) {
            try { variants += cropAndScale(original, left, top, width, height, scale) } catch (_: Exception) { }
        }
        addCrop(0f, 0f, 1f, .55f, 2f); addCrop(0f, .45f, 1f, .55f, 2f)
        addCrop(0f, 0f, .62f, 1f, 2.5f); addCrop(.38f, 0f, .62f, 1f, 2.5f)
        addCrop(.05f, .05f, .90f, .40f, 3f); addCrop(.05f, .55f, .90f, .40f, 3f)
        for (b in variants.drop(1).toList()) try { variants += enhanceContrast(b) } catch (_: Exception) { }

        val raws = linkedSetOf<String>()
        fun accept(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            raws += raw
            val parsed = parseBarcode(raw) ?: return null
            if (parsed.service != "NGC" || parsed.coinNumber.isBlank() || parsed.certNumber != expectedCert) return null
            return parsed.coinNumber
        }
        val options = BarcodeScannerOptions.Builder().setBarcodeFormats(
            Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_CODE_93, Barcode.FORMAT_CODABAR, Barcode.FORMAT_ITF
        ).build()
        val client = BarcodeScanning.getClient(options)
        try {
            for (bitmap in variants) {
                val codes = try { client.process(InputImage.fromBitmap(bitmap, 0)).await() } catch (_: Exception) { emptyList() }
                for (code in codes) accept(code.rawValue)?.let { return NgcCoinNumberDebug(it, "NGC scan SUCCESS; ML Kit; raw=${code.rawValue}") }
            }
            for (bitmap in variants) {
                val raw = decodeWithZxingNgc(bitmap)
                accept(raw)?.let { return NgcCoinNumberDebug(it, "NGC scan SUCCESS; ZXing; raw=$raw") }
            }
        } finally { client.close() }
        val rawText = if (raws.isEmpty()) "none" else raws.take(4).joinToString(" | ") { it.take(100) }
        val reason = if (raws.isEmpty()) "NO_CODE_DETECTED" else "CODE_DETECTED_BUT_NOT_VALID_NGC_OR_CERT_MISMATCH"
        return NgcCoinNumberDebug(null, "NGC scan $reason; expected=$expectedCert; raw=$rawText")
    }

    private fun parseBarcode(raw: String): Result? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 20) return null

        // Original NGC barcode layout:
        // first 6 digits = coin number, next 2 = grade, next 2 = details,
        // next 10 = certificate number.
        // Some readers can return extra leading/trailing data. Try each 20-digit
        // window and accept the first one which forms a valid NGC payload.
        for (start in 0..digits.length - 20) {
            val candidate = digits.substring(start, start + 20)
            parseNgc20(candidate)?.let { return it }
        }
        return null
    }

    private fun parseNgc20(digits: String): Result? {
        val coinNumber = digits.substring(0, 6)
        val gradeCode = digits.substring(6, 8)
        val detailsReason = digits.substring(8, 10)
        val cert10 = digits.substring(10, 20)
        val grade = gradeToString(gradeCode, detailsReason) ?: return null
        val cert = "${cert10.substring(0, 7)}-${cert10.substring(7)}"
        return Result(coinNumber, cert, grade, ngcUrl(cert, grade))
    }

    private fun parsePcgsBarcode(raw: String): Result? {
        val value = raw.trim()

        // A common PCGS holder payload is: PCGS-number.grade/certificate, for example
        // 331201.65/36068206. The number before the period identifies the issue/type
        // and is therefore useful as the PCGS sort key. Website metadata remains the
        // authoritative source and can overwrite the grade after verification.
        val structured = Regex("(\\d{3,8})\\.(\\d{1,2})(?:\\+)?/(\\d{7,8})").find(value)
        if (structured != null) {
            val pcgsNumber = structured.groupValues[1]
            val numericGrade = structured.groupValues[2]
            val cert = structured.groupValues[3]
            return Result(pcgsNumber, cert, numericGrade, "https://www.pcgs.com/cert/$cert", "PCGS")
        }

        // Fallback requested by the user: certificate is the final 8 characters,
        // otherwise the final 7 characters. PCGS Number/grade will be filled from
        // the official cert page when available.
        for (len in listOf(8, 7)) {
            if (value.length < len) continue
            val cert = value.takeLast(len)
            if (cert.all { it.isDigit() }) {
                return Result("", cert, "", "https://www.pcgs.com/cert/$cert", "PCGS")
            }
        }
        return null
    }

    private fun gradeToString(grade: String, designationCode: String): String? {
        // 87/88/89 are NGC Details lookup codes. For ordinary numeric grades, the
        // following two digits are NOT required to be 00: NGC also uses this field
        // for grade designations/modifiers (for example a slab graded 64+ can carry
        // a non-zero code). Do not reject an otherwise valid barcode because of it.
        // The cert page is authoritative for the complete displayed grade and will
        // later replace this base numeric grade (e.g. 64 -> 64+).
        if (grade == "87" || grade == "88" || grade == "89") return "NGCDetails"
        val value = grade.toIntOrNull() ?: return null
        if (value !in 1..70) return null
        return grade
    }

    private fun ngcUrl(cert: String, grade: String) =
        "https://www.ngccoin.uk/certlookup/$cert/$grade/"
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
