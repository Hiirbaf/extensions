package eu.kanade.tachiyomi.extension.all.hdoujin

import eu.kanade.tachiyomi.source.SourceFactory

class HDoujinFactory : SourceFactory {
    override fun createSources() = listOf(
        HDoujin("all"),
        HDoujin("en", "english"),
        HDoujin("ja", "japanese"),
        HDoujin("es", "spanish"),
        HDoujin("ko", "korean"),
        HDoujin("zh", "chinese"),
    )
}
