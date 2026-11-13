package com.ctfly.app

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class TourMapStateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app
        get() = instrumentation.targetContext.applicationContext as CtflyApplication

    private var scenario: ActivityScenario<MainActivity>? = null

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
        Thread.sleep(350)
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

    private fun launch(width: Int, height: Int, font: Float = 1f) {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        shell("wm size ${width}x$height")
        shell("wm density 160")
        shell("settings put system font_scale $font")
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 0")
        app.getSharedPreferences("ctfly_appearance", 0).edit().clear().commit()
        val fixture =
            JSONObject(
                instrumentation.context.assets.open("customer.json").bufferedReader().use {
                    it.readText()
                }
            )
        val base = FixtureGateway(fixture, true)
        app.gatewayFactory = {
            object : ClubGateway by base {
                override val cachedSnapshot
                    get() = fixture
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity {
            ViewModelProvider(it)[AppViewModel::class.java].apply {
                set("tourQuality", "BALANCED")
                set("page", "Tour")
            }
        }
        compose.waitUntil(45000) {
            compose.onAllNodesWithTag("tourJump").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        Thread.sleep(500)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @After
    fun cleanup() {
        scenario?.close()
        app.gatewayFactory = { CtflyApi(it) }
        shell("wm size reset")
        shell("wm density reset")
        shell("settings put system font_scale 1.0")
        shell("settings put system accelerometer_rotation 1")
    }

    private fun openMap() {
        compose.onNodeWithContentDescription("Toggle floor map").performClick()
        compose.onNodeWithContentDescription("Close floor map").assertIsDisplayed()
        val view = compose.onNodeWithTag("floorMapViewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Readable map width", view.width > 300f)
        assertTrue("Readable map height", view.height > 110f)
        val go = compose.onNodeWithTag("mapGoHere").fetchSemanticsNode().boundsInRoot
        assertFalse("Place controls stay beside the map", go.overlaps(view))
        compose.onNodeWithTag("mapGoHere").assertIsDisplayed()
        val mapBounds =
            compose
                .onNodeWithContentDescription("Venue floor map", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("Map controls stay inside the available screen", go.bottom <= mapBounds.bottom)
        compose.waitUntil(3000) { surface().tour.experiencePaused }
    }

    @Test
    fun phoneMapZoomPreviewNavigationAndResume() {
        launch(760, 360)
        openMap()
        val before = surface().tour.pose
        compose.onNodeWithText("Floor map").performClick()
        compose.onNodeWithContentDescription("Close floor map").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zoom in map").performClick()
        compose.onNodeWithText("140%").assertIsDisplayed()
        compose.onNodeWithTag("floorMapViewport").performTouchInput {
            swipe(center, center + Offset(70f, 25f))
        }
        capture("floor-map-phone-zoom")
        compose.onNodeWithTag("fitFloorMap").performClick()
        compose.onNodeWithText("100%").assertIsDisplayed()
        capture("floor-map-phone")
        compose.onNodeWithTag("mapFloor-BILLIARDS").performScrollTo().performClick()
        assertEquals(
            "Floor preview must not move the visitor",
            before.area,
            surface().tour.pose.area,
        )
        compose.onNodeWithTag("mapPlace-Pool lounge").performScrollTo().performClick()
        compose.onNodeWithTag("mapGoHere").performClick()
        compose.waitUntil(45000) {
            surface().tour.pose.area == TourArea.BILLIARDS &&
                compose.onAllNodesWithTag("tourJump").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(3000) { !surface().tour.experiencePaused }
        openMap()
        compose.onNodeWithContentDescription("Close floor map").performClick()
        compose.waitUntil(3000) { !surface().tour.experiencePaused }
        surface().tour.forward = 1f
        val z = surface().tour.pose.z
        compose.waitUntil(5000) { surface().tour.pose.z < z - .3f }
        surface().stopInput()
    }

    @Test
    fun smallPhoneLargeTextPinchAndRestore() {
        launch(640, 320, 1.5f)
        openMap()
        capture("floor-map-small-phone-large-text")
        compose.onNodeWithTag("floorMapViewport").performTouchInput {
            pinch(
                center - Offset(40f, 0f),
                center + Offset(40f, 0f),
                center - Offset(90f, 0f),
                center + Offset(90f, 0f),
            )
        }
        compose.onNodeWithText("100%").assertDoesNotExist()
        scenario!!.recreate()
        compose.waitUntil(15000) {
            compose
                .onAllNodesWithContentDescription("Close floor map")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Close floor map").assertIsDisplayed()
        compose.onNodeWithTag("fitFloorMap").performClick()
        compose.onNodeWithText("100%").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("Close floor map").assertDoesNotExist()
    }

    @Test
    fun tabletAllFloorsAndRacingMapRemainAccessible() {
        launch(1280, 800)
        openMap()
        capture("floor-map-tablet")
        for (area in TourArea.entries) {
            compose.onNodeWithTag("mapFloor-${area.name}").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Zoom in map").performClick()
            compose.onNodeWithTag("fitFloorMap").performClick()
            compose.onNodeWithText("100%").assertIsDisplayed()
        }
        compose.onNodeWithTag("mapGoHere").performClick()
        compose.waitUntil(45000) {
            compose.onAllNodesWithTag("kartStart").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("kartStart").performClick()
        compose
            .onNodeWithContentDescription("Venue floor map", useUnmergedTree = true)
            .performClick()
        compose.onNodeWithContentDescription("Close floor map").assertIsDisplayed()
        compose.onNodeWithTag("mapGoHere").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close floor map").performClick()
        compose.onNodeWithTag("kartGas").assertIsDisplayed()
    }
}
