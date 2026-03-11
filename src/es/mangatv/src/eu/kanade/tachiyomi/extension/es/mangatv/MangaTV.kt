package eu.kanade.tachiyomi.extension.es.mangatv

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.interceptor.rateLimit

class MangaTV :
    MangaThemesia(
        "Manga TV",
        "https://mangatv.net",
        "es",
        mangaUrlDirectory = "/lista",
    ) {
    override val client = super.client.newBuilder()
        .rateLimit(3)
        .build()
    }
