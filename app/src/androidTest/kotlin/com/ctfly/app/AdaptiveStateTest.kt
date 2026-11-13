package com.ctfly.app

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ctfly.app.auth.StaffSession
import com.ctfly.app.data.ClubGateway
import com.ctfly.app.data.CtflyApi
import com.ctfly.app.data.obj
import com.ctfly.app.data.rows
import com.ctfly.app.data.text
import com.ctfly.app.domain.onStaffDay
import com.ctfly.app.state.AppPreferences
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.CtflyTheme
import com.ctfly.app.ui.common.DateFields
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FixtureGateway(private val fixture: JSONObject, override var signedIn: Boolean) :
    ClubGateway {
    // Existing UI fixtures represent a staff member who has just verified their password.
    init {
        if (BuildConfig.STAFF_APP && signedIn) StaffSession(
            InstrumentationRegistry.getInstrumentation().targetContext
        ).verified(fixture.getJSONObject("profile").text("id"))
    }
    override val accountId get() = fixture.optJSONObject("profile")?.text("id").orEmpty()
    override val accountEmail get() = fixture.optJSONObject("profile")?.text("email").orEmpty()
    @Volatile var lastBooking: JSONObject? = null
    @Volatile var lastFeedback: JSONObject? = null
    @Volatile var failFeedbackOnce = false
    val rpcRequests = mutableListOf<String>()
    val feedbackRequests = mutableListOf<String>()
    private val submittedFeedback = mutableMapOf<String, String>()
    val feedbackCount
        get() = fixture.rows("feedback").size

    override suspend fun rpc(name: String, body: JSONObject, authenticated: Boolean): JSONObject {
        rpcRequests.add(name)
        if (name == "ctfly_staff_history" || name == "ctfly_staff_transactions") {
            val module =
                if (name == "ctfly_staff_transactions") "Transactions" else body.text("p_module")
            val source =
                if (module == "Feedback") fixture.rows("feedback")
                else fixture.rows("test_payments")
            val filtered =
                source
                    .filter {
                        (body.text("p_status", body.text("p_category", "All")) == "All" ||
                            it.text("status") == body.text("p_status", body.text("p_category"))) &&
                            (body.text("p_kind", "All") == "All" ||
                                it.text("kind") == body.text("p_kind")) &&
                            onStaffDay(it.text("created_at"), body.text("p_day"))
                    }
                    .map { row ->
                        JSONObject(row.toString())
                            .put("history_key", row.text("id"))
                            .put("feedback_category", row.text("category"))
                            .put("category", module)
                            .put(
                                "title",
                                if (module == "Transactions") row.text("kind")
                                else row.text("title"),
                            )
                            .put("payment", row)
                    }
            return obj("items" to org.json.JSONArray(filtered), "has_more" to false)
        }
        if (name == "ctfly_action" && body.text("p_action") == "feedback") {
            lastFeedback = JSONObject(body.getJSONObject("p_data").toString())
            val request = body.text("p_request")
            feedbackRequests.add(request)
            val id =
                submittedFeedback.getOrPut(request) {
                    val created = "fixture-feedback-${submittedFeedback.size}"
                    val row =
                        JSONObject(lastFeedback.toString()).apply {
                            put("id", created)
                            put("user_id", fixture.getJSONObject("profile").text("id"))
                            put("customer_name", fixture.getJSONObject("profile").text("name"))
                            put("created_at", java.time.Instant.now().toString())
                            put("status", "New")
                        }
                    fixture.optJSONArray("feedback")?.put(row)
                    created
                }
            if (failFeedbackOnce) {
                failFeedbackOnce = false
                throw java.io.IOException("Please retry feedback.")
            }
            return obj("message" to "Feedback sent. Thank you!", "id" to id)
        }
        if (name == "ctfly_action" && body.text("p_action") == "feedback_status") {
            val data = body.getJSONObject("p_data")
            fixture
                .rows("feedback")
                .find { it.text("id") == data.text("id") }
                ?.put("status", data.text("status"))
            return obj("message" to "Feedback status updated.")
        }
        if (name == "ctfly_action" && body.text("p_action") == "book") {
            lastBooking = JSONObject(body.getJSONObject("p_data").toString())
            return obj("message" to "Reserved", "id" to "fixture-booking")
        }
        if (name == "ctfly_quote") {
            kotlinx.coroutines.delay(if (body.text("p_kind") == "Extension30") 100 else 10)
            return obj(
                "id" to body.text("p_id"),
                "kind" to body.text("p_kind"),
                "subtotal" to 600,
                "discount" to 0,
                "total" to 600,
            )
        }
        return JSONObject(fixture.toString())
    }

    override suspend fun login(email: String, password: String) {
        signedIn = true
    }

    override suspend fun signup(
        email: String,
        password: String,
        name: String,
        dob: String,
        phone: String,
    ) = false

    override suspend fun recover(email: String) {}

    override suspend fun resend(email: String) {}

    override suspend fun verify(email: String, code: String, type: String) {
        signedIn = true
    }

    override suspend fun newPassword(password: String) {}

    override suspend fun logout() {
        signedIn = false
    }

    override fun localLogout() {
        signedIn = false
    }
}

class AdaptiveStateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var latestGateway: FixtureGateway? = null
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val app
        get() = instrumentation.targetContext.applicationContext as CtflyApplication

    private fun launch(
        role: String = "customer",
        signedIn: Boolean = true,
        allowWrongRole: Boolean = false,
    ) {
        app.getSharedPreferences("ctfly_appearance", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        if (signedIn && !allowWrongRole)
            org.junit.Assume.assumeTrue(BuildConfig.STAFF_APP == (role != "customer"))
        val fixture =
            JSONObject(
                instrumentation.context.assets.open("$role.json").bufferedReader().use {
                    it.readText()
                }
            )
        val gateway = FixtureGateway(fixture, signedIn)
        latestGateway = gateway
        app.gatewayFactory = { gateway }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun change(action: (AppViewModel) -> Unit) {
        scenario!!.onActivity { action(ViewModelProvider(it)[AppViewModel::class.java]) }
        compose.waitForIdle()
    }

    private fun capture(name: String, settle: Boolean = true) {
        compose.waitForIdle()
        if (settle) Thread.sleep(1200)
        compose.waitForIdle()
        val directory = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            File(directory, "$name.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        shell("mkdir -p /sdcard/Download/CTFLY-test")
        shell("cp ${directory.absolutePath}/$name.png /sdcard/Download/CTFLY-test/$name.png")
    }

    @After
    fun cleanup() {
        scenario?.close()
        app.gatewayFactory = { CtflyApi(it) }
        shell("wm size reset")
        shell("wm density reset")
        shell("settings put system font_scale 1.0")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
        Thread.sleep(600)
    }

    @Test
    fun settingsPersistLanguagesColorsAndRetryFeedbackWithoutDuplicates() {
        org.junit.Assume.assumeFalse(BuildConfig.STAFF_APP)
        launch()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("language-ms").performClick()
        compose.onNodeWithText("Tetapan").assertIsDisplayed()
        compose.onNodeWithTag("background-navy").performScrollTo().performClick()
        capture("settings-customer-malay")
        scenario!!.recreate()
        compose.onNodeWithText("Tetapan").assertIsDisplayed()
        val preferences = AppPreferences(app)
        assertEquals("ms", preferences.language)
        assertEquals("navy", preferences.background)
        compose.onNodeWithTag("language-en").performScrollTo().performClick()
        compose.onNodeWithTag("sendFeedback").performScrollTo().assertIsNotEnabled()
        compose
            .onNodeWithText("Feedback title")
            .performScrollTo()
            .performTextInput("Settings are useful")
        compose
            .onNodeWithText("Your feedback")
            .performScrollTo()
            .performTextInput("I like choosing a background color and changing the language.")
        val countBefore = latestGateway!!.feedbackCount
        latestGateway!!.failFeedbackOnce = true
        compose.onNodeWithTag("sendFeedback").performScrollTo().performClick()
        compose.waitUntil(5000) {
            var done = false
            scenario!!.onActivity { done = !ViewModelProvider(it)[AppViewModel::class.java].busy }
            !latestGateway!!.failFeedbackOnce && done
        }
        compose.onNodeWithText("Please retry feedback.").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose
            .onNodeWithText("Your feedback")
            .assertTextContains("I like choosing a background color and changing the language.")
        compose.onNodeWithTag("sendFeedback").performClick()
        compose.waitUntil(5000) { latestGateway!!.feedbackRequests.size == 2 }
        assertEquals(latestGateway!!.feedbackRequests[0], latestGateway!!.feedbackRequests[1])
        assertEquals(countBefore + 1, latestGateway!!.feedbackCount)
        compose.waitUntil(5000) {
            var empty = false
            scenario!!.onActivity {
                empty =
                    ViewModelProvider(it)[AppViewModel::class.java].field("feedbackBody").isEmpty()
            }
            empty
        }
        compose.onNodeWithTag("sendFeedback").assertIsNotEnabled()
        capture("settings-feedback-submitted")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun staffCanReadAndProcessCustomerFeedback() {
        org.junit.Assume.assumeTrue(BuildConfig.STAFF_APP)
        launch("staff")
        compose.onNodeWithContentDescription("Customer feedback").performClick()
        var row: JSONObject? = null
        scenario!!.onActivity {
            row =
                ViewModelProvider(it)[AppViewModel::class.java].rows("feedback").first { f ->
                    f.text("status") == "New"
                }
        }
        val id = row!!.text("id")
        compose.waitUntil(5000) { latestGateway!!.rpcRequests.contains("ctfly_staff_history") }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(row!!.text("body")))
        compose.onNodeWithText(row!!.text("body")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reviewFeedback-$id").performScrollTo().performClick()
        compose.waitUntil(5000) {
            var status = ""
            scenario!!.onActivity {
                status =
                    ViewModelProvider(it)[AppViewModel::class.java].rows("feedback")
                        .first { f -> f.text("id") == id }
                        .text("status")
            }
            status == "Reviewed"
        }
        compose.onNodeWithTag("resolveFeedback-$id").performScrollTo().performClick()
        compose.waitUntil(5000) {
            var status = ""
            scenario!!.onActivity {
                status =
                    ViewModelProvider(it)[AppViewModel::class.java].rows("feedback")
                        .first { f -> f.text("id") == id }
                        .text("status")
            }
            status == "Resolved"
        }
        capture("settings-staff-feedback-inbox")
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("language-ms").performClick()
        compose.onNodeWithTag("background-forest").performScrollTo().performClick()
        compose.onNodeWithText("Buka peti maklum balas").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Baca maklum balas ahli kelab.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Maklum balas pelanggan").assertIsDisplayed()
        capture("settings-staff-malay-feedback")
    }

    @Test
    fun privateRoomPartySelectionAndSimulatorTiersUseCorrectBookingPayload() {
        org.junit.Assume.assumeFalse(BuildConfig.STAFF_APP)
        launch()
        change { it.set("page", "Play") }
        compose.onNodeWithText("VIP room").performScrollTo().performClick()
        compose.onNodeWithText("3 players").performScrollTo().performClick()
        compose.onNodeWithText("VIP Room Squad 01").assertIsDisplayed()
        compose.onAllNodesWithText("VIP Room Solo 01").assertCountEquals(0)
        capture("usability-vip-booking-catalog")
        compose.onAllNodesWithText("Reserve")[0].performClick()
        compose.onNodeWithText("3 player(s)").assertIsDisplayed()
        compose.onNodeWithText("RM 26.00").assertIsDisplayed()
        capture("usability-vip-booking-three")
        compose.onNodeWithText("Confirm reservation").performScrollTo().performClick()
        compose.waitUntil(5000) { latestGateway!!.lastBooking != null }
        assertEquals(3, latestGateway!!.lastBooking!!.optInt("guests"))
        assertEquals(30, latestGateway!!.lastBooking!!.optInt("minutes"))
        change { it.set("page", "Play") }
        compose.onNodeWithText("Racing simulator").performScrollTo().performClick()
        compose.onNodeWithText("VIP", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("VIP Racing Simulator 01").assertIsDisplayed()
        capture("usability-simulator-booking-vip")
        compose.onAllNodesWithText("Reserve")[0].performClick()
        compose.onNodeWithText("1 player(s)").assertIsDisplayed()
        compose.onNodeWithText("RM 17.50").assertIsDisplayed()
        compose.onNodeWithText("Confirm reservation").performScrollTo().performClick()
        compose.waitUntil(5000) { latestGateway!!.lastBooking!!.optInt("guests") == 1 }
        val id = latestGateway!!.lastBooking!!.optLong("resource_id")
        scenario!!.onActivity {
            val r =
                ViewModelProvider(it)[AppViewModel::class.java].rows("resources").first { r ->
                    r.optLong("id") == id
                }
            assertEquals("Racing", r.text("kind"))
            assertEquals("VIP", r.text("zone"))
        }
    }

    @Test
    fun expandedBookingGroupsShowFloorAndKartChoices() {
        org.junit.Assume.assumeFalse(BuildConfig.STAFF_APP)
        launch()
        change {
            it.set("page", "Play")
            it.set("playKind", "Pool")
        }
        compose.onNodeWithText("8 pool facilities · Choose your time").assertExists()
        capture("booking-pool-expanded")
        compose.onNodeWithText("Go-kart", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("6 go-kart facilities · Choose your time").assertExists()
        capture("booking-outdoor-expanded")
    }

    @Test
    fun birthDateFitsSmallPhoneWithLargeText() {
        org.junit.Assume.assumeFalse(BuildConfig.STAFF_APP)
        shell("wm size 960x1920")
        shell("wm density 480")
        launch(signedIn = false)
        scenario!!.onActivity { activity ->
            activity.setContent {
                val original = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(original.density, 1.6f)
                ) {
                    CtflyTheme {
                        androidx.compose.foundation.layout.Column(
                            androidx.compose.ui.Modifier.fillMaxWidth().padding(24.dp)
                        ) {
                            DateFields("Date of birth · 18+", "2008-01-31", {}, true)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Day").assertIsDisplayed().performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
        ) { action ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            action(layouts)
            assertEquals(1, layouts.single().lineCount)
        }
        compose.onNodeWithText("Year").assertIsDisplayed()
        compose.onNodeWithText("2008").assertIsDisplayed()
        capture("phone-date-large-text")
    }

    @Test
    fun standaloneLoginHasNoPortalSwitcher() {
        launch(signedIn = false)
        compose.onNodeWithText("Explore the club first").assertDoesNotExist()
        compose.onNodeWithText("Customer").assertDoesNotExist()
        compose.onNodeWithText("Staff / Admin").assertDoesNotExist()
        if (BuildConfig.STAFF_APP) {
            compose.onNodeWithText("New here? Join CTFLY").assertDoesNotExist()
            compose.onNodeWithText("Explore the club first").assertDoesNotExist()
            compose.onNodeWithText("Your shift starts here.").assertExists()
        } else {
            compose.onNodeWithText("New here? Join CTFLY").assertExists()
        }
        capture(
            if (BuildConfig.STAFF_APP) "staff-standalone-login" else "customer-standalone-login"
        )
    }

    @Test
    fun wrongRoleCannotRestoreSession() {
        launch(if (BuildConfig.STAFF_APP) "customer" else "staff", allowWrongRole = true)
        compose.waitUntil(5000) {
            var rejected = false
            scenario!!.onActivity {
                rejected = !ViewModelProvider(it)[AppViewModel::class.java].hasAccount
            }
            rejected
        }
        compose.onNodeWithText("Email address").assertExists()
        assertFalse(app.gatewayFactory(app).signedIn)
    }

    @Test
    fun wrongRoleCannotSignIn() {
        launch(if (BuildConfig.STAFF_APP) "customer" else "staff", signedIn = false)
        change {
            it.set("email", "wrong-role@example.test")
            it.password = "GoodPassword123"
            it.authenticate()
        }
        compose.waitUntil(5000) {
            var done = false
            scenario!!.onActivity { done = !ViewModelProvider(it)[AppViewModel::class.java].busy }
            done
        }
        scenario!!.onActivity {
            assertFalse(ViewModelProvider(it)[AppViewModel::class.java].hasAccount)
        }
        compose.onNodeWithText("Email address").assertExists()
    }

    @Test
    fun typedLoginAndPasswordSurviveRotation() {
        launch(signedIn = false)
        compose.onNodeWithText("Email address").performTextInput("player@example.test")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("A_Strong_Password")
        scenario!!.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
        scenario!!.onActivity {
            val vm = ViewModelProvider(it)[AppViewModel::class.java]
            assertEquals("player@example.test", vm.field("email"))
            assertEquals("A_Strong_Password", vm.password)
        }
        compose
            .onNodeWithText("Email address")
            .performScrollTo()
            .assertTextContains("player@example.test")
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10000) {
            app.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        compose.waitForIdle()
        capture("phone-login")
    }

    @Test
    fun shoppingAndBookingDraftSurviveRecreation() {
        launch()
        change {
            it.cartChange("1", 2)
            it.set("bookDate", "2030-01-01")
            it.set("bookTime", "19:45")
            it.set("selectedResource", "1")
            it.set("page", "Play")
            it.set("dialog", "book")
        }
        scenario!!.recreate()
        compose.waitForIdle()
        scenario!!.onActivity {
            val vm = ViewModelProvider(it)[AppViewModel::class.java]
            assertEquals(2, vm.cart()["1"])
            assertEquals("19:45", vm.field("bookTime"))
            assertEquals("book", vm.field("dialog"))
        }
        compose.onNodeWithText("Make it your time.").assertExists()
        capture("phone-booking")
    }

    @Test
    fun phoneAllCustomerScreensAndCafeSearch() {
        launch()
        capture("phone-discover")
        for (page in listOf("Play", "Café", "Events", "Club", "Bookings")) {
            change { it.set("page", page) }
            capture("phone-${page.replace("é","e").lowercase()}")
        }
        change { it.set("page", "Café") }
        compose.onNodeWithText("Search drinks & bites").performTextInput("tea")
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Search drinks & bites").assertTextContains("tea")
        compose.onNodeWithText("Iced lemon tea").assertExists()
    }

    @Test
    fun staffSeesOnlyCrewPages() {
        launch("staff")
        compose.onNodeWithText("Keep the club moving.").assertExists()
        compose.onNodeWithText("Discover").assertDoesNotExist()
        compose.onNodeWithText("Café").assertDoesNotExist()
        for (page in listOf("Operations", "My shifts", "Handover", "Equipment")) {
            change { it.set("page", page) }
            capture("staff-${page.replace(" ","-")}")
        }
        change { it.set("page", "Café") }
        compose.onNodeWithText("Keep the club moving.").assertExists()
    }

    @Test
    fun tabletUsesRailAndSecondPane() {
        shell("wm size 1600x1000")
        shell("wm density 160")
        launch()
        scenario!!.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
        compose.onNodeWithText("YOUR NEXT SESSION").assertExists()
        capture("tablet-discover")
        change { it.set("page", "Café") }
        capture("tablet-cafe")
    }

    @Test
    fun smallPhoneWithLargeTextKeepsFormsUsable() {
        shell("wm size 640x1280")
        shell("wm density 320")
        shell("settings put system font_scale 1.3")
        try {
            launch(signedIn = false)
            compose
                .onNodeWithText("Email address")
                .performScrollTo()
                .performTextInput("small@example.test")
            compose.onNodeWithText("Password").performScrollTo().performTextInput("GoodPassword123")
            scenario!!.recreate()
            compose.waitForIdle()
            compose
                .onNodeWithText("Email address")
                .performScrollTo()
                .assertTextContains("small@example.test")
            capture("small-phone-large-text")
        } finally {
            shell("settings put system font_scale 1.0")
        }
    }

    @Test
    fun latestExtensionQuoteWinsWhenSelectionChanges() {
        launch("staff")
        change { vm ->
            vm.set("paymentId", "00000000-0000-0000-0000-000000000001")
            vm.set("paymentKind", "Extension30")
            vm.quotePayment()
            vm.set("paymentKind", "Extension60")
            vm.quotePayment()
        }
        compose.waitUntil(5000) {
            var done = false
            scenario!!.onActivity {
                done =
                    ViewModelProvider(it)[AppViewModel::class.java].paymentQuote.text("kind") ==
                        "Extension60"
            }
            done
        }
        scenario!!.onActivity {
            assertEquals(
                600,
                ViewModelProvider(it)[AppViewModel::class.java].paymentQuote.optInt("total"),
            )
        }
    }

    @Test
    fun managerPagesRenderWithData() {
        launch("admin")
        for (tab in
            listOf("Spaces", "Inventory", "Events", "Staff", "Members", "Rewards", "Reports")) {
            change {
                it.set("page", "Manage")
                it.set("manageTab", tab)
            }
            capture("manager-${tab.lowercase()}")
        }
    }
}
