package com.flexunlock.dexlsp

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

internal enum class ModuleLanguage(val tag: String) {
    CHINESE("zh-CN"), ENGLISH("en")
}

internal object ModuleLanguageStore {
    fun selected(context: Context): ModuleLanguage {
        val tag = context.getSharedPreferences("module_ui", Context.MODE_PRIVATE)
            .getString("language", ModuleLanguage.CHINESE.tag)
        return ModuleLanguage.entries.firstOrNull { it.tag == tag } ?: ModuleLanguage.CHINESE
    }

    fun save(context: Context, language: ModuleLanguage) {
        check(context.getSharedPreferences("module_ui", Context.MODE_PRIVATE)
            .edit().putString("language", language.tag).commit())
    }

    fun configuration(context: Context): Configuration = Configuration().apply {
        setLocales(LocaleList(Locale.forLanguageTag(selected(context).tag)))
    }

    fun wrap(context: Context): Context = context.createConfigurationContext(configuration(context))
}
