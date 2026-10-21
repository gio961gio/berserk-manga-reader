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
