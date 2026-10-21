package com.berserk.reader.ui.screens

import android.app.Activity
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.berserk.reader.data.MangaRepository
import com.berserk.reader.data.PreferencesManager
import com.berserk.reader.model.ReadingProgress
import com.berserk.reader.ui.components.ZoomableBox
import com.berserk.reader.ui.theme.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ReaderScreen(
    chapterSlug: String,
    initialPage: Int,
    repository: MangaRepository,
    prefs: PreferencesManager,
    onBackClick: () -> Unit,
    onNavigateChapter: (String, Int) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val window = (context as? Activity)?.window

    val chapter = remember(chapterSlug) { repository.getChapter(chapterSlug) }
    val folderPath by prefs.folderPathFlow.collectAsState()

    if (chapter == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(BerserkBlack),
            contentAlignment = Alignment.Center
        ) {
            Text("Capitolo non trovato", color = TextPrimary)
        }
        return
    }

    // Safe volume number resolution from repository (avoids any null issues)
    val volumeNumber = remember(chapter.slug) {
        repository.getVolumeForChapter(chapter.slug)?.volume_number
            ?: chapter.cdn_vol.toIntOrNull()
            ?: 1
    }

    val pageCount = remember(chapter.slug, folderPath) {
        repository.getChapterPageCount(chapter, folderPath)
    }

    // Pre-resolve chapter folder once on background/remember to prevent main thread I/O
    val chapterFolder = remember(chapter.slug, folderPath) {
        val currentPath = folderPath
        if (!currentPath.isNullOrBlank()) {
            chapter.resolveChapterFolder(File(currentPath))
        } else null
    }

    // Saveable state across screen orientation rotation
    var isUiVisible by rememberSaveable { mutableStateOf(false) }
    var readerMode by rememberSaveable { mutableStateOf(prefs.getReaderMode()) }
    var currentPage by rememberSaveable(chapter.slug, initialPage) {
        mutableIntStateOf(initialPage.coerceIn(1, pageCount.coerceAtLeast(1)))
    }

    // Restore System Bars cleanly when leaving the reader screen
    DisposableEffect(Unit) {
        onDispose {
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Manage full-screen immersive mode when UI visibility toggles
    LaunchedEffect(isUiVisible) {
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            if (isUiVisible) {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    val prevChapter = remember(chapter.slug) { repository.getPrevChapter(chapter.slug) }
    val nextChapter = remember(chapter.slug) { repository.getNextChapter(chapter.slug) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        key(chapter.slug) {
            if (readerMode == "VERTICAL") {
            // LazyColumn with initial scroll to current page
            val startIdx = (currentPage - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIdx)

            // Debounced snapshot flow to prevent spurious overwriting at frame 0
            LaunchedEffect(listState) {
                snapshotFlow {
                    if (listState.layoutInfo.totalItemsCount > 0) {
                        listState.firstVisibleItemIndex + 1
                    } else null
                }
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(200)
                .collect { visiblePage ->
                    currentPage = visiblePage
                    prefs.saveProgress(
                        ReadingProgress(
                            volumeNumber = volumeNumber,
                            chapterSlug = chapter.slug,
                            chapterTitle = chapter.title,
                            pageNumber = visiblePage,
                            totalPages = pageCount
                        )
                    )
                    if (visiblePage >= pageCount - 1) {
                        prefs.markChapterRead(chapter.slug)
                    }
                }
            }

            // Wrap LazyColumn in ZoomableBox so user can pinch-to-zoom even in vertical mode!
            ZoomableBox(
                resetKey = chapter.slug,
                onTap = { isUiVisible = !isUiVisible },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(pageCount, key = { it }) { index ->
                        val pageNum = index + 1
                        val pageSource = remember(pageNum, chapter.slug, chapterFolder) {
                            repository.resolvePageSource(chapter, pageNum, chapterFolder)
                        }

                        // heightIn ensures LazyColumn doesn't collapse pages to 0px before load
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 480.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(pageSource)
                                    .crossfade(true)
                                    .addHeader("User-Agent", "Mozilla/5.0")
                                    .addHeader("Referer", "https://onepiecepower.com/manga8/berserk/lista-capitoli")
                                    .build(),
                                contentDescription = "Pagina $pageNum",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Chapter End Footer
                    item {
                        ChapterEndFooter(
                            nextChapter = nextChapter,
                            onNextClick = {
                                if (nextChapter != null) onNavigateChapter(nextChapter.slug, 1)
                            }
                        )
                    }
                }
            }
        } else {
            // Horizontal Paged Mode
            val startPage = (currentPage - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val pagerState = rememberPagerState(
                initialPage = startPage,
                pageCount = { pageCount }
            )

            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage + 1 }
                    .distinctUntilChanged()
                    .debounce(150)
                    .collect { page ->
                        currentPage = page
                        prefs.saveProgress(
                            ReadingProgress(
                                volumeNumber = volumeNumber,
                                chapterSlug = chapter.slug,
                                chapterTitle = chapter.title,
                                pageNumber = page,
                                totalPages = pageCount
                            )
                        )
                        if (page >= pageCount) {
                            prefs.markChapterRead(chapter.slug)
                        }
                    }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { index ->
                val pageNum = index + 1
                val pageSource = remember(pageNum, chapter.slug, chapterFolder) {
                    repository.resolvePageSource(chapter, pageNum, chapterFolder)
                }

                ZoomableBox(
                    resetKey = pagerState.currentPage,
                    onTap = { isUiVisible = !isUiVisible },
                    modifier = Modifier.fillMaxSize()
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(pageSource)
                            .crossfade(true)
                            .addHeader("User-Agent", "Mozilla/5.0")
                            .addHeader("Referer", "https://onepiecepower.com/manga8/berserk/lista-capitoli")
                            .build(),
                        contentDescription = "Pagina $pageNum",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        }

        // Top Bar Overlay
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = BerserkDarkSurface.copy(alpha = 0.95f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Indietro",
                            tint = TextPrimary
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = chapter.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Vol. $volumeNumber • Pagina $currentPage di $pageCount",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }

                    IconButton(
                        onClick = {
                            val newMode = if (readerMode == "VERTICAL") "HORIZONTAL" else "VERTICAL"
                            readerMode = newMode
                            prefs.setReaderMode(newMode)
                        }
                    ) {
                        Icon(
                            imageVector = if (readerMode == "VERTICAL") Icons.Default.ViewDay else Icons.Default.ViewCarousel,
                            contentDescription = "Cambia Modalità",
                            tint = BerserkRed
                        )
                    }
                }
            }
        }

        // Bottom Bar Overlay
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = BerserkDarkSurface.copy(alpha = 0.95f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (prevChapter != null) onNavigateChapter(prevChapter.slug, 1)
                            },
                            enabled = prevChapter != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BerserkCardBg,
                                disabledContainerColor = BerserkDarkSurface
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Precedente", fontSize = 12.sp)
                        }

                        Text(
                            text = if (readerMode == "VERTICAL") "Scroll Verticale" else "Pagine Orizzontali",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )

                        Button(
                            onClick = {
                                if (nextChapter != null) onNavigateChapter(nextChapter.slug, 1)
                            },
                            enabled = nextChapter != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BerserkRed,
                                disabledContainerColor = BerserkDarkSurface
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Successivo", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChapterEndFooter(
    nextChapter: com.berserk.reader.model.ChapterData?,
    onNextClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Fine del Capitolo",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (nextChapter != null) {
            Button(
                onClick = onNextClick,
                colors = ButtonDefaults.buttonColors(containerColor = BerserkRed),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Passa al Capitolo Successivo")
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            Text(
                text = "Hai raggiunto l'ultimo capitolo disponibile!",
                fontSize = 13.sp,
                color = TextMuted
            )
        }
    }
}
