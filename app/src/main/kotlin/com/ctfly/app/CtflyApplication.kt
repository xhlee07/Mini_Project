package com.ctfly.app

import android.app.Application
import android.content.Context
import com.ctfly.app.auth.session.StaffSession
import com.ctfly.app.database.remote.ClubGateway
import com.ctfly.app.database.remote.supabase.CtflyApi
import com.ctfly.app.notifications.PickupNotifications

class CtflyApplication : Application() {
    // Activities and Firebase callbacks share the same session and refresh lock.
    private val gateway by lazy { CtflyApi(this) }
    var gatewayFactory: (Context) -> ClubGateway = { gateway }
    var staffSessionFactory: (Context) -> StaffSession = { StaffSession(it) }

    override fun onCreate() {
        super.onCreate()
        PickupNotifications.createChannel(this)
    }
}
