package com.ctfly.app.database.remote

import org.json.JSONObject

fun obj(vararg pairs: Pair<String, Any?>) =
    JSONObject().apply { pairs.forEach { put(it.first, it.second ?: JSONObject.NULL) } }

fun JSONObject.text(key: String, default: String = ""): String =
    if (isNull(key)) default else optString(key, default)

fun JSONObject.profileId(): String = text("profile_id", text("id"))

fun JSONObject.staffId(): String = text("staff_id", text("user_id"))

fun JSONObject.assignedStaffId(): String = text("assigned_staff", text("assigned_user"))

fun JSONObject.rows(key: String): List<JSONObject> =
    optJSONArray(key)?.let { array ->
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    } ?: emptyList()

class ApiFailure(val status: Int, message: String, val code: String = "") : Exception(message)
