package com.ctfly.app.auth.session

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ctfly.app.BuildConfig
import com.ctfly.app.database.remote.ClubGateway
import com.ctfly.app.domain.SessionRules
import org.json.JSONObject

/** Staff access expires only while away from the app. Customer sessions are independent. */
class StaffSession(
    private val context: Context,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val elapsedTime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val prefs = context.getSharedPreferences("ctfly_staff_access", Context.MODE_PRIVATE)
    private var owner = ""
    private var wall = prefs.getLong("wall", 0)
    private var elapsed = prefs.getLong("elapsed", -1)
    private var boot = prefs.getInt("boot", -1)
    private var lastWrite = 0L
    var foreground by mutableStateOf(false)
        private set

    var locked by mutableStateOf(false)
        private set

    private fun bootCount() =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -2)

    fun restore(account: String, signedIn: Boolean) {
        if (!BuildConfig.STAFF_APP || !signedIn) return
        owner = account
        locked =
            account.isBlank() ||
                prefs.getString("owner", "") != account ||
                prefs.getBoolean("locked", false) ||
                expired()
    }

    private fun expired() =
        SessionRules.expired(wall, elapsed, boot, wallTime(), elapsedTime(), bootCount())

    fun check(): Boolean {
        if (!BuildConfig.STAFF_APP || owner.isBlank()) return locked
        if (!locked && !foreground && expired()) {
            locked = true
            checkpoint()
        }
        return locked
    }

    fun resume() {
        check()
        foreground = true
        if (!locked) interaction()
    }

    fun background() {
        if (!BuildConfig.STAFF_APP || owner.isBlank()) {
            foreground = false
            return
        }
        if (foreground && !locked) stamp()
        foreground = false
        checkpoint()
    }

    private fun stamp() {
        wall = wallTime()
        elapsed = elapsedTime()
        boot = bootCount()
    }

    fun interaction() {
        if (!BuildConfig.STAFF_APP || owner.isBlank() || !foreground || locked) return
        stamp()
        if (elapsed - lastWrite >= 10_000) checkpoint()
    }

    /** A foreground crash must not make time spent reading look like time spent away. */
    fun heartbeat() {
        if (!BuildConfig.STAFF_APP || !foreground || locked || owner.isBlank()) return
        if (elapsedTime() - lastWrite >= 30_000) {
            stamp()
            checkpoint()
        }
    }

    fun verified(account: String) {
        owner = account
        locked = false
        stamp()
        checkpoint()
    }

    fun checkpoint() {
        if (!BuildConfig.STAFF_APP || owner.isBlank()) return
        prefs
            .edit()
            .putString("owner", owner)
            .putLong("wall", wall)
            .putLong("elapsed", elapsed)
            .putInt("boot", boot)
            .putBoolean("locked", locked)
            .commit()
        lastWrite = elapsedTime()
    }

    fun clear() {
        owner = ""
        locked = false
        prefs.edit().clear().commit()
    }
}

/** Every authenticated request is checked, including requests triggered by a stale dialog. */
class StaffSessionGateway(private val delegate: ClubGateway, private val session: StaffSession) :
    ClubGateway by delegate {
    private fun requireUnlocked() {
        check(!session.check()) { "Your staff session is locked. Sign in again to continue." }
    }

    override suspend fun rpc(name: String, body: JSONObject, authenticated: Boolean): JSONObject {
        if (authenticated) requireUnlocked()
        val result = delegate.rpc(name, body, authenticated)
        if (authenticated) requireUnlocked()
        return result
    }

    override suspend fun service(body: JSONObject): JSONObject {
        requireUnlocked()
        return delegate.service(body).also { requireUnlocked() }
    }

    override suspend fun staffDevice(body: JSONObject, authenticated: Boolean): JSONObject {
        if (authenticated) requireUnlocked()
        return delegate.staffDevice(body, authenticated).also {
            if (authenticated) requireUnlocked()
        }
    }

    override suspend fun uploadPoster(bytes: ByteArray): String {
        requireUnlocked()
        return delegate.uploadPoster(bytes).also { requireUnlocked() }
    }

    override suspend fun recover(email: String): String = delegate.recover(email)

    override suspend fun resend(email: String): String = delegate.resend(email)
}
