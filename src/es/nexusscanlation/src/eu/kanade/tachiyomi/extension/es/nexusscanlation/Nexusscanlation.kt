package eu.kanade.tachiyomi.extension.es.nexusscanlation

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class Nexusscanlation : HttpSource() {

    override val name = "NexusScanlation"
    override val baseUrl = "https://nexusscanlation.com"
    override val lang = "es"
    override val supportsLatest = true

    private val apiBaseUrl = "https://api.nexusscanlation.com/api/v1"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val (seriesSlug, chapterSlug) = chapter.url.split('/', limit = 2)
        return "$baseUrl/series/$seriesSlug/chapter/$chapterSlug"
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("catalog")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("orden", "popular")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val root = response.parseAs<CatalogResponseDto>()
        return MangasPage(root.data.orEmpty().mapNotNull(::catalogToManga), root.meta?.hasNext ?: false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("catalog")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("orden", "nuevo")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = apiBaseUrl.toHttpUrl().newBuilder()

        if (query.isNotBlank()) {
            urlBuilder
                .addPathSegment("catalog")
                .addPathSegment("search")
                .addQueryParameter("q", query)
        } else {
            urlBuilder.addPathSegment("catalog")
            filters.forEach { filter ->
                when (filter) {
                    is OrderFilter -> urlBuilder.addQueryParameter("orden", filter.selected())
                    is StatusFilter -> filter.selected().takeIf { it.isNotEmpty() }
                        ?.let { urlBuilder.addQueryParameter("estado", it) }
                    is TypeFilter -> filter.selected().takeIf { it.isNotEmpty() }
                        ?.let { urlBuilder.addQueryParameter("tipo", it) }
                    is GenreFilter -> filter.selected().takeIf { it.isNotEmpty() }
                        ?.let { urlBuilder.addQueryParameter("genero", it) }
                    else -> {}
                }
            }
        }

        urlBuilder.addQueryParameter("page", page.toString())
        return GET(urlBuilder.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("series")
            .addPathSegment(manga.url)
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val root = response.parseAs<SeriesPayloadDto>()
        return seriesToManga(root.serie)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("series")
            .addPathSegment(manga.url)
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val payload = response.parseAs<SeriesPayloadDto>()
        val seriesSlug = payload.serie.slug

        return payload.capitulos.orEmpty()
            .map { chapterToModel(seriesSlug, it) }
            .toList()
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val (seriesSlug, chapterSlug) = chapter.url.split('/', limit = 2)

        val url = apiBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("series")
            .addPathSegment(seriesSlug)
            .addPathSegment("capitulos")
            .addPathSegment(chapterSlug)
            .build()

        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val payload = response.parseAs<ChapterPagesPayloadDto>()
        return payload.data?.paginas.orEmpty()
            .mapIndexed { index, page -> Page(index, imageUrl = page.url) }
            .toList()
    }

    private fun catalogToManga(item: CatalogEntryDto): SManga? {
        if (item.slug.isBlank() || item.titulo.isBlank()) return null
        return SManga.create().apply {
            url = item.slug
            title = item.titulo
            thumbnail_url = item.portadaUrl
        }
    }

    private fun chapterToModel(seriesSlug: String, chapter: ChapterEntryDto): SChapter {
        val chapterNumber = chapter.numero.toString().removeSuffix(".0")

        return SChapter.create().apply {
            url = "$seriesSlug/${chapter.slug}"
            name = "Capitulo $chapterNumber"
            chapter_number = chapter.numero
            date_upload = dateFormat.tryParse(chapter.publishedAt)
        }
    }

    private fun seriesToManga(series: SeriesDto): SManga = SManga.create().apply {
        title = series.titulo
        thumbnail_url = series.portadaUrl
        description = series.descripcion
        genre = series.generos
            ?.mapNotNull { it.nombre.takeIf { name -> name.isNotBlank() } }
            ?.joinToString()

        status = when (series.estado.lowercase(Locale.ROOT)) {
            "en_emision" -> SManga.ONGOING
            "finalizado" -> SManga.COMPLETED
            "pausado" -> SManga.ON_HIATUS
            "cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val credits = series.autores.orEmpty().mapNotNull { credit ->
            credit.nombre.takeIf { it.isNotBlank() }?.trim()?.let { it to credit.rol?.lowercase(Locale.ROOT) }
        }

        author = credits
            .filter { (_, role) -> role != "artista" }
            .map { (name) -> name }
            .distinct()
            .joinToString()
            .ifBlank { null }

        artist = credits
            .filter { (_, role) -> role == "artista" }
            .map { (name) -> name }
            .distinct()
            .joinToString()
            .ifBlank { null }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ========================= Filters =========================

    override fun getFilterList() = FilterList(
        Filter.Header("Los filtros se ignoran al buscar por texto"),
        OrderFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    class OrderFilter :
        SelectFilter(
            "Ordenar por",
            listOf(
                Pair("Más nuevo", "nuevo"),
                Pair("Más popular", "popular"),
                Pair("Alfabético", "az"),
            ),
        )

    class StatusFilter :
        SelectFilter(
            "Estado",
            listOf(
                Pair("Todos", ""),
                Pair("En emisión", "en_emision"),
                Pair("Finalizado", "finalizado"),
                Pair("Pausado", "pausado"),
                Pair("Cancelado", "cancelado"),
            ),
        )

    class TypeFilter :
        SelectFilter(
            "Tipo",
            listOf(
                Pair("Todos", ""),
                Pair("Manhwa", "manhwa"),
                Pair("Manga", "manga"),
                Pair("Manhua", "manhua"),
            ),
        )

    class GenreFilter :
        SelectFilter(
            "Género",
            listOf(
                Pair("Todos", ""),
                Pair("Acción", "accion"),
                Pair("Artes Marciales", "artes-marciales"),
                Pair("Aventura", "aventura"),
                Pair("Ciencia Ficción", "ciencia-ficcion"),
                Pair("Comedia", "comedia"),
                Pair("Deportes", "deportes"),
                Pair("Drama", "drama"),
                Pair("Ecchi", "ecchi"),
                Pair("Escolar", "escolar"),
                Pair("Fantasía", "fantasia"),
                Pair("Harem", "harem"),
                Pair("Histórico", "historico"),
                Pair("Horror", "horror"),
                Pair("Isekai", "isekai"),
                Pair("Josei", "josei"),
                Pair("Mecha", "mecha"),
                Pair("Misterio", "misterio"),
                Pair("Música", "musica"),
                Pair("Psicológico", "psicologico"),
                Pair("Romance", "romance"),
                Pair("Seinen", "seinen"),
                Pair("Shoujo", "shoujo"),
                Pair("Shounen", "shounen"),
                Pair("Slice of Life", "slice-of-life"),
                Pair("Supernatural", "supernatural"),
                Pair("Supervivencia", "supervivencia"),
                Pair("Thriller", "thriller"),
                Pair("Webtoon", "webtoon"),
                Pair("Yaoi", "yaoi"),
                Pair("Yuri", "yuri"),
            ),
        )

    open class SelectFilter(
        name: String,
        private val options: List<Pair<String, String>>,
        private val default: Int = 0,
    ) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), default) {
        fun selected() = options[state].second
    }
}
