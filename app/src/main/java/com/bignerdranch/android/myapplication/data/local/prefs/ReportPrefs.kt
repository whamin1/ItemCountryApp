package com.bignerdranch.android.myapplication.data.local.prefs

import android.content.Context
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

// ✅ 1) Context 확장: 반드시 파일 최상단(top-level)에!
val Context.reportDataStore by preferencesDataStore(name = "report_prefs")

object ReportPrefsKeys {
    val SELECTED_ITEM_NAMES = stringSetPreferencesKey("selected_item_names")
}