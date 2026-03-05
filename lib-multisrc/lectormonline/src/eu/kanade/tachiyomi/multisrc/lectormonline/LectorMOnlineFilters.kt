package eu.kanade.tachiyomi.multisrc.lectormonline

import eu.kanade.tachiyomi.source.model.Filter

class SortByFilter(
    title: String,
    private val sortProperties: List<SortProperty>,
    defaultIndex: Int,
) : Filter.Sort(
    title,
    sortProperties.map { it.name }.toTypedArray(),
    Selection(defaultIndex, ascending = false),
) {
    val selected: String
        get() = sortProperties[state!!.index].value
}

class SortProperty(val name: String, val value: String) {
    override fun toString(): String = name
}

/**
 * Aquí enviamos exactamente el mismo texto
 * que la API acepta (con mayúsculas).
 */
class GenreFilter : UriPartFilter(
    "Género",
    arrayOf(
        "Todos" to "",
        "Nuevo" to "Nuevo",
        "Acción" to "Acción",
        "Fantasía" to "Fantasía",
        "Sistema" to "Sistema",
        "Murim" to "Murim",
        "Romance" to "Romance",
        "Comedia" to "Comedia",
        "Drama" to "Drama",
        "Isekai" to "Isekai",
        "Reencarnación" to "Reencarnación",
        "Regresion" to "Regresion",
        "Retornado" to "Retornado",
        "Demonios" to "Demonios",
        "Harem" to "Harem",
        "Ecchi" to "Ecchi",
        "Seinen " to "Seinen ",
        "Shounen " to "Shounen ",
        "Shoujo" to "Shoujo",
        "Manhwa" to "Manhwa",
        "Manhua" to "Manhua",
        "Webtoon" to "Webtoon",
    )
)

open class UriPartFilter(
    displayName: String,
    private val vals: Array<Pair<String, String>>,
) : Filter.Select<String>(
    displayName,
    vals.map { it.first }.toTypedArray(),
) {
    fun toUriPart(): String = vals[state].second
}
