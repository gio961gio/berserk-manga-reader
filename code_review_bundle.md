# Berserk Reader - Final Source Code Bundle (Supervisor 100% Review)

This document contains the final source code addressing all supervisor code review feedback items.

## File: download_berserk.py

`python
#!/usr/bin/env python3
"""
Berserk Manga Downloader
Downloads all chapters and volumes from onepiecepower.com
Organizes files cleanly:
Berserk_Manga/
  Volume 01/
    Capitolo 000-01 - Il guerriero nero/
      01.jpg
      02.jpg
      ...
"""

import os
import sys
import re
import json
import time
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

# Default headers matching a standard desktop browser
HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8',
    'Accept-Language': 'it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7',
    'Referer': 'https://onepiecepower.com/manga8/berserk/lista-capitoli'
}

def create_session():
    session = requests.Session()
    retries = Retry(
        total=5,
        backoff_factor=1,
        status_forcelist=[429, 500, 502, 503, 504],
        allowed_methods=["HEAD", "GET", "OPTIONS"]
    )
    adapter = HTTPAdapter(max_retries=retries, pool_connections=20, pool_maxsize=20)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    session.headers.update(HEADERS)
    return session

def sanitize_filename(name):
    clean = re.sub(r'[\\/*?:"<>|]', "", name)
    clean = re.sub(r'\s+', ' ', clean).strip('. ')
    return clean

def get_formatted_chapter(slug):
    raw_cap = slug.split("reader/")[1].strip()
    if "-" in raw_cap:
        formatted_cap = re.sub(r'^0', '', raw_cap)
    else:
        try:
            val = float(raw_cap)
            formatted_cap = raw_cap[1:] if val < 100 else raw_cap
        except ValueError:
            formatted_cap = raw_cap
    return raw_cap, formatted_cap

def download_page(session, url, target_path):
    if os.path.exists(target_path) and os.path.getsize(target_path) > 1000:
        return True, 200, os.path.getsize(target_path)

    try:
        resp = session.get(url, timeout=15)
        if resp.status_code == 200:
            if len(resp.content) > 500:
                with open(target_path, 'wb') as f:
                    f.write(resp.content)
                return True, 200, len(resp.content)
        return False, resp.status_code, 0
    except Exception:
        return False, -1, 0

def download_chapter(session, vol_str, chapter_info, chapter_dir, max_workers=4):
    raw_cap, formatted_cap = get_formatted_chapter(chapter_info['href'])
    marker_path = os.path.join(chapter_dir, ".completed")

    if os.path.exists(marker_path):
        existing_pages = [f for f in os.listdir(chapter_dir) if f.lower().endswith(('.jpg', '.jpeg', '.png', '.webp'))]
        return len(existing_pages), True

    os.makedirs(chapter_dir, exist_ok=True)

    page_start = 1
    clean_eof = False
    workers = max(1, max_workers)

    with ThreadPoolExecutor(max_workers=workers) as executor:
        while True:
            batch_pages = list(range(page_start, page_start + workers))
            future_to_page = {}
            for p in batch_pages:
                page_str = f"{p:02d}"
                target_file = os.path.join(chapter_dir, f"{page_str}.jpg")
                url = f"https://onepiecepower.com/manga8/berserk/volume{vol_str}/capitolo{formatted_cap}/{page_str}.jpg"
                future = executor.submit(download_page, session, url, target_file)
                future_to_page[future] = p

            batch_results = {}
            for future in as_completed(future_to_page):
                p = future_to_page[future]
                try:
                    success, status, size = future.result()
                    batch_results[p] = (success, status, size)
                except Exception:
                    batch_results[p] = (False, -1, 0)

            hit_404 = False
            error_on_page = False

            for p in sorted(batch_pages):
                success, status, size = batch_results.get(p, (False, -1, 0))
                if success:
                    continue
                elif status == 404:
                    hit_404 = True
                    # Clean up any phantom files from higher numbers in this batch
                    for phantom_p in range(p + 1, page_start + workers):
                        phantom_file = os.path.join(chapter_dir, f"{phantom_p:02d}.jpg")
                        if os.path.exists(phantom_file):
                            try: os.remove(phantom_file)
                            except Exception: pass
                    break
                else:
                    # Retry this single page with backoff
                    retry_success = False
                    for retry in range(1, 4):
                        time.sleep(0.5 * retry)
                        page_str = f"{p:02d}"
                        target_file = os.path.join(chapter_dir, f"{page_str}.jpg")
                        url = f"https://onepiecepower.com/manga8/berserk/volume{vol_str}/capitolo{formatted_cap}/{page_str}.jpg"
                        s_ok, s_stat, _ = download_page(session, url, target_file)
                        if s_ok:
                            retry_success = True
                            break
                        elif s_stat == 404:
                            hit_404 = True
                            break

                    if not retry_success and not hit_404:
                        error_on_page = True
                        break

            if hit_404:
                clean_eof = True
                break

            if error_on_page:
                clean_eof = False
                break

            page_start += workers

    existing_pages = [f for f in os.listdir(chapter_dir) if f.lower().endswith(('.jpg', '.jpeg', '.png', '.webp'))]
    pages_count = len(existing_pages)

    if clean_eof and pages_count > 0:
        with open(marker_path, 'w', encoding='utf-8') as f:
            f.write(f"pages={pages_count}\n")

    return pages_count, False

def load_index(index_path):
    with open(index_path, 'r', encoding='utf-8') as f:
        return json.load(f)

def run_download(index_path, output_dir, volume_filter=None, max_workers=4):
    volumes_data = load_index(index_path)
    os.makedirs(output_dir, exist_ok=True)
    session = create_session()

    print("==================================================")
    print("  BERSERK MANGA DOWNLOADER (Concurrent Batches)")
    print(f"  Output folder: {output_dir}")
    print(f"  Parallel workers: {max_workers}")
    print("==================================================")

    selected_volumes = []
    for vol in volumes_data:
        vol_name = vol['volume']
        vol_num_match = re.search(r'\d+', vol_name)
        vol_num = int(vol_num_match.group(0)) if vol_num_match else 0

        if volume_filter is not None:
            if isinstance(volume_filter, list) and vol_num not in volume_filter:
                continue

        selected_volumes.append(vol)

    total_vols = len(selected_volumes)
    print(f"Selected {total_vols} volumes to process.\n")

    catalog_path = os.path.join(output_dir, "berserk_catalog.json")
    existing_catalog_dict = {}
    if os.path.exists(catalog_path):
        try:
            with open(catalog_path, 'r', encoding='utf-8') as f:
                prev_list = json.load(f)
                for item in prev_list:
                    if 'volume_number' in item:
                        existing_catalog_dict[item['volume_number']] = item
        except Exception as e:
            print(f"Warning: could not read existing catalog ({e}), will re-create.")

    for v_idx, vol in enumerate(selected_volumes, 1):
        vol_name = vol['volume']
        vol_num_str = re.search(r'\d+', vol_name).group(0)
        vol_num = int(vol_num_str)
        clean_vol_name = sanitize_filename(vol_name)
        vol_dir = os.path.join(output_dir, clean_vol_name)
        os.makedirs(vol_dir, exist_ok=True)

        chapters = vol['chapters']
        valid_chapters = [c for c in chapters if "disponibile ??" not in c.get('title', '').lower()]
        print(f"[{v_idx}/{total_vols}] {clean_vol_name} ({len(valid_chapters)} chapters)...")

        vol_catalog = {
            'volume_number': vol_num,
            'volume_title': clean_vol_name,
            'cdn_vol': f"{vol_num:02d}",
            'chapters_count': len(valid_chapters),
            'chapters': []
        }

        for c_idx, ch in enumerate(valid_chapters, 1):
            ch_title = sanitize_filename(ch['title'])
            raw_cap, formatted_cap = get_formatted_chapter(ch['href'])
            ch_dir = os.path.join(vol_dir, ch_title)

            pages_count, already_done = download_chapter(session, vol_catalog['cdn_vol'], ch, ch_dir, max_workers)
            status_str = "cached" if already_done else f"{pages_count} pages downloaded"
            print(f"    [{c_idx}/{len(valid_chapters)}] {ch_title} -> {status_str}")

            vol_catalog['chapters'].append({
                'title': ch['title'],
                'folder_name': ch_title,
                'slug': ch['href'],
                'raw_cap': raw_cap,
                'cdn_cap': formatted_cap,
                'cdn_vol': vol_catalog['cdn_vol'],
                'pages_count': pages_count
            })

            if not already_done:
                time.sleep(0.1)

        existing_catalog_dict[vol_num] = vol_catalog

    final_catalog = [existing_catalog_dict[k] for k in sorted(existing_catalog_dict.keys())]
    with open(catalog_path, 'w', encoding='utf-8') as f:
        json.dump(final_catalog, f, ensure_ascii=False, indent=2)

    app_assets_catalog = os.path.join(os.path.dirname(__file__), "BerserkReaderApp", "app", "src", "main", "assets", "berserk_catalog.json")
    if os.path.exists(os.path.dirname(app_assets_catalog)):
        try:
            with open(app_assets_catalog, 'w', encoding='utf-8') as f:
                json.dump(final_catalog, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    print(f"\n[DONE] All requested volumes processed! Catalog saved to: {catalog_path}")

def parse_range(range_str):
    if '-' in range_str:
        start, end = map(int, range_str.split('-'))
        return list(range(start, end + 1))
    elif ',' in range_str:
        return [int(x.strip()) for x in range_str.split(',')]
    else:
        return [int(range_str)]

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Download Berserk manga chapters organized by volume")
    parser.add_argument('--index', default=os.path.join(os.path.dirname(__file__), 'manga_index.json'), help="Path to manga_index.json")
    parser.add_argument('--output', default=os.path.join(os.path.dirname(__file__), 'Berserk_Manga'), help="Output directory")
    parser.add_argument('--volume', default=None, help="Volume number or range (e.g. '1', '1-5', '1,2,3')")
    parser.add_argument('--all', action='store_true', help="Download all 44 volumes")
    parser.add_argument('--workers', type=int, default=4, help="Number of concurrent download threads")

    args = parser.parse_args()

    vol_filter = None
    if args.volume:
        vol_filter = parse_range(args.volume)
    elif not args.all:
        print("Note: No volume specified and --all not set. Defaulting to Volume 1 for test.")
        vol_filter = [1]

    run_download(args.index, args.output, volume_filter=vol_filter, max_workers=args.workers)
`

