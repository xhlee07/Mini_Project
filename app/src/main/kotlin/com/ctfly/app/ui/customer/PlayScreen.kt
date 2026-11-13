@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ctfly.app.ui.customer

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
import com.ctfly.app.data.rows
import com.ctfly.app.data.text
import com.ctfly.app.domain.VenueArea
import com.ctfly.app.domain.VenueDirectory
import com.ctfly.app.domain.bookingPrice
import com.ctfly.app.domain.money
import com.ctfly.app.state.AppIcon
import com.ctfly.app.state.AppText
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.ActivityArt
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.Chip
import com.ctfly.app.ui.common.EmptyState
import com.ctfly.app.ui.common.Eyebrow
import com.ctfly.app.ui.common.FilterRow
import com.ctfly.app.ui.common.Ink
import com.ctfly.app.ui.common.Lime
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.Photo
import com.ctfly.app.ui.common.SurfaceCard
import com.ctfly.app.ui.common.Violet
import java.time.Instant
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun PlayScreen(vm: AppViewModel) {
    val kind = vm.field("playKind", "PC")
    val zone = vm.field("zone", "All")
    val roomPlayers = vm.field("roomPlayers", "1").toIntOrNull()?.coerceIn(1, 4) ?: 1
    val area = VenueDirectory.area(kind)
    val count =
        vm.rows("resources").count { it.text("kind") == kind && it.text("status") != "Retired" }
    val resources =
        vm.rows("resources")
            .filter {
                it.text("kind") == kind &&
                    (kind !in listOf("PC", "Racing") || zone == "All" || it.text("zone") == zone) &&
                    (kind != "VIPRoom" || it.optInt("capacity", 1) >= roomPlayers) &&
                    it.text("status") != "Retired"
            }
            .sortedBy { if (kind == "VIPRoom") it.optInt("capacity", 1) else 0 }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (kind == "PC") 155.dp else 245.dp),
        modifier = Modifier.fillMaxSize().testTag("play-list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "play-header", span = { GridItemSpan(maxLineSpan) }) {
            Column(
                Modifier.fillMaxWidth().testTag("play-header"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(126.dp)) {
                    Photo(
                        when (area) {
                            VenueArea.BILLIARDS -> "pool_club"
                            VenueArea.OUTDOOR -> "kart_circuit"
                            VenueArea.LOUNGE -> "neon_cafe"
                            else -> "neon_arena"
                        },
                        Modifier.fillMaxSize(),
                        "${area.label} concept",
                    )
                    Box(
                        Modifier.fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(Ink.copy(alpha = .94f), Ink.copy(alpha = .3f))
                                )
                            )
                    )
                    Column(
                        Modifier.padding(18.dp).align(Alignment.CenterStart),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Eyebrow(
                            "${area.floor} / ${area.label.uppercase()}",
                            if (area == VenueArea.OUTDOOR) Orange else Lime,
                        )
                        AppText(
                            when (area) {
                                VenueArea.GAMING -> "Your next great game."
                                VenueArea.BILLIARDS -> "A whole floor for your crew."
                                VenueArea.LOUNGE -> "Find your kind of play."
                                VenueArea.OUTDOOR -> "Take it outside."
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        AppText(
                            "$count ${VenueDirectory.label(kind).lowercase()} facilities · Choose your time",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                FilterRow(
                    VenueDirectory.kinds.map { VenueDirectory.label(it) },
                    VenueDirectory.label(kind),
                ) { label ->
                    vm.set(
                        "playKind",
                        VenueDirectory.kinds.first { VenueDirectory.label(it) == label },
                    )
                    vm.set("zone", "All")
                }
                if (kind in listOf("PC", "Racing"))
                    FilterRow(listOf("All", "Standard", "VIP"), zone) { vm.set("zone", it) }
                if (kind == "VIPRoom")
                    FilterRow(
                        listOf("1 player", "2 players", "3 players", "4 players"),
                        if (roomPlayers == 1) "1 player" else "$roomPlayers players",
                    ) {
                        vm.set("roomPlayers", it.substringBefore(' '))
                        vm.set("bookGuests", it.substringBefore(' '))
                    }
                AppText(
                    if (kind == "GoKart")
                        "Kart sessions: 10, 15, 30 or 60 minutes · One driver per kart"
                    else if (kind == "VIPRoom")
                        "Choose your group · Price for the whole room · 3 guests use a 4-PC room"
                    else if (kind == "Racing")
                        "Standard or private VIP cockpit · One driver per simulator"
                    else "Rates per hour · Availability confirmed when you reserve",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
        }
        items(resources, key = { it.text("id") }) { r ->
            SurfaceCard {
                if (kind in listOf("PC", "VIPRoom")) {
                    AppIcon(
                        if (kind == "VIPRoom") Icons.Outlined.MeetingRoom
                        else Icons.Outlined.DesktopWindows,
                        null,
                        Modifier.size(44.dp),
                        tint = if (r.text("zone") == "VIP") Violet else Lime,
                    )
                    Chip(r.text("zone"), if (r.text("zone") == "VIP") Violet else Lime)
                } else {
                    ActivityArt(kind, Modifier.fillMaxWidth().height(120.dp))
                    if (kind == "Racing")
                        Chip(r.text("zone"), if (r.text("zone") == "VIP") Violet else Aqua)
                }
                AppText(r.text("name"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                AppText(
                    "${area.floor} · ${area.label} · Up to ${r.optInt("capacity",1)} player${if(r.optInt("capacity",1)>1)"s" else ""}",
                    color = Aqua,
                    fontSize = 11.sp,
                )
                AppText(r.text("description"), color = Muted, fontSize = 12.sp)
                AppText(
                    if (kind == "GoKart") "${money(bookingPrice(r.optInt("rate"),15))} / 15 min"
                    else if (kind == "VIPRoom") "${money(r.optInt("rate"))} / room / hr"
                    else "${money(r.optInt("rate"))} / hr",
                    color = if (kind == "PC") Lime else Violet,
                    fontWeight = FontWeight.Bold,
                )
                Button(
                    onClick = {
                        vm.resetBookingDraft(r.text("id"))
                        vm.set("bookGuests", if (kind == "VIPRoom") roomPlayers.toString() else "1")
                        vm.set("dialog", "book")
                    },
                    enabled = r.text("status") == "Available",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AppText(if (r.text("status") == "Available") "Reserve" else r.text("status"))
                }
            }
        }
        if (resources.isEmpty())
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    "No spaces to show",
                    if (vm.loading) "Loading your club…"
                    else if (kind == "VIPRoom")
                        "Private rooms need the 1.7 cloud inventory update. Then refresh Play."
                    else "New spaces are coming. Try another activity or refresh.",
                )
            }
    }
}
