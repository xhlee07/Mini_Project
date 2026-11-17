@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.ctfly.app.domain

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ctfly.app.database.remote.text
import com.ctfly.app.state.AppViewModel
import com.ctfly.app.ui.common.AppIcon
import com.ctfly.app.ui.common.AppText
import com.ctfly.app.ui.common.Input
import com.ctfly.app.ui.common.Muted
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/** A calendar day always means the venue's day, independent of the device timezone. */
fun venueDay(timestamp: String): LocalDate? =
    runCatching {
            if (timestamp.length == 10) LocalDate.parse(timestamp)
            else
                java.time.OffsetDateTime.parse(timestamp.replace(' ', 'T'))
                    .atZoneSameInstant(CafeZone)
                    .toLocalDate()
        }
        .getOrNull()

fun onStaffDay(timestamp: String, selected: String): Boolean =
    selected.isBlank() || venueDay(timestamp)?.toString() == selected

fun historyDateLabel(timestamp: String): String =
    runCatching {
            java.time.OffsetDateTime.parse(timestamp.replace(' ', 'T'))
                .atZoneSameInstant(CafeZone)
                .format(DateTimeFormatter.ofPattern("dd MMM yyyy · h:mm a", Locale.ENGLISH))
        }
        .getOrDefault(timestamp)

fun List<JSONObject>.onStaffDay(vm: AppViewModel, key: String, timestamp: String) = filter {
    onStaffDay(it.text(timestamp), vm.field(key))
}

@Composable
fun StaffDateFilter(vm: AppViewModel, key: String, label: String) {
    val selected = vm.field(key)
    val today = LocalDate.now(CafeZone)
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val compactPicker = screenHeight < 500
    var picking by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        AppText(label, color = Muted, fontSize = 12.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(selected.isBlank(), { vm.set(key, "") }, label = { AppText("All dates") })
            FilterChip(
                selected == today.toString(),
                { vm.set(key, today.toString()) },
                label = { AppText("Today") },
            )
            OutlinedButton(
                onClick = { picking = true },
                modifier = Modifier.heightIn(min = 48.dp).testTag("date-$key"),
            ) {
                AppIcon(Icons.Outlined.CalendarMonth, null)
                Spacer(Modifier.width(8.dp))
                AppText(
                    if (selected.isBlank()) "Choose date" else selected,
                    localize = selected.isBlank(),
                )
            }
        }
        if (selected.isNotBlank())
            Row(verticalAlignment = Alignment.CenterVertically) {
                val day = LocalDate.parse(selected)
                IconButton(onClick = { vm.set(key, day.minusDays(1).toString()) }) {
                    AppIcon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous day")
                }
                AppText(
                    day.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.ENGLISH)),
                    Modifier.weight(1f),
                    localize = false,
                )
                IconButton(onClick = { vm.set(key, day.plusDays(1).toString()) }) {
                    AppIcon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next day")
                }
            }
    }
    if (picking) {
        val initial =
            (runCatching { LocalDate.parse(selected) }.getOrNull() ?: today)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val picker =
            rememberDatePickerState(
                initialSelectedDateMillis = initial,
                initialDisplayMode = if (compactPicker) DisplayMode.Input else DisplayMode.Picker,
            )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        picker.selectedDateMillis?.let {
                            vm.set(
                                key,
                                Instant.ofEpochMilli(it)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                                    .toString(),
                            )
                        }
                        picking = false
                    },
                    enabled = picker.selectedDateMillis != null,
                ) {
                    AppText("Apply")
                }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { AppText("Cancel") } },
        ) {
            Box(
                if (compactPicker)
                    Modifier.heightIn(max = (screenHeight - 140).coerceAtLeast(160).dp)
                        .verticalScroll(rememberScrollState())
                else Modifier
            ) {
                DatePicker(state = picker, showModeToggle = true)
            }
        }
    }
}
