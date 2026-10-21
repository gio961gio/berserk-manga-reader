package com.berserk.reader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.berserk.reader.data.MangaRepository
import com.berserk.reader.data.PreferencesManager
import com.berserk.reader.model.ChapterData
import com.berserk.reader.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeDetailScreen(
    volumeNumber: Int,
    repository: MangaRepository,
    prefs: PreferencesManager,
    onBackClick: () -> Unit,
    onChapterClick: (String) -> Unit
) {
    val context = LocalContext.current
    val volume = remember(volumeNumber) { repository.getVolume(volumeNumber) }
    val folderPath by prefs.folderPathFlow.collectAsState()
    val readChapters by prefs.readChaptersFlow.collectAsState()

    if (volume == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(BerserkBlack),
            contentAlignment = Alignment.Center
        ) {
            Text("Volume non trovato", color = TextPrimary)
        }
        return
    }

    val firstChapter = volume.chapters.firstOrNull()
    val coverSource = remember(volume.volume_number, folderPath) {
        if (firstChapter != null) {
            repository.resolvePageSource(firstChapter, 1, folderPath)
        } else ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = volume.volume_title,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Indietro",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BerserkBlack)
            )
        },
        containerColor = BerserkBlack
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Volume Header Card
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = BerserkCardBg),
                    modifier = Modifier.fillMaxWidth().border(1.dp, BerserkCardBorder, RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(90.dp)
                                .aspectRatio(0.7f)
                                .clip(RoundedCornerShape(8.dp))
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
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "BERSERK",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = BerserkRed
                            )
                            Text(
                                text = volume.volume_title,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${volume.chapters.size} Capitoli inclusi",
                                fontSize = 14.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            if (firstChapter != null) {
                                Button(
                                    onClick = { onChapterClick(firstChapter.slug) },
                                    colors = ButtonDefaults.buttonColors(containerColor = BerserkRed),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Inizia a Leggere", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Section Header
            item {
                Text(
                    text = "CAPITOLI",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = BerserkRed,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
            }

            // Chapter items
            itemsIndexed(volume.chapters, key = { _, ch -> ch.slug }) { index, chapter ->
                val isRead = readChapters.contains(chapter.slug)
                ChapterItemCard(
                    chapter = chapter,
                    index = index + 1,
                    isRead = isRead,
                    onClick = { onChapterClick(chapter.slug) }
                )
            }
        }
    }
}

@Composable
fun ChapterItemCard(
    chapter: ChapterData,
    index: Int,
    isRead: Boolean,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = BerserkCardBg),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, if (isRead) BerserkCardBorder else BerserkRed.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isRead) Color(0xFF2E7D32) else BerserkDarkSurface),
                contentAlignment = Alignment.Center
            ) {
                if (isRead) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Letto",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(
                        text = "$index",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Leggi",
                tint = BerserkRed,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
