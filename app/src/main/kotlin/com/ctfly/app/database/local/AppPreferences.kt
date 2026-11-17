package com.ctfly.app.database.local

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

class AppPreferences(context: Context) {
    private val store = context.getSharedPreferences("ctfly_appearance", Context.MODE_PRIVATE)
    var language by
        mutableStateOf(
            store.getString("language", "en").orEmpty().let {
                if (it in listOf("en", "ms")) it else "en"
            }
        )
        private set

    var background by
        mutableStateOf(
            store.getString("background", "charcoal").orEmpty().let {
                if (it in palettes) it else "charcoal"
            }
        )
        private set

    fun chooseLanguage(value: String) {
        require(value in listOf("en", "ms"))
        language = value
        store.edit().putString("language", value).apply()
    }

    fun chooseBackground(value: String) {
        require(value in palettes)
        background = value
        store.edit().putString("background", value).apply()
    }

    data class Palette(val name: String, val background: Color, val panel: Color)

    companion object {
        val palettes =
            linkedMapOf(
                "charcoal" to Palette("Charcoal", Color(0xFF0B1013), Color(0xFF151D22)),
                "navy" to Palette("Midnight blue", Color(0xFF0B1731), Color(0xFF162848)),
                "forest" to Palette("Forest green", Color(0xFF0A241E), Color(0xFF153B30)),
                "plum" to Palette("Plum purple", Color(0xFF24132C), Color(0xFF3A2245)),
            )
    }
}
