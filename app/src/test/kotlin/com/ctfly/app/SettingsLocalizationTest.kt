package com.ctfly.app

import com.ctfly.app.database.remote.text
import com.ctfly.app.ui.common.AppStrings
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SettingsLocalizationTest {
    private val source
        get() =
            File("src/main/assets-ui/languages.tsv").takeIf { it.exists() }
                ?: File("../app/src/main/assets-ui/languages.tsv")

    @Before
    fun load() {
        source.useLines { AppStrings.install(it) }
    }

    @Test
    fun everyTranslationHasMalayAndMatchingPlaceholders() {
        val keys = mutableSetOf<String>()
        source.forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine
            val parts = line.split('\t')
            assertEquals(line, 2, parts.size)
            assertTrue("Duplicate ${parts[0]}", keys.add(parts[0]))
            assertTrue(parts.all { it.isNotBlank() })
            val placeholders = Regex("\\{\\d+\\}").findAll(parts[0]).map { it.value }.toSet()
            for (value in parts.drop(1)) assertEquals(
                parts[0],
                placeholders,
                Regex("\\{\\d+\\}").findAll(value).map { it.value }.toSet(),
            )
        }
        assertTrue(keys.size > 400)
    }

    @Test
    fun navigationAuthenticationBookingAndFeedbackAreLocalized() {
        assertEquals("Tetapan", AppStrings.translate("Settings", "ms"))
        assertEquals("Tempah", AppStrings.translate("Reserve", "ms"))
        assertEquals("Kata laluan", AppStrings.translate("Password", "ms"))
        assertEquals("Maklum balas pelanggan", AppStrings.translate("Customer feedback", "ms"))
        assertEquals("L1 Permainan", AppStrings.translate("L1 Gaming", "ms"))
    }

    @Test
    fun dynamicLabelsPreserveNamesNumbersAndAmounts() {
        assertEquals("RM 52.00 / bilik / jam", AppStrings.translate("RM 52.00 / room / hr", "ms"))
        assertEquals("3 pemain", AppStrings.translate("3 player(s)", "ms"))
        assertEquals(
            "Selamat kembali, Ryan Lee",
            AppStrings.translate("Welcome back, Ryan Lee", "ms"),
        )
        assertEquals("12 mata · 7 lagi", AppStrings.translate("12 points · 7 left", "ms"))
        assertTrue(
            AppStrings.translate(
                    "Whole private room · 4 gaming PCs · 3 guest(s). The room price stays the same for fewer guests.",
                    "ms",
                )
                .contains("4 PC · 3 tetamu")
        )
        assertEquals("Player-089", AppStrings.translate("Player-089", "ms"))
        assertEquals("Pasukan anda: Play", AppStrings.translate("Your team: Play", "ms"))
        assertEquals("RM 1,234.50", AppStrings.translate("RM 1,234.50", "ms"))
    }

    @Test
    fun englishLeavesExistingLabelsAndBusinessValuesIntact() {
        for (text in
            listOf(
                "Settings",
                "VIP Room Squad 01",
                "4 player(s)",
                "feedback_status",
                "RM 52.00",
            )) assertEquals(text, AppStrings.translate(text, "en"))
    }
}
