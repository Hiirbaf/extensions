package eu.kanade.tachiyomi.extension.es.manhwaonline

import android.util.Base64
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.multisrc.madara.MadaraChapter
import eu.kanade.tachiyomi.source.model.Page
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class ManhwaOnline :
    Madara(
        "ManhwaOnline",
        "https://manhwa-online.com",
        "es",
        dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("es")),
    ) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = true

    override fun pageListRequest(chapter: MadaraChapter) = super.pageListRequest(chapter).also {
        // Esto no es necesario si sobreescribimos pageListParse
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        // 1️⃣ Extraer script "mowl-shield"
        val shieldScript = document.selectFirst("script#mowl-shield")?.html()
        if (shieldScript != null) {
            // 2️⃣ Extraer array _d
            val regexD = Regex("var _d=\\[(.*?)\\];", RegexOption.DOT_MATCHES_ALL)
            val matchD = regexD.find(shieldScript)?.groups?.get(1)?.value
            val arrayD = matchD?.split(",")?.map { it.trim().trim('"') } ?: emptyList()

            // 3️⃣ Calcular _k
            val imgCount = document.select(".wp-manga-chapter-img").size
            val k = (imgCount xor 4) xor imgCount

            // 4️⃣ Función de desencriptado
            fun decodeX(s: String): String {
                val decoded = Base64.decode(s, Base64.DEFAULT)
                return decoded.map { (it.toInt() xor k).toChar() }.joinToString("")
            }

            // 5️⃣ Generar lista de Page
            arrayD.forEachIndexed { index, encoded ->
                if (encoded.isNotEmpty()) {
                    val url = decodeX(encoded)
                    pages.add(Page(index, "", url))
                }
            }
        } else {
            // Fallback a Madara normal (por si el script no existe)
            return super.pageListParse(document)
        }

        return pages
    }
}
