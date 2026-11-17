package com.ctfly.app

import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ctfly.app.database.remote.ClubGateway
import com.ctfly.app.database.remote.obj
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.supabase.CtflyApi
import com.ctfly.app.database.remote.text
import com.ctfly.app.domain.onStaffDay
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.staff.operations.reviewStaffCode
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class StaffHistoryStateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app
        get() = instrumentation.targetContext.applicationContext as CtflyApplication

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var fixture: JSONObject
    private var pickupVerified = false
    @Volatile private var historyLoads = 0
    @Volatile private var lastTransactionQuery = JSONObject()

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
        Thread.sleep(200)
    }

    private fun launch(width: Int, height: Int, font: String = "1.0") {
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 0")
        shell("wm size ${width}x$height")
        shell("wm density 160")
        shell("settings put system font_scale $font")
        app.getSharedPreferences("ctfly_appearance", 0).edit().clear().commit()
        val role = if (BuildConfig.STAFF_APP) "staff" else "customer"
        fixture =
            JSONObject(
                    instrumentation.context.assets.open("$role.json").bufferedReader().use {
                        it.readText()
                    }
                )
                .put("server_time", Instant.now().toString())
                .put("orders", JSONArray())
        app.getSharedPreferences("ctfly_pickup_notifications", 0)
            .edit()
            .putBoolean("permissionAsked.${fixture.getJSONObject("profile").text("id")}", true)
            .commit()
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
                    if (name == "ctfly_staff_history" || name == "ctfly_staff_transactions") {
                        historyLoads++
                        if (name == "ctfly_staff_transactions")
                            lastTransactionQuery = JSONObject(body.toString())
                        val rows =
                            when (
                                if (name == "ctfly_staff_transactions") "Transactions"
                                else body.text("p_module")
                            ) {
                                "Activity" ->
                                    listOf(
                                        obj(
                                            "id" to "booking-history",
                                            "category" to "Bookings",
                                            "customer_name" to "Customer A",
                                            "title" to "PC 01 — Standard",
                                            "detail" to "PC · Standard",
                                            "status" to "Completed",
                                            "paid" to true,
                                            "total" to 1200,
                                            "starts_at" to "2026-10-16T04:00:00Z",
                                            "ends_at" to "2026-10-16T06:00:00Z",
                                            "minutes" to 120,
                                            "guests" to 1,
                                        ),
                                        obj(
                                            "id" to "order-history",
                                            "category" to "Orders",
                                            "customer_name" to "Customer A",
                                            "title" to "Café order",
                                            "detail" to "2 × Iced coffee",
                                            "status" to "Received",
                                            "paid" to true,
                                            "total" to 1300,
                                            "created_at" to "2026-10-16T03:00:00Z",
                                        ),
                                        obj(
                                            "id" to "event-history",
                                            "category" to "Competitions",
                                            "customer_name" to "Customer B",
                                            "title" to "Weekend Valorant Cup",
                                            "detail" to "Lime Legends · Valorant",
                                            "status" to "Completed",
                                            "paid" to true,
                                            "total" to 1500,
                                            "starts_at" to "2026-10-16T07:00:00Z",
                                            "ends_at" to "2026-10-16T09:00:00Z",
                                        ),
                                        obj(
                                            "id" to "reward-history",
                                            "category" to "Rewards",
                                            "customer_name" to "Customer B",
                                            "title" to "Free iced coffee",
                                            "detail" to "Points redemption",
                                            "status" to "Collected",
                                            "points" to 100,
                                            "created_at" to "2026-10-16T02:00:00Z",
                                        ),
                                    )
                                "Transactions" ->
                                    listOf(
                                        obj(
                                            "id" to "cash-history",
                                            "category" to "Transactions",
                                            "customer_name" to "Customer A",
                                            "title" to "Order",
                                            "method" to "Cash at counter",
                                            "reference" to "COUNTER-0001",
                                            "environment" to "counter",
                                            "status" to "Paid",
                                            "total" to 1300,
                                            "created_at" to "2026-10-16T03:00:00Z",
                                        ),
                                        obj(
                                            "id" to "booking-cash",
                                            "category" to "Transactions",
                                            "customer_name" to "Customer A",
                                            "title" to "Booking",
                                            "method" to "Cash at counter",
                                            "reference" to "BOOKING-0001",
                                            "environment" to "counter",
                                            "status" to "Paid",
                                            "total" to 1200,
                                            "created_at" to "2026-10-16T03:00:00Z",
                                        ),
                                        obj(
                                            "id" to "event-cash",
                                            "category" to "Transactions",
                                            "customer_name" to "Customer B",
                                            "title" to "Event",
                                            "method" to "Cash at counter",
                                            "reference" to "EVENT-0001",
                                            "environment" to "counter",
                                            "status" to "Paid",
                                            "total" to 1500,
                                            "created_at" to "2026-10-16T03:00:00Z",
                                        ),
                                        obj(
                                            "id" to "extension-cash",
                                            "category" to "Transactions",
                                            "customer_name" to "Customer A",
                                            "title" to "Extension",
                                            "method" to "Cash at counter",
                                            "reference" to "EXTENSION-0001",
                                            "environment" to "counter",
                                            "status" to "Paid",
                                            "total" to 600,
                                            "created_at" to "2026-10-16T03:00:00Z",
                                        ),
                                    )
                                else -> emptyList()
                            }.map {
                                it.put("history_key", it.text("id"))
                                    .put("sort_at", it.text("starts_at", it.text("created_at")))
                            }
                        val filtered =
                            rows.filter {
                                (body.text("p_category", "All") == "All" ||
                                    it.text("category") == body.text("p_category")) &&
                                    (body.text("p_status", "All") == "All" ||
                                        it.text("status") == body.text("p_status")) &&
                                    (body.text("p_kind", "All") == "All" ||
                                        it.text("title") == body.text("p_kind")) &&
                                    onStaffDay(it.text("sort_at"), body.text("p_day")) &&
                                    (it.text("title") + it.text("customer_name")).contains(
                                        body.text("p_search"),
                                        true,
                                    )
                            }
                        return obj("items" to JSONArray(filtered), "has_more" to false)
                    }
                    if (name == "ctfly_action" && body.text("p_action") == "pickup_preview") {
                        pickupVerified = true
                        return obj(
                            "customer_name" to "Customer A",
                            "order" to obj("id" to "pickup-order"),
                            "items" to JSONArray().put(obj("qty" to 1, "name" to "Iced coffee")),
                        )
                    }
                    return base.rpc(name, body, authenticated)
                }
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun change(block: (AppViewModel) -> Unit) {
        scenario!!.onActivity { block(ViewModelProvider(it)[AppViewModel::class.java]) }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(250)
        val dir = File(app.getExternalFilesDir(null), "staff-history").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(dir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun waitHistory() {
        compose.waitUntil(10000) { historyLoads > 0 }
        compose.waitForIdle()
    }

    private fun historyLayout(width: Int, height: Int, font: String, name: String) {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        launch(width, height, font)
        change { it.set("page", "Customer history") }
        waitHistory()
        capture(name)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("PC 01 — Standard"))
        compose.onNodeWithText("PC 01 — Standard").assertIsDisplayed()
    }

    @Test fun historyPhone() = historyLayout(390, 844, "1.0", "phone-history")

    @Test fun historyTablet() = historyLayout(1100, 760, "1.0", "tablet-history")

    @Test fun historyLandscape() = historyLayout(800, 390, "1.0", "landscape-history")

    @Test fun historyLargeText() = historyLayout(390, 844, "1.5", "large-text-history")

    private fun transactionFiltersLayout(width: Int, height: Int, font: String, name: String) {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        launch(width, height, font)
        change { it.set("page", "Transactions") }
        compose.waitUntil(10000) { lastTransactionQuery.has("p_kind") }
        compose.waitForIdle()
        compose.onAllNodesWithText("Transactions").assertCountEquals(1)
        compose.onNodeWithContentDescription("Back to modules").assertIsDisplayed()
        val typeBounds = compose.onNodeWithTag("transaction-type").fetchSemanticsNode().boundsInRoot
        val statusBounds =
            compose.onNodeWithTag("transaction-status").fetchSemanticsNode().boundsInRoot
        if (width >= 840 && font == "1.0") assertEquals(typeBounds.top, statusBounds.top)
        capture(name)
        compose.onNodeWithTag("transaction-type").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Bookings").performClick()
        compose.waitUntil(5000) { lastTransactionQuery.text("p_kind") == "Booking" }
        compose.onNodeWithTag("transaction-status").performScrollTo().performClick()
        compose.onNode(hasText("Paid") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(5000) { lastTransactionQuery.text("p_status") == "Paid" }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("BOOKING-0001"))
        compose.onNodeWithText("BOOKING-0001").assertIsDisplayed()
        compose.onNodeWithText("COUNTER-0001").assertDoesNotExist()
        change {
            assertEquals("Booking", it.field("transactionKind"))
            assertEquals("Paid", it.field("transactionStatus"))
        }
        compose.onNodeWithContentDescription("Back to modules").performScrollTo().performClick()
        compose.onNodeWithText("Staff modules").assertIsDisplayed()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        change { assertEquals("Operations", it.field("page")) }
    }

    @Test
    fun transactionFiltersPhone() =
        transactionFiltersLayout(390, 844, "1.0", "phone-transaction-filters")

    @Test
    fun transactionFiltersTablet() =
        transactionFiltersLayout(1100, 760, "1.0", "tablet-transaction-filters")

    @Test
    fun transactionFiltersLargeText() =
        transactionFiltersLayout(320, 700, "1.5", "large-text-transaction-filters")

    @Test
    fun transactionFiltersLandscape() =
        transactionFiltersLayout(800, 390, "1.0", "landscape-transaction-filters")

    @Test
    fun datePickerFitsLandscape() {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        launch(800, 390)
        change { it.set("page", "Customer history") }
        waitHistory()
        compose.onNodeWithTag("date-ActivityHistoryDay").performScrollTo().performClick()
        compose.onNodeWithText("Apply").assertIsDisplayed()
        capture("landscape-date-picker")
        compose.onNodeWithText("Apply").performClick()
        change { assertTrue(it.field("ActivityHistoryDay").isNotBlank()) }
    }

    @Test
    fun datePickerFitsSmallPhone() {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        launch(320, 640, "1.3")
        change { it.set("page", "Customer history") }
        waitHistory()
        compose.onNodeWithTag("date-ActivityHistoryDay").performScrollTo().performClick()
        compose.onNodeWithText("Apply").assertIsDisplayed()
        capture("small-phone-date-picker")
        compose.onNodeWithText("Apply").performClick()
    }

    @Test
    fun modulesTransactionsAndDatePicker() {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        launch(1100, 760)
        change { it.set("page", "Modules") }
        compose.onNodeWithText("Staff modules").assertIsDisplayed()
        capture("tablet-modules")
        compose.onNodeWithText("Customer history").performClick()
        waitHistory()
        compose.onNodeWithTag("date-ActivityHistoryDay").performClick()
        capture("tablet-date-picker")
        compose.onNodeWithText("Apply").performClick()
        change {
            assertTrue(it.field("ActivityHistoryDay").isNotBlank())
            it.set("ActivityHistoryDay", "1999-01-01")
        }
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("No records found").fetchSemanticsNodes().isNotEmpty()
        }
        change { it.set("page", "Transactions") }
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("COUNTER-0001").fetchSemanticsNodes().isNotEmpty()
        }
        capture("tablet-transactions")
    }

    @Test
    fun oneScannerRoutesPickupAndEntry() {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        launch(390, 844)
        change { it.set("page", "Operations") }
        compose.onNodeWithText("Scan QR").assertIsDisplayed()
        change { it.reviewStaffCode("ctfly-pickup:22222222-2222-4222-8222-222222222222") }
        compose.waitUntil(5000) { pickupVerified }
        compose.onNodeWithText("Confirm handover").performScrollTo().assertIsDisplayed()
        change {
            it.reviewStaffCode("ctfly:22222222-2222-4222-8222-222222222222")
            assertEquals("scan", it.field("dialog"))
        }
        compose.onNodeWithText("Verify & check in").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun rotationKeepsHistoryFilters() {
        Assume.assumeTrue(BuildConfig.STAFF_APP)
        launch(390, 844)
        change {
            it.set("page", "Customer history")
            it.set("activityCategory", "Orders")
            it.set("ActivityHistoryDay", "2026-10-16")
            it.set("ActivityHistorySearch", "Customer A")
        }
        scenario!!.onActivity {
            it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
        change {
            assertEquals("Customer history", it.field("page"))
            assertEquals("Orders", it.field("activityCategory"))
            assertEquals("2026-10-16", it.field("ActivityHistoryDay"))
            assertEquals("Customer A", it.field("ActivityHistorySearch"))
        }
    }

    @Test
    fun cafeBarOnlyWithCartOrActiveOrder() {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        launch(1100, 760)
        change { it.set("page", "Café") }
        compose.onNodeWithText("Your café orders").assertDoesNotExist()
        compose.onNodeWithText("Review & pay").assertDoesNotExist()
        change { it.cartChange("1", 1) }
        compose.onNodeWithText("Review & pay").assertIsDisplayed()
        change { it.cartChange("1", -1) }
        compose.onNodeWithText("Review & pay").assertDoesNotExist()
        fixture.put(
            "orders",
            JSONArray()
                .put(
                    obj(
                        "id" to "active-order",
                        "paid" to true,
                        "status" to "Preparing",
                        "total" to 650,
                    )
                ),
        )
        change { it.refresh() }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("View orders").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("View orders").assertIsDisplayed()
        fixture.rows("orders").first().put("status", "Received")
        change { it.refresh() }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("View orders").fetchSemanticsNodes().isEmpty()
        }
        capture("tablet-cafe-no-bottom-bar")
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
}
