package com.example.ngccoingallery

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.content.pm.PackageManager
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val coinStore = CoinStore(this)
        val scanner = NgcScanner(this)
        viewModel = MainViewModel(this, coinStore, scanner)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GalleryScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun GalleryScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val coins by viewModel.coins.collectAsState()
    val trash by viewModel.trash.collectAsState()
    val processing by viewModel.processing.collectAsState()
    val message by viewModel.message.collectAsState()
    val downloadingNgcImages by viewModel.downloadingNgcImages.collectAsState()
    val newCoinId by viewModel.newCoinId.collectAsState()
    val gridState = rememberLazyGridState()
    var showAddMenu by remember { mutableStateOf(false) }
    var showManualBarcode by remember { mutableStateOf(false) }
    var manualBarcodeText by remember { mutableStateOf("") }
    var showManualPcgsCert by remember { mutableStateOf(false) }
    var manualPcgsCert by remember { mutableStateOf("") }
    var showManualNgcCert by remember { mutableStateOf(false) }
    var manualNgcCert by remember { mutableStateOf("") }
    var manualNgcGrade by remember { mutableStateOf("70") }
    var showNgcGradeMenu by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var showBarcodeSide by rememberSaveable { mutableStateOf(true) }
    val uiPrefs = remember { context.getSharedPreferences("gallery_ui_settings", Context.MODE_PRIVATE) }
    var groupByType by remember { mutableStateOf(uiPrefs.getBoolean("group_by_type", false)) }
    var mineOnly by remember { mutableStateOf(uiPrefs.getBoolean("mine_only", false)) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<MainViewModel.CollectionImport?>(null) }
    var showPasteImport by remember { mutableStateOf(false) }
    var pasteImportText by remember { mutableStateOf("") }
    var openedGroupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var manualPhotoCoinId by rememberSaveable { mutableStateOf<String?>(null) }
    var showLiveScanner by rememberSaveable { mutableStateOf(false) }
    val liveBarcodeParser = remember { NgcScanner(context) }

    val cameraPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showLiveScanner = true
        else Toast.makeText(context, "Camera permission is required for live barcode scanning.", Toast.LENGTH_LONG).show()
    }

    val takePhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = cameraUriString?.let(Uri::parse)
        if (success && uri != null) {
            viewModel.processBatchPhotos(listOf(uri))
        } else {
            cameraPath?.let { File(it).delete() }
        }
        cameraUriString = null
        cameraPath = null
    }

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris -> viewModel.processBatchPhotos(uris) }

    val pickManualPhotos = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        val coin = coins.firstOrNull { it.id == manualPhotoCoinId }
        if (coin != null) viewModel.addManualPhotos(coin, uris)
        manualPhotoCoinId = null
    }

    val exportCollection = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(viewModel.exportCollectionText())
                }
                Toast.makeText(context, "Collection exported.", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Could not export collection.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun handleImportText(text: String) {
        val parsed = viewModel.parseCollectionImport(text)
        if (parsed == null) {
            Toast.makeText(context, "No valid collection links were found.", Toast.LENGTH_LONG).show()
        } else if (parsed.mineUrls.isNotEmpty()) {
            pendingImport = parsed
        } else {
            viewModel.importCollection(parsed, importMineStatus = false)
        }
    }

    val importCollection = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                handleImportText(text)
            } catch (_: Exception) {
                Toast.makeText(context, "Could not read import file.", Toast.LENGTH_LONG).show()
            }
        }
    }

    val visibleCoins = remember(coins, mineOnly) {
        if (mineOnly) coins.filter { it.isMine } else coins
    }
    val coinGroups = remember(visibleCoins) { buildCoinGroups(visibleCoins) }
    val openedGroup = openedGroupKey?.let { key -> coinGroups.firstOrNull { it.key == key } }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(newCoinId, visibleCoins, groupByType) {
        val id = newCoinId ?: return@LaunchedEffect
        val index = if (groupByType) {
            coinGroups.indexOfFirst { group -> group.coins.any { it.id == id } }
        } else {
            visibleCoins.indexOfFirst { it.id == id }
        }
        if (index >= 0) gridState.animateScrollToItem(index)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = R.mipmap.ic_launcher,
                contentDescription = "NGC Coin Gallery logo",
                modifier = Modifier.size(24.dp),
                contentScale = ContentScale.Fit
            )
            Text(
                "Coin Gallery",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { showBarcodeSide = !showBarcodeSide },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(if (showBarcodeSide) "Show REV" else "Show OBV", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Box {
                OutlinedButton(
                    onClick = { showSettingsMenu = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Settings", style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(
                    expanded = showSettingsMenu,
                    onDismissRequest = { showSettingsMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text((if (groupByType) "✓ " else "") + "Group by coin type") },
                        onClick = {
                            groupByType = !groupByType
                            uiPrefs.edit().putBoolean("group_by_type", groupByType).apply()
                            openedGroupKey = null
                        }
                    )
                    DropdownMenuItem(
                        text = { Text((if (mineOnly) "✓ " else "") + "Mine only") },
                        onClick = {
                            mineOnly = !mineOnly
                            uiPrefs.edit().putBoolean("mine_only", mineOnly).apply()
                            openedGroupKey = null
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Export collection to file") },
                        onClick = {
                            showSettingsMenu = false
                            exportCollection.launch("graded-coins-collection.txt")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share collection") },
                        onClick = {
                            showSettingsMenu = false
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "GradedCoins collection")
                                putExtra(Intent.EXTRA_TEXT, viewModel.exportCollectionText())
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share collection"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Import collection from file") },
                        onClick = {
                            showSettingsMenu = false
                            importCollection.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Import from pasted text") },
                        onClick = {
                            showSettingsMenu = false
                            pasteImportText = ""
                            showPasteImport = true
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(
                onClick = {
                    viewModel.purgeExpiredTrash()
                    showTrash = true
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Trash${if (trash.isNotEmpty()) " (${trash.size})" else ""}", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Box {
                Button(
                    enabled = !processing,
                    onClick = { showAddMenu = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp,
                        vertical = 2.dp
                    ),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        if (processing) "Scanning..." else "Add",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Choose photos") },
                        onClick = {
                            showAddMenu = false
                            pickMedia.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add PCGS certificate") },
                        onClick = { showAddMenu = false; manualPcgsCert = ""; showManualPcgsCert = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Add NGC certificate + grade") },
                        onClick = { showAddMenu = false; manualNgcCert = ""; manualNgcGrade = "70"; showManualNgcCert = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Enter barcode manually") },
                        onClick = {
                            showAddMenu = false
                            manualBarcodeText = ""
                            showManualBarcode = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Scan barcode with camera") },
                        onClick = {
                            showAddMenu = false
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                showLiveScanner = true
                            } else {
                                cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        }
                    )
                }
            }
        }

        if (processing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }

        if (!processing && visibleCoins.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(if (mineOnly) "No coins are marked as Mine." else "Choose or take a clear photo of an NGC or PCGS barcode.")
            }
        } else if (groupByType) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(coinGroups, key = { it.key }) { group ->
                    if (group.coins.size > 1) {
                        CoinGroupCard(
                            group = group,
                            showBarcodeSide = showBarcodeSide,
                            isNew = newCoinId?.let { id -> group.coins.any { it.id == id } } == true,
                            onOpen = { openedGroupKey = group.key }
                        )
                    } else {
                        val coin = group.coins.first()
                        CoinCard(
                            isNew = coin.id == newCoinId,
                            coin = coin,
                            showBarcodeSide = showBarcodeSide,
                            ngcImagesDownloading = coin.id in downloadingNgcImages,
                            onOpen = {
                                viewModel.ensureSiteImages(coin)
                                openUrl(context, coin.url)
                            },
                            onLongPress = { viewModel.ensureSiteImages(coin) },
                            onCopy = { copyUrl(context, coin.url) },
                            onSetMine = { mine -> viewModel.setMine(coin, mine) },
                            onSetGradingError = { enabled, note -> viewModel.setGradingError(coin, enabled, note) },
                            onAddManualPhotos = {
                                manualPhotoCoinId = coin.id
                                pickManualPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onRemoveManualPhoto = { path -> viewModel.removeManualPhoto(coin, path) },
                            onRemoveSitePhoto = { path -> viewModel.removeSitePhoto(coin, path) },
                            onRedownloadSitePhotos = { viewModel.redownloadSitePhotos(coin) },
                            onSwitchService = { viewModel.switchService(coin) },
                            onRetryNgcCoinNumber = { viewModel.retryNgcCoinNumberScan(coin) },
                            onDelete = { viewModel.deleteCoin(coin) }
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(visibleCoins, key = { it.id }) { coin ->
                    CoinCard(
                        isNew = coin.id == newCoinId,
                        coin = coin,
                        showBarcodeSide = showBarcodeSide,
                        ngcImagesDownloading = coin.id in downloadingNgcImages,
                        onOpen = {
                            viewModel.ensureSiteImages(coin)
                            openUrl(context, coin.url)
                        },
                        onLongPress = { viewModel.ensureSiteImages(coin) },
                        onCopy = { copyUrl(context, coin.url) },
                        onSetMine = { mine -> viewModel.setMine(coin, mine) },
                            onSetGradingError = { enabled, note -> viewModel.setGradingError(coin, enabled, note) },
                        onAddManualPhotos = {
                            manualPhotoCoinId = coin.id
                            pickManualPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onRemoveManualPhoto = { path -> viewModel.removeManualPhoto(coin, path) },
                            onRemoveSitePhoto = { path -> viewModel.removeSitePhoto(coin, path) },
                            onRedownloadSitePhotos = { viewModel.redownloadSitePhotos(coin) },
                        onSwitchService = { viewModel.switchService(coin) },
                        onRetryNgcCoinNumber = { viewModel.retryNgcCoinNumberScan(coin) },
                        onDelete = { viewModel.deleteCoin(coin) }
                    )
                }
            }
        }
    }

    if (openedGroup != null) {
        var groupShowBarcodeSide by remember(openedGroup.key) { mutableStateOf(true) }
        Dialog(
            onDismissRequest = { openedGroupKey = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${openedGroup.service} ${openedGroup.coinNumber.ifBlank { "Unknown type" }} (${openedGroup.coins.size})",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { groupShowBarcodeSide = !groupShowBarcodeSide },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(if (groupShowBarcodeSide) "Show REV" else "Show OBV", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedButton(onClick = { openedGroupKey = null }, modifier = Modifier.height(32.dp)) {
                            Text("Close", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(openedGroup.coins, key = { it.id }) { coin ->
                            CoinCard(
                                isNew = coin.id == newCoinId,
                                coin = coin,
                                showBarcodeSide = groupShowBarcodeSide,
                                ngcImagesDownloading = coin.id in downloadingNgcImages,
                                onOpen = {
                                    viewModel.ensureSiteImages(coin)
                                    openUrl(context, coin.url)
                                },
                                onLongPress = { viewModel.ensureSiteImages(coin) },
                                onCopy = { copyUrl(context, coin.url) },
                                onSetMine = { mine -> viewModel.setMine(coin, mine) },
                            onSetGradingError = { enabled, note -> viewModel.setGradingError(coin, enabled, note) },
                                onAddManualPhotos = {
                                    manualPhotoCoinId = coin.id
                                    pickManualPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                onRemoveManualPhoto = { path -> viewModel.removeManualPhoto(coin, path) },
                            onRemoveSitePhoto = { path -> viewModel.removeSitePhoto(coin, path) },
                            onRedownloadSitePhotos = { viewModel.redownloadSitePhotos(coin) },
                                onSwitchService = { viewModel.switchService(coin) },
                                onRetryNgcCoinNumber = { viewModel.retryNgcCoinNumberScan(coin) },
                                onDelete = { viewModel.deleteCoin(coin) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLiveScanner) {
        Dialog(
            onDismissRequest = { showLiveScanner = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            LiveBarcodeScanner(
                parser = liveBarcodeParser,
                onResult = { result ->
                    showLiveScanner = false
                    viewModel.addScannedBarcode(result)
                },
                onCancel = { showLiveScanner = false }
            )
        }
    }

    if (showManualPcgsCert) {
        AlertDialog(
            onDismissRequest = { showManualPcgsCert = false },
            title = { Text("Add PCGS by certificate") },
            text = { TextField(value = manualPcgsCert, onValueChange = { manualPcgsCert = it }, singleLine = true, label = { Text("PCGS serial / certificate") }) },
            confirmButton = { Button(enabled = manualPcgsCert.isNotBlank(), onClick = { if (viewModel.addPcgsByCert(manualPcgsCert)) showManualPcgsCert = false }) { Text("Add coin") } },
            dismissButton = { OutlinedButton(onClick = { showManualPcgsCert = false }) { Text("Cancel") } }
        )
    }

    if (showManualNgcCert) {
        AlertDialog(
            onDismissRequest = { showManualNgcCert = false },
            title = { Text("Add NGC by certificate") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(value = manualNgcCert, onValueChange = { manualNgcCert = it }, singleLine = true, label = { Text("NGC serial / certificate") }, modifier = Modifier.fillMaxWidth())
                    Box {
                        OutlinedButton(onClick = { showNgcGradeMenu = true }) { Text("Grade: $manualNgcGrade") }
                        DropdownMenu(expanded = showNgcGradeMenu, onDismissRequest = { showNgcGradeMenu = false }) {
                            MainViewModel.NGC_GRADES.forEach { grade ->
                                DropdownMenuItem(text = { Text(grade) }, onClick = { manualNgcGrade = grade; showNgcGradeMenu = false })
                            }
                        }
                    }
                    Text("The app will build the NGC lookup URL and download the available NGC site photos/details.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(enabled = manualNgcCert.isNotBlank(), onClick = { if (viewModel.addNgcByCertAndGrade(manualNgcCert, manualNgcGrade)) showManualNgcCert = false }) { Text("Add coin") } },
            dismissButton = { OutlinedButton(onClick = { showManualNgcCert = false }) { Text("Cancel") } }
        )
    }

    if (showManualBarcode) {
        AlertDialog(
            onDismissRequest = { showManualBarcode = false },
            title = { Text("Enter full barcode") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the entire barcode text. The app first checks the strict NGC format; if it is not NGC, it tries PCGS using the final 8 digits and then the final 7 digits as the certificate number.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextField(
                        value = manualBarcodeText,
                        onValueChange = { manualBarcodeText = it },
                        singleLine = true,
                        label = { Text("Barcode text") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = manualBarcodeText.isNotBlank(),
                    onClick = {
                        if (viewModel.processManualBarcode(manualBarcodeText)) {
                            showManualBarcode = false
                            manualBarcodeText = ""
                        }
                    }
                ) { Text("Add coin") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showManualBarcode = false }) { Text("Cancel") }
            }
        )
    }

    if (showPasteImport) {
        AlertDialog(
            onDismissRequest = { showPasteImport = false },
            title = { Text("Import collection text") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste a GradedCoins collection list below. Both Mine: and Not mine: sections are supported.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextField(
                        value = pasteImportText,
                        onValueChange = { pasteImportText = it },
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        placeholder = { Text("Mine:\nhttps://...\n\nNot mine:\nhttps://...") }
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = pasteImportText.isNotBlank(),
                    onClick = {
                        val text = pasteImportText
                        val parsed = viewModel.parseCollectionImport(text)
                        if (parsed == null) {
                            Toast.makeText(context, "No valid collection links were found.", Toast.LENGTH_LONG).show()
                        } else {
                            showPasteImport = false
                            pasteImportText = ""
                            if (parsed.mineUrls.isNotEmpty()) pendingImport = parsed
                            else viewModel.importCollection(parsed, importMineStatus = false)
                        }
                    }
                ) { Text("Import") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPasteImport = false }) { Text("Cancel") }
            }
        )
    }

    pendingImport?.let { data ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import Mine status?") },
            text = {
                Text("The file contains ${data.mineUrls.size} coin(s) marked as Mine. Do you want to mark those coins as Mine in your collection? Existing Mine status will never be removed.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    viewModel.importCollection(data, importMineStatus = true)
                }) { Text("Yes") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingImport = null
                        viewModel.importCollection(data, importMineStatus = false)
                    }) { Text("No") }
                    TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
                }
            }
        )
    }

    if (showTrash) {
        TrashDialog(
            trash = trash,
            onDismiss = { showTrash = false },
            onRestore = { viewModel.restoreCoin(it) },
            onDeleteForever = { viewModel.permanentlyDelete(it) }
        )
    }
}

private data class CoinGroup(
    val key: String,
    val service: String,
    val coinNumber: String,
    val typeLabel: String,
    val coins: List<Coin>,
    val representative: Coin
)

private fun buildCoinGroups(coins: List<Coin>): List<CoinGroup> {
    return coins
        .groupBy { coin ->
            // NGC groups by its six-digit coin number. PCGS groups by the visible
            // coin-type heading from the PCGS certificate page. Unknown types stay separate.
            val typeKey = if (coin.service == "PCGS") coin.pcgsTypeName.trim() else coin.coinNumber.trim()
            val ngcUngroupable = coin.service == "NGC" && (
                typeKey.startsWith("Not scanable", ignoreCase = true) ||
                    (typeKey.isNotBlank() && typeKey.all { it == '0' })
            )
            if (coin.gradingError || typeKey.isBlank() || ngcUngroupable) "${coin.service}:unknown:${coin.id}"
            else "${coin.service}:${typeKey.lowercase()}"
        }
        .map { (key, members) ->
            val sortedMembers = members.sortedWith(
                compareByDescending<Coin> { gradeScore(it.grade) }
                    .thenByDescending { it.addedAt }
            )
            CoinGroup(
                key = key,
                service = members.first().service,
                coinNumber = members.first().coinNumber,
                typeLabel = if (members.first().service == "PCGS") members.first().pcgsTypeName else members.first().coinNumber,
                coins = sortedMembers,
                representative = sortedMembers.first()
            )
        }
        .sortedWith(Comparator { a, b ->
            val errorCmp = b.coins.any { it.gradingError }.compareTo(a.coins.any { it.gradingError })
            if (errorCmp != 0) return@Comparator errorCmp
            val serviceCmp = a.service.compareTo(b.service, ignoreCase = true)
            if (serviceCmp != 0) return@Comparator serviceCmp
            if (a.service == "PCGS") a.typeLabel.compareTo(b.typeLabel, ignoreCase = true)
            else (a.coinNumber.toLongOrNull() ?: Long.MAX_VALUE).compareTo(b.coinNumber.toLongOrNull() ?: Long.MAX_VALUE)
        })
}

private fun gradeScore(grade: String): Int {
    val normalized = grade.uppercase()
    val number = Regex("(\\d{1,2})(?:[+*])?").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return when {
        number != null -> number
        "DETAIL" in normalized -> -1
        "ANCIENT" in normalized -> -2
        else -> -3
    }
}

@Composable
private fun CoinGroupCard(
    group: CoinGroup,
    showBarcodeSide: Boolean,
    isNew: Boolean,
    onOpen: () -> Unit
) {
    val coin = group.representative
    val siteSidePath = if (showBarcodeSide) coin.frontImagePath else coin.backImagePath
    val manualSidePath = if (coin.service == "PCGS") {
        coin.manualImagePaths.getOrNull(if (showBarcodeSide) 0 else 1)?.takeIf { File(it).exists() }
    } else {
        coin.manualImagePaths.firstOrNull { File(it).exists() }
    }
    val displayPath = siteSidePath.takeIf { it.isNotBlank() && File(it).exists() }
        ?: manualSidePath
        ?: coin.imagePath

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(if (isNew) Color(0xFFFFF3B0) else Color.Transparent)
            .padding(if (isNew) 3.dp else 0.dp)
            .clickable(onClick = onOpen)
    ) {
        if (displayPath.isNotBlank() && File(displayPath).exists()) {
            AsyncImage(
                model = File(displayPath),
                contentDescription = "${group.service} type ${group.typeLabel}, ${group.coins.size} coins",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("${group.service}\n${group.typeLabel.ifBlank { "Unknown" }}", style = MaterialTheme.typography.labelSmall)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(4.dp)
                .size(26.dp)
                .background(Color.Black.copy(alpha = 0.75f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(group.coins.size.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        if (group.coins.any { it.isMine }) {
            Text("★", modifier = Modifier.align(Alignment.TopEnd).padding(2.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoinCard(
    coin: Coin,
    isNew: Boolean,
    showBarcodeSide: Boolean,
    ngcImagesDownloading: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onCopy: () -> Unit,
    onSetMine: (Boolean) -> Unit,
    onSetGradingError: (Boolean, String) -> Unit,
    onAddManualPhotos: () -> Unit,
    onRemoveManualPhoto: (String) -> Unit,
    onRemoveSitePhoto: (String) -> Unit = {},
    onRedownloadSitePhotos: () -> Unit = {},
    onSwitchService: () -> Unit,
    onRetryNgcCoinNumber: () -> Unit,
    onDelete: () -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }
    var zoomImagePath by remember { mutableStateOf<String?>(null) }
    var showServiceOverride by remember { mutableStateOf(false) }
    var photoMenuPath by remember { mutableStateOf<String?>(null) }
    var photoMenuIsManual by remember { mutableStateOf(false) }
    var showGradingErrorDialog by remember { mutableStateOf(false) }
    var gradingErrorNoteDraft by remember(coin.gradingErrorNote) { mutableStateOf(coin.gradingErrorNote) }

    val siteSidePath = if (showBarcodeSide) coin.frontImagePath else coin.backImagePath
    val manualSidePath = if (coin.service == "PCGS") {
        val index = if (showBarcodeSide) 0 else 1
        coin.manualImagePaths.getOrNull(index)?.takeIf { File(it).exists() }
    } else {
        coin.manualImagePaths.firstOrNull { File(it).exists() }
    }
    val displayPath = siteSidePath.takeIf { it.isNotBlank() && File(it).exists() }
        ?: manualSidePath
        ?: coin.imagePath

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(if (isNew) Color(0xFFFFF3B0) else Color.Transparent)
            .padding(if (isNew) 3.dp else 0.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = onOpen,
                    onDoubleClick = {
                        onLongPress()
                        showDetails = true
                    },
                    onLongClick = {
                        onLongPress()
                        showDetails = true
                    }
                ),
            shape = RectangleShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            if (displayPath.isNotBlank() && File(displayPath).exists()) {
                AsyncImage(
                    model = File(displayPath),
                    contentDescription = "${coin.service} coin ${coin.certNumber}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("${coin.service}\n${coin.certNumber}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (coin.isMine) {
            Text(
                "★",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (coin.gradingError) {
            Text(
                "!",
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp).background(Color(0xFFFFD54F)).padding(horizontal = 5.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("${coin.service} coin") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ngcImagesDownloading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text("Downloading ${coin.service} photos...", modifier = Modifier.padding(start = 8.dp))
                        }
                    } else if (coin.siteImagePaths.isNotEmpty()) {
                        Text("${coin.service} site photos", fontWeight = FontWeight.SemiBold)
                        LazyRow(modifier = Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // PCGS shows the original un-cut combined image here. NGC shows its downloaded originals.
                            lazyRowItems(coin.siteImagePaths) { path ->
                                AsyncImage(
                                    model = File(path),
                                    contentDescription = "Site photo ${coin.certNumber}",
                                    modifier = Modifier.width(180.dp).height(180.dp).combinedClickable(
                                        onClick = { zoomImagePath = path },
                                        onLongClick = { photoMenuPath = path; photoMenuIsManual = false }
                                    ),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    } else {
                        Text("No downloaded site photo. Many PCGS coins do not have a TrueView image.", style = MaterialTheme.typography.bodySmall)
                    }

                    Text("Manual photos", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = onAddManualPhotos) { Text("Add photos") }
                    if (coin.manualImagePaths.isNotEmpty()) {
                        LazyRow(modifier = Modifier.fillMaxWidth().height(165.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(coin.manualImagePaths.size) { index ->
                                val path = coin.manualImagePaths[index]
                                Column {
                                    if (coin.service == "PCGS" && index < 2) {
                                        Text(if (index == 0) "Front / OBV" else "Back / REV", style = MaterialTheme.typography.labelSmall)
                                    }
                                    AsyncImage(
                                        model = File(path),
                                        contentDescription = "Manual photo",
                                        modifier = Modifier.width(120.dp).height(110.dp).combinedClickable(
                                            onClick = { zoomImagePath = path },
                                            onLongClick = { photoMenuPath = path; photoMenuIsManual = true }
                                        ),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { onSetMine(!coin.isMine) }) {
                        Text(if (coin.isMine) "Remove from Mine" else "Mark as Mine")
                    }
                    OutlinedButton(onClick = {
                        gradingErrorNoteDraft = coin.gradingErrorNote
                        showGradingErrorDialog = true
                    }) {
                        Text(if (coin.gradingError) "Edit grading error" else "Mark grading error")
                    }
                    if (coin.gradingError) {
                        Text("Grading error${if (coin.gradingErrorNote.isNotBlank()) ": ${coin.gradingErrorNote}" else ""}", style = MaterialTheme.typography.bodySmall)
                    }

                    if (coin.coinNumber.isNotBlank()) {
                        if (coin.service == "PCGS") {
                            if (coin.pcgsTypeName.isNotBlank()) Text("Type: ${coin.pcgsTypeName}")
                            Text("PCGS number: ${coin.coinNumber}")
                        } else Text(
                            "Coin number: ${coin.coinNumber}",
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = onRetryNgcCoinNumber
                            )
                        )
                    }
                    Text(
                        "Service: ${coin.service}",
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { showServiceOverride = !showServiceOverride }
                        )
                    )
                    if (showServiceOverride) {
                        OutlinedButton(
                            onClick = {
                                showServiceOverride = false
                                onSwitchService()
                                showDetails = false
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 1.dp)
                        ) {
                            Text(
                                if (coin.service == "NGC") "Use PCGS identification" else "Use NGC identification",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    Text("Serial number: ${coin.certNumber}")
                    if (coin.grade.isNotBlank()) Text("Grade: ${coin.grade}")
                    if (coin.description.isNotBlank()) Text("Description: ${coin.description}")
                    if (coin.year.isNotBlank()) Text("Year: ${coin.year}")
                    if (coin.country.isNotBlank()) Text("Country: ${coin.country}")
                    if (coin.denomination.isNotBlank()) Text("Denomination: ${coin.denomination}")
                    if (coin.variety.isNotBlank()) Text("Variety: ${coin.variety}")
                    if (coin.siteVerified) Text("Official site: verified", style = MaterialTheme.typography.labelSmall)
                    Text(coin.url, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onCopy()
                    showDetails = false
                }) {
                    Text("Copy URL")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { showDetails = false }) {
                        Text("Close")
                    }
                    OutlinedButton(onClick = {
                        showDetails = false
                        onDelete()
                    }) {
                        Text("Delete")
                    }
                }
            }
        )
    }


    if (showGradingErrorDialog) {
        AlertDialog(
            onDismissRequest = { showGradingErrorDialog = false },
            title = { Text(if (coin.gradingError) "Grading error" else "Mark grading error") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Optional note about the grading error")
                    OutlinedTextField(
                        value = gradingErrorNoteDraft,
                        onValueChange = { gradingErrorNoteDraft = it },
                        placeholder = { Text("e.g. Wrong variety / should be MS64") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { onSetGradingError(true, gradingErrorNoteDraft); showGradingErrorDialog = false }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (coin.gradingError) TextButton(onClick = { onSetGradingError(false, ""); showGradingErrorDialog = false }) { Text("Remove mark") }
                    TextButton(onClick = { showGradingErrorDialog = false }) { Text("Cancel") }
                }
            }
        )
    }

    photoMenuPath?.let { path ->
        AlertDialog(
            onDismissRequest = { photoMenuPath = null },
            title = { Text("Photo options") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (photoMenuIsManual) "Manual photo" else "Downloaded site photo")
                    OutlinedButton(onClick = {
                        photoMenuPath = null
                        onRedownloadSitePhotos()
                    }) { Text("Re-download site photos") }
                    if (photoMenuIsManual) {
                        OutlinedButton(onClick = {
                            onRemoveManualPhoto(path)
                            photoMenuPath = null
                        }) { Text("Delete photo") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { photoMenuPath = null; zoomImagePath = path }) { Text("View / zoom") }
            },
            dismissButton = { TextButton(onClick = { photoMenuPath = null }) { Text("Close") } }
        )
    }

    zoomImagePath?.let { path ->
        ZoomableNgcImage(
            imagePath = path,
            certNumber = coin.certNumber,
            onDismiss = { zoomImagePath = null }
        )
    }
}

@Composable
private fun TrashDialog(
    trash: List<Coin>,
    onDismiss: () -> Unit,
    onRestore: (Coin) -> Unit,
    onDeleteForever: (Coin) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trash — kept for 7 days") },
        text = {
            if (trash.isEmpty()) {
                Text("Trash is empty.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    trash.take(12).forEach { coin ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val preview = coin.siteImagePaths.firstOrNull { File(it).exists() } ?: coin.manualImagePaths.firstOrNull { File(it).exists() }
                                ?: coin.imagePath.takeIf { it.isNotBlank() && File(it).exists() }
                            if (preview != null) {
                                AsyncImage(
                                    model = File(preview),
                                    contentDescription = "Deleted coin ${coin.certNumber}",
                                    modifier = Modifier.size(48.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                                Text(coin.certNumber, style = MaterialTheme.typography.labelMedium)
                                Text("Coin ${coin.coinNumber}", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { onRestore(coin) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 1.dp)
                            ) { Text("Restore", style = MaterialTheme.typography.labelSmall) }
                            Spacer(modifier = Modifier.width(3.dp))
                            OutlinedButton(
                                onClick = { onDeleteForever(coin) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 1.dp)
                            ) { Text("Delete", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    if (trash.size > 12) {
                        Text("Showing first 12 of ${trash.size} deleted coins.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ZoomableNgcImage(
    imagePath: String,
    certNumber: String,
    onDismiss: () -> Unit
) {
    var scale by remember(imagePath) { mutableStateOf(1f) }
    var offset by remember(imagePath) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 8f)
        scale = newScale
        offset = if (newScale <= 1f) Offset.Zero else offset + panChange
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.scrim
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(transformState),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = File(imagePath),
                    contentDescription = "Zoomed coin photo $certNumber",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit
                )

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    Text("Close")
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(context, "No browser is available to open this URL.", Toast.LENGTH_LONG).show()
    }
}

private fun copyUrl(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Coin URL", url))
    Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
}