---

## File: MangaModels.kt

`kotlin
package com.berserk.reader.model

import java.io.File

data class VolumeData(
    val volume_number: Int = 1,
    val volume_title: String = "Volume 01",
    val cdn_vol: String = "01",
    val chapters_count: Int = 0,
    val chapters: List<ChapterData> = emptyList()
) {
    fun getCoverUrl(): String {
        return chapters.firstOrNull()?.getPageUrl(1) ?: ""
    }
}

data class ChapterData(
    val title: String = "",
    val folder_name: String = "",
    val slug: String = "",
    val raw_cap: String = "",
    val cdn_cap: String = "",
    val cdn_vol: String = "",
    val pages_count: Int = 0
) {
    fun getPageUrl(page: Int): String {
        val pageStr = String.format("%02d", page)
        val vol = if (cdn_vol.isNotBlank()) cdn_vol else "01"
        val cap = if (cdn_cap.isNotBlank()) cdn_cap else slug.substringAfter("reader/").removePrefix("0")
        return "https://onepiecepower.com/manga8/berserk/volume$vol/capitolo$cap/$pageStr.jpg"
    }

    /**
     * Resolves the physical folder on disk for this chapter.
     * Called ONCE per chapter load and remembered in Compose state.
     */
    fun resolveChapterFolder(baseFolder: File): File? {
        if (!baseFolder.exists() || !baseFolder.isDirectory) return null

        val volNumber = cdn_vol.toIntOrNull() ?: 1
        val volFolderVariants = listOf(
            File(baseFolder, "Volume ${String.format("%02d", volNumber)}"),
            File(baseFolder, "Volume $volNumber"),
            File(baseFolder, "Volume $cdn_vol")
        )
        val volFolder = volFolderVariants.firstOrNull { it.exists() && it.isDirectory } ?: return null

        // Try exact folder_name first (fastest)
        if (folder_name.isNotBlank()) {
            val direct = File(volFolder, folder_name)
            if (direct.exists() && direct.isDirectory) return direct
        }

        // Try exact title
        if (title.isNotBlank()) {
            val directTitle = File(volFolder, title)
            if (directTitle.exists() && directTitle.isDirectory) return directTitle
        }

        // Fallback: search directory once
        val subDirs = volFolder.listFiles { f -> f.isDirectory } ?: return null
        return subDirs.firstOrNull { dir ->
            (raw_cap.isNotBlank() && dir.name.contains(raw_cap)) ||
            (title.isNotBlank() && dir.name.contains(title))
        }
    }

    /**
     * Directly builds the page file path without directory scans.
     */
    fun getLocalPageFile(chapterFolder: File, page: Int): File? {
        val pageStr = String.format("%02d", page)
        val file = File(chapterFolder, "$pageStr.jpg")
        return if (file.exists()) file else null
    }
}

data class ReadingProgress(
    val volumeNumber: Int = 1,
    val chapterSlug: String = "reader/000-01",
    val chapterTitle: String = "Capitolo 000-01 - Il guerriero nero",
    val pageNumber: Int = 1,
    val totalPages: Int = 97
)
`

