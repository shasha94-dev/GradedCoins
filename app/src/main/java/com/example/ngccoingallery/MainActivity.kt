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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(newCoinId, coins) {
        val id = newCoinId ?: return@LaunchedEffect
        val index = coins.indexOfFirst { it.id == id }
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

        if (!processing && coins.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Choose or take a clear photo of an NGC or PCGS barcode.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(coins, key = { it.id }) { coin ->
                    CoinCard(
                        isNew = coin.id == newCoinId,
                        coin = coin,
                        showBarcodeSide = showBarcodeSide,
                        ngcImagesDownloading = coin.id in downloadingNgcImages,
                        onOpen = {
                            // First normal tap starts the one-time local download. The NGC
                            // website still opens immediately, as before.
                            viewModel.ensureSiteImages(coin)
                            openUrl(context, coin.url)
                        },
                        onLongPress = { viewModel.ensureSiteImages(coin) },
                        onCopy = { copyUrl(context, coin.url) },
                        onSetMine = { mine -> viewModel.setMine(coin, mine) },
                        onAddManualPhotos = {
                            manualPhotoCoinId = coin.id
                            pickManualPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onRemoveManualPhoto = { path -> viewModel.removeManualPhoto(coin, path) },
                        onSwitchService = { viewModel.switchService(coin) },
                        onDelete = { viewModel.deleteCoin(coin) }
                    )
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

    if (showTrash) {
        TrashDialog(
            trash = trash,
            onDismiss = { showTrash = false },
            onRestore = { viewModel.restoreCoin(it) },
            onDeleteForever = { viewModel.permanentlyDelete(it) }
        )
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
    onAddManualPhotos: () -> Unit,
    onRemoveManualPhoto: (String) -> Unit,
    onSwitchService: () -> Unit,
    onDelete: () -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }
    var zoomImagePath by remember { mutableStateOf<String?>(null) }
    var showServiceOverride by remember { mutableStateOf(false) }

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
                                AsyncImage(model = File(path), contentDescription = "Site photo ${coin.certNumber}", modifier = Modifier.width(180.dp).height(180.dp).clickable { zoomImagePath = path }, contentScale = ContentScale.Fit)
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
                                    AsyncImage(model = File(path), contentDescription = "Manual photo", modifier = Modifier.width(120.dp).height(110.dp).clickable { zoomImagePath = path }, contentScale = ContentScale.Fit)
                                    OutlinedButton(onClick = { onRemoveManualPhoto(path) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("Remove", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                    Text("Downloaded site photos cannot be removed manually.", style = MaterialTheme.typography.labelSmall)

                    OutlinedButton(onClick = { onSetMine(!coin.isMine) }) {
                        Text(if (coin.isMine) "Remove from Mine" else "Mark as Mine")
                    }

                    if (coin.coinNumber.isNotBlank()) {
                        Text("Coin number: ${coin.coinNumber}")
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
