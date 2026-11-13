package com.ctfly.app

import android.app.Application
import android.content.Context
import com.ctfly.app.auth.StaffSession
import com.ctfly.app.data.ClubGateway
import com.ctfly.app.data.CtflyApi
import com.ctfly.app.notifications.PickupNotifications

class CtflyApplication : Application() {
    var gatewayFactory: (Context) -> ClubGateway = { CtflyApi(it) }
    var staffSessionFactory: (Context) -> StaffSession = { StaffSession(it) }

    override fun onCreate() {
        super.onCreate()
        PickupNotifications.createChannel(this)
    }
}
