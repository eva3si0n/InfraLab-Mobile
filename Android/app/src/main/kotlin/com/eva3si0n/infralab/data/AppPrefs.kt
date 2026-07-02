package com.eva3si0n.infralab.data

import android.content.Context
import android.content.SharedPreferences

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var kumaBaseURL: String by StringPref(prefs, "kumaBaseURL", "")
    var kumaSlug: String by StringPref(prefs, "kumaSlug", "")
    var grafanaBaseURL: String by StringPref(prefs, "grafanaBaseURL", "")
    var grafanaDatasourceUID: String by StringPref(prefs, "grafanaDatasourceUID", "prometheus")
    var homePageBaseURL: String by StringPref(prefs, "homePageBaseURL", "")
    var refreshIntervalSecs: Long by LongPref(prefs, "refreshIntervalSecs", 30L)
}

private class StringPref(
    private val prefs: SharedPreferences,
    private val key: String,
    private val default: String
) {
    operator fun getValue(thisRef: Any?, property: Any?) = prefs.getString(key, default) ?: default
    operator fun setValue(thisRef: Any?, property: Any?, value: String) =
        prefs.edit().putString(key, value).apply()
}

private class LongPref(
    private val prefs: SharedPreferences,
    private val key: String,
    private val default: Long
) {
    operator fun getValue(thisRef: Any?, property: Any?) = prefs.getLong(key, default)
    operator fun setValue(thisRef: Any?, property: Any?, value: Long) =
        prefs.edit().putLong(key, value).apply()
}
