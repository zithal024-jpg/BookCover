package com.bookcover.diagnostic.reader

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class PageTurnPreferencesTest {
    @Test fun paperPageTurnDefaultsOffAndRoundTrips() {
        val preferences = MemoryPreferences()
        assertFalse("First build: the smoothed slide is the default", PAPER_PAGE_TURN_DEFAULT)
        assertFalse(preferences.paperPageTurn())
        preferences.savePaperPageTurn(true)
        assertTrue(preferences.paperPageTurn())
        assertEquals(true, preferences.values["paperPageTurn"])
        preferences.savePaperPageTurn(false)
        assertFalse(preferences.paperPageTurn())
        assertEquals(PageTurnStyle.PAPER, PageTurnStyle.of(true))
        assertEquals(PageTurnStyle.SLIDE, PageTurnStyle.of(false))
    }

    @Test fun savingTheToggleLeavesOtherReaderSettingsAlone() {
        val preferences = MemoryPreferences()
        preferences.values["volumeKeys"] = true
        preferences.values["theme"] = "Night"
        preferences.savePaperPageTurn(true)
        assertEquals(true, preferences.values["volumeKeys"])
        assertEquals("Night", preferences.values["theme"])
    }
}

/** Minimal in-memory [SharedPreferences] for JVM tests (no Android runtime needed). */
private class MemoryPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
