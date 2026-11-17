package com.ctfly.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.supabase.CtflyApi
import com.ctfly.app.database.remote.text
import com.ctfly.app.state.AppViewModel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class CheckoutLifecycleStateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app
        get() = inst.targetContext.applicationContext as CtflyApplication

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var gateway: PaymentFixtureGateway
    private lateinit var orderId: String
    private var vm: AppViewModel? = null

    private fun change(block: (AppViewModel) -> Unit) {
        scenario!!.onActivity { block(ViewModelProvider(it)[AppViewModel::class.java]) }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(1000)
        compose.waitForIdle()
        val path = File(app.getExternalFilesDir(null), "$name.png")
        inst.uiAutomation.takeScreenshot().let { image ->
            path.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        for (command in
            listOf(
                "mkdir -p /sdcard/Download/CTFLY-checkout-test",
                "cp ${path.absolutePath} /sdcard/Download/CTFLY-checkout-test/$name.png",
            )) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    inst.uiAutomation.executeShellCommand(command)
                )
                .use { it.readBytes() }
        }
    }

    @Before
    fun prepare() {
        Assume.assumeFalse(BuildConfig.STAFF_APP)
        app.getSharedPreferences("ctfly_appearance", 0).edit().clear().commit()
        val fixture =
            JSONObject(
                inst.context.assets.open("payment-customer.json").bufferedReader().use {
                    it.readText()
                }
            )
        val draft =
            fixture.rows("orders").first {
                !it.optBoolean("paid") && it.text("status") == "Pending"
            }
        orderId = draft.text("id")
        fixture
            .put("orders", JSONArray().put(draft))
            .put("test_payments", JSONArray())
            .put("bills", JSONArray())
        gateway = PaymentFixtureGateway(fixture)
        app.gatewayFactory = { gateway }
        app.getSharedPreferences("ctfly_pickup_notifications", 0)
            .edit()
            .putBoolean("permissionAsked.${fixture.getJSONObject("profile").text("id")}", true)
            .commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        change { vm = it }
    }

    @After
    fun cleanup() {
        scenario?.close()
        app.gatewayFactory = { CtflyApi(it) }
    }

    private fun openCheckout() {
        change { it.openCheckout("Order", orderId) }
        compose.waitUntil(8000) { vm!!.checkoutQuote.length() > 0 }
        compose.onNodeWithText("Order sent. Pay at the counter.").assertDoesNotExist()
    }

    private fun assertCancelled() {
        compose.waitUntil(8000) { vm!!.field("page") == "Bookings" && !vm!!.checkoutLeaving }
        assertEquals("Cancelled", gateway.fixture.rows("orders").single().text("status"))
        assertTrue(vm!!.confirmedCafeOrders().isEmpty())
        compose.onNodeWithText("Order #${orderId.take(8)}").assertDoesNotExist()
        compose
            .onNodeWithText("Order cancelled. Place a new order when you're ready.")
            .assertIsDisplayed()
    }

    @Test
    fun cartCreatesOnlyAHoldAndReturningRequiresANewOrder() {
        gateway.fixture.put("orders", JSONArray())
        change { it.refresh(silent = true) }
        compose.waitUntil(8000) { vm!!.rows("orders").isEmpty() }
        val product =
            gateway.fixture.rows("products").first {
                it.optBoolean("active", true) && it.optInt("stock") > 1
            }
        change {
            it.cartChange(product.text("id"), 1)
            it.checkout()
        }
        compose.waitUntil(8000) {
            vm!!.field("page") == "Checkout" && vm!!.checkoutQuote.length() > 0 && !vm!!.busy
        }
        orderId = vm!!.field("checkoutSource")
        val firstOrder = orderId
        assertTrue(vm!!.confirmedCafeOrders().isEmpty())
        assertEquals("", vm!!.message)
        compose.onNodeWithText("Order sent. Pay at the counter.").assertDoesNotExist()
        change { it.leaveCheckout() }
        assertCancelled()
        change {
            it.cartChange(product.text("id"), 1)
            it.checkout()
        }
        compose.waitUntil(8000) {
            vm!!.field("page") == "Checkout" && vm!!.checkoutQuote.length() > 0 && !vm!!.busy
        }
        assertNotEquals(firstOrder, vm!!.field("checkoutSource"))
        assertTrue(vm!!.confirmedCafeOrders().isEmpty())
    }

    @Test
    fun unpaidDraftDoesNotAppearInOrdersOrCafeSummary() {
        change {
            it.set("bookingTab", "Orders")
            it.set("page", "Bookings")
        }
        compose.onNodeWithText("Order #${orderId.take(8)}").assertDoesNotExist()
        compose.onNodeWithText("Your cravings belong here").assertIsDisplayed()
        change { it.set("page", "Café") }
        compose.onNodeWithText("Pay for order").assertDoesNotExist()
        assertTrue(vm!!.confirmedCafeOrders().isEmpty())
    }

    @Test
    fun backBeforePaymentCancelsAndNoticeDisappears() {
        openCheckout()
        compose.onNodeWithContentDescription("Back").performClick()
        assertCancelled()
        capture("checkout-cancelled-orders")
        compose.mainClock.advanceTimeBy(6000)
        compose.waitForIdle()
        compose.waitUntil(7000) {
            compose
                .onAllNodesWithText("Order cancelled. Place a new order when you're ready.")
                .fetchSemanticsNodes()
                .isEmpty()
        }
        assertEquals(1, gateway.cancellationRequests.size)
    }

    @Test
    fun systemBackAfterStartingPaymentCancelsOnlyOnce() {
        openCheckout()
        change { it.startCheckoutPayment() }
        compose.waitUntil(8000) { vm!!.currentCheckoutPayment().text("status") == "Pending" }
        inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        assertCancelled()
        assertEquals(1, gateway.cancellationRequests.size)
        assertEquals("Cancelled", gateway.fixture.rows("test_payments").single().text("status"))
        assertTrue(vm!!.cart().isEmpty())
    }

    @Test
    fun paidOrderAppearsAndLeavingDoesNotCancelIt() {
        openCheckout()
        change { it.startCheckoutPayment() }
        compose.waitUntil(8000) { vm!!.currentCheckoutPayment().text("status") == "Pending" }
        change { it.confirmCheckoutPayment() }
        compose.waitUntil(8000) {
            vm!!.currentCheckoutPayment().text("status") == "Succeeded" && !vm!!.busy
        }
        change { it.leaveCheckout() }
        compose.waitUntil(8000) { vm!!.field("page") == "Bookings" }
        compose.onNodeWithText("Order #${orderId.take(8)}").assertIsDisplayed()
        assertTrue(gateway.cancellationRequests.isEmpty())
        assertTrue(gateway.fixture.rows("orders").single().optBoolean("paid"))
        capture("checkout-paid-order-visible")
    }

    @Test
    fun leavingDuringConfirmationWaitsForSuccess() {
        openCheckout()
        change { it.startCheckoutPayment() }
        compose.waitUntil(8000) { vm!!.currentCheckoutPayment().text("status") == "Pending" }
        gateway.confirmDelayMillis = 600
        change {
            it.confirmCheckoutPayment()
            it.leaveCheckout()
        }
        compose.waitUntil(8000) { vm!!.field("page") == "Bookings" && !vm!!.checkoutLeaving }
        assertTrue(gateway.fixture.rows("orders").single().optBoolean("paid"))
        assertTrue(gateway.cancellationRequests.isEmpty())
    }

    @Test
    fun paymentCompletedElsewhereIsRecoveredInsteadOfReportedCancelled() {
        openCheckout()
        gateway.fixture.rows("orders").single().put("paid", true)
        change { it.leaveCheckout() }
        compose.waitUntil(8000) { vm!!.field("page") == "Bookings" && !vm!!.checkoutLeaving }
        compose.onNodeWithText("Order #${orderId.take(8)}").assertIsDisplayed()
        compose
            .onNodeWithText("Order cancelled. Place a new order when you're ready.")
            .assertDoesNotExist()
        assertTrue(gateway.fixture.rows("orders").single().optBoolean("paid"))
        assertEquals("Pending", gateway.fixture.rows("orders").single().text("status"))
    }

    @Test
    fun failedCancellationStaysInCheckoutAndRetryKeepsRequestId() {
        openCheckout()
        gateway.failCancellation = true
        change { it.leaveCheckout() }
        compose.waitUntil(8000) { !vm!!.busy && !vm!!.checkoutLeaving }
        assertEquals("Checkout", vm!!.field("page"))
        assertEquals("Pending", gateway.fixture.rows("orders").single().text("status"))
        compose
            .onNodeWithText(
                "Unable to confirm cancellation. Check your connection and try leaving again."
            )
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        gateway.failCancellation = false
        change { it.leaveCheckout() }
        assertCancelled()
        assertEquals(gateway.cancellationRequests[0], gateway.cancellationRequests[1])
    }

    @Test
    fun playBannerAndFiltersScrollWithAllActivityCards() {
        change { it.set("page", "Play") }
        for (kind in listOf("PC", "VIPRoom", "Pool", "Racing")) {
            change {
                it.set("playKind", kind)
                it.set("zone", "All")
            }
            compose.onNodeWithTag("play-list").performScrollToIndex(0)
            compose.onNodeWithTag("play-header").assertIsDisplayed()
            compose.onNodeWithTag("play-list").performScrollToIndex(1)
            compose.onNodeWithTag("play-header").assertIsNotDisplayed()
            capture("play-scrolled-$kind")
            compose.onNodeWithTag("play-list").performScrollToIndex(0)
            compose.onNodeWithTag("play-header").assertIsDisplayed()
        }
    }
}
