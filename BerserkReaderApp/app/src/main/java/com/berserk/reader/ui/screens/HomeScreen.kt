package com.berserk.reader.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.berserk.reader.data.MangaRepository
import com.berserk.reader.data.PreferencesManager
import com.berserk.reader.model.VolumeData
import com.berserk.reader.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: MangaRepository,
    prefs: PreferencesManager,
    onVolumeClick: (Int) -> Unit,
    onResumeClick: (String, Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val volumes = remember { repository.getVolumes() }

    // Collect reactive flows from PreferencesManager
    val progress by prefs.progressFlow.collectAsState()
    val folderPath by prefs.folderPathFlow.collectAsState()
    val readChapters by prefs.readChaptersFlow.collectAsState()

    var showFolderDialog by rememberSaveable { mutableStateOf(false) }
    var inputPath by rememberSaveable { mutableStateOf(folderPath ?: "/sdcard/Download/Berserk_Manga") }

    // Reactive check for MANAGE_EXTERNAL_STORAGE that refreshes on ON_RESUME
    var hasAllFilesAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else true
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Storage Access Framework Folder Picker
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val resolvedPath = resolveTreeUriToPath(uri) ?: uri.toString()
            inputPath = resolvedPath
            prefs.setFolderPath(resolvedPath)
            showFolderDialog = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "BERSERK",
                            fontWeight = FontWeight.Black,
                            fontSize = 24.sp,
                            color = BerserkRed,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "READER",
                            fontWeight = FontWeight.Light,
                            fontSize = 20.sp,
                            color = TextPrimary,
                            letterSpacing = 1.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showFolderDialog = true }) {
                        val iconColor = when {
                            folderPath != null && hasAllFilesAccess -> Color(0xFF4CAF50)
                            folderPath != null && !hasAllFilesAccess -> Color(0xFFE50914)
                            else -> BerserkRed
                        }
                        Icon(
                            imageVector = if (folderPath != null) Icons.Default.FolderOpen else Icons.Default.Cloud,
                            contentDescription = "Cartella offline",
                            tint = iconColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BerserkBlack)
            )
        },
        containerColor = BerserkBlack
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Continua a Leggere Banner (reactive)
            item(span = { GridItemSpan(maxLineSpan) }) {
                ContinueReadingCard(
                    progress = progress,
                    onClick = {
                        onResumeClick(progress.chapterSlug, progress.pageNumber)
                    }
                )
            }

            // Storage mode banner
            item(span = { GridItemSpan(maxLineSpan) }) {
                StorageStatusBanner(
                    folderPath = folderPath,
                    hasAllFilesAccess = hasAllFilesAccess,
                    onConfigureClick = { showFolderDialog = true }
                )
            }

            // Section Header
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TUTTI I VOLUMI (${volumes.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextPrimary
                    )
                }
            }

            // Volume Items
            items(volumes, key = { it.volume_number }) { volume ->
                VolumeCard(
                    volume = volume,
                    repository = repository,
                    folderPath = folderPath,
                    readChapters = readChapters,
                    onClick = { onVolumeClick(volume.volume_number) }
                )
            }
        }
    }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = {
                Text(
                    text = "Percorso Manga Offline",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Scegli la cartella 'Berserk_Manga' sul dispositivo per leggere 100% offline senza connessione internet.",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Button to launch native SAF folder picker
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = BerserkCardBg),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, BerserkRed, RoundedCornerShape(8.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = BerserkRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scegli Cartella dal Dispositivo", color = TextPrimary, fontSize = 13.sp)
                    }

                    if (!hasAllFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1E1E)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, BerserkRed, RoundedCornerShape(8.dp))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = BerserkRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Permesso Accesso File Richiesto",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Su Android 11+ è necessario abilitare 'Accesso a tutti i file' affinché l'app possa leggere le immagini dalla cartella Download.",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                            context.startActivity(fallback)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = BerserkRed),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Apri Impostazioni Permesso", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = inputPath,
                        onValueChange = { inputPath = it },
                        label = { Text("Percorso manuale") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BerserkRed,
                            unfocusedBorderColor = BerserkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val path = inputPath.trim()
                        if (path.isEmpty()) {
                            prefs.setFolderPath(null)
                        } else {
                            prefs.setFolderPath(path)
                        }
                        showFolderDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BerserkRed)
                ) {
                    Text("Salva")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) {
                    Text("Annulla", color = TextSecondary)
                }
            },
            containerColor = BerserkDarkSurface
        )
    }
}