---

## File: MangaRepository.kt

`kotlin
package com.berserk.reader.data

import android.content.Context
import com.berserk.reader.model.ChapterData
import com.berserk.reader.model.VolumeData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.InputStreamReader

class MangaRepository(private val context: Context) {
    private var cachedVolumes: List<VolumeData> = emptyList()

    init {
        loadCatalog()
    }

    private fun loadCatalog() {
        try {
            context.assets.open("berserk_catalog.json").use { stream ->
                val reader = InputStreamReader(stream)
                val type = object : TypeToken<List<VolumeData>>() {}.type
                cachedVolumes = Gson().fromJson(reader, type) ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            cachedVolumes = emptyList()
        }
    }

    fun getVolumes(): List<VolumeData> = cachedVolumes

    fun getVolume(volNum: Int): VolumeData? {
        return cachedVolumes.firstOrNull { it.volume_number == volNum }
    }

    fun getChapter(slug: String): ChapterData? {
        for (v in cachedVolumes) {
            val ch = v.chapters.firstOrNull { it.slug == slug }
            if (ch != null) return ch
        }
        return null
    }

    fun getVolumeForChapter(slug: String): VolumeData? {
        return cachedVolumes.firstOrNull { v -> v.chapters.any { it.slug == slug } }
    }

    fun getNextChapter(slug: String): ChapterData? {
        val allChapters = cachedVolumes.flatMap { it.chapters }
        val idx = allChapters.indexOfFirst { it.slug == slug }
        return if (idx != -1 && idx + 1 < allChapters.size) allChapters[idx + 1] else null
    }

    fun getPrevChapter(slug: String): ChapterData? {
        val allChapters = cachedVolumes.flatMap { it.chapters }
        val idx = allChapters.indexOfFirst { it.slug == slug }
        return if (idx > 0) allChapters[idx - 1] else null
    }

    /**
     * Resolves page image source using pre-resolved chapterFolder to prevent Main Thread disk scans.
     */
    fun resolvePageSource(chapter: ChapterData, page: Int, chapterFolder: File?): Any {
        if (chapterFolder != null && chapterFolder.exists()) {
            val localFile = chapter.getLocalPageFile(chapterFolder, page)
            if (localFile != null && localFile.exists()) {
                return localFile
            }
        }
        return chapter.getPageUrl(page)
    }

    /**
     * Overload for callers who only have the root folder path.
     */
    fun resolvePageSource(chapter: ChapterData, page: Int, folderPath: String?): Any {
        val chFolder = if (!folderPath.isNullOrBlank()) {
            chapter.resolveChapterFolder(File(folderPath))
        } else null
        return resolvePageSource(chapter, page, chFolder)
    }

    fun getChapterPageCount(chapter: ChapterData, folderPath: String?): Int {
        // 1. If local folder exists, check actual number of files on disk
        if (!folderPath.isNullOrBlank()) {
            val baseFolder = File(folderPath)
            val chFolder = chapter.resolveChapterFolder(baseFolder)
            if (chFolder != null && chFolder.exists()) {
                val count = chFolder.listFiles { f ->
                    f.isFile && f.name.lowercase().endsWith(".jpg")
                }?.size ?: 0
                if (count > 0) return count
            }
        }

        // 2. If catalog contains explicit pages_count, use it
        if (chapter.pages_count > 0) {
            return chapter.pages_count
        }

        // 3. Fallback estimations for edge-cases
        return when {
            chapter.slug == "reader/000-01" -> 97
            chapter.slug == "reader/000-02" -> 70
            chapter.slug == "reader/000-03" -> 57
            chapter.slug == "reader/000-04" -> 127
            chapter.slug == "reader/000-05" -> 109
            chapter.slug.contains("000-") -> 45
            else -> 22
        }
    }
}
`

