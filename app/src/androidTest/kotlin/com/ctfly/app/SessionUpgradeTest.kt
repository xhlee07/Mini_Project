package com.ctfly.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ctfly.app.database.local.SessionVault
import com.ctfly.app.database.remote.obj
import com.ctfly.app.database.remote.supabase.CtflyApi
import com.ctfly.app.database.remote.text
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Run seed, install the same application ID with -r, then run verify. No cloud credentials. */
class SessionUpgradeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app
        get() =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as CtflyApplication

    @Test
    fun seedForCoverUpdate() {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        val fixture =
            JSONObject(
                InstrumentationRegistry.getInstrumentation()
                    .context
                    .assets
                    .open("customer.json")
                    .bufferedReader()
                    .use { it.readText() }
            )
        SessionVault(app)
            .save(
                obj(
                    "access_token" to "offline-upgrade-fixture",
                    "refresh_token" to "offline-upgrade-fixture",
                    "expires_at" to 0,
                    "user" to
                        obj(
                            "id" to fixture.getJSONObject("profile").text("id"),
                            "email" to "upgrade@example.com",
                        ),
                    "cached_snapshot" to fixture,
                )
            )
    }

    @Test
    fun verifyCoverUpdateKeepsAccount() {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        assertEquals("offline-upgrade-fixture", SessionVault(app).read()?.text("refresh_token"))
        app.gatewayFactory = {
            CtflyApi(it) { _, _, _, _ -> throw java.io.IOException("Offline upgrade test") }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForIdle()
                compose.onNodeWithText("Email").assertDoesNotExist()
                compose.onNodeWithTag("account-restoring").assertDoesNotExist()
                assertTrue(CtflyApi(app).signedIn)
            }
        } finally {
            SessionVault(app).clear()
            app.gatewayFactory = { CtflyApi(it) }
        }
    }
}
