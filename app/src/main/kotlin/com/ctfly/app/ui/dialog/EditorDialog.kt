@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ctfly.app.ui.dialog

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ctfly.app.database.remote.profileId
import com.ctfly.app.database.remote.rows
import com.ctfly.app.database.remote.text
import com.ctfly.app.domain.CafeZone
import com.ctfly.app.domain.VenueDirectory
import com.ctfly.app.domain.moneyInputSen
import com.ctfly.app.domain.validateFormFields
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.admin.events.PosterPicker
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.DateFields
import com.ctfly.app.ui.common.Input
import com.ctfly.app.ui.common.Select
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import org.json.JSONObject

@Composable
fun TimeFields(label: String, value: String, onChange: (String) -> Unit) {
    val t = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(18, 0))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Select(
            "$label · hour",
            t.hour.toString(),
            (0..23).map { it.toString() to "%02d".format(it) },
            { onChange(t.withHour(it.toInt()).toString()) },
            Modifier.weight(1f),
        )
        Select(
            "Minute",
            t.minute.toString(),
            (0..59).map { it.toString() to "%02d".format(it) },
            { onChange(t.withMinute(it.toInt()).toString()) },
            Modifier.weight(1f),
        )
    }
}

data class FormField(
    val key: String,
    val label: String,
    val kind: String = "text",
    val options: List<Pair<String, String>> = emptyList(),
    val default: String = "",
    val optional: Boolean = false,
)

fun choices(vararg values: String) = values.map { it to it }