---

## File: PreferencesManager.kt

`kotlin
package com.berserk.reader.data

import android.content.Context
import android.content.SharedPreferences
import com.berserk.reader.model.ReadingProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("berserk_reader_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_VOL = "last_volume"
        private const val KEY_LAST_CH_SLUG = "last_chapter_slug"
        private const val KEY_LAST_CH_TITLE = "last_chapter_title"
        private const val KEY_LAST_PAGE = "last_page"
        private const val KEY_TOTAL_PAGES = "last_total_pages"
        private const val KEY_FOLDER_PATH = "custom_folder_path"
        private const val KEY_READER_MODE = "reader_mode"
        private const val KEY_READ_CHAPTERS = "read_chapters"
    }

    private val _progressFlow = MutableStateFlow(loadProgress())
    val progressFlow: StateFlow<ReadingProgress> = _progressFlow.asStateFlow()

    private val _folderPathFlow = MutableStateFlow(loadFolderPath())
    val folderPathFlow: StateFlow<String?> = _folderPathFlow.asStateFlow()

    private val _readChaptersFlow = MutableStateFlow(loadReadChapters())
    val readChaptersFlow: StateFlow<Set<String>> = _readChaptersFlow.asStateFlow()

    private val _readerModeFlow = MutableStateFlow(loadReaderMode())
    val readerModeFlow: StateFlow<String> = _readerModeFlow.asStateFlow()

    private fun loadProgress(): ReadingProgress {
        return ReadingProgress(
            volumeNumber = prefs.getInt(KEY_LAST_VOL, 1),
            chapterSlug = prefs.getString(KEY_LAST_CH_SLUG, "reader/000-01") ?: "reader/000-01",
            chapterTitle = prefs.getString(KEY_LAST_CH_TITLE, "Capitolo 000-01 - Il guerriero nero")
                ?: "Capitolo 000-01 - Il guerriero nero",
            pageNumber = prefs.getInt(KEY_LAST_PAGE, 1),
            totalPages = prefs.getInt(KEY_TOTAL_PAGES, 97)
        )
    }

    private fun loadFolderPath(): String? {
        return prefs.getString(KEY_FOLDER_PATH, null)
    }

    private fun loadReadChapters(): Set<String> {
        val raw = prefs.getStringSet(KEY_READ_CHAPTERS, emptySet()) ?: emptySet()
        return HashSet(raw)
    }

    private fun loadReaderMode(): String {
        return prefs.getString(KEY_READER_MODE, "VERTICAL") ?: "VERTICAL"
    }

    fun saveProgress(progress: ReadingProgress) {
        prefs.edit()
            .putInt(KEY_LAST_VOL, progress.volumeNumber)
            .putString(KEY_LAST_CH_SLUG, progress.chapterSlug)
            .putString(KEY_LAST_CH_TITLE, progress.chapterTitle)
            .putInt(KEY_LAST_PAGE, progress.pageNumber)
            .putInt(KEY_TOTAL_PAGES, progress.totalPages)
            .apply()
        _progressFlow.value = progress
    }

    fun getProgress(): ReadingProgress = _progressFlow.value

    fun setFolderPath(path: String?) {
        val clean = path?.trim()?.ifEmpty { null }
        prefs.edit().putString(KEY_FOLDER_PATH, clean).apply()
        _folderPathFlow.value = clean
    }

    fun getFolderPath(): String? = _folderPathFlow.value

    fun setReaderMode(mode: String) {
        prefs.edit().putString(KEY_READER_MODE, mode).apply()
        _readerModeFlow.value = mode
    }

    fun getReaderMode(): String = _readerModeFlow.value

    fun markChapterRead(slug: String) {
        val current = _readChaptersFlow.value
        if (!current.contains(slug)) {
            val updated = HashSet(current).apply { add(slug) }
            prefs.edit().putStringSet(KEY_READ_CHAPTERS, updated).apply()
            _readChaptersFlow.value = updated
        }
    }

    fun isChapterRead(slug: String): Boolean {
        return _readChaptersFlow.value.contains(slug)
    }

    fun getReadChapters(): Set<String> = _readChaptersFlow.value
}
`

