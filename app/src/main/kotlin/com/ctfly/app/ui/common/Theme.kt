@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ctfly.app.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ctfly.app.BuildConfig
import com.ctfly.app.data.text
import com.ctfly.app.state.AppPreferences
import com.ctfly.app.state.AppStrings
import com.ctfly.app.state.LocalAppLanguage
import com.ctfly.app.state.LocalAppPreferences
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Ink: Color
    @Composable
    get() = AppPreferences.palettes.getValue(LocalAppPreferences.current.background).background
val Panel: Color
    @Composable
    get() = AppPreferences.palettes.getValue(LocalAppPreferences.current.background).panel
val Muted = Color(0xFF9DAEB8)
val Lime = Color(0xFFC6F56B)
val Violet = Color(0xFFB5A0FF)
val Aqua = Color(0xFF7EDFCC)
val Orange = Color(0xFFFFBA85)

@Composable
fun CtflyTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember {
        AppStrings.load(context)
        AppPreferences(context)
    }
    CompositionLocalProvider(
        LocalAppPreferences provides preferences,
        LocalAppLanguage provides preferences.language,
    ) {
        MaterialTheme(
            colorScheme =
                darkColorScheme(
                    primary = if (BuildConfig.STAFF_APP) Aqua else Lime,
                    onPrimary = Ink,
                    secondary = Violet,
                    tertiary = Aqua,
                    background = Ink,
                    surface = Panel,
                    onSurface = Color(0xFFF4F7F8),
                    onSurfaceVariant = Muted,
                    outline = Color(0xFF39464D),
                ),
            typography =
                Typography(
                    headlineLarge =
                        androidx.compose.ui.text.TextStyle(
                            fontSize = 36.sp,
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.ExtraBold,
                        ),
                    headlineMedium =
                        androidx.compose.ui.text.TextStyle(
                            fontSize = 28.sp,
                            lineHeight = 34.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    titleLarge =
                        androidx.compose.ui.text.TextStyle(
                            fontSize = 21.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                ),
            content = { Surface(color = Ink, contentColor = Color(0xFFF4F7F8)) { content() } },
        )
    }
}
