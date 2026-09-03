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
        val denomination: String = "", val variety: String = "",
        val grade: String = "", val coinNumber: String = "", val pcgsTypeName: String = ""
    )
    enum class VerifyStatus { VALID, INVALID, TEMPORARY_ERROR }
    data class VerifyResult(val status: VerifyStatus, val details: SiteDetails = SiteDetails())

    private data class PcgsEmbeddedImage(val label: String, val downloadUrl: String, val thumbnailUrl: String, val popupUrl: String)
    private data class PcgsEmbeddedData(val certNo: String, val valid: Boolean, val details: SiteDetails, val images: List<PcgsEmbeddedImage>)

    /** PCGS embeds the certificate payload in SvelteKit page data. It is JavaScript-like
     * rather than strict JSON, so parse only the stable named fields we need. */
    private fun parsePcgsEmbedded(html: String): PcgsEmbeddedData? {
        val marker = html.indexOf("certPageData:")
        if (marker < 0) return null
        val tail = html.substring(marker)

        fun section(from: String, until: String): String {
            val a = tail.indexOf(from)
            if (a < 0) return ""
            val startAt = a + from.length
            val b = tail.indexOf(until, startAt)
            return if (b < 0) tail.substring(startAt) else tail.substring(startAt, b)
        }
        fun stringField(text: String, name: String): String =
            Regex("""\b${Regex.escape(name)}:\"([^\"]*)\"""").find(text)?.groupValues?.getOrNull(1).orEmpty()
                .replace("\\/", "/").replace("&trade;", "™")
        fun numberField(text: String, name: String): String =
            Regex("\\b" + Regex.escape(name) + ":([0-9]+)").find(text)?.groupValues?.getOrNull(1).orEmpty()
        fun boolField(text: String, name: String): Boolean? =
            Regex("\\b" + Regex.escape(name) + ":(true|false)").find(text)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()

        // Scope fields to their real PCGS objects. In particular, don't take a generic
        // `description` elsewhere in the Svelte payload: the gallery title is specifically
        // specSearchResult.description.
        val certInfo = section("certInfo:{", "},populationInfo:")
        val specSearch = section("specSearchResult:{", "},videos:")
        val certNo = stringField(certInfo, "certNo")
        if (certNo.isBlank()) return null

        val description = stringField(specSearch, "description")
        val date = stringField(certInfo, "currencyDate").ifBlank { stringField(certInfo, "dateMintmark") }.trim().trim('(', ')')
        val denomination = stringField(certInfo, "denomination")
        val variety = stringField(certInfo, "displayVariety").ifBlank { stringField(certInfo, "variety") }
        val country = stringField(certInfo, "country")
        val grade = stringField(certInfo, "gradeDesc").ifBlank { stringField(certInfo, "displayGrade") }.ifBlank { numberField(certInfo, "grade") }
        val specNo = stringField(certInfo, "specNo").ifBlank { numberField(certInfo, "specNo") }
        val typeName = description.ifBlank { listOf(date, denomination, variety).filter { it.isNotBlank() }.joinToString(" ") }

        val imageSection = section("imageInfo:{images:[", "]},auctionPriceInfo:")
        val images = Regex("""\{([^{}]+)\}""").findAll(imageSection).mapNotNull { m ->
            val obj = m.groupValues[1]
            fun f(n: String) = stringField(obj, n).replace("http://", "https://")
            val download = f("downloadUrl")
            val popup = f("popupUrl")
            val thumb = f("thumbnailUrl")
            if (download.isBlank() && popup.isBlank() && thumb.isBlank()) null
            else PcgsEmbeddedImage(f("label"), download, thumb, popup)
        }.toList()

        val certBlock = section("cert:{", "},certNFCInfo:")
        val valid = boolField(certBlock, "isValid") ?: boolField(certInfo, "validFlag") ?: true
        return PcgsEmbeddedData(certNo, valid, SiteDetails(description, date, country, denomination, variety, grade, specNo, typeName), images)
    }

    fun verifyAndReadDetails(coin: Coin): VerifyResult {
        val fetch = fetchPage(coin.url) ?: return VerifyResult(VerifyStatus.TEMPORARY_ERROR)
        val (code, html) = fetch
        if (code !in 200..299) return VerifyResult(if (code == 404) VerifyStatus.INVALID else VerifyStatus.TEMPORARY_ERROR)
        val low = html.lowercase(Locale.US)
        val temporaryMarkers = listOf("exceeded our limits", "try again later", "temporarily unavailable", "access denied", "captcha")
        if (temporaryMarkers.any { it in low }) return VerifyResult(VerifyStatus.TEMPORARY_ERROR)

        if (coin.service == "PCGS") {
            val embedded = parsePcgsEmbedded(html)
            if (embedded != null) {
                val requested = coin.certNumber.filter { it.isDigit() }
                val returned = embedded.certNo.filter { it.isDigit() }
                if (requested != returned || !embedded.valid) return VerifyResult(VerifyStatus.INVALID)
                return VerifyResult(VerifyStatus.VALID, embedded.details)
            }
        }

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

        // PCGS exposes both the assigned grade and a PCGS Number (issue/type ID).
        // The PCGS Number is the closest equivalent to the NGC coin number for sorting:
        // all examples of the same PCGS issue/variety share this number, while certNumber
        // identifies the individual slab. Prefer explicit page labels, then embedded JSON/text.
        var siteGrade = if (coin.service == "PCGS") {
            labeled("Grade", "PCGS Grade")
        } else {
            // NGC barcode grade is only a lookup/base grade. The cert page is the
            // authoritative source for designations such as +, star, PL, etc.
            labeled("Grade", "NGC Grade", "Final Grade")
        }
        var siteCoinNumber = if (coin.service == "PCGS") labeled("PCGS #", "PCGS Number", "PCGS No.", "PCGS No") else ""
        var pcgsTypeName = ""

        if (coin.service == "NGC" && siteGrade.isBlank()) {
            // Fallback for NGC layouts where the grade is embedded in script/text
            // rather than exposed as a simple labeled DOM field. Preserve + and
            // common NGC designations when present.
            val ngcGradePatterns = listOf(
                Regex("(?i)\\b(?:MS|PF|PR|SP|AU|XF|VF|F|VG|G)?\\s*(\\d{1,2})(\\+)?(?:\\s*(★|\\*|PL|DPL|BN|RB|RD|CAMEO|ULTRA CAMEO))?\\b"),
                Regex("""(?i)["'](?:grade|displayGrade|finalGrade)["']\s*[:=]\s*["']([^"']{1,32})["']""")
            )
            for (r in ngcGradePatterns) {
                val m = r.find(html) ?: continue
                val candidate = if (m.groupValues.size >= 3 && m.groupValues[1].all { it.isDigit() }) {
                    buildString {
                        append(m.groupValues[1])
                        if (m.groupValues.getOrNull(2) == "+") append('+')
                        val suffix = m.groupValues.getOrNull(3).orEmpty()
                        if (suffix.isNotBlank()) append(" ").append(suffix)
                    }
                } else m.groupValues.getOrNull(1).orEmpty()
                val numeric = Regex("\\d{1,2}").find(candidate)?.value?.toIntOrNull()
                if (numeric != null && numeric in 1..70) { siteGrade = candidate.trim(); break }
            }
        }

        if (coin.service == "PCGS") {
            // PCGS' coin type is the prominent title immediately above the cert data.
            // Do not simply take the first H1/H2: PCGS pages contain generic headings too.
            // Locate the cert number in DOM order and prefer the nearest valid heading before it.
            fun cleanPcgsType(raw: String): String = raw
                .replace(Regex("(?i)\\s*[|–—-]\\s*PCGS.*$"), "")
                .replace(Regex("\\s+"), " ")
                .trim()

            fun validPcgsType(raw: String): Boolean {
                val c = cleanPcgsType(raw)
                if (c.length !in 3..160) return false
                if (c.contains(coin.certNumber, true)) return false
                if (certDigits.isNotBlank() && c.filter { it.isDigit() } == certDigits) return false
                val generic = listOf(
                    "PCGS", "Cert Verification", "Certificate Verification", "Verify Certification",
                    "Verify PCGS Certification", "PCGS Cert Verification", "CoinFacts", "TrueView",
                    "Certification Number", "Cert Number", "Grade", "Population", "Price Guide"
                )
                if (generic.any { c.equals(it, true) }) return false
                if (c.startsWith("PCGS Cert", true) || c.startsWith("Verify PCGS", true)) return false
                return true
            }

            fun typeNearCertificate(): String {
                val all = doc.getAllElements()
                val certForms = listOf(coin.certNumber, certDigits).filter { it.isNotBlank() }
                val certIndexes = all.mapIndexedNotNull { index, e ->
                    val own = e.ownText().trim()
                    if (own.isNotBlank() && certForms.any { own.contains(it, true) }) index else null
                }
                if (certIndexes.isEmpty()) return ""

                val headings = all.mapIndexedNotNull { index, e ->
                    val tag = e.tagName().lowercase(Locale.US)
                    val cls = e.className().lowercase(Locale.US)
                    val looksLikeHeading = tag in setOf("h1", "h2", "h3", "h4") ||
                        "title" in cls || "coin-name" in cls || "coinname" in cls || "cert-name" in cls
                    val text = e.text().trim()
                    if (looksLikeHeading && validPcgsType(text)) index to cleanPcgsType(text) else null
                }

                for (certIndex in certIndexes.sorted()) {
                    val nearest = headings
                        .filter { it.first < certIndex }
                        .minByOrNull { certIndex - it.first }
                    if (nearest != null && certIndex - nearest.first < 250) return nearest.second
                }
                return ""
            }

            pcgsTypeName = typeNearCertificate()

            // Structured-data fallback. PCGS has used several field names over time.
            if (pcgsTypeName.isBlank()) {
                val jsonPatterns = listOf(
                    Regex("(?is)[\\\"'](?:coinName|displayName|issueName|itemName)[\\\"']\\s*[:=]\\s*[\\\"']([^\\\"']{3,160})[\\\"']"),
                    Regex("(?is)[\\\"']name[\\\"']\\s*:\\s*[\\\"']([^\\\"']{3,160})[\\\"']")
                )
                for (pattern in jsonPatterns) {
                    val candidate = pattern.findAll(html).map { it.groupValues[1] }
                        .firstOrNull { validPcgsType(it) }
                    if (candidate != null) { pcgsTypeName = cleanPcgsType(candidate); break }
                }
            }

            // Metadata/title is deliberately last because it often contains generic PCGS page text.
            if (pcgsTypeName.isBlank()) {
                val fallbackCandidates = listOf(meta("og:title", "twitter:title"), title)
                pcgsTypeName = fallbackCandidates.firstOrNull { validPcgsType(it) }
                    ?.let(::cleanPcgsType).orEmpty()
            }

            if (siteCoinNumber.isBlank()) {
                val patterns = listOf(
                    Regex("(?i)PCGS\\s*(?:#|No\\.?|Number)\\s*[:#]?\\s*(\\d{3,8})"),
                    Regex("""(?i)["'](?:pcgsNo|pcgsNumber|coinNumber)["']\s*[:=]\s*["']?(\d{3,8})""")
                )
                siteCoinNumber = patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }.orEmpty()
            }
            siteCoinNumber = siteCoinNumber.filter { it.isDigit() }

            if (siteGrade.isBlank()) {
                val gradePatterns = listOf(
                    Regex("""(?i)["'](?:grade|pcgsGrade)["']\s*[:=]\s*["']?([^"',}]{1,24})"""),
                    Regex("(?i)\\b(PO|FR|AG|G|VG|F|VF|XF|AU|MS|PR|PF|SP)\\s*[- ]?(\\d{1,2})(\\+)?(?:\\s*(BN|RB|RD|CAM|DCAM|FS|FB|FH|PL|DMPL))?\\b")
                )
                for (r in gradePatterns) {
                    val m = r.find(html) ?: r.find(body) ?: continue
                    siteGrade = if (m.groupValues.size >= 3 && m.groupValues[1].length <= 3) {
                        buildString {
                            append(m.groupValues[1].uppercase(Locale.US))
                            append(m.groupValues[2])
                            if (m.groupValues.getOrNull(3) == "+") append('+')
                            val suffix = m.groupValues.getOrNull(4).orEmpty()
                            if (suffix.isNotBlank()) append(" ").append(suffix.uppercase(Locale.US))
                        }
                    } else m.groupValues.getOrNull(1).orEmpty().trim()
                    if (siteGrade.isNotBlank()) break
                }
            }
            siteGrade = siteGrade.trim().replace(Regex("\\s+"), " ").take(32)
        }

        return VerifyResult(
            VerifyStatus.VALID,
            SiteDetails(description, year, country, denomination, variety, siteGrade, siteCoinNumber, pcgsTypeName)
        )
    }

    data class PcgsDebugResult(val summary: String, val html: String)

    /** Diagnostic helper for PCGS metadata/title extraction. This intentionally
     * reports text/JSON context rather than image candidates. */
    fun debugPcgsPage(certRaw: String): PcgsDebugResult {
        val cert = certRaw.filter { it.isDigit() }
        if (cert.length !in 7..8) return PcgsDebugResult("Invalid PCGS certificate: $certRaw", "")
        val requested = "https://www.pcgs.com/cert/$cert"
        return try {
            val c = (URL(requested).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000; readTimeout = 20000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            val code = c.responseCode
            val finalUrl = c.url.toString()
            val contentType = c.contentType.orEmpty()
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val html = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val doc = Jsoup.parse(html, finalUrl)
            val lines = mutableListOf<String>()
            lines += "=== PCGS METADATA DEBUG ==="
            lines += "Requested: $requested"
            lines += "Final URL: $finalUrl"
            lines += "HTTP: $code"
            lines += "Content-Type: $contentType"
            lines += "HTML chars: ${html.length}"
            lines += "Document title: ${doc.title()}"
            lines += "og:title: ${doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()}"
            lines += "twitter:title: ${doc.selectFirst("meta[name=twitter:title]")?.attr("content").orEmpty()}"
            lines += "meta description: ${doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()}"
            lines += "og:description: ${doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()}"

            lines += "\nHeadings / likely metadata elements:"
            doc.select("h1,h2,h3,h4,h5,h6,[class*=title],[class*=grade],[class*=denom],[class*=variety],[class*=coin]")
                .map { it.text().trim() }.filter { it.isNotBlank() }.distinct().take(50)
                .forEachIndexed { i, text -> lines += "  [$i] ${text.take(500)}" }

            fun contexts(label: String, needle: String, max: Int = 8) {
                lines += "\nContexts for $label ('$needle'):"
                var from = 0; var count = 0
                while (count < max) {
                    val idx = html.indexOf(needle, from, ignoreCase = true)
                    if (idx < 0) break
                    val start = (idx - 350).coerceAtLeast(0)
                    val end = (idx + needle.length + 700).coerceAtMost(html.length)
                    lines += "--- $label match ${count + 1} @ $idx ---"
                    lines += html.substring(start, end).replace(Regex("\\s+"), " ")
                    from = idx + needle.length; count++
                }
                if (count == 0) lines += "  (none)"
            }
            contexts("certificate", cert, 10)
            listOf("SPECIMEN", "title", "description", "grade", "denomination", "variety", "coinFacts", "coinNumber", "pcgsNumber")
                .forEach { contexts(it, it, 4) }

            lines += "\nRelevant <script> blocks:"
            var scriptCount = 0
            doc.select("script").forEachIndexed { index, script ->
                val text = script.data().ifBlank { script.html() }
                if (text.contains(cert, true) || listOf("SPECIMEN","coinFacts","denomination","variety","grade","pcgsNumber").any { text.contains(it, true) }) {
                    lines += "--- script[$index] type='${script.attr("type")}' chars=${text.length} ---"
                    lines += text.take(5000).replace(Regex("\\s+"), " ")
                    scriptCount++
                }
            }
            if (scriptCount == 0) lines += "  (none)"

            PcgsDebugResult(lines.joinToString("\n"), html)
        } catch (e: Exception) {
            PcgsDebugResult("PCGS metadata debug failed: ${e.javaClass.simpleName}: ${e.message}", "")
        }
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
        val certUrl = "https://www.pcgs.com/cert/$cert"

        // Primary path: PCGS itself embeds certPageData, including imageInfo.images.
        // Prefer popupUrl (the large display image) so we do not automatically cache
        // enormous originals. Only fall back to the legacy discovery code if this
        // structured payload is unavailable.
        val pageHtml = getBytes(certUrl, certUrl)?.toString(Charsets.UTF_8)
        val embedded = pageHtml?.let(::parsePcgsEmbedded)
        if (embedded != null && embedded.certNo.filter { it.isDigit() } == cert && embedded.valid && embedded.images.isNotEmpty()) {
            val saved = mutableListOf<Pair<PcgsEmbeddedImage, String>>()
            embedded.images.forEachIndexed { index, image ->
                val chosen = image.popupUrl.ifBlank { image.thumbnailUrl }.ifBlank { image.downloadUrl }
                if (chosen.isBlank()) return@forEachIndexed
                val ext = when {
                    ".png" in chosen.lowercase(Locale.US) -> ".png"
                    ".webp" in chosen.lowercase(Locale.US) -> ".webp"
                    else -> ".jpg"
                }
                downloadFile(chosen, dir, "pcgs_${cert}_site_${index + 1}$ext", certUrl)?.let { saved += image to it }
            }
            if (saved.isNotEmpty()) {
                // A labeled TrueView is the preferred gallery source. PCGS TrueView
                // images are commonly a combined obverse/reverse landscape image.
                val preferred = saved.firstOrNull { it.first.label.equals("TrueView", true) } ?: saved.first()
                val full = preferred.second
                val bitmap = BitmapFactory.decodeFile(full)
                if (bitmap != null && bitmap.width >= bitmap.height * 1.20f && bitmap.width >= 2) {
                    val mid = bitmap.width / 2
                    val left = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, mid, bitmap.height)
                    val right = android.graphics.Bitmap.createBitmap(bitmap, mid, 0, bitmap.width - mid, bitmap.height)
                    val front = File(dir, "pcgs_${cert}_front.jpg")
                    val back = File(dir, "pcgs_${cert}_back.jpg")
                    front.outputStream().use { left.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
                    back.outputStream().use { right.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
                    left.recycle(); right.recycle(); bitmap.recycle()
                    return DownloadResult(saved.map { it.second }, front.absolutePath, back.absolutePath)
                }
                bitmap?.recycle()
                return DownloadResult(saved.map { it.second }, front = full)
            }
        }

        return downloadPcgsLegacy(coin, dir, cert)
    }

    private fun downloadPcgsLegacy(coin: Coin, dir: File, cert: String): DownloadResult {
        val candidates = linkedSetOf<String>()
        fun addCandidate(raw: String) {
            val u = raw.trim().replace("\\u0026", "&").replace("\\/", "/")
            if (!u.startsWith("http://") && !u.startsWith("https://")) return
            val low = u.lowercase(Locale.US)
            if (listOf(".jpg", ".jpeg", ".png", ".webp").any { it in low } && listOf("pcgs.com", "cloudfront.net").any { it in low } && "meta.jpg" !in low) candidates += u.substringBefore("#")
        }
        fun discover(page: String) {
            val html = getBytes(page, page)?.toString(Charsets.UTF_8) ?: return
            val doc = Jsoup.parse(html, page)
            doc.select("a[href],img[src],img[data-src],img[data-original],source[srcset],img[srcset]").forEach { e ->
                listOf("href","src","data-src","data-original").forEach { a -> if (e.hasAttr(a)) addCandidate(e.absUrl(a)) }
                if (e.hasAttr("srcset")) e.attr("srcset").split(',').forEach { item ->
                    val raw=item.trim().substringBefore(' '); if(raw.startsWith("//")) addCandidate("https:$raw") else if(raw.startsWith("http")) addCandidate(raw)
                }
            }
            Regex("""https?[^"'<>\s]+""", RegexOption.IGNORE_CASE).findAll(html).forEach { addCandidate(it.value) }
        }
        discover("https://www.pcgs.com/cert/$cert")
        discover("https://www.pcgs.com/trueview/$cert")
        val full = candidates.sortedByDescending { cert in it }.firstNotNullOfOrNull { u -> downloadFile(u, dir, "pcgs_${cert}_fallback.jpg", "https://www.pcgs.com/cert/$cert") } ?: return DownloadResult(emptyList())
        val bitmap=BitmapFactory.decodeFile(full) ?: return DownloadResult(listOf(full))
        if(bitmap.width < bitmap.height*1.20f || bitmap.width < 2){ bitmap.recycle(); return DownloadResult(listOf(full), front=full) }
        val mid=bitmap.width/2; val left=android.graphics.Bitmap.createBitmap(bitmap,0,0,mid,bitmap.height); val right=android.graphics.Bitmap.createBitmap(bitmap,mid,0,bitmap.width-mid,bitmap.height)
        val front=File(dir,"pcgs_${cert}_front.jpg"); val back=File(dir,"pcgs_${cert}_back.jpg")
        front.outputStream().use{left.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it)}; back.outputStream().use{right.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it)}
        left.recycle();right.recycle();bitmap.recycle(); return DownloadResult(listOf(full),front.absolutePath,back.absolutePath)
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
