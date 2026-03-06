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

class SortProperty(
    val name: String,
    val value: String,
) {
    override fun toString(): String = name
}

class GenreFilter(
    genres: List<String>,
) : Filter.Select<String>(
    "Género",
    arrayOf("Todos", *genres.toTypedArray()),
) {
    fun toUriPart(): String {
        val selected = values[state]
        return if (selected == "Todos") "" else selected
    }
}
