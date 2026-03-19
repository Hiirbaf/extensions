package eu.kanade.tachiyomi.multisrc.lectormonline

import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceScreen

class LectorMOnlinePreferences(
    private val preferences: SharedPreferences,
) {

    fun setup(screen: PreferenceScreen) {
        val nsfwPref = CheckBoxPreference(screen.context).apply {
            key = SHOW_NSFW
            title = "Mostrar contenido NSFW"
            summary = "Incluir mangas +18 en los resultados"
            setDefaultValue(false)
        }

        screen.addPreference(nsfwPref)
    }

    fun showNsfw(): Boolean = preferences.getBoolean(SHOW_NSFW, false)

    companion object {
        const val SHOW_NSFW = "show_nsfw"
    }
}
