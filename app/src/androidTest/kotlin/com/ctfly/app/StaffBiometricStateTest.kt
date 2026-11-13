package com.ctfly.app

import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ctfly.app.auth.StaffBiometrics
import com.ctfly.app.auth.StaffSession
import com.ctfly.app.data.ClubGateway
import com.ctfly.app.data.CtflyApi
import com.ctfly.app.data.obj
import com.ctfly.app.data.text
import com.ctfly.app.domain.SessionRules
import com.ctfly.app.state.AppViewModel
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Emulator integration: enroll a test fingerprint, then inject finger touches at each prompt. */
class StaffBiometricStateTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun optInAndCryptographicUnlock() {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CtflyApplication
        Assume.assumeTrue(StaffBiometrics(app).available())
        app.getSharedPreferences("ctfly_staff_biometrics", 0).edit().clear().commit()
        app.getSharedPreferences("ctfly_appearance", 0).edit().clear().commit()
        val fixture =
            JSONObject(
                instrumentation.context.assets.open("staff.json").bufferedReader().use {
                    it.readText()
                }
            )
        val base = FixtureGateway(fixture, false)
        var registered: JSONObject? = null
        var publicKey = ""
        val challenges = mutableMapOf<String, String>()
        fun verified(signature: String, payload: String): Boolean {
            val key =
                KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(Base64.decode(publicKey, Base64.NO_WRAP)))
            return Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(payload.toByteArray())
                verify(Base64.decode(signature, Base64.NO_WRAP))
            }
        }
        app.gatewayFactory = {
            object : ClubGateway by base {
                override val cachedSnapshot
                    get() = if (signedIn) fixture else JSONObject()

                override suspend fun rpc(
                    name: String,
                    body: JSONObject,
                    authenticated: Boolean,
                ): JSONObject {
                    delay(50)
                    return fixture
                }

                override suspend fun staffDevice(
                    body: JSONObject,
                    authenticated: Boolean,
                ): JSONObject {
                    delay(30)
                    if (authenticated) check(signedIn)
                    when (body.text("operation")) {
                        "register" -> {
                            publicKey = body.text("public_key")
                            check(
                                verified(
                                    body.text("signature"),
                                    "ctfly-enroll:${base.accountId}:${body.text("device_id")}",
                                )
                            )
                            registered =
                                obj(
                                    "id" to body.text("device_id"),
                                    "staff_name" to body.text("staff_name"),
                                    "device_name" to body.text("device_name"),
                                )
                        }
                        "remove" -> registered = null
                        "challenge" -> {
                            check(
                                registered != null &&
                                    registered!!.text("id") == body.text("device_id")
                            )
                            val id = java.util.UUID.randomUUID().toString()
                            val nonce = java.util.UUID.randomUUID().toString()
                            challenges[id] = nonce
                            return obj("challenge_id" to id, "nonce" to nonce)
                        }
                    }
                    return obj(
                        "devices" to JSONArray().apply { registered?.let { put(it) } },
                        "limit" to 5,
                    )
                }

                override suspend fun loginDevice(body: JSONObject) {
                    check(registered != null)
                    val id = body.text("device_id")
                    val challenge = body.text("challenge_id")
                    val nonce = challenges.remove(challenge) ?: error("Expired")
                    check(registered!!.text("id") == id)
                    check(verified(body.text("signature"), "ctfly-login:$id:$challenge:$nonce"))
                    base.login("crew@example.com", "fixture-password")
                }
            }
        }
        var wall = System.currentTimeMillis()
        var uptime = android.os.SystemClock.elapsedRealtime()
        app.staffSessionFactory = { StaffSession(it, { wall }, { uptime }) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity {
                    val vm = ViewModelProvider(it)[AppViewModel::class.java]
                    vm.set("email", "crew@example.com")
                    vm.password = "fixture-password"
                    vm.authenticate()
                }
                compose.waitUntil(5000) {
                    compose
                        .onAllNodesWithText("Use fingerprint next time?")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithText("Staff name").performTextInput("Jamie")
                compose.onNodeWithText("Add device").performClick()
                android.util.Log.i("StaffBiometricTest", "WAIT_ENABLE_TOUCH")
                compose.waitUntil(45_000) { StaffBiometrics(app).enabled(base.accountId) }
                compose.waitUntil(5000) {
                    var idle = false
                    activity.onActivity {
                        idle = !ViewModelProvider(it)[AppViewModel::class.java].busy
                    }
                    idle
                }
                assertEquals("Jamie", StaffBiometrics(app).staffName)
                activity.onActivity { ViewModelProvider(it)[AppViewModel::class.java].logout() }
                compose.waitUntil(5000) { !base.signedIn }
                compose.waitForIdle()
                assertTrue(
                    "Sign out keeps device binding",
                    StaffBiometrics(app).enabled(base.accountId),
                )
                activity.recreate()
                compose.onNodeWithText("Jamie").assertDoesNotExist()
                compose.onNodeWithText("Sign in with fingerprint").performScrollTo().performClick()
                android.util.Log.i("StaffBiometricTest", "WAIT_POST_SIGNOUT_TOUCH")
                compose.waitUntil(45_000) {
                    var ready = false
                    activity.onActivity {
                        ready = ViewModelProvider(it)[AppViewModel::class.java].hasAccount
                    }
                    ready
                }
                assertTrue(base.signedIn)
                activity.onActivity {
                    ViewModelProvider(it)[AppViewModel::class.java].set("page", "Settings")
                }
                compose.onNodeWithText("Registered devices").assertExists()
                compose.onNodeWithText("Jamie").assertExists()
                compose.onNodeWithText("This device is registered").assertExists()
                val image = instrumentation.uiAutomation.takeScreenshot()
                java.io
                    .File(app.getExternalFilesDir(null), "fingerprint-devices.png")
                    .outputStream()
                    .use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                activity.onActivity {
                    val vm = ViewModelProvider(it)[AppViewModel::class.java]
                    vm.staffBackground()
                    wall += SessionRules.STAFF_IDLE_MS
                    uptime += SessionRules.STAFF_IDLE_MS
                    vm.staffForeground()
                }
                compose.onNodeWithText("Your shift starts here.").assertIsDisplayed()
                compose.onNodeWithText("Sign in with fingerprint").performScrollTo().performClick()
                android.util.Log.i("StaffBiometricTest", "WAIT_UNLOCK_TOUCH")
                compose.waitUntil(45_000) {
                    compose
                        .onAllNodesWithText("Your shift starts here.")
                        .fetchSemanticsNodes()
                        .isEmpty()
                }
                activity.onActivity {
                    assertFalse(ViewModelProvider(it)[AppViewModel::class.java].staffLocked)
                    ViewModelProvider(it)[AppViewModel::class.java].removeStaffDevice(
                        StaffBiometrics(app).deviceId
                    )
                }
                compose.waitUntil(5000) { !StaffBiometrics(app).enabled(base.accountId) }
                assertNull(registered)
            }
        } finally {
            StaffBiometrics(app).disable()
            app.gatewayFactory = { CtflyApi(it) }
            app.staffSessionFactory = { StaffSession(it) }
            app.getSharedPreferences("ctfly_staff_access", 0).edit().clear().commit()
        }
    }
}
