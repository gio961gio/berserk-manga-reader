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
