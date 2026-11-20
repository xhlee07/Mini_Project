package com.ctfly.app.database.remote

import org.json.JSONObject

fun obj(vararg pairs: Pair<String, Any?>) =
    JSONObject().apply { pairs.forEach { put(it.first, it.second ?: JSONObject.NULL) } }

fun JSONObject.text(key: String, default: String = ""): String =
    if (isNull(key)) default else optString(key, default)

fun JSONObject.profileId(): String = text("profile_id", text("id"))

fun JSONObject.resourceId(): String = text("resource_id", text("id"))

fun JSONObject.bookingId(): String = text("booking_id", text("id"))

fun JSONObject.categoryId(): String = text("category_id", text("id"))

fun JSONObject.productId(): String = text("product_id", text("id"))

fun JSONObject.orderId(): String = text("order_id", text("id"))

fun JSONObject.eventId(): String = text("event_id", text("id"))

fun JSONObject.registrationId(): String = text("registration_id", text("id"))

fun JSONObject.billId(): String = text("bill_id", text("id"))

fun JSONObject.rewardId(): String = text("reward_id", text("id"))

fun JSONObject.redeemId(): String = text("redeem_id", text("id"))

fun JSONObject.shiftId(): String = text("shift_id", text("id"))

fun JSONObject.leaveRequestId(): String = text("leave_request_id", text("id"))

fun JSONObject.taskId(): String = text("task_id", text("id"))

fun JSONObject.handoverId(): String = text("handover_id", text("id"))

fun JSONObject.ticketId(): String = text("ticket_id", text("id"))

fun JSONObject.feedbackId(): String = text("feedback_id", text("id"))

fun JSONObject.paymentId(): String = text("payment_id", text("id"))

fun JSONObject.promotionId(): String = text("promotion_id", text("id"))

fun JSONObject.staffId(): String = text("staff_id", text("user_id"))

fun JSONObject.assignedStaffId(): String = text("assigned_staff", text("assigned_user"))

fun JSONObject.rows(key: String): List<JSONObject> =
    optJSONArray(key)?.let { array ->
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    } ?: emptyList()

class ApiFailure(val status: Int, message: String, val code: String = "") : Exception(message)
