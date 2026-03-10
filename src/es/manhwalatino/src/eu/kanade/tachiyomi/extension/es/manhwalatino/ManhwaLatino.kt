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
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36",
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .header("Referer", baseUrl)
                .header("Connection", "keep-alive")
                .build()

            chain.proceed(request)
        }
        .build()

    override val useNewChapterEndpoint = false

    override val pageListParseSelector = "div.reading-content img"
}
