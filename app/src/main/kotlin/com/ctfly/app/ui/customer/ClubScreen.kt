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
import com.ctfly.app.data.obj
import com.ctfly.app.data.rows
import com.ctfly.app.data.text
import com.ctfly.app.domain.money
import com.ctfly.app.domain.tier
import com.ctfly.app.state.AppIcon
import com.ctfly.app.state.AppText
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.state.tr
import com.ctfly.app.ui.common.Brand
import com.ctfly.app.ui.common.Chip
import com.ctfly.app.ui.common.Eyebrow
import com.ctfly.app.ui.common.Lime
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.SectionTitle
import com.ctfly.app.ui.common.SurfaceCard
import com.ctfly.app.ui.common.Violet
import java.time.Instant
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun ClubScreen(vm: AppViewModel) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Eyebrow("MEMBERS GET MORE", Violet)
            AppText("Your player pass.", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            SurfaceCard(Modifier.background(Lime.copy(alpha = .06f), RoundedCornerShape(22.dp))) {
                Brand()
                AppText(
                    vm.profile.text("name", "Join the club"),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Chip("${tier(vm.profile.optInt("spent"))} member", Violet)
                AppText(
                    "${vm.profile.optInt("points")} points",
                    color = Lime,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                val spent = vm.profile.optInt("spent")
                val next = if (spent < 30000) 30000 else 100000
                LinearProgressIndicator(
                    progress = { (spent.toFloat() / next).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                AppText(
                    if (spent >= 100000) "Gold unlocked · 10% off your next bill"
                    else
                        "${money(next-spent)} to ${if(next==30000)"Silver · 5%" else "Gold · 10%"} discount",
                    color = Muted,
                    fontSize = 12.sp,
                )
                AppText(
                    "Earn 1 point per RM 1 paid. Tier upgrades apply to the next payment.",
                    fontSize = 11.sp,
                    color = Muted,
                )
                Row {
                    TextButton(onClick = { vm.set("dialog", "profile") }) {
                        AppText("Edit profile")
                    }
                    TextButton(
                        onClick = {
                            vm.set("page", "Bookings")
                            vm.set("bookingTab", "Receipts")
                        }
                    ) {
                        AppText("Receipts")
                    }
                }
            }
        }
        if (vm.testPaymentsEnabled)
            item {
                SurfaceCard {
                    AppText("Demo membership", fontWeight = FontWeight.Bold)
                    AppText(
                        tr("Demo points: %s").format(vm.profile.optInt("test_points")),
                        color = Lime,
                        localize = false,
                    )
                    AppText(
                        tr("Test spend: %s").format(money(vm.profile.optInt("test_spent"))),
                        color = Muted,
                        localize = false,
                    )
                    AppText("Demo points cannot be exchanged for real rewards.", color = Muted)
                }
            }
        item { SectionTitle("Good things, earned.", "Exchange points for club favourites") }
        items(
            vm.rows("rewards").filter { it.optBoolean("active", true) },
            key = { it.text("id") },
        ) { r ->
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(
                        Icons.Outlined.Redeem,
                        null,
                        tint = Violet,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        AppText(r.text("name"), fontWeight = FontWeight.Bold)
                        AppText(
                            "${r.optInt("points")} points · ${r.optInt("stock")} left",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { vm.action("redeem", obj("id" to r.text("id"))) },
                    enabled =
                        !vm.busy &&
                            vm.profile.optInt("points") >= r.optInt("points") &&
                            r.optInt("stock") > 0,
                ) {
                    AppText("Redeem reward")
                }
            }
        }
        if (vm.rows("redemptions").isNotEmpty())
            item { SectionTitle("Your rewards", "Show your voucher at the counter") }
        items(vm.rows("redemptions")) { r ->
            SurfaceCard {
                AppText(r.text("name"), fontWeight = FontWeight.Bold)
                Chip(r.text("status"))
                AppText("Voucher ${r.text("id").take(8)}", color = Muted)
            }
        }
        if (vm.rows("points_transactions").isNotEmpty()) item { SectionTitle("Points activity") }
        items(vm.rows("points_transactions").take(20)) { p ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AppText(p.text("reason"), color = Muted, modifier = Modifier.weight(1f))
                AppText("${p.optInt("points")} pts", color = Lime)
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    if (vm.hasAccount) vm.set("dialog", "logout") else vm.set("explore", "false")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppText(if (vm.hasAccount) "Sign out" else "Sign in to join")
            }
        }
    }
}
