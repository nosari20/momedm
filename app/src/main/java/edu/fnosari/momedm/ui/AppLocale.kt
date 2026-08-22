package edu.fnosari.momedm.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app language. On API 33+ the framework owns the setting (it also shows up in
 * system settings → app → language); below that we override the resource configuration
 * ourselves and recreate the activity.
 */
object AppLocale {
    const val SYSTEM = "system"
    val TAGS = listOf(SYSTEM, "fr", "en")

    /** The tag actually in effect: the framework value on API 33+, else [stored]. */
    fun current(context: Context, stored: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return stored
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        return if (locales == null || locales.isEmpty) SYSTEM else locales[0].language
    }

    /** Wraps [context] so resources resolve in [tag]. No-op for "system" and on API 33+. */
    fun wrap(context: Context, tag: String): Context {
        if (tag == SYSTEM || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    /** Applies [tag]; returns true when the caller must recreate itself to show the change. */
    fun apply(context: Context, tag: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        context.getSystemService(LocaleManager::class.java)?.applicationLocales =
            if (tag == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        return false
    }
}
