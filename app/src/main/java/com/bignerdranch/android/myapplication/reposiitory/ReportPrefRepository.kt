package com.bignerdranch.android.myapplication.repository

import android.content.Context
import com.bignerdranch.android.myapplication.data.local.prefs.ReportPrefsKeys
import com.bignerdranch.android.myapplication.data.local.prefs.reportDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.edit


class ReportPrefRepository(private val context: Context) {

    private val appContext = context.applicationContext

    private val KEY_SELECTED = ReportPrefsKeys.SELECTED_ITEM_NAMES

    val selectedItemNamesFlow: Flow<Set<String>> =
        context.reportDataStore.data.map { prefs ->
            prefs[ReportPrefsKeys.SELECTED_ITEM_NAMES] ?: emptySet()
        }

    suspend fun saveSelectedItemNames(names: Set<String>) {
        context.reportDataStore.edit { prefs ->
            prefs[ReportPrefsKeys.SELECTED_ITEM_NAMES] = names
        }
    }

    suspend fun clearSelectedItemNames() {
        context.reportDataStore.edit { prefs ->
            prefs.remove(ReportPrefsKeys.SELECTED_ITEM_NAMES)
        }
    }


}