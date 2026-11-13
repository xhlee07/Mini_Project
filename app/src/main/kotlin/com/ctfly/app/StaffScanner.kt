package com.ctfly.app

import android.content.Context
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

enum class StaffQrKind {
    ENTRY,
    PICKUP,
    UNSUPPORTED,
}

fun staffQrKind(raw: String): StaffQrKind {
    val code = raw.trim()
    val uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    return when {
        Regex("ctfly-pickup:$uuid").matches(code) -> StaffQrKind.PICKUP
        Regex("(?:ctfly:)?$uuid").matches(code) -> StaffQrKind.ENTRY
        else -> StaffQrKind.UNSUPPORTED
    }
}

/** One camera entry point; each QR still uses its own server authorization checks. */
fun startStaffScan(context: Context, vm: AppViewModel) {
    GmsBarcodeScanning.getClient(context)
        .startScan()
        .addOnSuccessListener { barcode -> vm.reviewStaffCode(barcode.rawValue.orEmpty()) }
        .addOnFailureListener {
            vm.set("scanToken", "")
            vm.set("dialog", "scan")
            vm.invalid("Scanner unavailable. Enter the customer's QR code manually or try again.")
        }
}

fun AppViewModel.reviewStaffCode(raw: String) {
    val token = raw.trim()
    set("scanToken", token)
    when (staffQrKind(token)) {
        StaffQrKind.ENTRY -> set("dialog", "scan")
        StaffQrKind.PICKUP -> {
            set("pickupScanToken", token)
            set("pickupPreview", "")
            set("dialog", "pickupScan")
            action(
                "pickup_preview",
                obj("token" to token),
                onResult = { set("pickupPreview", it.toString()) },
            )
        }
        StaffQrKind.UNSUPPORTED -> {
            set("dialog", "scan")
            invalid("This QR is not supported. Scan a CTFLY entry pass or café pickup QR.")
        }
    }
}

fun AppViewModel.confirmStaffCode() {
    val token = field("scanToken").trim()
    if (staffQrKind(token) == StaffQrKind.ENTRY)
        action("check_in", obj("token" to token)) {
            set("dialog", "")
            set("scanToken", "")
        }
    else reviewStaffCode(token)
}
