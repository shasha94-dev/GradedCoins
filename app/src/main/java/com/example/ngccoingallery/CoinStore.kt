package com.example.ngccoingallery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class CoinStore(private val context: Context) {
    companion object { private const val TRASH_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L }
    private val prefs = context.getSharedPreferences("coins", Context.MODE_PRIVATE)
    private val _coins = MutableStateFlow<List<Coin>>(emptyList())
    val coins: StateFlow<List<Coin>> = _coins.asStateFlow()
    private val _trash = MutableStateFlow<List<Coin>>(emptyList())
    val trash: StateFlow<List<Coin>> = _trash.asStateFlow()

    init { loadAll(); purgeExpiredTrash() }

    fun saveCoin(coin: Coin) {
        val current = _coins.value.toMutableList()
        val existing = current.indexOfFirst { it.service == coin.service && it.certNumber == coin.certNumber }
        if (existing >= 0) {
            val old = current[existing]
            if (old.imagePath.isNotBlank() && old.imagePath != coin.imagePath) File(old.imagePath).delete()
            current[existing] = coin.copy(
                siteImagePaths = old.siteImagePaths,
                frontImagePath = old.frontImagePath,
                backImagePath = old.backImagePath,
                manualImagePaths = old.manualImagePaths,
                isMine = old.isMine,
                deletedAt = null,
                rawBarcode = if (coin.rawBarcode.isNotBlank()) coin.rawBarcode else old.rawBarcode
            )
        } else current.add(coin.copy(deletedAt = null))
        _coins.value = sortCoins(current); persistAll()
    }

    fun updateSiteImages(coinId: String, paths: List<String>, front: String = "", back: String = "") {
        fun update(list: List<Coin>) = list.map { if (it.id == coinId) it.copy(siteImagePaths = paths, frontImagePath = front, backImagePath = back) else it }
        _coins.value = sortCoins(update(_coins.value)); _trash.value = update(_trash.value); persistAll()
    }


    fun updateSiteDetails(coinId: String, details: SiteImageDownloader.SiteDetails) {
        _coins.value = sortCoins(_coins.value.map { if (it.id == coinId) it.copy(
            description = details.description, year = details.year, country = details.country,
            denomination = details.denomination, variety = details.variety, siteVerified = true
        ) else it })
        persistAll()
    }

    fun markSiteVerified(coinId: String) {
        _coins.value = sortCoins(_coins.value.map { if (it.id == coinId) it.copy(siteVerified = true) else it })
        persistAll()
    }

    fun updateCoinNumber(coinId: String, coinNumber: String) {
        if (coinNumber.isBlank()) return
        _coins.value = sortCoins(_coins.value.map { if (it.id == coinId) it.copy(coinNumber = coinNumber) else it })
        persistAll()
    }

    fun replaceIdentification(coinId: String, parsed: NgcScanner.Result) {
        _coins.value = sortCoins(_coins.value.map { c ->
            if (c.id == coinId) c.copy(
                service = parsed.service,
                coinNumber = parsed.coinNumber,
                certNumber = parsed.certNumber,
                grade = parsed.grade,
                url = parsed.url,
                rawBarcode = parsed.rawBarcode,
                siteImagePaths = emptyList(),
                frontImagePath = "",
                backImagePath = ""
            ) else c
        })
        persistAll()
    }

    fun addManualImages(coinId: String, paths: List<String>) {
        _coins.value = sortCoins(_coins.value.map { if (it.id == coinId) it.copy(manualImagePaths = (it.manualImagePaths + paths).distinct()) else it })
        persistAll()
    }

    fun removeManualImage(coinId: String, path: String) {
        val coin = _coins.value.firstOrNull { it.id == coinId } ?: return
        if (path !in coin.manualImagePaths) return
        File(path).delete()
        _coins.value = sortCoins(_coins.value.map { if (it.id == coinId) it.copy(manualImagePaths = it.manualImagePaths.filterNot { p -> p == path }) else it })
        persistAll()
    }

    fun setMine(coinId: String, isMine: Boolean) { _coins.value = sortCoins(_coins.value.map { if (it.id == coinId) it.copy(isMine = isMine) else it }); persistAll() }
    fun deleteCoin(coin: Coin) { val d=coin.copy(deletedAt=System.currentTimeMillis()); _coins.value=sortCoins(_coins.value.filterNot{it.id==coin.id}); _trash.value=listOf(d)+_trash.value.filterNot{it.id==coin.id}; persistAll() }
    fun restoreCoin(coin: Coin) { _trash.value=_trash.value.filterNot{it.id==coin.id}; _coins.value=sortCoins(_coins.value.filterNot{it.id==coin.id}+coin.copy(deletedAt=null)); persistAll() }
    fun permanentlyDelete(coin: Coin) { deleteFiles(coin); _trash.value=_trash.value.filterNot{it.id==coin.id}; persistAll() }
    fun purgeExpiredTrash() { val cutoff=System.currentTimeMillis()-TRASH_RETENTION_MS; val expired=_trash.value.filter{(it.deletedAt?:Long.MAX_VALUE)<=cutoff}; expired.forEach(::deleteFiles); val ids=expired.map{it.id}.toSet(); _trash.value=_trash.value.filterNot{it.id in ids}; if(expired.isNotEmpty()) persistAll() }

    private fun deleteFiles(c: Coin) {
        (listOf(c.imagePath,c.frontImagePath,c.backImagePath)+c.siteImagePaths+c.manualImagePaths).filter{it.isNotBlank()}.distinct().forEach{File(it).delete()}
        c.siteImagePaths.firstOrNull()?.let{File(it).parentFile?.deleteRecursively()}
        c.manualImagePaths.firstOrNull()?.let{File(it).parentFile?.deleteRecursively()}
    }
    private fun sortCoins(list: List<Coin>) = list.sortedWith(compareBy<Coin>{it.service}.thenBy{it.coinNumber.toIntOrNull()?:Int.MAX_VALUE}.thenBy{it.certNumber})

    private fun persistAll() { prefs.edit().putString("items",toJson(_coins.value).toString()).putString("trash_items",toJson(_trash.value).toString()).apply() }
    private fun toJson(list: List<Coin>)=JSONArray().apply{list.forEach{c->put(JSONObject().apply{
        put("id",c.id);put("service",c.service);put("coinNumber",c.coinNumber);put("certNumber",c.certNumber);put("grade",c.grade);put("url",c.url);put("imagePath",c.imagePath)
        put("description",c.description);put("year",c.year);put("country",c.country);put("denomination",c.denomination);put("variety",c.variety);put("siteVerified",c.siteVerified);put("siteImagePaths",JSONArray(c.siteImagePaths));put("frontImagePath",c.frontImagePath);put("backImagePath",c.backImagePath);put("manualImagePaths",JSONArray(c.manualImagePaths));put("isMine",c.isMine);put("addedAt",c.addedAt);put("rawBarcode",c.rawBarcode);if(c.deletedAt!=null)put("deletedAt",c.deletedAt)else put("deletedAt",JSONObject.NULL)
    })}}
    private fun readPaths(obj: JSONObject,key:String)=buildList{val a=obj.optJSONArray(key);if(a!=null)for(i in 0 until a.length()){val p=a.optString(i);if(p.isNotBlank()&&File(p).exists())add(p)}}
    private fun loadAll(){_coins.value=sortCoins(readList("items").map{it.copy(deletedAt=null)});_trash.value=readList("trash_items").sortedByDescending{it.deletedAt?:0L}}
    private fun readList(key:String):List<Coin> = try { val a=JSONArray(prefs.getString(key,"[]")?:"[]");buildList{for(i in 0 until a.length()){val o=a.getJSONObject(i);val image=o.optString("imagePath","");val oldNgc=readPaths(o,"ngcImagePaths");val site=readPaths(o,"siteImagePaths").ifEmpty{oldNgc};val manual=readPaths(o,"manualImagePaths");val front=o.optString("frontImagePath","").takeIf{it.isNotBlank()&&File(it).exists()}?:site.firstOrNull{File(it).name.uppercase().contains("_OBV.")}.orEmpty();val back=o.optString("backImagePath","").takeIf{it.isNotBlank()&&File(it).exists()}?:site.firstOrNull{File(it).name.uppercase().contains("_REV.")}.orEmpty();add(Coin(o.getString("id"),o.optString("service","NGC"),o.optString("coinNumber",""),o.getString("certNumber"),o.optString("grade",""),o.getString("url"),image,site,front,back,manual,o.optBoolean("isMine",false),o.optLong("addedAt",0L),if(o.isNull("deletedAt"))null else o.optLong("deletedAt").takeIf{it>0},o.optString("rawBarcode",""),o.optString("description",""),o.optString("year",""),o.optString("country",""),o.optString("denomination",""),o.optString("variety",""),o.optBoolean("siteVerified",false)))}} } catch(_:Exception){emptyList()}
}
