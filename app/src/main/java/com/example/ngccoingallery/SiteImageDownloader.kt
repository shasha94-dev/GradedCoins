package com.example.ngccoingallery

import android.content.Context
import android.graphics.BitmapFactory
import org.jsoup.Jsoup
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.util.Locale

class SiteImageDownloader(private val context: Context) {
    data class DownloadResult(val siteImages: List<String>, val front: String = "", val back: String = "")
    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"

    fun download(coin: Coin): DownloadResult = if (coin.service == "PCGS") downloadPcgs(coin) else downloadNgc(coin)

    data class SiteDetails(
        val description: String = "", val year: String = "", val country: String = "",
        val denomination: String = "", val variety: String = ""
    )
    enum class VerifyStatus { VALID, INVALID, TEMPORARY_ERROR }
    data class VerifyResult(val status: VerifyStatus, val details: SiteDetails = SiteDetails())

    fun verifyAndReadDetails(coin: Coin): VerifyResult {
        val fetch = fetchPage(coin.url) ?: return VerifyResult(VerifyStatus.TEMPORARY_ERROR)
        val (code, html) = fetch
        if (code !in 200..299) return VerifyResult(if (code == 404) VerifyStatus.INVALID else VerifyStatus.TEMPORARY_ERROR)
        val low = html.lowercase(Locale.US)
        val temporaryMarkers = listOf("exceeded our limits", "try again later", "temporarily unavailable", "access denied", "captcha")
        if (temporaryMarkers.any { it in low }) return VerifyResult(VerifyStatus.TEMPORARY_ERROR)
        val invalidMarkers = if (coin.service == "NGC") listOf("this item cannot be found", "certification number was entered correctly")
            else listOf("certificate not found", "certification number not found", "unable to locate")
        if (invalidMarkers.any { it in low }) return VerifyResult(VerifyStatus.INVALID)
        val doc = Jsoup.parse(html, coin.url)
        val body = doc.body()?.text().orEmpty()
        val certDigits = coin.certNumber.filter { it.isDigit() }
        if (certDigits.isNotBlank() && !body.filter { it.isDigit() }.contains(certDigits)) {
            // PCGS/NGC sometimes render cert data in scripts/meta rather than visible body.
            if (!html.filter { it.isDigit() }.contains(certDigits)) return VerifyResult(VerifyStatus.INVALID)
        }
        fun meta(vararg names: String): String {
            for (n in names) {
                val v = doc.selectFirst("meta[name=$n],meta[property=$n]")?.attr("content")?.trim().orEmpty()
                if (v.isNotBlank()) return v
            }; return ""
        }
        fun labeled(vararg labels: String): String {
            for (row in doc.select("tr")) {
                val cells=row.select("th,td")
                if(cells.size >= 2 && labels.any { cells[0].text().trim().equals(it, true) || cells[0].text().trim().startsWith(it, true) }) {
                    val v=cells[1].text().trim(); if(v.isNotBlank() && v.length < 160) return v
                }
            }
            for (dt in doc.select("dt")) {
                if(labels.any { dt.text().trim().startsWith(it, true) }) {
                    val v=dt.nextElementSibling()?.text()?.trim().orEmpty(); if(v.isNotBlank() && v.length < 160) return v
                }
            }
            val elements = doc.select("li,div,p")
            for (e in elements) {
                val t=e.text().trim()
                for(label in labels) if(t.startsWith(label, ignoreCase=true)) {
                    val v=t.substringAfter(':', "").trim()
                    if(v.isNotBlank() && v.length < 160) return v
                }
            }; return ""
        }
        val title = meta("og:title", "twitter:title").ifBlank { doc.title() }.replace(Regex("\\s*[|–-]\\s*(NGC|PCGS).*$", RegexOption.IGNORE_CASE), "").trim()
        val description = labeled("Description", "Coin Description").ifBlank { title }
        val year = labeled("Year", "Date").ifBlank { Regex("\\b(1[0-9]{3}|20[0-9]{2})\\b").find(description)?.value.orEmpty() }
        val country = labeled("Country", "Country/Region")
        val denomination = labeled("Denomination")
        val variety = labeled("Variety", "Variety Attribution")
        return VerifyResult(VerifyStatus.VALID, SiteDetails(description, year, country, denomination, variety))
    }

