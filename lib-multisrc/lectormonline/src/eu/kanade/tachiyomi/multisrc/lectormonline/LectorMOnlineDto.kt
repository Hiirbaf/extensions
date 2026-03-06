package eu.kanade.tachiyomi.multisrc.lectormonline

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/* ============================
 * DATE FORMAT
 * ============================ */

private val dateFormat =
    SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.ROOT,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

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
 * COMIC
 * ============================ */

@Serializable
class ComicDto(
    val id: Int,
    val title: String,
    val slug: String,
    val description: String? = null,
    val coverImage: String? = null,
    val status: String? = null,
    val comicGenres: List<ComicGenreWrapper> = emptyList(),
    val comicScans: List<ScanDto> = emptyList(),
) {

    fun toSManga() =
        SManga.create().apply {
            url = slug
            title = this@ComicDto.title
            thumbnail_url = coverImage
            status = parseStatus(this@ComicDto.status)
        }

    fun toSMangaDetails() =
        SManga.create().apply {
            url = slug
            title = this@ComicDto.title
            thumbnail_url = coverImage
            description = this@ComicDto.description
            status = parseStatus(this@ComicDto.status)
            genre = comicGenres.joinToString(", ") { it.genre.name }
        }

    fun getChapters(): List<SChapter> =
        comicScans
            .flatMap { it.chapters }
            .map { it.toSChapter() }
            .sortedByDescending { it.chapter_number }
}

/* ============================
 * GENRES
 * ============================ */

@Serializable
class ComicGenreWrapper(
    val genre: GenreDto,
)

@Serializable
class GenreDto(
    val id: Int,
    val name: String,
    val slug: String,
)

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

@Serializable
class ChapterDto(
    val id: Int,
    val chapterNumber: Float,
    val title: String? = null,
    val slug: String,
    val releaseDate: String,
    val urlPages: List<String> = emptyList(),
) {

    fun toSChapter() =
        SChapter.create().apply {
            url = id.toString()
            name =
                title?.let { "Capítulo $chapterNumber - $it" }
                    ?: "Capítulo $chapterNumber"
            chapter_number = chapterNumber
            date_upload = dateFormat.tryParse(releaseDate) ?: 0L
        }
}

/* ============================
 * STATUS PARSER
 * ============================ */

private fun parseStatus(status: String?): Int =
    when (status?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }
