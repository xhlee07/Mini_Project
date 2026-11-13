package com.ctfly.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StaffDeviceApiTest {
    private val app
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun session() =
        obj(
            "access_token" to "fixture-access",
            "refresh_token" to "fixture-refresh",
            "expires_in" to 3600,
            "user" to obj("id" to "staff-id", "email" to "staff@example.test"),
        )

    @Before
    fun before() {
        SessionVault(app).clear()
    }

    @After
    fun after() {
        SessionVault(app).clear()
    }

    @Test
    fun fingerprintCreatesFreshSessionAfterExplicitSignOut() = runBlocking {
        var loginCount = 0
        var logoutCount = 0
        val api =
            CtflyApi(app) { path, body, token, _ ->
                when {
                    path == "/functions/v1/staff-device-auth" -> {
                        assertNull("Fingerprint login needs no existing access token", token)
                        assertEquals("login", body!!.text("operation"))
                        loginCount++
                        session()
                    }
                    path.contains("/logout") -> {
                        logoutCount++
                        obj()
                    }
                    else -> {
                        assertEquals("fixture-access", token)
                        obj("profile" to obj("id" to "staff-id"))
                    }
                }
            }
        repeat(2) {
            assertFalse(api.signedIn)
            api.loginDevice(
                obj("operation" to "login", "device_id" to "phone", "signature" to "proof")
            )
            assertTrue(api.signedIn)
            assertEquals("staff-id", CtflyApi(app).accountId)
            api.rpc("ctfly_snapshot")
            api.logout()
            assertNull(SessionVault(app).read())
        }
        assertEquals(2, loginCount)
        assertEquals(2, logoutCount)
    }

    @Test
    fun lateFingerprintResponseCannotUndoSignOut() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api =
            CtflyApi(app) { _, _, _, _ ->
                started.complete(Unit)
                release.await()
                session()
            }
        coroutineScope {
            val login = async { runCatching { api.loginDevice(obj("operation" to "login")) } }
            started.await()
            api.localLogout()
            release.complete(Unit)
            assertTrue(login.await().isFailure)
        }
        assertFalse(api.signedIn)
        assertNull(SessionVault(app).read())
    }

    @Test
    fun offlineFingerprintLoginDoesNotInventSession() = runBlocking {
        val api = CtflyApi(app) { _, _, _, _ -> throw IOException("Offline") }
        assertTrue(runCatching { api.loginDevice(obj("operation" to "login")) }.isFailure)
        assertFalse(api.signedIn)
        assertNull(SessionVault(app).read())
    }
}
