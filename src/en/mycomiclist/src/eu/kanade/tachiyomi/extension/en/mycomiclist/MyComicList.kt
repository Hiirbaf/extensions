package eu.kanade.tachiyomi.extension.en.mycomiclist

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import org.jsoup.nodes.Document

class MyComicList : HttpSource() {

    override val name = "MyComicList"
    override val baseUrl = "https://mycomiclist.org"
    override val lang = "en"
    override val supportsLatest = true

    // =========================
    // Cache de géneros
    // =========================
    private var genreList: List<Pair<String, String>> = emptyList()

    // =========================
    // Popular
    // =========================
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular-comic?page=$page", headers)

    override fun popularMangaParse(response: okhttp3.Response): MangasPage {
        val doc = response.asJsoup()
        return MangasPage(parseMangaList(doc), true)
    }

    // =========================
    // Latest
    // =========================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/new-comic?page=$page", headers)

    override fun latestUpdatesParse(response: okhttp3.Response): MangasPage {
        val doc = response.asJsoup()
        return MangasPage(parseMangaList(doc), true)
    }

    // =========================
    // Search + filtros
    // =========================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var genre: String? = null

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> genre = filter.keys[filter.state]
                else -> {}
            }
        }

        val url = when {
            query.isNotEmpty() -> {
                "$baseUrl/comic-search?key=$query&page=$page"
            }

            !genre.isNullOrEmpty() -> {
                "$baseUrl/" + genre + "-comic?page=$page"
            }

            statePath != null -> {
                "$baseUrl/$statePath?page=$page"
            }

            else -> {
                "$baseUrl/ongoing-comic?page=$page"
            }
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: okhttp3.Response): MangasPage {
        val doc = response.asJsoup()
        return MangasPage(parseMangaList(doc), true)
    }

    // =========================
    // Lista de mangas
    // =========================
    private fun parseMangaList(doc: Document): List<SManga> = doc.select("div.manga-box").map { div ->
        SManga.create().apply {
            title = div.selectFirst("h3 a")?.text().orEmpty()
            url = div.selectFirst("a")?.attr("href")?.substringAfter(baseUrl).orEmpty()
            thumbnail_url = div.selectFirst("img.lazyload")?.attr("data-src")
        }
    }

    // =========================
    // Detalles
    // =========================
    override fun mangaDetailsParse(response: okhttp3.Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("td:contains(Name:) + td strong")?.text()
                ?: document.selectFirst("h1")?.ownText().orEmpty()

            author = document.selectFirst("td:contains(Author:) + td")?.text()

            genre = document.select("td:contains(Genres:) + td a")
                .joinToString(", ") { it.text() }

            status = when (document.selectFirst("td:contains(Status:) + td a")?.text()?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            description = document.selectFirst("div.manga-desc p.pdesc")?.text()

            thumbnail_url = document.selectFirst("div.manga-cover img")?.attr("src")
        }
    }

    // =========================
    // Capítulos
    // =========================
    override fun chapterListParse(response: okhttp3.Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("ul.basic-list li").mapIndexedNotNull { i, li ->
            val a = li.selectFirst("a.ch-name") ?: return@mapIndexedNotNull null

            SChapter.create().apply {
                name = a.text()
                url = a.attr("href").substringAfter(baseUrl)
                chapter_number = name.substringAfter("#").toFloatOrNull() ?: (i + 1).toFloat()
            }
        }
    }

    // =========================
    // Request de páginas (/all)
    // =========================
    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url + "/all", headers)

    // =========================
    // Páginas
    // =========================
    override fun pageListParse(response: okhttp3.Response): List<Page> {
        val document = response.asJsoup()

        return document.select("img.chapter_img.lazyload").mapIndexedNotNull { i, img ->
            val url = img.attr("data-src")
            if (url.isNullOrEmpty()) return@mapIndexedNotNull null

            Page(i, "", url)
        }
    }

    override fun imageUrlParse(response: okhttp3.Response): String = throw UnsupportedOperationException()

    // =========================
    // Filtros dinámicos
    // =========================
    override fun getFilterList(): FilterList {
        if (genreList.isEmpty()) {
            genreList = try {
                fetchGenres()
            } catch (e: Exception) {
                emptyList()
            }
        }

        return FilterList(
            GenreFilter(genreList),
            StateFilter(),
        )
    }

    private fun fetchGenres(): List<Pair<String, String>> {
        val request = GET(baseUrl, headers)

        client.newCall(request).execute().use { response ->
            val doc = response.asJsoup()

            return doc.select("div.cr-anime-box.genre-box a.genre-name").map { element ->
                val name = element.text()
                val key = element.attr("href")
                    .substringAfterLast("/")
                    .removeSuffix("-comic")

                key to name
            }
        }
    }

    class GenreFilter(genres: List<Pair<String, String>>) :
        Filter.Select<String>(
            "Género",
            arrayOf("Todos") + genres.map { it.second }.toTypedArray(),
        ) {
        val keys = listOf("") + genres.map { it.first }
    }

    class StateFilter :
        Filter.Select<String>("Status", arrayOf("Any", "Ongoing", "Completed"))
}
