package eu.kanade.tachiyomi.extension.es.uchuujinprojects

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale

class UchuujinProjects :
    MangaThemesia(
        "Uchuujin Projects",
        "https://uchuujinmangas.com",
        "es",
        dateFormat = SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale("es")),
    ) {
    override val client = super.client.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 3, 1)
        .build()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Verificar si hay filtros aplicados
        val hasFilters = filters.any { filter ->
            when (filter) {
                is AuthorFilter, is YearFilter, is StatusFilter, is TypeFilter, is OrderByFilter,
                is GenreListFilter, is ProjectFilter,
                else -> false
            }
        }

        return if (!hasFilters) {
            // --- BUSCADOR GLOBAL (lupa) ---
            val urlBuilder = baseUrl.toHttpUrl().newBuilder()

            // Paginación: /page/<número>/ solo si page > 1
            if (page > 1) {
                urlBuilder.addPathSegment("page")
                urlBuilder.addPathSegment(page.toString())
            }

            urlBuilder.addQueryParameter("s", query)
            GET(urlBuilder.build(), headers)
        } else {
            // --- DIRECTORIO /manga/ CON FILTROS ---
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment(mangaUrlDirectory.substring(1))
                .addQueryParameter("title", query)
                .addQueryParameter("page", page.toString())

            filters.forEach { filter ->
                when (filter) {
                    is AuthorFilter -> url.addQueryParameter("author", filter.state)
                    is YearFilter -> url.addQueryParameter("yearx", filter.state)
                    is StatusFilter -> url.addQueryParameter("status", filter.selectedValue())
                    is TypeFilter -> url.addQueryParameter("type", filter.selectedValue())
                    is OrderByFilter -> url.addQueryParameter("order", filter.selectedValue())
                    is GenreListFilter ->
                        filter.state
                            .filter { it.state != Filter.TriState.STATE_IGNORE }
                            .forEach {
                                val value = if (it.state == Filter.TriState.STATE_EXCLUDE) "-${it.value}" else it.value
                                url.addQueryParameter("genre[]", value)
                            }
                    is ProjectFilter -> if (filter.selectedValue() == "project-filter-on") {
                        url.setPathSegment(0, projectPageString.substring(1))
                    }
                    else -> {}
                }
            }
            url.addPathSegment("") // asegurar que termine con '/'
            GET(url.build(), headers)
        }
    }

    override val hasProjectPage = true
}
