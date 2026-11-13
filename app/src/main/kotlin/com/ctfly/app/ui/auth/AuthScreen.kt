@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ctfly.app.ui.auth

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
import com.ctfly.app.BuildConfig
import com.ctfly.app.state.AppText
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.Brand
import com.ctfly.app.ui.common.DateFields
import com.ctfly.app.ui.common.Eyebrow
import com.ctfly.app.ui.common.Ink
import com.ctfly.app.ui.common.Input
import com.ctfly.app.ui.common.Lime
import com.ctfly.app.ui.common.Muted
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.Photo
import com.ctfly.app.ui.customer.StaffFingerprintSignIn
import java.time.Instant
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun AuthScreen(vm: AppViewModel, wide: Boolean) {
    val mode = vm.field("authMode", "login")
    val portal = if (BuildConfig.STAFF_APP) "staff" else "customer"
    Row(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        if (wide)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Photo("neon_arena", Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Ink.copy(alpha = .3f), Ink)
                            )
                        )
                )
                Column(
                    Modifier.align(Alignment.BottomStart).padding(48.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Brand()
                    Eyebrow(
                        if (BuildConfig.STAFF_APP) "THE PEOPLE BEHIND THE PLAY"
                        else "YOUR AFTER-HOURS UPGRADE"
                    )
                    AppText(
                        if (BuildConfig.STAFF_APP) "One team.\nEvery shift.\nReady to go."
                        else "Log in.\nLevel up.\nStay a while.",
                        fontSize = 52.sp,
                        lineHeight = 57.sp,
                        fontWeight = FontWeight.Black,
                    )
                    AppText(
                        if (BuildConfig.STAFF_APP) "Operations, people and the club — together."
                        else "Premium rigs. Real competition. Your people.",
                        color = Muted,
                    )
                }
            }
        Column(
            Modifier.weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 28.dp)
                .widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Brand()
            Spacer(Modifier.height(4.dp))
            Eyebrow(
                if (portal == "staff") "THE PEOPLE BEHIND THE PLAY"
                else "WELCOME TO YOUR NEXT LEVEL",
                if (portal == "staff") Aqua else Lime,
            )
            AppText(
                when (mode) {
                    "register" -> "Join the club."
                    "forgot" -> "Let’s get you back."
                    "verify" -> "Check your inbox."
                    "reset" -> "A fresh start."
                    else -> if (portal == "staff") "Your shift starts here." else "Good to see you."
                },
                style = MaterialTheme.typography.headlineLarge,
            )
            AppText(
                when (mode) {
                    "register" -> "Create your 18+ player account."
                    "verify" -> "Enter the verification code sent to your email."
                    "forgot" -> "We’ll send a recovery code to your registered email."
                    "reset" -> "Choose a new password for your CTFLY account."
                    else ->
                        if (portal == "staff") "Staff and managers only. Your tools, in one place."
                        else "Sign in. Pick your space. Make it a good night."
                },
                color = Muted,
            )
            if (mode != "reset") Input("Email address", vm.field("email"), { vm.set("email", it) })
            if (mode == "register") {
                Input("Full name", vm.field("name"), { vm.set("name", it) })
                Input("Phone number", vm.field("phone"), { vm.set("phone", it) })
                DateFields(
                    "Date of birth · 18+",
                    vm.field("dob"),
                    { vm.set("dob", it) },
                    birth = true,
                )
            }
            if (mode in listOf("login", "register", "reset"))
                Input("Password", vm.password, { vm.password = it }, password = true)
            if (mode in listOf("register", "reset"))
                Input(
                    "Confirm password",
                    vm.confirmPassword,
                    { vm.confirmPassword = it },
                    password = true,
                )
            if (mode == "verify")
                Input("Email code", vm.field("code"), { vm.set("code", it) }, numeric = true)
            if (mode == "login")
                TextButton(
                    onClick = { vm.set("authMode", "forgot") },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    AppText("Forgot password?")
                }
            Button(
                onClick = { vm.authenticate() },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (vm.busy)
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Ink)
                else
                    AppText(
                        when (mode) {
                            "register" -> "Create account"
                            "verify" -> "Verify code"
                            "forgot" -> "Send recovery code"
                            "reset" -> "Save new password"
                            else ->
                                if (BuildConfig.STAFF_APP) "Start your shift  →"
                                else "Let’s play  →"
                        },
                        fontWeight = FontWeight.Bold,
                    )
            }
            if (mode == "login" && portal == "staff") StaffFingerprintSignIn(vm)
            if (mode == "login" && portal == "customer")
                OutlinedButton(
                    onClick = { vm.set("authMode", "register") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AppText("New here? Join CTFLY")
                }
            if (mode != "login")
                TextButton(
                    onClick = {
                        vm.set("authMode", "login")
                        vm.set("code", "")
                        vm.password = ""
                        vm.confirmPassword = ""
                    }
                ) {
                    AppText("Back to sign in")
                }
            if (mode == "verify")
                TextButton(onClick = { vm.resend() }, enabled = !vm.busy) { AppText("Resend code") }
            if (vm.loadError.isNotEmpty()) AppText(vm.loadError, color = Orange, fontSize = 12.sp)
            AppText(
                if (BuildConfig.STAFF_APP)
                    "Staff CTFLY · Malaysia\nStaff and administrators only. Contact your manager for access."
                else
                    "CTFLY Gaming Cafe · Malaysia\nBookings available to players aged 18 and above.",
                color = Muted,
                fontSize = 11.sp,
            )
        }
    }
}
