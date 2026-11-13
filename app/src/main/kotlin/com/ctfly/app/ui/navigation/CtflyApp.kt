@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ctfly.app.ui.navigation

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
import com.ctfly.app.domain.tier
import com.ctfly.app.state.AppIcon
import com.ctfly.app.state.AppSnackbarHost
import com.ctfly.app.state.AppText
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.state.tr
import com.ctfly.app.ui.admin.ManagementScreen
import com.ctfly.app.ui.admin.StaffEventsScreen
import com.ctfly.app.ui.auth.AuthScreen
import com.ctfly.app.ui.common.Brand
import com.ctfly.app.ui.common.Eyebrow
import com.ctfly.app.ui.common.Ink
import com.ctfly.app.ui.common.Lime
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.Panel
import com.ctfly.app.ui.common.Photo
import com.ctfly.app.ui.common.Violet
import com.ctfly.app.ui.customer.AccountRestoringScreen
import com.ctfly.app.ui.customer.BookingSummary
import com.ctfly.app.ui.customer.BookingsScreen
import com.ctfly.app.ui.customer.CafeScreen
import com.ctfly.app.ui.customer.ClubScreen
import com.ctfly.app.ui.customer.DiscoverScreen
import com.ctfly.app.ui.customer.EventsScreen
import com.ctfly.app.ui.customer.PlayScreen
import com.ctfly.app.ui.customer.StaffFingerprintOffer
import com.ctfly.app.ui.dialog.AppDialogs
import com.ctfly.app.ui.notifications.PickupPermissionPrompt
import com.ctfly.app.ui.notifications.ReadyPickupBanner
import com.ctfly.app.ui.payment.CheckoutScreen
import com.ctfly.app.ui.settings.SettingsScreen
import com.ctfly.app.ui.settings.openSettings
import com.ctfly.app.ui.staff.CrewScreen
import com.ctfly.app.ui.staff.EquipmentScreen
import com.ctfly.app.ui.staff.FeedbackInboxScreen
import com.ctfly.app.ui.staff.HandoverScreen
import com.ctfly.app.ui.staff.OperationsScreen
import com.ctfly.app.ui.staff.StaffHistoryScreen
import com.ctfly.app.ui.staff.StaffModulesScreen
import com.ctfly.app.ui.staff.TransactionShortcut
import com.ctfly.app.ui.staff.TransactionsScreen
import com.ctfly.app.ui.staff.staffBackDestination
import java.time.Instant
import kotlinx.coroutines.delay
import org.json.JSONObject

data class Destination(val label: String, val icon: ImageVector)

val CustomerPages =
    listOf(
        Destination("Discover", Icons.Outlined.Explore),
        Destination("Play", Icons.Outlined.SportsEsports),
        Destination("Café", Icons.Outlined.LocalCafe),
        Destination("Events", Icons.Outlined.EmojiEvents),
        Destination("Club", Icons.Outlined.PersonOutline),
    )
val CrewPages =
    listOf(
        Destination("Operations", Icons.Outlined.Dashboard),
        Destination("My shifts", Icons.Outlined.Badge),
        Destination("Handover", Icons.Outlined.Forum),
        Destination("Equipment", Icons.Outlined.Build),
        Destination("Modules", Icons.Outlined.GridView),
    )