---

## File: ZoomableBox.kt

`kotlin
package com.berserk.reader.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = 4f,
    resetKey: Any? = null,
    onTap: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Auto-reset when page or chapter changes
    LaunchedEffect(resetKey) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (scale > 1.1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                            val centerX = containerSize.width / 2f
                            val centerY = containerSize.height / 2f
                            val targetOffsetX = (centerX - tapOffset.x) * (2.5f - 1f)
                            val targetOffsetY = (centerY - tapOffset.y) * (2.5f - 1f)
                            val maxX = ((containerSize.width * 2.5f - containerSize.width) / 2f).coerceAtLeast(0f)
                            val maxY = ((containerSize.height * 2.5f - containerSize.height) / 2f).coerceAtLeast(0f)
                            offsetX = targetOffsetX.coerceIn(-maxX, maxX)
                            offsetY = targetOffsetY.coerceIn(-maxY, maxY)
                        }
                    },
                    onTap = {
                        onTap?.invoke()
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        val isMultiTouch = event.changes.size > 1
                        val isZooming = zoomChange != 1f
                        val isZoomedIn = scale > 1.05f

                        if (isMultiTouch || isZooming || isZoomedIn) {
                            val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
                            scale = newScale

                            val maxX = ((containerSize.width * scale - containerSize.width) / 2f).coerceAtLeast(0f)
                            val maxY = ((containerSize.height * scale - containerSize.height) / 2f).coerceAtLeast(0f)

                            if (scale > 1f) {
                                offsetX = (offsetX + panChange.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + panChange.y).coerceIn(-maxY, maxY)
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }

                            // Consume pointer movement when zooming or zoomed-in
                            event.changes.forEach { it.consume() }
                        }
                        // When scale == 1f and single touch, we do not consume,
                        // allowing HorizontalPager or LazyColumn to scroll smoothly!
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
        ) {
            content()
        }
    }
}
`

