package eu.kanade.tachiyomi.multisrc.lectormonline

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/* ============================
 * LIST RESPONSE
 * ============================ */

@Serializable
class ComicListDto(
    val comics: List<ComicDto>,
    val page: Int,
    val totalPages: Int,
) {
    fun hasNextPage() = page < totalPages
}

/* ============================
 * COMIC DETAILS
 * ============================ */

@Serializable
class ComicDto(
    val id: Int,
    val title: String,
    val description: String? = null,
    val coverImage: String? = null,
    val status: String? = null,
    val comicGenres: List<GenreDto> = emptyList(),
    val comicScans: List<ScanDto> = emptyList(),
) {

    fun toSManga() = SManga.create().apply {
        url = id.toString()
        this.title = this@ComicDto.title
        thumbnail_url = coverImage
        status = status.parseStatus()
    }

    fun toSMangaDetails() = SManga.create().apply {
        url = id.toString()
        this.title = this@ComicDto.title
        thumbnail_url = coverImage
        description = this@ComicDto.description
        status = status.parseStatus()
        genre = comicGenres.joinToString(", ") { it.name }
    }

    fun getChapters(): List<SChapter> {
        return comicScans.flatMap { scan ->
            scan.chapters.map { it.toSChapter() }
        }.sortedByDescending { it.chapter_number }
    }

    private fun String?.parseStatus(): Int = when (this?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }
}

/* ============================
 * SCANS
 * ============================ */

@Serializable
class ScanDto(
    val chapters: List<ChapterDto> = emptyList(),
)

/* ============================
 * CHAPTER
 * ============================ */

val dateFormat = SimpleDateFormat(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    Locale.ROOT
).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

@Serializable
class ChapterDto(
    val id: Int,
    val chapterNumber: Float,
    val releaseDate: String,
    val urlPages: List<String> = emptyList(),
) {

    fun toSChapter() = S
