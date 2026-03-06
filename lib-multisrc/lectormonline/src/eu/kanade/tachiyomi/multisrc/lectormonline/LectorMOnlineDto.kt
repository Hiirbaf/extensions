package eu.kanade.tachiyomi.multisrc.lectormonline

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
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
    val data: List<ComicDto>,
    val pagination: PaginationDto,
) {
    fun hasNextPage() = pagination.page < pagination.totalPages
}

@Serializable
class PaginationDto(
    val page: Int,
    val totalPages: Int,
)

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

    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@ComicDto.title
        thumbnail_url = coverImage
        status = parseStatus(this@ComicDto.status)
    }

    fun toSMangaDetails() = SManga.create().apply {
        url = slug
        title = this@ComicDto.title
        thumbnail_url = coverImage
        description = this@ComicDto.description
        status = parseStatus(this@ComicDto.status)
        genre = comicGenres.joinToString(", ") { it.genre.name }
    }

    fun getChapters(): List<SChapter> = comicScans
        .flatMap { it.chapters }
        .map { it.toSChapter() }
        .sortedByDescending { it.chapter_number }
}

/* ============================
 * GENRES
 * ============================ */

@Serializable
data class GenreResponseDto(
    val genres: List<String>,
)

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
 * CHAPTER (LIST)
 * ============================ */

@Serializable
class ChapterDto(
    val id: Int,
    val chapterNumber: Float? = null,
    val title: String? = null,
    val releaseDate: String? = null,
    val urlPages: List<String> = emptyList(),
) {

    fun toSChapter() = SChapter.create().apply {
        url = id.toString()
        name = ("Capítulo ${chapterNumber ?: 0f}")
        chapter_number = chapterNumber ?: 0f
        date_upload = dateFormat.tryParse(releaseDate) ?: 0L
    }
}

/* ============================
 * CHAPTER (PAGES RESPONSE)
 * ============================ */

@Serializable
class ChapterResponseDto(
    val data: ChapterPageDto,
)

@Serializable
class ChapterPageDto(
    val id: Int,
    @SerialName("chapter_number")
    val chapterNumber: String? = null,
    val title: String? = null,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("url_pages")
    val urlPages: List<String> = emptyList(),
)

/* ============================
 * STATUS PARSER
 * ============================ */

private fun parseStatus(status: String?): Int = when (status?.lowercase()) {
    "ongoing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    else -> SManga.UNKNOWN
}
