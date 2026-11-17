@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ctfly.app.ui.customer.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.repeatOnLifecycle
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.text
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.AppIcon
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.Chip
import com.ctfly.app.ui.common.Eyebrow
import com.ctfly.app.ui.common.Hero
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.SectionTitle
import com.ctfly.app.ui.common.Stat
import com.ctfly.app.ui.common.SurfaceCard
import com.ctfly.app.ui.common.Violet
import com.ctfly.app.ui.customer.bookings.BookingCard
import java.time.Instant
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun DiscoverScreen(vm: AppViewModel) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("MAKE TONIGHT COUNT")
                    AppText(
                        "Hey, ${vm.profile.text("name","player").substringBefore(" ") }.",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                Chip("18+ CLUB")
            }
        }
        item {
            Hero(
                "THE CTFLY EXPERIENCE",
                "Your game.\nYour space.",
                "144 Hz to 240 Hz. Zero distractions. Find your favourite seat.",
                button = "Find a PC",
            ) {
                vm.set("playKind", "PC")
                vm.set("page", "Play")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(
                    vm.rows("resources")
                        .count { it.text("kind") == "PC" && it.text("status") == "Available" }
                        .toString(),
                    "Gaming PCs",
                    modifier = Modifier.weight(1f),
                )
                Stat("4", "Ways to unwind", Violet, Modifier.weight(1f))
            }
        }
        item { SectionTitle("Your next move", "Everything for a great session") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SurfaceCard(
                    Modifier.weight(1f).clickable {
                        vm.set("playKind", "Pool")
                        vm.set("page", "Play")
                    }
                ) {
                    AppIcon(Icons.Outlined.SportsEsports, null, tint = Violet)
                    AppText("Beyond the PC", fontWeight = FontWeight.Bold)
                    AppText("Pool, snooker & more", color = Muted, fontSize = 12.sp)
                }
                SurfaceCard(Modifier.weight(1f).clickable { vm.set("page", "Café") }) {
                    AppIcon(Icons.Outlined.LocalCafe, null, tint = Orange)
                    AppText("Fuel your play", fontWeight = FontWeight.Bold)
                    AppText("Bites & cold drinks", color = Muted, fontSize = 12.sp)
                }
            }
        }
        item {
            SectionTitle("On your calendar", action = "View all") { vm.set("page", "Bookings") }
            Spacer(Modifier.height(12.dp))
            BookingSummary(vm)
        }
        item {
            Hero(
                "COMPETE TOGETHER",
                "Bring your\nA-game.",
                "Find your next tournament and meet your next rival.",
                "tournament_banner",
                Violet,
                "Explore events",
            ) {
                vm.set("page", "Events")
            }
        }
    }
}

@Composable
fun BookingSummary(vm: AppViewModel) {
    val b =
        vm.rows("bookings")
            .filter { it.text("status") in listOf("Reserved", "Confirmed", "Active") }
            .minByOrNull { it.text("starts_at") }
    if (b == null)
        SurfaceCard {
            AppText("A good night starts here.", fontWeight = FontWeight.Bold)
            AppText("Pick a PC or bring friends to the lounge.", color = Muted, fontSize = 13.sp)
            TextButton(onClick = { vm.set("page", "Play") }) { AppText("Find your space →") }
        }
    else BookingCard(vm, b)
}
