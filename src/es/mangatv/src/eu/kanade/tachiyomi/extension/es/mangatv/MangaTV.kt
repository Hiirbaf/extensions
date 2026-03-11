package eu.kanade.tachiyomi.extension.es.mangatv

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.FilterList
import java.text.SimpleDateFormat
import java.util.Locale

class MangaTV :
    MangaThemesia(
        "Manga TV",
        "https://mangatv.net",
        "es",
        mangaUrlDirectory = "/lista",
        dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT),
    ) {
    override fun chapterListSelector() = "div.eplister ul li:has(div.chbox):has(div.eph-num):has(a[href])"

    override fun getFilterList(): FilterList {
        val filters = super.getFilterList().filterNot { it is AuthorFilter || it is YearFilter }
        return FilterList(filters)
    }
}
