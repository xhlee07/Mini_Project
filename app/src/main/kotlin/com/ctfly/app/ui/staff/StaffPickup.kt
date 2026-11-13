package com.ctfly.app.ui.staff

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ctfly.app.data.obj
import com.ctfly.app.data.rows
import com.ctfly.app.data.text
import com.ctfly.app.state.AppText
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.Aqua
import com.ctfly.app.ui.common.Lime
import com.ctfly.app.ui.common.Muted
import org.json.JSONObject

@Composable
fun StaffPickupContent(vm: AppViewModel) {
    val context = LocalContext.current
    val preview = runCatching { JSONObject(vm.field("pickupPreview")) }.getOrDefault(JSONObject())
    val order = preview.optJSONObject("order")
    if (order != null) {
        AppText("Order #${order.text("id").take(8)}", fontWeight = FontWeight.Bold)
        AppText(preview.text("customer_name"), color = Aqua)
        preview.rows("items").forEach { AppText("${it.optInt("qty")} × ${it.text("name")}") }
        AppText("Paid · Ready for pickup", color = Lime, fontWeight = FontWeight.Bold)
        AppText(
            "Check the items, then hand the order to this customer.",
            color = Muted,
            fontSize = 13.sp,
        )
        Button(
            onClick = {
                vm.action(
                    "order_pickup",
                    obj("token" to vm.field("pickupScanToken")),
                    onSuccess = {
                        vm.set("pickupScanToken", "")
                        vm.set("pickupPreview", "")
                        vm.set("dialog", "")
                    },
                )
            },
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            AppText("Confirm handover")
        }
    } else {
        AppText(
            "Scan the QR shown in the customer's ready order. Payment, readiness and prior collection are checked by the server.",
            color = Muted,
            fontSize = 13.sp,
        )
        Button(
            onClick = { startStaffScan(context, vm) },
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AppText("Scan QR")
        }
    }
}
