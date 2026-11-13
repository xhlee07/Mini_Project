package com.ctfly.app

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class VirtualCctvStateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app
        get() = instrumentation.targetContext.applicationContext as CtflyApplication

    private var scenario: ActivityScenario<MainActivity>? = null
    @Volatile private var visitor = JSONObject()
    @Volatile private var offline = false
    @Volatile private var active = true
    @Volatile private var loads = 0
    @Volatile private var latest: JSONObject? = null
    @Volatile private var count = 0

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
        Thread.sleep(250)
    }

    private fun change(block: (AppViewModel) -> Unit) {
        scenario!!.onActivity { block(ViewModelProvider(it)[AppViewModel::class.java]) }
        compose.waitForIdle()
    }

    private fun surface(): TourSurface {
        fun find(view: View): TourSurface? {
            if (view is TourSurface) return view
            if (view is ViewGroup)
                for (i in 0 until view.childCount) find(view.getChildAt(i))?.let {
                    return it
                }
            return null
        }
        var result: TourSurface? = null
        scenario!!.onActivity { result = find(it.window.decorView) }
        return requireNotNull(result)
    }

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Before
    fun prepare() {
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 0")
        shell("wm size 1280x800")
        shell("wm density 160")
        app.getSharedPreferences("ctfly_appearance", 0).edit().clear().commit()
    }

    @After
    fun cleanup() {
        scenario?.close()
        app.gatewayFactory = { CtflyApi(it) }
        shell("wm size reset")
        shell("wm density reset")
        shell("settings put system accelerometer_rotation 1")
    }

    private fun fixture(): JSONObject =
        JSONObject(
            instrumentation.context.assets
                .open(if (BuildConfig.STAFF_APP) "staff.json" else "customer.json")
                .bufferedReader()
                .use { it.readText() }
        )

    @Test
    fun staffSeesMovingVisitorsCanPanFollowSwitchFloorAndRecover() {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        val fixture = fixture()
        val base = FixtureGateway(fixture, true)
        val id = "11111111-1111-4111-8111-111111111111"
        visitor =
            obj(
                "id" to id,
                "area" to "GAMING",
                "x" to 0,
                "z" to 23,
                "yaw" to 0,
                "age_ms" to 0,
                "moving" to true,
                "mode" to "walk",
            )
        offline = false
        active = true
        loads = 0
        app.gatewayFactory = {
            object : ClubGateway by base {
                override val cachedSnapshot
                    get() = fixture

                override suspend fun rpc(
                    name: String,
                    body: JSONObject,
                    authenticated: Boolean,
                ): JSONObject {
                    delay(20)
                    if (name == "ctfly_tour_watch") {
                        loads++
                        if (offline) throw IOException("Offline")
                        return obj("visitors" to JSONArray().apply { if (active) put(visitor) })
                    }
                    return fixture
                }
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        change { it.set("page", "Modules") }
        compose.onNodeWithText("Virtual CCTV").performScrollTo().performClick()
        compose.waitUntil(30000) {
            runCatching {
                    surface().presentedFrames > 3 && surface().tour.monitoredVisitors.size == 1
                }
                .getOrDefault(false)
        }
        compose.onNodeWithText("Connected").assertExists()
        assertEquals(-13.25f, surface().tour.pose.x, .001f)
        val yaw = surface().tour.pose.yaw
        compose.onNodeWithTag("cctv-pan-left").performClick()
        compose.waitUntil(5000) { surface().tour.pose.yaw < yaw - .2f }
        compose.onNodeWithTag("cctv-zoom").performSemanticsAction(SemanticsActions.SetProgress) {
            it(1.5f)
        }
        visitor = JSONObject(visitor.toString()).put("x", 2)
        compose.waitUntil(6000) {
            surface().tour.monitoredVisitors.firstOrNull()?.pose?.x?.let { it > 1.8f } == true
        }
        compose.onNodeWithText("Reset view").performClick()
        compose.onNodeWithTag("cctv-follow-$id").performScrollTo().performClick()
        compose.waitUntil(5000) { kotlin.math.abs(surface().tour.pose.yaw - 1.185f) < .15f }
        compose.onNodeWithTag("cctv-zoom").performSemanticsAction(SemanticsActions.SetProgress) {
            it(1.7f)
        }
        Thread.sleep(500)
        capture("virtual-cctv-landscape")
        visitor = JSONObject(visitor.toString()).put("area", "BILLIARDS").put("x", 0).put("z", 7)
        compose.waitUntil(30000) {
            surface().tour.pose.area == TourArea.BILLIARDS &&
                surface().tour.monitoredVisitors.firstOrNull()?.pose?.area == TourArea.BILLIARDS
        }
        compose.onNodeWithTag("cctv-camera-3").performScrollTo().performClick()
        compose.waitUntil(30000) {
            surface().tour.pose.area == TourArea.LOUNGE && surface().tour.renderedSize.first > 0
        }
        offline = true
        compose.waitUntil(6000) {
            compose
                .onAllNodesWithText("Virtual tour connection lost. Reconnecting…")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.waitUntil(5000) { surface().tour.monitoredVisitors.isEmpty() }
        offline = false
        active = false
        compose.onNodeWithText("Retry connection").performClick()
        compose.waitUntil(6000) {
            compose.onAllNodesWithText("Connected").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("No visitors in this area").assertExists()
        shell("wm size 800x1200")
        compose.waitUntil(15000) {
            runCatching { surface().presentedFrames > 3 }.getOrDefault(false)
        }
        capture("virtual-cctv-portrait")
        compose.onNodeWithContentDescription("Back to modules").performClick()
        compose.onNodeWithText("Staff modules").assertExists()
        val stopped = loads
        Thread.sleep(1500)
        assertEquals("Watching stops when the module closes", stopped, loads)
    }

    @Test
    fun customerSeesMountedCameraAndItsSharedHeadTurnsSmoothly() {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        val fixture = fixture().put("orders", JSONArray())
        val base = FixtureGateway(fixture, true)
        var yaw = 1.1f
        app.gatewayFactory = {
            object : ClubGateway by base {
                override val cachedSnapshot
                    get() = fixture

                override suspend fun rpc(
                    name: String,
                    body: JSONObject,
                    authenticated: Boolean,
                ): JSONObject {
                    if (name == "ctfly_tour_cameras")
                        return obj(
                            "server_ms" to System.currentTimeMillis(),
                            "cameras" to
                                JSONArray()
                                    .put(
                                        obj(
                                            "camera_id" to 0,
                                            "yaw" to yaw,
                                            "pitch" to -.2f,
                                            "remaining_ms" to 15000,
                                        )
                                    ),
                        )
                    return fixture
                }
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        change {
            it.set("tourPose", "-10.2,30.5,-1.115,0.42,GAMING")
            it.set("tourQuality", "SMOOTH")
            it.set("page", "Tour")
        }
        compose.waitUntil(30000) {
            runCatching {
                    surface().presentedFrames > 3 &&
                        kotlin.math.abs(surface().tour.visibleCameraDirections.first().yaw - 1.1f) <
                            .03f
                }
                .getOrDefault(false)
        }
        capture("cctv-customer-wall-mount")
        val view = surface()
        val before = view.presentedFrames
        val started = android.os.SystemClock.elapsedRealtime()
        yaw = 2.2f
        compose.waitUntil(10000) {
            kotlin.math.abs(view.tour.visibleCameraDirections.first().yaw - 2.2f) < .03f
        }
        assertEquals(
            "Physical camera remains anchored to the wall",
            -13.25f,
            view.tour.visibleCameraDirections.first().x,
            .001f,
        )
        Thread.sleep(2000)
        android.util.Log.i(
            "CCTVPerformance",
            "Customer presented ${view.presentedFrames-before} frames in ${android.os.SystemClock.elapsedRealtime()-started} ms",
        )
        capture("cctv-customer-head-turned")
    }

    @Test
    fun notificationPermissionRequestDoesNotCrashCustomerActivity() {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        val fixture = fixture()
        val base = FixtureGateway(fixture, true)
        val key = "permissionAsked.${fixture.getJSONObject("profile").text("id")}"
        app.getSharedPreferences("ctfly_pickup_notifications", 0).edit().remove(key).commit()
        app.gatewayFactory = {
            object : ClubGateway by base {
                override val cachedSnapshot
                    get() = fixture

                override suspend fun rpc(name: String, body: JSONObject, authenticated: Boolean) =
                    fixture
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(1000)
        assertTrue(app.getSharedPreferences("ctfly_pickup_notifications", 0).getBoolean(key, false))
        shell("input keyevent KEYCODE_BACK")
        scenario!!.onActivity { assertFalse(it.isFinishing) }
    }

    @Test
    fun customerPublishesMotionOnlyInForegroundAndLeavesWhenTourCloses() {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        val fixture = fixture().put("orders", JSONArray())
        val base = FixtureGateway(fixture, true)
        latest = null
        count = 0
        app.gatewayFactory = {
            object : ClubGateway by base {
                override val cachedSnapshot
                    get() = fixture

                override suspend fun rpc(
                    name: String,
                    body: JSONObject,
                    authenticated: Boolean,
                ): JSONObject {
                    if (name == "ctfly_tour_presence") {
                        latest = JSONObject(body.toString())
                        count++
                        return obj("ok" to true)
                    }
                    return fixture
                }
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        change { it.set("page", "Tour") }
        compose.waitUntil(30000) { latest != null }
        val session = latest!!.text("p_session")
        val start = latest!!.getDouble("p_z")
        surface().tour.forward = 1f
        compose.waitUntil(6000) { latest!!.getDouble("p_z") < start - .6 }
        surface().stopInput()
        val before = latest!!.getDouble("p_yaw")
        surface().addLook(.8f, 0f)
        compose.waitUntil(6000) { latest!!.getDouble("p_yaw") > before + .5 }
        val view = surface()
        view.queueEvent { view.tour.go(TourPlan.entry(TourArea.BILLIARDS)) }
        compose.waitUntil(30000) { latest!!.text("p_area") == "BILLIARDS" }
        compose.onNodeWithText("Virtual visit visible to staff").assertExists()
        capture("virtual-tour-customer-sync")
        scenario!!.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5000) { latest!!.optBoolean("p_leaving") }
        val paused = count
        Thread.sleep(1300)
        assertEquals(paused, count)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(15000) {
            latest!!.text("p_session") != session && !latest!!.optBoolean("p_leaving")
        }
        change { it.set("page", "Discover") }
        compose.waitUntil(5000) { latest!!.optBoolean("p_leaving") }
    }
}
