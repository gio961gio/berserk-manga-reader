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
