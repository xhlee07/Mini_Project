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
import com.ctfly.app.R
import com.ctfly.app.state.AppIcon
import com.ctfly.app.state.AppText
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Photo(name: String, modifier: Modifier = Modifier, description: String? = null) {
    if (name == "game_card") {
        Box(
            modifier.background(Brush.linearGradient(listOf(Color(0xFF243C54), Ink))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(
                    Icons.Outlined.SportsEsports,
                    null,
                    tint = Lime,
                    modifier = Modifier.size(38.dp),
                )
                AppText(
                    "GAME CREDIT",
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = Color.White,
                )
            }
        }
        return
    }
    val context = LocalContext.current
    val id =
        when (name) {
            "neon_arena" -> R.drawable.neon_arena
            "neon_cafe" -> R.drawable.neon_cafe
            "pool_club" -> R.drawable.pool_club
            "kart_circuit" -> R.drawable.kart_circuit
            "tournament_banner" -> R.drawable.tournament_banner
            "iced_coffee" -> R.drawable.iced_coffee
            "iced_lemon_tea" -> R.drawable.iced_lemon_tea
            "energy_drink" -> R.drawable.energy_drink
            "mineral_water" -> R.drawable.mineral_water
            "chicken_burger" -> R.drawable.chicken_burger
            "fries" -> R.drawable.fries
            "cup_noodles" -> R.drawable.cup_noodles
            "potato_chips" -> R.drawable.potato_chips
            else -> 0
        }
    val bitmap by
        produceState<ImageBitmap?>(null, id) {
            value =
                withContext(Dispatchers.IO) {
                    if (id == 0) null
                    else {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeResource(context.resources, id, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 1000) sample *= 2
                        BitmapFactory.decodeResource(
                                context.resources,
                                id,
                                BitmapFactory.Options().apply { inSampleSize = sample },
                            )
                            ?.asImageBitmap()
                    }
                }
        }
    if (bitmap != null) Image(bitmap!!, description, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Brush.linearGradient(listOf(Panel, Color(0xFF34433A)))))
}

@Composable
fun Brand(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(32.dp)) {
            val p =
                Path().apply {
                    moveTo(0f, size.height * .15f)
                    lineTo(size.width * .43f, size.height * .4f)
                    lineTo(size.width, size.height * .05f)
                    lineTo(size.width * .65f, size.height * .7f)
                    lineTo(size.width * .35f, size.height)
                    lineTo(size.width * .3f, size.height * .55f)
                    close()
                }
            drawPath(p, if (BuildConfig.STAFF_APP) Aqua else Lime)
        }
        if (!compact)
            Column {
                AppText(
                    if (BuildConfig.STAFF_APP) "Staff CTFLY" else "CTFLY",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
                if (!BuildConfig.STAFF_APP) AppText("Gaming Cafe", fontSize = 14.sp, color = Muted)
            }
    }
}
