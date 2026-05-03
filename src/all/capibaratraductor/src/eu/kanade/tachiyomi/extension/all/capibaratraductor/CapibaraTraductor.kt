package eu.kanade.tachiyomi.extension.all.capibaratraductor

import eu.kanade.tachiyomi.multisrc.lectormoe.LectorMoe
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class CapibaraTraductor :
    LectorMoe(
        name = "CapibaraTraductor",
        baseUrl = "https://capibaratraductor.com",
        lang = "es",
    ) {
    private val apiBase = "https://capibaratraductor.com"
    private val json: Json by injectLazy()

    private fun orgHeaders(orgSlug: String) = headersBuilder()
        .add("x-organization", orgSlug)
        .build()

    // Parse the listing manually to extract organization.slug without modifying the DTO
    override fun searchMangaParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")!!.toInt()
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val data = root["data"]!!.jsonObject
        val items = data["items"]!!.jsonArray
        val maxPage = data["maxPage"]!!.jsonPrimitive.content.toInt()

        val mangas = items.map { item ->
            val obj = item.jsonObject
            val orgSlug = obj["organization"]?.jsonObject?.get("slug")?.jsonPrimitive?.content ?: "unknown"
            val mangaSlug = obj["manga"]!!.jsonObject["slug"]!!.jsonPrimitive.content
            val title = obj["title"]!!.jsonPrimitive.content
            val imageUrl = obj["imageUrl"]?.jsonPrimitive?.content

            SManga.create().apply {
                url = "$orgSlug/$mangaSlug"
                this.title = title
                thumbnail_url = imageUrl
            }
        }

        return MangasPage(mangas, page < maxPage)
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
