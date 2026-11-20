@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ctfly.app.ui.customer.events

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
import com.ctfly.app.database.remote.obj
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.eventId
import com.ctfly.app.database.remote.registrationId
import com.ctfly.app.database.remote.text
import com.ctfly.app.domain.money
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.admin.events.EventInformation
import com.ctfly.app.ui.admin.events.EventPoster
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.Chip
import com.ctfly.app.ui.common.EmptyState
import com.ctfly.app.ui.common.Hero
import com.ctfly.app.ui.common.Lime
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.SectionTitle
import com.ctfly.app.ui.common.SurfaceCard
import com.ctfly.app.ui.common.Violet
import com.ctfly.app.ui.payment.PayButton
import java.time.Instant
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun EventsScreen(vm: AppViewModel) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Hero(
                "THE ARENA",
                "Small stage.\nBig moments.",
                "Join the bracket. Find your rivals. Play for the story.",
                "tournament_banner",
                Violet,
            )
        }
        item { SectionTitle("Upcoming & live", "One team. One shot.") }
        items(
            vm.rows("events").filter { it.text("status") !in listOf("Cancelled", "Draft") },
            key = { it.eventId() },
        ) { e ->
            SurfaceCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Chip(e.text("game"), Violet)
                    Chip(e.text("status"), Aqua)
                }
                EventPoster(e.text("poster_url"), Modifier.fillMaxWidth().height(170.dp))
                AppText(e.text("name"), style = MaterialTheme.typography.titleLarge)
                EventInformation(e)
                AppText(
                    "${money(e.optInt("fee"))} / team · ${e.optInt("capacity")} slots",
                    color = Violet,
                )
                val mine =
                    vm.rows("registrations").find {
                        it.text("event_id") == e.eventId() && it.text("status") == "Registered"
                    }
                if (mine == null && e.text("status") == "Open")
                    Button(
                        onClick = {
                            vm.set("selectedEvent", e.eventId())
                            vm.set("dialog", "event")
                        }
                    ) {
                        AppText("Enter tournament →")
                    }
                if (mine != null) {
                    AppText("Your team: ${mine.text("team")}", color = Lime)
                    if (
                        vm.rows("test_payments").any {
                            it.text("source_id") == mine.registrationId() &&
                                it.text("status") == "Succeeded"
                        }
                    )
                        Chip("Online payment", Aqua)
                    if (!mine.optBoolean("paid")) {
                        AppText("Entry fee payable at counter", color = Orange)
                        PayButton(vm, "Event", mine.registrationId())
                        TextButton(
                            onClick = { vm.action("withdraw_event", obj("id" to mine.registrationId())) },
                            enabled = !vm.busy,
                        ) {
                            AppText("Withdraw entry")
                        }
                    }
                }
                val matches = vm.rows("matches").filter { it.text("event_id") == e.eventId() }
                if (matches.isNotEmpty()) {
                    HorizontalDivider()
                    AppText("Tournament bracket", fontWeight = FontWeight.Bold)
                    matches.forEach { m ->
                        AppText(
                            "R${m.optInt("round")} · ${m.text("team_a","TBD")}  vs  ${m.text("team_b","Bye / TBD")}" +
                                (if (m.optBoolean("done")) "  → ${m.text("team_winner", m.text("winner"))}" else ""),
                            fontSize = 12.sp,
                            color = Muted,
                        )
                    }
                }
            }
        }
        if (vm.rows("events").isEmpty())
            item { EmptyState("The next match is coming", "Check back for new tournaments.") }
    }
}
