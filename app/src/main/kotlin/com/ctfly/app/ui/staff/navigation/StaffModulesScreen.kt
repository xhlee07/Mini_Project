package com.ctfly.app.ui.staff.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ctfly.app.R
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.tr
import com.ctfly.app.ui.common.AppIcon
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.Eyebrow
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Panel

@Composable
fun TransactionSymbol() {
    Image(painterResource(R.drawable.transactions), tr("Transactions"), Modifier.size(28.dp))
}

@Composable
fun TransactionShortcut(vm: AppViewModel) {
    IconButton(onClick = { vm.set("page", "Transactions") }) { TransactionSymbol() }
}

@Composable
fun StaffModulesScreen(vm: AppViewModel) {
    val modules =
        listOf(
            Triple(
                "Customer history",
                "Customer history",
                "Bookings, café orders, competition entries and reward redemptions.",
            ),
            Triple("Transactions", "Transactions", "Payment records, references and receipts."),
            Triple("Feedback", "Customer feedback", "Review suggestions and issues by date."),
            Triple("Events", "Competitions & offers", "Manage competitions and promo codes."),
        )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(280.dp),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("STAFF TOOLS", Aqua)
                StaffModuleHeader(vm, "Staff modules")
                AppText("Find a record or manage the next event.", color = Muted)
                Spacer(Modifier.height(8.dp))
            }
        }
        items(modules) { (page, title, description) ->
            Card(
                onClick = { vm.set("page", page) },
                colors = CardDefaults.cardColors(containerColor = Panel),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (page == "Transactions") TransactionSymbol()
                    else
                        AppIcon(
                            when (page) {
                                "Customer history" -> Icons.Outlined.History
                                "Feedback" -> Icons.Outlined.Feedback
                                else -> Icons.Outlined.EmojiEvents
                            },
                            null,
                            tint = Aqua,
                        )
                    AppText(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    AppText(description, color = Muted)
                    AppText("Open module", color = Aqua)
                }
            }
        }
    }
}