    private fun fetchPage(url:String): Pair<Int,String>? = try {
        val c=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=12000;readTimeout=20000;instanceFollowRedirects=true;setRequestProperty("User-Agent",ua)}
        val code=c.responseCode
        val stream=if(code in 200..299)c.inputStream else c.errorStream
        Pair(code, stream?.bufferedReader()?.use{it.readText()}.orEmpty())
    } catch(_:Exception){null}

    private fun downloadNgc(coin: Coin): DownloadResult {
        val dir = File(context.filesDir, "site_images/${coin.id}").apply { mkdirs() }
        val html = getBytes(coin.url)?.toString(Charsets.UTF_8) ?: return DownloadResult(emptyList())
        val doc = Jsoup.parse(html, coin.url)
        val urls = linkedSetOf<String>()
        doc.select("a[href]").forEach { a ->
            val href=a.absUrl("href"); val low=href.lowercase(Locale.US)
            if(coin.certNumber in href && listOf(".jpg",".jpeg",".png").any{it in low}) urls+=href
        }
        if(urls.isEmpty()) doc.select("img[src]").forEach { img ->
            val src=img.absUrl("src").substringBefore('?'); if(coin.certNumber in src) urls+=src
        }
        val paths=urls.mapIndexedNotNull{i,u->downloadFile(u,dir,safeName(u,"ngc_${i+1}.jpg"))}
        val front=paths.firstOrNull{"_OBV." in File(it).name.uppercase()}.orEmpty()
        val back=paths.firstOrNull{"_REV." in File(it).name.uppercase()}.orEmpty()
        return DownloadResult(paths,front,back)
    }

    private fun downloadPcgs(coin: Coin): DownloadResult {
        val dir = File(context.filesDir, "site_images/${coin.id}").apply { mkdirs() }
        val cert = coin.certNumber.filter { it.isDigit() }
        if (cert.isBlank()) return DownloadResult(emptyList())

        // PCGS images are served in several different ways depending on the age/type
        // of the holder. Newer TrueViews commonly use images.pcgs.com, while cert
        // pages may reference CloudFront or another PCGS CDN URL. Discover all of
        // them before giving up.
        val candidates = linkedSetOf<String>()

        fun addCandidate(raw: String) {
            val u = raw.trim().replace("\\u0026", "&").replace("\\/", "/")
            if (!u.startsWith("http://") && !u.startsWith("https://")) return
            val low = u.lowercase(Locale.US)
            val looksLikeImage = listOf(".jpg", ".jpeg", ".png", ".webp").any { it in low }
            val pcgsHost = listOf("pcgs.com", "cloudfront.net").any { it in low }
            if (looksLikeImage && pcgsHost) candidates += u.substringBefore("#")
        }

        // Direct TrueView variants. PCGS examples use lowercase size suffixes, but
        // keep the legacy variants too because older records aren't fully uniform.
        listOf(
            "https://images.pcgs.com/trueview/${cert}_max.jpg",
            "https://images.pcgs.com/trueview/${cert}_large.jpg",
            "https://images.pcgs.com/trueview/${cert}_medium.jpg",
            "https://images.pcgs.com/TrueView/${cert}_max.jpg",
            "https://images.pcgs.com/TrueView/${cert}_large.jpg",
            "https://images.pcgs.com/TrueView/${cert}_medium.jpg",
            "https://images.pcgs.com/TrueView/${cert}_Max.jpg",
            "https://images.pcgs.com/TrueView/${cert}_Large.jpg"
        ).forEach(::addCandidate)

        fun discoverFromPage(page: String) {
            val htmlBytes = getBytes(page, page) ?: return
            val html = htmlBytes.toString(Charsets.UTF_8)
            val doc = Jsoup.parse(html, page)

            // Normal image/link attributes, lazy-loaded images and responsive srcsets.
            doc.select("a[href],img[src],img[data-src],img[data-original],source[srcset],img[srcset]").forEach { e ->
                listOf("href", "src", "data-src", "data-original").forEach { attr ->
                    if (e.hasAttr(attr)) addCandidate(e.absUrl(attr))
                }
                listOf("srcset").forEach { attr ->
                    if (e.hasAttr(attr)) {
                        e.attr(attr).split(',').forEach { item ->
                            val raw = item.trim().substringBefore(' ')
                            if (raw.startsWith("//")) addCandidate("https:$raw")
                            else if (raw.startsWith("http")) addCandidate(raw)
                            else if (raw.isNotBlank()) {
                                try { addCandidate(URL(URL(page), raw).toString()) } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }

            // Social-preview metadata is often the easiest way to find a cert image.
            doc.select("meta[property=og:image],meta[name=twitter:image],meta[property=og:image:secure_url]").forEach {
                addCandidate(it.attr("content"))
            }

            // Some PCGS pages embed image URLs in JSON/JavaScript rather than DOM nodes.
            val urlRegex = Regex("https?:\\\\?/\\\\?/[^\\\"'<>\\s]+", RegexOption.IGNORE_CASE)
            urlRegex.findAll(html).forEach { m -> addCandidate(m.value) }
        }

        // Search both the cert page and the TrueView landing page. Older PCGS certs
        // can have a certification image even when no conventional TrueView URL exists.
        discoverFromPage("https://www.pcgs.com/cert/$cert")
        discoverFromPage("https://images.pcgs.com/trueview/$cert")
        discoverFromPage("https://www.pcgs.com/trueview/$cert")

        // Prefer higher-resolution looking candidates and URLs tied to this cert.
        val ordered = candidates.sortedWith(
            compareByDescending<String> { cert in it }
                .thenByDescending { "max" in it.lowercase(Locale.US) }
                .thenByDescending { "large" in it.lowercase(Locale.US) }
                .thenByDescending { "trueview" in it.lowercase(Locale.US) }
        )

        var full = ""
        for ((index, u) in ordered.withIndex()) {
            val ext = when {
                ".png" in u.lowercase(Locale.US) -> ".png"
                ".webp" in u.lowercase(Locale.US) -> ".webp"
                else -> ".jpg"
            }
            val p = downloadFile(u, dir, "pcgs_${cert}_source_${index + 1}$ext", "https://www.pcgs.com/cert/$cert")
            if (p != null) {
                full = p
                break
            }
        }
        if (full.isBlank()) return DownloadResult(emptyList())

        val bitmap = BitmapFactory.decodeFile(full) ?: return DownloadResult(listOf(full))
        if (bitmap.width < 2) return DownloadResult(listOf(full))

        // TrueView combined images are landscape (obverse left, reverse right). If an
        // older cert only exposes a single portrait holder photo, keep it intact rather
        // than incorrectly cutting the slab in half.
        if (bitmap.width < bitmap.height * 1.20f) {
            bitmap.recycle()
            return DownloadResult(listOf(full), front = full)
        }

        val mid = bitmap.width / 2
        val left = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, mid, bitmap.height)
        val right = android.graphics.Bitmap.createBitmap(bitmap, mid, 0, bitmap.width - mid, bitmap.height)
        val front = File(dir, "pcgs_${cert}_front.jpg")
        val back = File(dir, "pcgs_${cert}_back.jpg")
        front.outputStream().use { left.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
        back.outputStream().use { right.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
        left.recycle(); right.recycle(); bitmap.recycle()
        return DownloadResult(listOf(full), front.absolutePath, back.absolutePath)
    }

    private fun getBytes(url: String, referer: String = ""): ByteArray? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (referer.isNotBlank()) setRequestProperty("Referer", referer)
        }
        if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }
    } catch (_: Exception) { null }

    private fun downloadFile(url: String, dir: File, name: String, referer: String = ""): String? {
        val b = getBytes(url, referer) ?: return null
        if (b.size < 1024) return null
        val f = File(dir, name)
        f.writeBytes(b)
        return if (BitmapFactory.decodeFile(f.absolutePath) != null) f.absolutePath else { f.delete(); null }
    }
    private fun safeName(url:String,fallback:String):String=try{URI(url).path.substringAfterLast('/').takeIf{it.contains('.')}?:fallback}catch(_:Exception){fallback}
}
