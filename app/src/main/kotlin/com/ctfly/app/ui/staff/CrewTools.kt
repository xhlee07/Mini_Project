package com.ctfly.app.ui.staff

import android.content.Intent
import com.ctfly.app.data.rows
import com.ctfly.app.data.text
import com.ctfly.app.state.AppViewModel
import org.json.JSONObject

fun AppViewModel.openForm(type: String, initial: JSONObject = JSONObject()) {
    set("formType", type)
    set("formData", initial.toString())
    set("dialog", "form")
}

fun AppViewModel.person(id: String) =
    rows("people").find { it.text("id") == id }?.text("name") ?: id.take(8)

fun shareText(context: android.content.Context, title: String, text: String) {
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            },
            title,
        )
    )
}
