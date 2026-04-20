package eu.kanade.tachiyomi.extension.en.novelcrow

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.Request

class NovelCrow : Madara("NovelCrow", "https://novelcrow.com", "en") {
    override val useNewChapterEndpoint = true
    override val mangaSubString = "comic"

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/comic/?m_orderby=trending&page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/comic/?m_orderby=&page=$page", headers)
}
