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
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.AppIcon
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.Orange
import com.ctfly.app.ui.common.Panel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import org.json.JSONObject

@Composable
fun DialogFrame(title: String, vm: AppViewModel, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = { if (!vm.busy) vm.set("dialog", "") },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogHeight =
            minOf(
                740.dp,
                (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 100)
                    .coerceAtLeast(140)
                    .dp,
            )
        Surface(
            Modifier.widthIn(max = 600.dp)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(16.dp)
                .imePadding(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = Panel,
        ) {
            Column(Modifier.heightIn(max = dialogHeight)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.set("dialog", "") }, enabled = !vm.busy) {
                        AppIcon(Icons.Outlined.Close, "Close dialog")
                    }
                }
                if (vm.validationError.isNotBlank())
                    Surface(
                        modifier =
                            Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                        color = Orange.copy(alpha = .12f),
                    ) {
                        AppText(
                            vm.validationError,
                            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                            color = Orange,
                            fontSize = 13.sp,
                        )
                    }
                Column(
                    Modifier.weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 22.dp, end = 22.dp, bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    content()
                    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
