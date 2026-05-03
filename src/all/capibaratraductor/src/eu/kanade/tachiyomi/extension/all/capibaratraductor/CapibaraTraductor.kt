package eu.kanade.tachiyomi.extension.all.capibaratraductor

import eu.kanade.tachiyomi.multisrc.lectormoe.Data
import eu.kanade.tachiyomi.multisrc.lectormoe.LectorMoe
import eu.kanade.tachiyomi.multisrc.lectormoe.SeriesListDataDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import okhttp3.Request
import okhttp3.Response

class CapibaraTraductor :
    LectorMoe(
        name = "CapibaraTraductor",
        baseUrl = "https://capibaratraductor.com",
        lang = "es",
    ) {
    private val apiBase = "https://capibaratraductor.com"

    private fun orgHeaders(orgSlug: String) = headersBuilder()
        .add("x-organization", orgSlug)
        .build()

    override fun searchMangaParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")!!.toInt()
        val result = response.parseAs<Data<SeriesListDataDto>>()
        val mangas = result.data.series.map { series ->
            series.toSManga().apply {
                val orgSlug = series.organization?.slug ?: "unknown"
                url = "$orgSlug/$url"
            }
        }
        return MangasPage(mangas, page < result.data.maxPage)
    }

    private fun SManga.orgSlug() = url.substringBefore("/")
    private fun SManga.mangaSlug() = url.substringAfter("/")
    private fun SChapter.orgSlug() = url.substringBefore("/")
    private fun SChapter.chapterUrl() = url.substringAfter("/")

    override fun getMangaUrl(manga: SManga) = "$baseUrl/${manga.orgSlug()}/manga/${manga.mangaSlug()}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiBase/api/manga-custom/${manga.mangaSlug()}", orgHeaders(manga.orgSlug()))

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val orgSlug = response.request.headers["x-organization"] ?: return super.chapterListParse(response)
        return super.chapterListParse(response).map { ch ->
            ch.apply { url = "$orgSlug/$url" }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val orgSlug = chapter.orgSlug()
        val (seriesSlug, number) = chapter.chapterUrl().split("/")
        return "$baseUrl/$orgSlug/manga/$seriesSlug/chapters/$number"
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val orgSlug = chapter.orgSlug()
        val (seriesSlug, number) = chapter.chapterUrl().split("/")
        return GET("$apiBase/api/manga-custom/$seriesSlug/chapter/$number/pages", orgHeaders(orgSlug))
    }
}