@Composable
fun EditorDialog(vm: AppViewModel) {
    val type = vm.field("formType")
    val current = runCatching { JSONObject(vm.field("formData", "{}")) }.getOrDefault(JSONObject())
    val staff =
        vm.rows("people")
            .filter { it.text("role") in listOf("staff", "admin") }
            .map { it.profileId() to it.text("name") }
    val fields =
        when (type) {
            "save_resource" ->
                listOf(
                    FormField("name", "Space name"),
                    FormField(
                        "kind",
                        "Type",
                        "select",
                        VenueDirectory.kinds.map { it to VenueDirectory.label(it) },
                        "PC",
                    ),
                    FormField(
                        "zone",
                        "Zone",
                        "select",
                        choices(
                            "Standard",
                            "VIP",
                            "Solo",
                            "Duo",
                            "Squad",
                            "Social",
                            "Lounge",
                            "Simulator",
                        ),
                        "Standard",
                    ),
                    FormField("description", "Specifications / description"),
                    FormField("capacity", "Player capacity", "number", default = "1"),
                    FormField("rate", "Hourly rate (sen: RM 6 = 600)", "number"),
                    FormField(
                        "status",
                        "Availability",
                        "select",
                        choices("Available", "Maintenance", "Retired"),
                        "Available",
                    ),
                )
            "save_product" ->
                listOf(
                    FormField("name", "Product name"),
                    FormField(
                        "category_id",
                        "Category",
                        "select",
                        vm.rows("categories").map { it.text("id") to it.text("name") },
                    ),
                    FormField("price", "Price (sen: RM 6 = 600)", "number"),
                    FormField("threshold", "Low stock threshold", "number", default = "5"),
                    FormField(
                        "image",
                        "Product image",
                        "select",
                        choices(
                            "iced_coffee",
                            "iced_lemon_tea",
                            "energy_drink",
                            "mineral_water",
                            "chicken_burger",
                            "fries",
                            "cup_noodles",
                            "potato_chips",
                            "game_card",
                        ),
                        "iced_coffee",
                    ),
                    FormField(
                        "active",
                        "On sale",
                        "select",
                        listOf("true" to "Yes", "false" to "No"),
                        "true",
                    ),
                )
            "restock" ->
                listOf(
                    FormField("quantity", "Quantity", "number"),
                    FormField("reason", "Supplier / restock note"),
                )
            "save_category" -> listOf(FormField("name", "Category name"))
            "save_event" ->
                listOf(
                    FormField("name", "Competition name"),
                    FormField("game", "Game"),
                    FormField("description", "Details / rules", "multiline", optional = true),
                    FormField("starts_at", "Starts", "datetime"),
                    FormField("ends_at", "Ends", "datetime"),
                    FormField("registration_closes_at", "Registration closes", "datetime"),
                    FormField("fee_rm", "Entry fee (RM)", "money", default = "0.00"),
                    FormField("capacity", "Team slots (2–64)", "number", default = "8"),
                    FormField(
                        "prize_first",
                        "First prize",
                        "multiline",
                        default = "To be announced",
                    ),
                    FormField(
                        "prize_second",
                        "Second prize",
                        "multiline",
                        default = "To be announced",
                    ),
                    FormField(
                        "prize_third",
                        "Third prize",
                        "multiline",
                        default = "To be announced",
                    ),
                    FormField(
                        "prize_consolation",
                        "Consolation prize",
                        "multiline",
                        default = "No consolation prize",
                    ),
                    FormField(
                        "status",
                        "Status",
                        "select",
                        choices("Draft", "Open", "Cancelled"),
                        "Draft",
                    ),
                )
            "save_promotion" ->
                listOf(
                    FormField("code", "Promo code"),
                    FormField("title", "Promo title"),
                    FormField(
                        "scope",
                        "Applies to",
                        "select",
                        choices("All", "Booking", "Order", "Event"),
                        "Booking",
                    ),
                    FormField(
                        "benefit",
                        "Benefit",
                        "select",
                        choices("Percentage", "Fixed", "Free"),
                        "Percentage",
                    ),
                ) +
                    (when (current.text("benefit", "Percentage")) {
                        "Fixed" ->
                            listOf(
                                FormField("amount_rm", "Discount (RM)", "money", default = "5.00")
                            )
                        "Free" -> emptyList()
                        else ->
                            listOf(FormField("percent", "Discount (%)", "number", default = "10"))
                    }) +
                    listOf(
                        FormField(
                            "resource_kind",
                            "Booking activity",
                            "select",
                            choices(
                                "All",
                                "PC",
                                "VIPRoom",
                                "Console",
                                "Racing",
                                "Arcade",
                                "GoKart",
                                "Pool",
                                "Snooker",
                            ),
                            "All",
                        ),
                        FormField(
                            "max_minutes",
                            "Maximum minutes (0 = any)",
                            "number",
                            default = "0",
                        ),
                        FormField("starts_at", "Valid from", "datetime"),
                        FormField("ends_at", "Expires", "datetime"),
                        FormField("usage_limit", "Total uses", "number", default = "50"),
                        FormField("per_user_limit", "Uses per customer", "number", default = "1"),
                        FormField(
                            "target_user",
                            "Customer (optional)",
                            "select",
                            listOf("" to "Any customer") +
                                vm.rows("people")
                                    .filter { it.text("role") == "customer" }
                                    .map { it.profileId() to it.text("name") },
                            optional = true,
                        ),
                        FormField(
                            "active",
                            "Enabled",
                            "select",
                            listOf("true" to "Yes", "false" to "No"),
                            "true",
                        ),
                    )
            "save_reward" ->
                listOf(
                    FormField("name", "Reward name"),
                    FormField("points", "Points required", "number"),
                    FormField("stock", "Available quantity", "number"),
                    FormField(
                        "active",
                        "Available",
                        "select",
                        listOf("true" to "Yes", "false" to "No"),
                        "true",
                    ),
                )
            "void_bill" -> listOf(FormField("reason", "Reason / cash refund note", "multiline"))
            "member_profile" ->
                listOf(FormField("name", "Member name"), FormField("phone", "Phone number"))
            "weekly_roster" ->
                listOf(
                    FormField("staff_id", "Staff member", "select", staff),
                    FormField("day", "Week begins", "date"),
                    FormField(
                        "pattern",
                        "Working days",
                        "select",
                        choices("Weekdays", "Every day", "Weekend"),
                        "Weekdays",
                    ),
                    FormField("start_time", "Start time", "time", default = "09:00"),
                    FormField("end_time", "End time", "time", default = "17:00"),
                )
            "schedule" ->
                listOf(
                    FormField("staff_id", "Staff member", "select", staff),
                    FormField("starts_at", "Shift start", "datetime"),
                    FormField("ends_at", "Shift end", "datetime"),
                )
            "task" ->
                (if (vm.admin) listOf(FormField("staff_id", "Assigned to", "select", staff))
                else emptyList()) +
                    listOf(FormField("title", "Mission"), FormField("day", "Due date", "date"))
            "leave" ->
                listOf(
                    FormField("day", "Leave date", "date"),
                    FormField("reason", "Reason", "multiline"),
                )
            "handover" ->
                listOf(
                    FormField(
                        "category",
                        "Category",
                        "select",
                        choices("Opening", "Closing", "Stock", "General"),
                        "General",
                    ),
                    FormField("title", "Title"),
                    FormField("note", "Handover details", "multiline"),
                )
            "ticket" ->
                listOf(
                    FormField("asset", "PC / equipment name"),
                    FormField("title", "What needs attention?", "multiline"),
                    FormField(
                        "priority",
                        "Priority",
                        "select",
                        choices("Low", "Normal", "Urgent"),
                        "Normal",
                    ),
                )
            "resolve" -> listOf(FormField("resolution", "What was fixed?", "multiline"))
            "extend_booking" ->
                listOf(
                    FormField(
                        "minutes",
                        "Extra time",
                        "select",
                        listOf("30" to "30 minutes", "60" to "60 minutes"),
                        "30",
                    )
                )
            else -> emptyList()
        }
    val initialTime = remember {
        ZonedDateTime.now(CafeZone).plusDays(1).withHour(18).withMinute(0).withSecond(0).withNano(0)
    }
    fun default(f: FormField) =
        when (f.kind) {
            "date" -> LocalDate.now(CafeZone).toString()
            "datetime" ->
                (if (type == "save_promotion") {
                        if (f.key == "ends_at") initialTime.plusDays(30)
                        else java.time.Instant.ofEpochMilli(vm.nowMillis).atZone(CafeZone)
                    } else if (f.key == "ends_at") initialTime.plusHours(4) else initialTime)
                    .toInstant()
                    .toString()
            "select" -> f.default.ifBlank { f.options.firstOrNull()?.first.orEmpty() }
            else -> f.default
        }
    fun update(key: String, value: String) {
        vm.set("formData", JSONObject(current.toString()).put(key, value).toString())
    }
    DialogFrame(
        when (type) {
            "save_event" -> "Competition details"
            "save_promotion" -> "Promo code details"
            else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
        },
        vm,
    ) {
        if (type == "save_event") PosterPicker(vm, current.text("poster_url"))
        fields.forEach { f ->
            val value = current.text(f.key, default(f))
            when (f.kind) {
                "select" -> Select(f.label, value, f.options, { update(f.key, it) })
                "date" -> DateFields(f.label, value, { update(f.key, it) })
                "time" -> TimeFields(f.label, value, { update(f.key, it) })
                "datetime" -> {
                    val dt =
                        runCatching { ZonedDateTime.parse(value).withZoneSameInstant(CafeZone) }
                            .getOrDefault(initialTime)
                    DateFields(
                        "${f.label} · Malaysia time",
                        dt.toLocalDate().toString(),
                        {
                            update(
                                f.key,
                                LocalDateTime.of(LocalDate.parse(it), dt.toLocalTime())
                                    .atZone(CafeZone)
                                    .toInstant()
                                    .toString(),
                            )
                        },
                    )
                    TimeFields(
                        f.label,
                        dt.toLocalTime().toString(),
                        {
                            update(
                                f.key,
                                LocalDateTime.of(dt.toLocalDate(), LocalTime.parse(it))
                                    .atZone(CafeZone)
                                    .toInstant()
                                    .toString(),
                            )
                        },
                    )
                }
                else ->
                    Input(
                        f.label,
                        value,
                        { update(f.key, it) },
                        numeric = f.kind == "number",
                        decimal = f.kind == "money",
                        multiline = f.kind == "multiline",
                    )
            }
        }
        Button(
            onClick = {
                val payload = JSONObject(current.toString())
                fields.forEach { f -> if (!payload.has(f.key)) payload.put(f.key, default(f)) }
                val errors =
                    validateFormFields(
                        type,
                        fields.associate { it.key to payload.text(it.key) },
                        fields,
                    )
                if (errors.isNotEmpty()) vm.invalid(errors.joinToString("\n"))
                else {
                    if (type == "save_event")
                        payload.put("fee", moneyInputSen(payload.text("fee_rm")))
                    if (type == "save_promotion")
                        payload.put(
                            "amount",
                            when (payload.text("benefit")) {
                                "Free" -> 0
                                "Fixed" -> moneyInputSen(payload.text("amount_rm"))
                                else -> payload.text("percent").toInt()
                            },
                        )
                    vm.action(type, payload) {
                        vm.set("dialog", "")
                        vm.set("formData", "{}")
                    }
                }
            },
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AppText("Save")
        }
    }
}
