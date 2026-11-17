package com.ctfly.app

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ctfly.app.auth.session.StaffSession
import com.ctfly.app.database.local.SessionVault
import com.ctfly.app.database.remote.ApiFailure
import com.ctfly.app.database.remote.ClubGateway
import com.ctfly.app.database.remote.obj
import com.ctfly.app.database.remote.supabase.CtflyApi
import com.ctfly.app.database.remote.text
import com.ctfly.app.domain.SessionRules
import com.ctfly.app.state.AppViewModel
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class SessionStateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app
        get() = instrumentation.targetContext.applicationContext as CtflyApplication

    private var scenario: ActivityScenario<MainActivity>? = null
    private var wall = System.currentTimeMillis()
    private var uptime = SystemClock.elapsedRealtime()
    private lateinit var fixture: JSONObject

    @Before
    fun prepare() {
        val role = if (BuildConfig.STAFF_APP) "staff" else "customer"
        fixture =
            JSONObject(
                instrumentation.context.assets.open("$role.json").bufferedReader().use {
                    it.readText()
                }
            )
        app.getSharedPreferences("ctfly_appearance", 0).edit().clear().commit()
        app.getSharedPreferences("ctfly_staff_access", 0).edit().clear().commit()
        app.getSharedPreferences("ctfly_pickup_notifications", 0)
            .edit()
            .putBoolean("permissionAsked.${fixture.getJSONObject("profile").text("id")}", true)
            .commit()
        app.staffSessionFactory = { StaffSession(it, { wall }, { uptime }) }
    }

    private fun vm(block: (AppViewModel) -> Unit) =
        scenario!!.onActivity { block(ViewModelProvider(it)[AppViewModel::class.java]) }

    private fun gateway(
        cached: Boolean = true,
        load: suspend () -> JSONObject = { fixture },
    ): ClubGateway {
        val base = FixtureGateway(fixture, true)
        if (BuildConfig.STAFF_APP) StaffSession(app, { wall }, { uptime }).verified(base.accountId)
        return object : ClubGateway by base {
            override val cachedSnapshot
                get() = if (cached) JSONObject(fixture.toString()) else JSONObject()

            override suspend fun rpc(name: String, body: JSONObject, authenticated: Boolean) =
                load()
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // UiAutomation captures the last compositor buffer, after Compose's semantics settle.
        Thread.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(app.getExternalFilesDir(null), "session-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @After
    fun cleanup() {
        scenario?.close()
        app.gatewayFactory = { CtflyApi(it) }
        app.staffSessionFactory = { StaffSession(it) }
        SessionVault(app).clear()
        app.getSharedPreferences("ctfly_staff_access", 0).edit().clear().commit()
        instrumentation.uiAutomation.executeShellCommand("wm size reset").close()
        instrumentation.uiAutomation.executeShellCommand("wm density reset").close()
        instrumentation.uiAutomation
            .executeShellCommand("settings put system font_scale 1.0")
            .close()
    }

    @Test
    fun coldRestoreNeverShowsLoginWhileAccountIsLoading() {
        val release = CompletableDeferred<Unit>()
        app.gatewayFactory = {
            gateway(false) {
                release.await()
                fixture
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("account-restoring").assertIsDisplayed()
        compose.onNodeWithText("Email").assertDoesNotExist()
        release.complete(Unit)
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("account-restoring").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Email").assertDoesNotExist()
    }

    @Test
    fun networkFailureKeepsCachedAccountAcrossRecreation() {
        app.gatewayFactory = { gateway { throw IOException("Offline") } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        vm {
            assertTrue(it.hasAccount)
            assertTrue(it.role.isNotBlank())
        }
        compose.onNodeWithText("Email").assertDoesNotExist()
        scenario!!.recreate()
        compose.waitForIdle()
        vm { assertTrue(it.hasAccount) }
        compose.onNodeWithText("Email").assertDoesNotExist()
    }

    @Test
    fun staffBackgroundTimeoutReturnsToOriginalBlankSignIn() {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        app.getSharedPreferences("ctfly_staff_biometrics", 0).edit().clear().commit()
        var requests = 0
        app.gatewayFactory = {
            gateway {
                requests++
                fixture
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        vm {
            it.set("page", "Transactions")
            it.set("email", "previous@example.com")
            it.set("dialog", "receipt")
            it.staffBackground()
            wall += SessionRules.STAFF_IDLE_MS
            uptime += SessionRules.STAFF_IDLE_MS
            it.staffForeground()
        }
        compose.onNodeWithText("Your shift starts here.").assertIsDisplayed()
        compose.onNodeWithText("Email address").assertIsDisplayed()
        compose.onNodeWithText("Password").assertIsDisplayed()
        compose.onNodeWithText("Welcome back, crew").assertDoesNotExist()
        compose.onNodeWithText("previous@example.com").assertDoesNotExist()
        compose.onNodeWithText("Payment records, references and receipts.").assertDoesNotExist()
        screenshot("phone")
        val count = requests
        vm {
            it.refresh()
            assertFalse(it.hasAccount)
            assertEquals("", it.field("email"))
            assertEquals("", it.password)
        }
        compose.waitForIdle()
        assertEquals(count, requests)
        vm {
            it.set("email", "crew@example.com")
            it.password = "fixture-password"
            it.authenticate()
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Your shift starts here.").fetchSemanticsNodes().isEmpty()
        }
        vm {
            assertTrue(it.hasAccount)
            assertEquals("Operations", it.field("page"))
            assertEquals("", it.password)
            assertEquals("", it.field("email"))
            if (it.offerFingerprint) it.declineFingerprint()
        }
    }

    @Test
    fun foregroundReadingNeverTimesOutAndShortBackgroundReturnsDirectly() {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        app.gatewayFactory = { gateway() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        vm {
            it.set("page", "Modules")
            wall += 45 * 60_000L
            uptime += 45 * 60_000L
            it.tick()
            assertTrue(it.hasAccount)
            assertFalse(it.staffLocked)
            it.staffBackground()
            wall += SessionRules.STAFF_IDLE_MS - 1
            uptime += SessionRules.STAFF_IDLE_MS - 1
            it.staffForeground()
            assertTrue(it.hasAccount)
            assertEquals("Modules", it.field("page"))
        }
        compose.onNodeWithText("Your shift starts here.").assertDoesNotExist()
    }

    @Test
    fun backgroundAndRestartCannotResetStaffTimeout() {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        val base = gateway()
        app.gatewayFactory = { base }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        vm { it.staffBackground() }
        scenario!!.close()
        scenario = null
        wall += SessionRules.STAFF_IDLE_MS
        uptime += SessionRules.STAFF_IDLE_MS
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Your shift starts here.").assertIsDisplayed()
        vm {
            assertFalse(it.hasAccount)
            assertEquals("", it.field("email"))
        }
        scenario!!.recreate()
        compose.onNodeWithText("Your shift starts here.").assertIsDisplayed()
    }

    @Test
    fun customerRemainsSignedInAfterLongAbsence() {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        app.gatewayFactory = { gateway() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        vm {
            wall += 90L * 24 * 60 * 60 * 1000
            uptime += 90L * 24 * 60 * 60 * 1000
            it.tick()
            assertFalse(it.staffLocked)
            assertTrue(it.hasAccount)
        }
        compose.onNodeWithText("Email address").assertDoesNotExist()
    }

    @Test
    fun tabletSignInIsScrollableAtLargeTextAndRotation() {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        instrumentation.uiAutomation.executeShellCommand("wm size 1100x760").close()
        instrumentation.uiAutomation.executeShellCommand("wm density 160").close()
        instrumentation.uiAutomation
            .executeShellCommand("settings put system font_scale 1.5")
            .close()
        app.gatewayFactory = { gateway() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        vm {
            it.staffBackground()
            wall += SessionRules.STAFF_IDLE_MS
            uptime += SessionRules.STAFF_IDLE_MS
            it.staffForeground()
        }
        compose.onNodeWithText("Your shift starts here.").assertIsDisplayed()
        compose.onNodeWithText("Start your shift  →").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Welcome back, crew").assertDoesNotExist()
        compose.onNodeWithTag("staff-fingerprint-sign-in").assertDoesNotExist()
        screenshot("tablet-large-text")
        vm { assertFalse(it.staffBiometrics.phone()) }
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitForIdle()
        compose.onNodeWithText("Email address").performScrollTo().assertIsDisplayed()
    }

    private fun token(owner: String = "one", expires: Long = 0) =
        obj(
            "access_token" to "access-$owner",
            "refresh_token" to "refresh-$owner",
            "expires_at" to expires,
            "expires_in" to 3600,
            "user" to obj("id" to owner, "email" to "$owner@example.com"),
        )

    @Test
    fun encryptedSessionSurvivesNewApiInstanceAndNetworkFailure() = runBlocking {
        val saved = token().put("cached_snapshot", fixture)
        SessionVault(app).save(saved)
        val api = CtflyApi(app) { _, _, _, _ -> throw IOException("Offline") }
        assertTrue(api.signedIn)
        try {
            api.rpc("ctfly_snapshot")
            fail()
        } catch (_: IOException) {}
        val reopened = CtflyApi(app) { _, _, _, _ -> throw IOException("Offline") }
        assertTrue(reopened.signedIn)
        assertEquals(fixture.toString(), reopened.cachedSnapshot.toString())
    }

    @Test
    fun activityAndFirebaseUseTheSameSessionGateway() {
        val application = Instrumentation.newApplication(CtflyApplication::class.java, app)
            as CtflyApplication
        assertSame(application.gatewayFactory(application), application.gatewayFactory(app))
    }

    @Test
    fun refreshIsSerializedAndSignOutCannotBeUndoneByLateRefresh() = runBlocking {
        SessionVault(app).save(token())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var refreshes = 0
        val api =
            CtflyApi(app) { path, _, _, _ ->
                if (path.contains("refresh_token")) {
                    refreshes++
                    started.complete(Unit)
                    release.await()
                    token()
                } else fixture
            }
        coroutineScope {
            val first = async { runCatching { api.rpc("ctfly_snapshot") } }
            started.await()
            api.localLogout()
            release.complete(Unit)
            assertTrue(first.await().isFailure)
        }
        assertEquals(1, refreshes)
        assertFalse(api.signedIn)
        assertNull(SessionVault(app).read())
    }

    @Test
    fun accountSwitchDoesNotCopyPrivateSnapshot() = runBlocking {
        SessionVault(app).save(token("one").put("cached_snapshot", fixture))
        val api = CtflyApi(app) { _, _, _, _ -> token("two") }
        api.login("two@example.com", "example-only")
        assertEquals("two", api.accountId)
        assertFalse(api.cachedSnapshot.has("profile"))
    }

    @Test
    fun simultaneousLoadsOnlyRotateRefreshTokenOnce() = runBlocking {
        SessionVault(app).save(token())
        var refreshes = 0
        val api =
            CtflyApi(app) { path, _, _, _ ->
                if (path.contains("refresh_token")) {
                    refreshes++
                    kotlinx.coroutines.delay(100)
                    token()
                } else fixture
            }
        coroutineScope { (1..8).map { async { api.rpc("ctfly_snapshot") } }.forEach { it.await() } }
        assertEquals(1, refreshes)
        assertTrue(api.signedIn)
        assertTrue(CtflyApi(app).signedIn)
    }

    @Test
    fun revokedRefreshTokenClearsSessionButOrdinary401DoesNot() = runBlocking {
        SessionVault(app).save(token())
        val transient =
            CtflyApi(app) { _, _, _, _ -> throw ApiFailure(401, "Temporary gateway error") }
        runCatching { transient.rpc("ctfly_snapshot") }
        assertTrue(transient.signedIn)
        val revoked =
            CtflyApi(app) { _, _, _, _ ->
                throw ApiFailure(400, "Revoked", "refresh_token_not_found")
            }
        runCatching { revoked.rpc("ctfly_snapshot") }
        assertFalse(revoked.signedIn)
    }
}
