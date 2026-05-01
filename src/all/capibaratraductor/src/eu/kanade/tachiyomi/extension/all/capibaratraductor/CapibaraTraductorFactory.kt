package eu.kanade.tachiyomi.extension.all.capibaratraductor

import eu.kanade.tachiyomi.source.SourceFactory

class CapibaraTraductorFactory : SourceFactory {
    override fun createSources() = listOf(
        CapibaraTraductor("SenshiManga", "senshimanga"),
        CapibaraTraductor("Rakuen Translations", "rakuen"),
        CapibaraTraductor("El Scan Semanal", "elscansemanal"),
        CapibaraTraductor("6ianfranc9 Manga", "6ianfranc9"),
        // Añade más scans aquí con su slug correspondiente
    )
}