@Composable
fun CtflyApp(vm: AppViewModel) {
    PickupPermissionPrompt(vm)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message, vm.field("dialog"), vm.field("page")) {
        snackbar.currentSnackbarData?.dismiss()
        val text = vm.message
        if (
            vm.message.isNotEmpty() &&
                vm.field("page") != "Checkout" &&
                vm.field("dialog").isBlank()
        ) {
            snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
            if (vm.message == text) vm.dismissMessage()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh(silent = true) }
    // Lifecycle-aware refresh: no polling while the app is stopped.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, vm.hasAccount) {
        lifecycle.repeatOnLifecycleCompat {
            var elapsed = 0
            while (true) {
                delay(1000)
                vm.tick()
                elapsed++
                val pickupPending =
                    vm.rows("orders").any {
                        it.optBoolean("paid") &&
                            it.text("status") in listOf("Pending", "Preparing", "Ready")
                    }
                if (elapsed % (if (pickupPending) 5 else 30) == 0 && vm.hasAccount && !vm.busy) {
                    vm.refresh(silent = true)
                }
            }
        }
    }
    val logged = vm.hasAccount && vm.role.isNotEmpty()
    val savedPage = vm.field("page", if (vm.crew) "Operations" else "Discover")
    val page =
        when (savedPage) {
            "Tour" -> if (vm.crew) "Operations" else "Discover"
            "CCTV" -> if (vm.crew) "Modules" else "Discover"
            else -> savedPage.ifBlank { if (vm.crew) "Operations" else "Discover" }
        }
    LaunchedEffect(savedPage, page) {
        if (savedPage == "Tour" || savedPage == "CCTV") vm.set("page", page)
    }
    BackHandler(logged && !vm.staffLocked) {
        when {
            vm.field("dialog").isNotBlank() -> vm.set("dialog", "")
            vm.crew && staffBackDestination(page) != null ->
                vm.set("page", staffBackDestination(page)!!)
            page != (if (vm.crew) "Operations" else "Discover") ->
                vm.set("page", if (vm.crew) "Operations" else "Discover")
            else -> vm.set("dialog", "logout")
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val wide = maxWidth >= 840.dp
        val compactLandscape = maxWidth >= 600.dp && maxHeight < 500.dp
        val railNavigation = wide || compactLandscape
        val narrowStaffHeader = vm.admin && maxWidth < 360.dp
        if (vm.staffResuming) {
            Box(Modifier.fillMaxSize().background(Ink))
        } else if (vm.restoringAccount) {
            AccountRestoringScreen(vm)
        } else if (page == "Settings") {
            BackHandler { vm.set("page", vm.field("settingsReturn", "Discover")) }
            SettingsScreen(vm) { vm.set("page", vm.field("settingsReturn", "Discover")) }
            AppSnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        } else if (!logged) {
            Box(Modifier.fillMaxSize()) {
                AuthScreen(vm, wide)
                IconButton(
                    onClick = { vm.openSettings() },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding(),
                ) {
                    AppIcon(Icons.Outlined.Settings, tr("Settings"))
                }
                AppSnackbarHost(
                    snackbar,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
            }
        } else if (page == "Checkout") {
            CheckoutScreen(vm) { vm.leaveCheckout() }
        } else {
            val pages = if (vm.crew) CrewPages else CustomerPages
            Scaffold(
                containerColor = Ink,
                snackbarHost = { AppSnackbarHost(snackbar) },
                bottomBar = {
                    if (!railNavigation)
                        NavigationBar(containerColor = Panel) {
                            pages.forEach { d ->
                                NavigationBarItem(
                                    selected =
                                        page == d.label ||
                                            (vm.crew &&
                                                d.label == "Modules" &&
                                                page in
                                                    listOf(
                                                        "Customer history",
                                                        "Transactions",
                                                        "Feedback",
                                                        "Events",
                                                    )),
                                    onClick = { vm.set("page", d.label) },
                                    icon = { AppIcon(d.icon, d.label) },
                                    label = { AppText(d.label, fontSize = 10.sp) },
                                )
                            }
                        }
                },
            ) { insets ->
                Row(Modifier.fillMaxSize().padding(insets)) {
                    if (railNavigation)
                        NavigationRail(
                            containerColor = Panel,
                            windowInsets =
                                if (compactLandscape) WindowInsets(0, 0, 0, 0)
                                else NavigationRailDefaults.windowInsets,
                            modifier =
                                Modifier.fillMaxHeight()
                                    .width(if (compactLandscape) 72.dp else 96.dp),
                            header = {
                                Brand(
                                    Modifier.padding(
                                        vertical = if (compactLandscape) 4.dp else 22.dp
                                    ),
                                    compact = true,
                                )
                            },
                        ) {
                            pages.forEach { d ->
                                NavigationRailItem(
                                    page == d.label ||
                                        (vm.crew &&
                                            d.label == "Modules" &&
                                            page in
                                                listOf(
                                                    "Customer history",
                                                    "Transactions",
                                                    "Feedback",
                                                    "Events",
                                                )),
                                    { vm.set("page", d.label) },
                                    icon = { AppIcon(d.icon, d.label) },
                                    label =
                                        if (compactLandscape) null
                                        else ({ AppText(d.label, fontSize = 10.sp) }),
                                    modifier =
                                        if (compactLandscape) Modifier.height(48.dp) else Modifier,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (!compactLandscape)
                                AppText(
                                    "MY · 18+",
                                    color = Muted,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(14.dp),
                                )
                        }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(
                                    horizontal = if (railNavigation) 20.dp else 10.dp,
                                    vertical = if (compactLandscape) 4.dp else 12.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!railNavigation && !narrowStaffHeader) Brand(compact = true)
                            else if (wide)
                                AppText(
                                    if (vm.crew) "CREW WORKSPACE" else "GAMING • SOCIAL • CULTURE",
                                    color = Muted,
                                    fontSize = 11.sp,
                                    letterSpacing = 1.sp,
                                )
                            Spacer(Modifier.weight(1f))
                            if (vm.admin)
                                IconButton(onClick = { vm.set("page", "Manage") }) {
                                    AppIcon(Icons.Outlined.Tune, "Management")
                                }
                            if (!vm.crew)
                                IconButton(onClick = { vm.set("page", "Bookings") }) {
                                    AppIcon(Icons.Outlined.ConfirmationNumber, "My bookings")
                                }
                            if (vm.crew) TransactionShortcut(vm)
                            if (vm.crew)
                                IconButton(onClick = { vm.set("page", "Feedback") }) {
                                    AppIcon(Icons.Outlined.Feedback, tr("Customer feedback"))
                                }
                            IconButton(onClick = { vm.openSettings() }) {
                                AppIcon(Icons.Outlined.Settings, tr("Settings"))
                            }
                            IconButton(onClick = { vm.refresh() }) {
                                AppIcon(Icons.Outlined.Refresh, "Refresh")
                            }
                            if (vm.crew)
                                IconButton(onClick = { vm.set("dialog", "logout") }) {
                                    AppIcon(Icons.Outlined.Logout, "Sign out")
                                }
                        }
                        if (vm.loading)
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lime)
                        if (vm.loadError.isNotEmpty())
                            Surface(color = Orange.copy(alpha = .12f)) {
                                AppText(
                                    vm.loadError,
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    color = Orange,
                                    fontSize = 12.sp,
                                )
                            }
                        ReadyPickupBanner(vm)
                        Row(Modifier.weight(1f)) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                key(page) {
                                    when {
                                        vm.crew ->
                                            when (page) {
                                                "My shifts" -> CrewScreen(vm)
                                                "Handover" -> HandoverScreen(vm)
                                                "Equipment" -> EquipmentScreen(vm)
                                                "Feedback" -> FeedbackInboxScreen(vm)
                                                "Transactions" -> TransactionsScreen(vm)
                                                "Customer history" -> StaffHistoryScreen(vm)
                                                "Modules" -> StaffModulesScreen(vm)
                                                "Events" -> StaffEventsScreen(vm)
                                                "Manage" ->
                                                    if (vm.admin) ManagementScreen(vm)
                                                    else OperationsScreen(vm)
                                                else -> OperationsScreen(vm)
                                            }
                                        else ->
                                            when (page) {
                                                "Play" -> PlayScreen(vm)
                                                "Café" -> CafeScreen(vm)
                                                "Events" -> EventsScreen(vm)
                                                "Club" -> ClubScreen(vm)
                                                "Bookings" -> BookingsScreen(vm)
                                                else -> DiscoverScreen(vm)
                                            }
                                    }
                                }
                            }
                            if (wide && !vm.crew)
                                Column(
                                    Modifier.width(280.dp)
                                        .fillMaxHeight()
                                        .background(Panel.copy(alpha = .4f))
                                        .padding(20.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(20.dp),
                                ) {
                                    Eyebrow("YOUR NEXT SESSION")
                                    BookingSummary(vm)
                                    HorizontalDivider(color = Muted.copy(alpha = .2f))
                                    Eyebrow("CLUB STATUS", Violet)
                                    AppText(
                                        if (logged) vm.profile.text("name") else "Welcome, player",
                                        fontSize = 23.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    AppText(
                                        if (logged)
                                            "${tier(vm.profile.optInt("spent"))} member · ${vm.profile.optInt("points")} points"
                                        else "Make room for a better game.",
                                        color = Muted,
                                    )
                                    Button(
                                        onClick = {
                                            if (logged) vm.set("page", "Club")
                                            else vm.set("explore", "false")
                                        }
                                    ) {
                                        AppText(if (logged) "Your player pass" else "Sign in")
                                    }
                                    Photo("neon_cafe", Modifier.fillMaxWidth().height(150.dp))
                                    AppText(
                                        "Good games.\nGreat company.",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                }
                        }
                    }
                }
            }
        }
        if (!vm.staffResuming && !vm.restoringAccount) AppDialogs(vm)
        if (
            !vm.staffResuming &&
                !vm.restoringAccount &&
                vm.validationError.isNotBlank() &&
                !vm.offerFingerprint &&
                vm.field("dialog").isBlank()
        )
            AlertDialog(
                onDismissRequest = { vm.clearValidationError() },
                title = { AppText("Please check your details") },
                text = { AppText(vm.validationError) },
                confirmButton = {
                    TextButton(onClick = { vm.clearValidationError() }) { AppText("OK") }
                },
            )
    }
    StaffFingerprintOffer(vm)
}

private suspend fun Lifecycle.repeatOnLifecycleCompat(
    block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
) = repeatOnLifecycle(Lifecycle.State.STARTED, block)