---

## File: ReaderScreen.kt

`kotlin
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
`

---

## File: HomeScreen.kt

`kotlin
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
`

---

## File: VolumeDetailScreen.kt

`kotlin
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
`

---

## File: AppNavigation.kt

`kotlin
package com.berserk.reader.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.berserk.reader.data.MangaRepository
import com.berserk.reader.data.PreferencesManager
import com.berserk.reader.ui.screens.HomeScreen
import com.berserk.reader.ui.screens.ReaderScreen
import com.berserk.reader.ui.screens.VolumeDetailScreen

@Composable
fun AppNavigation(
    repository: MangaRepository,
    prefs: PreferencesManager
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        // HOME SCREEN
        composable("home") {
            HomeScreen(
                repository = repository,
                prefs = prefs,
                onVolumeClick = { volNum ->
                    navController.navigate("volume/$volNum")
                },
                onResumeClick = { slug, page ->
                    val encodedSlug = Uri.encode(slug)
                    navController.navigate("reader?slug=$encodedSlug&page=$page")
                }
            )
        }

        // VOLUME DETAIL SCREEN
        composable(
            route = "volume/{volumeNumber}",
            arguments = listOf(
                navArgument("volumeNumber") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val volNum = backStackEntry.arguments?.getInt("volumeNumber") ?: 1
            VolumeDetailScreen(
                volumeNumber = volNum,
                repository = repository,
                prefs = prefs,
                onBackClick = { navController.popBackStack() },
                onChapterClick = { slug ->
                    val encodedSlug = Uri.encode(slug)
                    navController.navigate("reader?slug=$encodedSlug&page=1")
                }
            )
        }

        // READER SCREEN (Uses query parameters so slashes in slug don't break NavDeepLink path matching)
        composable(
            route = "reader?slug={chapterSlug}&page={page}",
            arguments = listOf(
                navArgument("chapterSlug") {
                    type = NavType.StringType
                    defaultValue = "reader/000-01"
                },
                navArgument("page") {
                    type = NavType.IntType
                    defaultValue = 1
                }
            )
        ) { backStackEntry ->
            val rawSlug = backStackEntry.arguments?.getString("chapterSlug") ?: "reader/000-01"
            val slug = Uri.decode(rawSlug)
            val page = backStackEntry.arguments?.getInt("page") ?: 1

            ReaderScreen(
                chapterSlug = slug,
                initialPage = page,
                repository = repository,
                prefs = prefs,
                onBackClick = { navController.popBackStack() },
                onNavigateChapter = { nextSlug, startPage ->
                    val encodedNext = Uri.encode(nextSlug)
                    navController.navigate("reader?slug=$encodedNext&page=$startPage") {
                        popUpTo("reader?slug={chapterSlug}&page={page}") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
`