/**
 * Resolves a SAF Tree Uri (e.g. content://.../tree/primary%3ADownload%2FBerserk_Manga)
 * into a direct file path (/storage/emulated/0/Download/Berserk_Manga) when available.
 */
private fun resolveTreeUriToPath(uri: Uri): String? {
    try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        if (docId.startsWith("primary:")) {
            val relativePath = docId.substringAfter("primary:")
            return "/storage/emulated/0/$relativePath"
        } else if (docId.contains(":")) {
            val parts = docId.split(":")
            if (parts.size == 2) {
                return "/storage/${parts[0]}/${parts[1]}"
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return uri.path
}

@Composable
fun ContinueReadingCard(
    progress: com.berserk.reader.model.ReadingProgress,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BerserkCardBg),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, BerserkRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BerserkRed),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CONTINUA LA LETTURA",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = BerserkRed,
                    letterSpacing = 1.sp
                )
                Text(
                    text = progress.chapterTitle,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Volume ${progress.volumeNumber} • Pagina ${progress.pageNumber} di ${progress.totalPages}",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextSecondary
            )
        }
    }
}

@Composable
fun StorageStatusBanner(
    folderPath: String?,
    hasAllFilesAccess: Boolean,
    onConfigureClick: () -> Unit
) {
    val isMissingPermission = folderPath != null && !hasAllFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val bgColor = when {
        isMissingPermission -> Color(0xFF3B1E1E)
        folderPath != null -> Color(0xFF1B2E1E)
        else -> Color(0xFF261C1C)
    }

    val iconTint = when {
        isMissingPermission -> Color(0xFFE50914)
        folderPath != null -> Color(0xFF4CAF50)
        else -> Color(0xFFFFA000)
    }

    val iconVector = when {
        isMissingPermission -> Icons.Default.Warning
        folderPath != null -> Icons.Default.CheckCircle
        else -> Icons.Default.CloudQueue
    }

    val bannerText = when {
        isMissingPermission -> "Attenzione: Permesso accesso file mancante! Tocca per autorizzare"
        folderPath != null -> "Archivio locale attivo: $folderPath"
        else -> "Modalità Online attiva: tocca per selezionare cartella locale"
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConfigureClick)
            .border(
                1.dp,
                if (isMissingPermission) BerserkRed else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = bannerText,
                fontSize = 12.sp,
                fontWeight = if (isMissingPermission) FontWeight.Bold else FontWeight.Normal,
                color = if (isMissingPermission) Color(0xFFFFCDD2) else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun VolumeCard(
    volume: VolumeData,
    repository: MangaRepository,
    folderPath: String?,
    readChapters: Set<String>,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val readCount = remember(volume.chapters, readChapters) {
        volume.chapters.count { readChapters.contains(it.slug) }
    }
    val firstChapter = volume.chapters.firstOrNull()
    val coverSource = remember(volume.volume_number, folderPath) {
        if (firstChapter != null) {
            repository.resolvePageSource(firstChapter, 1, folderPath)
        } else ""
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = BerserkCardBg),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, BerserkCardBorder, RoundedCornerShape(10.dp))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(coverSource)
                        .crossfade(true)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .addHeader("Referer", "https://onepiecepower.com/manga8/berserk/lista-capitoli")
                        .build(),
                    contentDescription = volume.volume_title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Top badge with Volume number
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(BerserkRed)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .align(Alignment.TopStart)
                ) {
                    Text(
                        text = "VOL. ${volume.cdn_vol}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, BerserkCardBg)
                            )
                        )
                )
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = volume.volume_title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${volume.chapters_count} capitoli",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    if (readCount > 0) {
                        Text(
                            text = "$readCount/${volume.chapters_count}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = BerserkRed
                        )
                    }
                }
            }
        }
    }
}
