package eu.kanade.tachiyomi.extension.es.manhwalatino

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale

class ManhwaLatino :
    Madara(
        "Manhwa-Latino",
        "https://manhwa-latino.com",
        "es",
        SimpleDateFormat("dd/MM/yyyy", Locale("es")),
    ) {

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 1, 2)
        .build()

    override val useNewChapterEndpoint = false

    override val pageListParseSelector = "div.reading-content img"
}
