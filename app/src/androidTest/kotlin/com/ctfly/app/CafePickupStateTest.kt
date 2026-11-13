package com.ctfly.app

import android.app.NotificationManager
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.ctfly.app.data.ClubGateway
import com.ctfly.app.data.CtflyApi
import com.ctfly.app.data.obj
import com.ctfly.app.data.rows
import com.ctfly.app.data.text
import com.ctfly.app.state.AppViewModel
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Rule
import org.junit.Test

class CafePickupStateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app
        get() = instrumentation.targetContext.applicationContext as CtflyApplication

    private var scenario: ActivityScenario<MainActivity>? = null
    private val orderId = "11111111-1111-4111-8111-111111111111"
    private lateinit var fixture: JSONObject
    private var lastPickupAction = ""

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
        Thread.sleep(250)
    }

    private fun launch(width: Int, height: Int, ready: Boolean = false, font: String = "1.0") {
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 0")
        shell("wm size ${width}x$height")
        shell("wm density 160")
        shell("settings put system font_scale $font")
        app.getSharedPreferences("ctfly_appearance", 0).edit().clear().commit()
        app.getSharedPreferences("ctfly_pickup_notifications", 0).edit().clear().commit()
        val role = if (BuildConfig.STAFF_APP) "staff" else "customer"
        app.getSharedPreferences("ctfly_pickup_notifications", 0)
            .edit()
            .putBoolean("permissionAsked.${fixtureOwner(role)}", true)
            .commit()
        fixture =
            JSONObject(
                    instrumentation.context.assets.open("$role.json").bufferedReader().use {
                        it.readText()
                    }
                )
                .put("server_time", Instant.now().toString())
                .put(
                    "orders",
                    JSONArray().apply {
                        if (ready)
                            put(
                                obj(
                                    "id" to orderId,
                                    "user_id" to fixtureOwner(role),
                                    "paid" to true,
                                    "status" to "Ready",
                                    "total" to 650,
                                    "pickup_token" to "22222222-2222-4222-8222-222222222222",
                                )
                            )
                    },
                )
                .put(
                    "order_items",
                    JSONArray().put(obj("order_id" to orderId, "name" to "Iced coffee", "qty" to 1)),
                )
        val base = FixtureGateway(fixture, true)
        app.gatewayFactory = {
            object : ClubGateway by base {
                override val cachedSnapshot
                    get() = JSONObject(fixture.toString())

                override suspend fun rpc(
                    name: String,
                    body: JSONObject,
                    authenticated: Boolean,
                ): JSONObject {
                    if (name == "ctfly_action" && body.text("p_action") == "order_pickup") {
                        lastPickupAction = body.getJSONObject("p_data").text("token")
                        fixture.rows("orders").first().put("status", "Received")
                        return obj("message" to "Order collected. Enjoy your food!")
                    }
                    return base.rpc(name, body, authenticated)
                }
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun fixtureOwner(role: String): String {
        val source =
            JSONObject(
                instrumentation.context.assets.open("$role.json").bufferedReader().use {
                    it.readText()
                }
            )
        return source.getJSONObject("profile").text("id")
    }

    private fun change(block: (AppViewModel) -> Unit) {
        scenario!!.onActivity { block(ViewModelProvider(it)[AppViewModel::class.java]) }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(300)
        val directory = File(app.getExternalFilesDir(null), "cafe-pickup").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            File(directory, "$name.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @After
    fun cleanup() {
        scenario?.close()
        app.gatewayFactory = { CtflyApi(it) }
        app.getSystemService(NotificationManager::class.java).cancelAll()
        shell("wm size reset")
        shell("wm density reset")
        shell("settings put system font_scale 1.0")
    }

    private fun paymentLayout(width: Int, height: Int, font: String, name: String) {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        launch(width, height, font = font)
        change {
            it.set("page", "Café")
            it.cartChange("1", 1)
            it.cartChange("2", 1)
        }
        val button = compose.onNodeWithText("Review & pay")
        button.assertIsDisplayed()
        capture(name)
        if (width > height && width < 840)
            compose.onNodeWithContentDescription("Club").assertIsDisplayed()
        if (width >= 840) compose.onNodeWithText("Club").assertIsDisplayed()
        compose.onNodeWithContentDescription("Shopping bag").assertDoesNotExist()
        button.performClick()
        compose.onNodeWithText("Your pit stop order").assertIsDisplayed()
        compose.onNodeWithText("Continue to payment").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Counter pickup · Collect with your order QR when ready")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test fun phonePaymentIsVisible() = paymentLayout(390, 844, "1.0", "phone-payment")

    @Test fun landscapePaymentIsVisible() = paymentLayout(800, 390, "1.0", "landscape-payment")

    @Test fun tabletPaymentIsVisible() = paymentLayout(1280, 800, "1.0", "tablet-payment")

    @Test fun largeTextPaymentIsVisible() = paymentLayout(390, 844, "1.5", "large-text-payment")

    @Test
    fun readyAlertOpensSingleUseQrAndDisappearsAfterCollection() {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        launch(390, 844, ready = true)
        compose.onNodeWithText("Your order is ready!").assertIsDisplayed()
        compose.onNodeWithText("Show QR").performClick()
        compose
            .onNodeWithContentDescription("Order pickup QR code")
            .performScrollTo()
            .assertIsDisplayed()
        val qr =
            compose
                .onNodeWithContentDescription("Order pickup QR code")
                .captureToImage()
                .asAndroidBitmap()
        val pixels = IntArray(qr.width * qr.height)
        qr.getPixels(pixels, 0, qr.width, 0, 0, qr.width, qr.height)
        val decoded =
            MultiFormatReader()
                .decode(
                    BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width, qr.height, pixels)))
                )
        assertEquals("ctfly-pickup:22222222-2222-4222-8222-222222222222", decoded.text)
        capture("phone-pickup-qr")
        fixture.rows("orders").first().put("status", "Received")
        change { it.refresh(silent = true) }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Order collected").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Order pickup QR code").assertDoesNotExist()
    }

    @Test
    fun staffVerifiesItemsThenConfirmsHandover() {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        launch(800, 640, ready = true)
        change {
            it.set("page", "Operations")
            it.set("opsTab", "Orders")
        }
        compose.onNodeWithText("Scan QR").assertIsDisplayed()
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Use Scan QR above to confirm collection."))
        compose.onNodeWithText("Use Scan QR above to confirm collection.").assertIsDisplayed()
        compose.onNodeWithText("Mark Delivered").assertDoesNotExist()
        change {
            it.set(
                "pickupPreview",
                obj(
                        "order" to fixture.rows("orders").first(),
                        "customer_name" to "Lee",
                        "items" to JSONArray().put(obj("name" to "Iced coffee", "qty" to 1)),
                    )
                    .toString(),
            )
            it.set("pickupScanToken", "ctfly-pickup:22222222-2222-4222-8222-222222222222")
            it.set("dialog", "pickupScan")
        }
        compose.onNodeWithText("Lee").assertIsDisplayed()
        capture("staff-pickup-confirm")
        compose.onNodeWithText("Confirm handover").performScrollTo().performClick()
        compose.waitUntil(5000) { lastPickupAction.isNotBlank() }
        assertEquals("Received", fixture.rows("orders").first().text("status"))
    }

    @Test
    fun notificationRoutesOnlyForItsOwner() {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        launch(390, 844, ready = true)
        change { vm ->
            vm.openOrderFromNotification("another-user", orderId)
            assertTrue(vm.field("dialog").isBlank())
            vm.openOrderFromNotification(vm.userId, orderId)
            assertEquals("pickupQr", vm.field("dialog"))
            assertEquals(orderId, vm.field("selectedOrder"))
        }
    }

    @Test
    fun rotationKeepsCartAndPaymentEntry() {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        launch(390, 844)
        change {
            it.set("page", "Café")
            it.cartChange("1", 1)
        }
        scenario!!.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
        compose.onNodeWithText("Review & pay").assertIsDisplayed()
        change {
            assertEquals(1, it.cart()["1"])
            assertEquals("Café", it.field("page"))
        }
    }

    @Test
    fun landscapePickupQrIsFullyVisible() {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        launch(800, 390, ready = true)
        compose.onNodeWithText("Show QR").performClick()
        val node = compose.onNodeWithContentDescription("Order pickup QR code").performScrollTo()
        node.assertIsDisplayed()
        assertTrue(node.fetchSemanticsNode().boundsInRoot.height <= 180)
        capture("landscape-pickup-qr")
    }
}
