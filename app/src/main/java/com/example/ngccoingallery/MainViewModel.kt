package com.example.ngccoingallery

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainViewModel(private val context: Context, private val coinStore: CoinStore, private val scanner: NgcScanner) : ViewModel() {
    val coins: StateFlow<List<Coin>> = coinStore.coins
    val trash: StateFlow<List<Coin>> = coinStore.trash
    private val downloader=SiteImageDownloader(context)
    private val _processing=MutableStateFlow(false);val processing=_processing.asStateFlow()
    private val _message=MutableStateFlow<String?>(null);val message=_message.asStateFlow()
    private val _downloading=MutableStateFlow<Set<String>>(emptySet());val downloadingNgcImages=_downloading.asStateFlow()
    private val _newCoinId=MutableStateFlow<String?>(null); val newCoinId=_newCoinId.asStateFlow()
    private val _refreshingDetails=MutableStateFlow<Set<String>>(emptySet()); val refreshingDetails=_refreshingDetails.asStateFlow()
    private val _pcgsDebugInfo=MutableStateFlow(""); val pcgsDebugInfo=_pcgsDebugInfo.asStateFlow()
    fun fetchPcgsDebug(cert:String){ viewModelScope.launch(Dispatchers.IO){ _pcgsDebugInfo.value=downloader.debugPcgsPage(cert).summary } }

    init {
        refreshExistingPcgsMetadata()
        repairExistingMissingNgcCoinNumbersOnce()
    }

    /**
     * One-time migration for NGC records created before QR-derived coin numbers were stored.
     * A conclusive failed scan is persisted as "Not scanable :(" so it is not retried on every launch.
     */
    private fun repairExistingMissingNgcCoinNumbersOnce() {
        val prefs = context.getSharedPreferences("metadata_migrations", Context.MODE_PRIVATE)
        if (prefs.getInt("ngc_coin_number_repair_version", 0) >= 2) return
        // Version 1 incorrectly wrote "Not scanable" before a conclusive scan.
        // Retry blank values and that exact legacy marker once. The new marker contains
        // a sad face and therefore won't be retried on later launches.
        val targets = coins.value.filter {
            it.service == "NGC" && (it.coinNumber.isBlank() || it.coinNumber == "Not scanable")
        }
        prefs.edit().putInt("ngc_coin_number_repair_version", 2).apply()
        targets.forEach { ensureSiteImages(it) }
    }

    fun clearMessage(){_message.value=null}

    data class CollectionImport(val mineUrls: List<String>, val notMineUrls: List<String>, val gradingErrors: Map<String, String> = emptyMap()) {
        val totalCount: Int get() = (mineUrls + notMineUrls).distinct().size
    }

    fun exportCollectionText(): String {
        val active = coins.value
        fun exportLine(c: Coin): String = if (c.gradingError) "${c.url} (${c.gradingErrorNote.ifBlank { "Grading error" }})" else c.url
        val mine = active.filter { it.isMine }.map(::exportLine).distinct()
        val notMine = active.filterNot { it.isMine }.map(::exportLine).distinct()
        return buildString {
            appendLine("Mine:")
            mine.forEach { appendLine(it) }
            appendLine()
            appendLine("Not mine:")
            notMine.forEach { appendLine(it) }
        }
    }

    fun parseCollectionImport(text: String): CollectionImport? {
        var section = ""
        val mine = mutableListOf<String>()
        val notMine = mutableListOf<String>()
        val gradingErrors = linkedMapOf<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isBlank()) continue
            when (line.lowercase()) {
                "mine:" -> { section = "mine"; continue }
                "not mine:", "notmine:" -> { section = "notmine"; continue }
            }
            if (!line.startsWith("http://") && !line.startsWith("https://")) continue
            val m = Regex("^(https?://\\S+?)(?:\\s+\\((.*)\\))?$").matchEntire(line)
            val url = m?.groupValues?.getOrNull(1)?.trim() ?: line
            val note = m?.groupValues?.getOrNull(2)?.trim().orEmpty()
            if (note.isNotBlank()) gradingErrors[url] = if (note.equals("Grading error", true)) "" else note
            when (section) {
                "mine" -> mine += url
                "notmine" -> notMine += url
            }
        }
        if (mine.isEmpty() && notMine.isEmpty()) return null
        return CollectionImport(mine.distinct(), notMine.distinct(), gradingErrors)
    }

    fun importCollection(data: CollectionImport, importMineStatus: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            var added = 0
            var existing = 0
            var invalid = 0
            val mineSet = data.mineUrls.toSet()
            val allUrls = (data.mineUrls + data.notMineUrls).distinct()
            for (url in allUrls) {
                val parsed = coinFromCollectionUrl(url)
                if (parsed == null) { invalid++; continue }
                val old = coins.value.firstOrNull { it.service == parsed.service && it.certNumber == parsed.certNumber }
                if (old != null) {
                    existing++
                    if (importMineStatus && url in mineSet && !old.isMine) coinStore.setMine(old.id, true)
                    if (url in data.gradingErrors) coinStore.setGradingError(old.id, true, data.gradingErrors[url].orEmpty())
                    continue
                }
                val shouldMine = importMineStatus && url in mineSet
                val coin = parsed.copy(isMine = shouldMine)
                coinStore.saveCoin(coin)
                if (url in data.gradingErrors) coinStore.setGradingError(coin.id, true, data.gradingErrors[url].orEmpty())
                added++
                // Import is intentionally non-destructive and doesn't block on site verification.
                ensureSiteImages(coin)
                try {
                    val vr = downloader.verifyAndReadDetails(coin)
                    if (vr.status == SiteImageDownloader.VerifyStatus.VALID) coinStore.updateSiteDetails(coin.id, vr.details)
                } catch (_: Exception) { }
            }
            _message.value = "Import finished: $added added, $existing already present${if (invalid > 0) ", $invalid invalid" else ""}."
        }
    }

    private fun coinFromCollectionUrl(rawUrl: String): Coin? {
        val url = rawUrl.trim()
        val pcgs = Regex("""(?i)^https?://(?:www\.)?pcgs\.com/cert/(\d{7,8})/?(?:[?#].*)?$""").find(url)
        if (pcgs != null) {
            val cert = pcgs.groupValues[1]
            return Coin(UUID.randomUUID().toString(), "PCGS", "", cert, "", "https://www.pcgs.com/cert/$cert")
        }
        val ngc = Regex("""(?i)^https?://(?:www\.)?(?:ngccoin\.uk|ngccoin\.com)/certlookup/(\d{7}-\d{3})/([^/?#]+)/?(?:[?#].*)?$""").find(url)
        if (ngc != null) {
            val cert = ngc.groupValues[1]
            val grade = ngc.groupValues[2]
            return Coin(UUID.randomUUID().toString(), "NGC", "", cert, grade, "https://www.ngccoin.uk/certlookup/$cert/$grade/")
        }
        return null
    }

    /**
     * Older saved PCGS records predate the PCGS grade / PCGS Number metadata fields.
     * Refresh only records which are missing one of those values, and do it silently so
     * upgrading the app enriches the existing collection without user interaction.
     */
    val pcgsMetadataStatus: String
        get() {
            val pcgs = coins.value.filter { it.service == "PCGS" }
            val complete = pcgs.count { it.pcgsTypeName.isNotBlank() && it.grade.isNotBlank() }
            return "PCGS metadata: $complete/${pcgs.size} complete"
        }

    fun refreshAllPcgsMetadata() {
        val targets = coins.value.filter { it.service == "PCGS" }
        viewModelScope.launch(Dispatchers.IO) {
            for (coin in targets) { refreshPcgsMetadataIfNeeded(coin, force = true); kotlinx.coroutines.delay(250) }
            _message.value = "PCGS metadata refresh finished."
        }
    }

    private fun refreshExistingPcgsMetadata() {
        val migrationPrefs = context.getSharedPreferences("metadata_migrations", Context.MODE_PRIVATE)
        val forceAll = migrationPrefs.getInt("pcgs_details_version", 0) < 3
        val targets = coins.value.filter {
            it.service == "PCGS" && (forceAll || it.grade.isBlank() || it.coinNumber.isBlank() || it.pcgsTypeName.isBlank())
        }
        if (targets.isEmpty()) {
            if (forceAll) migrationPrefs.edit().putInt("pcgs_details_version", 3).apply()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            for (coin in targets) {
                refreshPcgsMetadataIfNeeded(coin, force = forceAll)
                // Avoid hitting the public cert site in a tight burst for larger collections.
                kotlinx.coroutines.delay(250)
            }
            // Run a full refresh once after upgrading to the metadata-aware version.
            // Any item which still lacks data will keep retrying on later launches/open.
            if (forceAll) migrationPrefs.edit().putInt("pcgs_details_version", 3).apply()
        }
    }

    private suspend fun refreshPcgsMetadataIfNeeded(coin: Coin, force: Boolean = false) {
        if (coin.service != "PCGS" || (!force && coin.grade.isNotBlank() && coin.coinNumber.isNotBlank() && coin.pcgsTypeName.isNotBlank())) return
        if (coin.id in _refreshingDetails.value) return
        _refreshingDetails.value = _refreshingDetails.value + coin.id
        try {
            val result = downloader.verifyAndReadDetails(coin)
            if (result.status == SiteImageDownloader.VerifyStatus.VALID) {
                coinStore.updateSiteDetails(coin.id, result.details)
            }
        } catch (_: Exception) {
            // Best effort. A later app launch / coin open will retry.
        } finally {
            _refreshingDetails.value = _refreshingDetails.value - coin.id
        }
    }

    fun addPcgsByCert(certRaw: String): Boolean {
        val cert = certRaw.filter { it.isDigit() }
        if (cert.length !in 7..8) { _message.value = "PCGS certificate must be 7 or 8 digits."; return false }
        val c = Coin(UUID.randomUUID().toString(), "PCGS", "", cert, "", "https://www.pcgs.com/cert/$cert")
        validateAndSave(c, "Added PCGS $cert."); return true
    }

    fun addNgcByCertAndGrade(certRaw: String, grade: String): Boolean {
        val certDigits = certRaw.filter { it.isDigit() }
        val cert = when {
            Regex("^\\d{7}-\\d{3}$").matches(certRaw.trim()) -> certRaw.trim()
            certDigits.length == 10 -> "${certDigits.substring(0,7)}-${certDigits.substring(7)}"
            else -> { _message.value = "NGC certificate must be 10 digits (or 7 digits-3 digits)."; return false }
        }
        if (grade !in NGC_GRADES) { _message.value = "Invalid NGC grade."; return false }
        val c = Coin(UUID.randomUUID().toString(), "NGC", "", cert, grade, "https://www.ngccoin.uk/certlookup/$cert/$grade/")
        validateAndSave(c, "Added NGC $cert / $grade."); return true
    }

    companion object {
        val NGC_GRADES = listOf("70","69","68","67","66","65","64","63","62","61","60","58","55","53","50","45","40","35","30","25","20","15","12","10","8","6","4","3","2","1","NGCAncients","NGCDetails")
    }

    fun addScannedBarcode(result: NgcScanner.Result) {
        val existing = coins.value.firstOrNull { it.service == result.service && it.certNumber == result.certNumber }
        if (existing != null) { _message.value = "${result.service} ${result.certNumber} is already in the gallery."; return }
        val c = Coin(UUID.randomUUID().toString(), result.service, result.coinNumber, result.certNumber, result.grade, result.url, rawBarcode = result.rawBarcode)
        validateAndSave(c, "Scanned ${result.service} ${result.certNumber}.")
    }

    fun processManualBarcode(raw:String):Boolean {
        val s=scanner.parseManualBarcode(raw)?:run{_message.value="Invalid barcode text.";return false}
        val c=Coin(UUID.randomUUID().toString(),s.service,s.coinNumber,s.certNumber,s.grade,s.url, rawBarcode = s.rawBarcode)
        validateAndSave(c,"Added ${s.service} ${s.certNumber}.");return true
    }

    fun processBatchPhotos(uris:List<Uri>){
        if(uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO){
            _processing.value=true; var ok=0; var bad=0
            try {
                for(uri in uris){
                    try {
                        val scan = scanner.scanImage(uri)
                        if (scan == null) {
                            bad++
                            continue
                        }
                        val p=copy(uri,"coin_photos")
                        val c=Coin(UUID.randomUUID().toString(),scan.service,scan.coinNumber,scan.certNumber,scan.grade,scan.url,p, rawBarcode=scan.rawBarcode)
                        val vr=downloader.verifyAndReadDetails(c)
                        if (vr.status == SiteImageDownloader.VerifyStatus.VALID) {
                            saveVerified(c, vr.details); ok++
                        } else if (c.service == "NGC") {
                            // NGC may block automated HTTP requests even while Cert Lookup is live.
                            // A valid barcode scan is enough to save; details/images are retried in background.
                            saveUnverified(c); ok++
                        } else {
                            File(p).delete(); bad++
                        }
                    } catch(_:Exception){ bad++ }
                }
                _message.value=if(ok>0) "Added $ok verified coin(s)${if(bad>0) "; rejected $bad" else ""}." else "No valid, verifiable NGC or PCGS coin was detected."
            } finally { _processing.value=false }
        }
    }

    private fun validateAndSave(coin: Coin, successMessage: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _processing.value=true
            try {
                when(val vr=downloader.verifyAndReadDetails(coin)) {
                    is SiteImageDownloader.VerifyResult -> when(vr.status) {
                        SiteImageDownloader.VerifyStatus.VALID -> { saveVerified(coin, vr.details); _message.value=successMessage }
                        SiteImageDownloader.VerifyStatus.INVALID -> {
                            if (coin.service == "NGC") {
                                saveUnverified(coin)
                                _message.value=successMessage
                            } else {
                                _message.value="${coin.service} ${coin.certNumber} was not found on the official site, so it was not added."
                            }
                        }
                        SiteImageDownloader.VerifyStatus.TEMPORARY_ERROR -> {
                            if (coin.service == "NGC") {
                                saveUnverified(coin)
                                _message.value=successMessage
                            } else {
                                _message.value="Could not verify ${coin.service} ${coin.certNumber} because the site/network is temporarily unavailable. Nothing was added; try again later."
                            }
                        }
                    }
                }
            } finally { _processing.value=false }
        }
    }

    private fun saveUnverified(coin: Coin) {
        val saved = coin.copy(siteVerified = false)
        coinStore.saveCoin(saved)
        _newCoinId.value = saved.id
        viewModelScope.launch {
            kotlinx.coroutines.delay(15000)
            if (_newCoinId.value == saved.id) _newCoinId.value = null
        }
        // Best-effort only: NGC may allow image URLs while blocking the cert page,
        // or may become accessible on a later retry. Never remove the saved coin.
        ensureSiteImages(saved)
    }

    private fun saveVerified(coin: Coin, details: SiteImageDownloader.SiteDetails) {
        val saved=coin.copy(
            description=details.description, year=details.year, country=details.country,
            denomination=details.denomination, variety=details.variety,
            grade=details.grade.ifBlank { coin.grade },
            coinNumber=details.coinNumber.ifBlank { coin.coinNumber },
            siteVerified=true
        )
        coinStore.saveCoin(saved)
        _newCoinId.value=saved.id
        viewModelScope.launch { kotlinx.coroutines.delay(15000); if(_newCoinId.value==saved.id) _newCoinId.value=null }
        ensureSiteImages(saved)
    }

    fun ensureSiteImages(coin: Coin) {
        if (coin.service == "PCGS" && (coin.grade.isBlank() || coin.coinNumber.isBlank() || coin.pcgsTypeName.isBlank())) {
            viewModelScope.launch(Dispatchers.IO) { refreshPcgsMetadataIfNeeded(coin) }
        }
        val existingFront = coin.frontImagePath.takeIf { it.isNotBlank() && File(it).exists() }
        if (coin.siteImagePaths.any { File(it).exists() }) {
            if (coin.service == "NGC" && coin.coinNumber.isBlank() && existingFront != null) {
                fillNgcCoinNumberFromObv(coin.id, coin.certNumber, existingFront)
            }
            return
        }
        if (coin.id in _downloading.value) return
        _downloading.value = _downloading.value + coin.id
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val verified = downloader.verifyAndReadDetails(coin)
                if (verified.status == SiteImageDownloader.VerifyStatus.VALID) coinStore.updateSiteDetails(coin.id, verified.details)
                val r = downloader.download(coin)
                if (r.siteImages.isNotEmpty()) {
                    coinStore.updateSiteImages(coin.id, r.siteImages, r.front, r.back)
                    if (coin.service == "NGC") coinStore.markSiteVerified(coin.id)
                    if (coin.service == "NGC" && coin.coinNumber.isBlank() && r.front.isNotBlank()) {
                        val number = scanner.scanNgcCoinNumberFromFile(r.front, coin.certNumber)
                        if (!number.isNullOrBlank()) {
                            coinStore.updateCoinNumber(coin.id, number)
                            _message.value = "Downloaded NGC photos and found coin number $number from the OBV QR."
                        } else {
                            coinStore.updateCoinNumber(coin.id, "Not scanable :(")
                            _message.value = "Downloaded NGC photo(s); the OBV QR is not scanable."
                        }
                    } else {
                        _message.value = "Downloaded ${coin.service} photo(s)."
                    }
                } else {
                    // No downloaded OBV means no scan was actually attempted. Keep the
                    // coin number unresolved rather than permanently marking it unscanable.
                    _message.value = "No ${coin.service} site photo is available for ${coin.certNumber}."
                }
            } catch (_: Exception) {
                _message.value = "Could not download ${coin.service} photos."
            } finally {
                _downloading.value = _downloading.value - coin.id
            }
        }
    }

    private fun fillNgcCoinNumberFromObv(coinId: String, certNumber: String, frontPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val number = scanner.scanNgcCoinNumberFromFile(frontPath, certNumber)
            coinStore.updateCoinNumber(coinId, number?.takeIf { it.isNotBlank() } ?: "Not scanable :(")
        }
    }

    fun retryNgcCoinNumberScan(coin: Coin) {
        if (coin.service != "NGC") return
        val front = coin.frontImagePath.takeIf { it.isNotBlank() && File(it).exists() }
        if (front == null) {
            _message.value = "NGC OBV image is missing. Re-download site photos first."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val debug = scanner.debugNgcCoinNumberFromFile(front, coin.certNumber)
            if (!debug.coinNumber.isNullOrBlank()) {
                coinStore.updateCoinNumber(coin.id, debug.coinNumber)
                _message.value = "Coin number repaired: ${debug.coinNumber}. ${debug.summary}"
            } else {
                coinStore.updateCoinNumber(coin.id, "Not scanable :(")
                _message.value = debug.summary
            }
        }
    }

    fun switchService(coin: Coin) {
        val raw = coin.rawBarcode
        if (raw.isBlank()) {
            _message.value = "This coin has no saved original barcode, so its service cannot be reclassified automatically."
            return
        }
        val target = if (coin.service == "NGC") "PCGS" else "NGC"
        val parsed = scanner.parseAsService(raw, target)
        if (parsed == null) {
            _message.value = "The saved barcode cannot be parsed as $target. Use the manual certificate option if needed."
            return
        }
        coinStore.replaceIdentification(coin.id, parsed)
        val updated = coin.copy(
            service = parsed.service, coinNumber = parsed.coinNumber, certNumber = parsed.certNumber,
            grade = parsed.grade, url = parsed.url, rawBarcode = parsed.rawBarcode,
            siteImagePaths = emptyList(), frontImagePath = "", backImagePath = ""
        )
        _message.value = "Changed identification to ${parsed.service} ${parsed.certNumber}."
        ensureSiteImages(updated)
    }
    fun addManualPhotos(coin:Coin,uris:List<Uri>){if(uris.isEmpty())return;viewModelScope.launch(Dispatchers.IO){val paths=uris.mapNotNull{try{copy(it,"manual_photos/${coin.id}")}catch(_:Exception){null}};if(paths.isNotEmpty()){coinStore.addManualImages(coin.id,paths);_message.value="Added ${paths.size} manual photo(s)."}}}
    fun removeManualPhoto(coin:Coin,path:String)=coinStore.removeManualImage(coin.id,path)
    fun removeSitePhoto(coin: Coin, path: String) { coinStore.removeSiteImage(coin.id, path); _message.value = "Site photo deleted locally." }
    fun redownloadSitePhotos(coin: Coin) {
        coin.siteImagePaths.forEach { File(it).delete() }
        coinStore.updateSiteImages(coin.id, emptyList(), "", "")
        coinStore.allowSiteImagesAgain(coin.id)
        val fresh = coins.value.firstOrNull { it.id == coin.id } ?: coin.copy(siteImagePaths=emptyList(), frontImagePath="", backImagePath="", deletedSitePhotoNames=emptyList())
        ensureSiteImages(fresh)
    }
    fun setMine(c:Coin,v:Boolean)=coinStore.setMine(c.id,v);fun setGradingError(c:Coin,v:Boolean,note:String="")=coinStore.setGradingError(c.id,v,note);fun deleteCoin(c:Coin)=coinStore.deleteCoin(c);fun restoreCoin(c:Coin)=coinStore.restoreCoin(c);fun permanentlyDelete(c:Coin)=coinStore.permanentlyDelete(c);fun purgeExpiredTrash()=coinStore.purgeExpiredTrash()
    private fun copy(uri:Uri,subdir:String):String{val d=File(context.filesDir,subdir).apply{mkdirs()};val f=File(d,"${UUID.randomUUID()}.jpg");context.contentResolver.openInputStream(uri).use{input->requireNotNull(input);f.outputStream().use{input.copyTo(it)}};return f.absolutePath}
}