---

## File: MainActivity.kt

`kotlin
package com.berserk.reader

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.berserk.reader.data.MangaRepository
import com.berserk.reader.data.PreferencesManager
import com.berserk.reader.navigation.AppNavigation
import com.berserk.reader.ui.theme.BerserkReaderTheme
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        private var repositoryInstance: MangaRepository? = null

        @Volatile
        private var prefsInstance: PreferencesManager? = null

        @Volatile
        private var isImageLoaderInitialized = false

        fun getRepository(context: Context): MangaRepository {
            return repositoryInstance ?: synchronized(this) {
                repositoryInstance ?: MangaRepository(context.applicationContext).also {
                    repositoryInstance = it
                }
            }
        }

        fun getPreferences(context: Context): PreferencesManager {
            return prefsInstance ?: synchronized(this) {
                prefsInstance ?: PreferencesManager(context.applicationContext).also {
                    prefsInstance = it
                }
            }
        }

        fun initImageLoader(context: Context) {
            if (!isImageLoaderInitialized) {
                synchronized(this) {
                    if (!isImageLoaderInitialized) {
                        val appContext = context.applicationContext
                        val okHttpClient = OkHttpClient.Builder()
                            .addInterceptor { chain ->
                                val request = chain.request().newBuilder()
                                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                    .header("Referer", "https://onepiecepower.com/manga8/berserk/lista-capitoli")
                                    .build()
                                chain.proceed(request)
                            }
                            .build()

                        val imageLoader = ImageLoader.Builder(appContext)
                            .okHttpClient(okHttpClient)
                            .memoryCache {
                                MemoryCache.Builder(appContext)
                                    .maxSizePercent(0.25)
                                    .build()
                            }
                            .diskCache {
                                DiskCache.Builder()
                                    .directory(appContext.cacheDir.resolve("manga_image_cache"))
                                    .maxSizeBytes(512L * 1024 * 1024) // 512 MB disk cache
                                    .build()
                            }
                            .respectCacheHeaders(false)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .build()

                        Coil.setImageLoader(imageLoader)
                        isImageLoaderInitialized = true
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = getRepository(this)
        val prefs = getPreferences(this)
        initImageLoader(this)

        setContent {
            BerserkReaderTheme {
                AppNavigation(
                    repository = repository,
                    prefs = prefs
                )
            }
        }
    }
}
`

---

## File: AndroidManifest.xml

`xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher"
        android:supportsRtl="true"
        android:theme="@style/Theme.BerserkReader"
        android:usesCleartextTraffic="true">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"
            android:theme="@style/Theme.BerserkReader">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
`

---

