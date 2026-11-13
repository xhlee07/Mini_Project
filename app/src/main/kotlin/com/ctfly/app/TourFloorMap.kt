package com.ctfly.app

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The tour is paused while this map is open; floor selection only previews the plan. */
@Composable
internal fun TourFloorMap(
    pose: TourPose,
    racing: Boolean,
    onDismiss: () -> Unit,
    onGo: (TourStop) -> Unit,
) {
    var areaName by rememberSaveable { mutableStateOf(pose.area.name) }
    val area = TourArea.parse(areaName)
    val stops = remember(area) { TourPlan.stops.filter { it.pose.area == area } }
    val context = LocalContext.current
    // Only read the small collision header, not the scene's large triangle buffers.
    val furniture by
        produceState(emptyList<TourObstacle>(), area) {
            value = emptyList()
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                            DataInputStream(context.assets.open(area.asset)).use { stream ->
                                val header = ByteArray(16).also { stream.readFully(it) }
                                val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                                check(data.int == 0x43544632 && data.int == 2)
                                val count = data.int
                                check(count in 1..1000)
                                val boxes = ByteArray(count * 16).also { stream.readFully(it) }
                                val coords = ByteBuffer.wrap(boxes).order(ByteOrder.LITTLE_ENDIAN)
                                List(count) {
                                    TourObstacle(
                                        coords.float,
                                        coords.float,
                                        coords.float,
                                        coords.float,
                                    )
                                }
                            }
                        }
                        .getOrDefault(emptyList())
                }
        }
    var selected by rememberSaveable(areaName) { mutableIntStateOf(0) }
    var zoom by rememberSaveable(areaName) { mutableFloatStateOf(1f) }
    var pan by remember(areaName) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    fun bounded(value: Offset, scale: Float): Offset {
        val x = viewport.width * (scale - 1f) / 2
        val y = viewport.height * (scale - 1f) / 2
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
    fun scaleTo(value: Float) {
        val next = value.coerceIn(1f, 4f)
        pan = bounded(pan * (next / zoom), next)
        zoom = next
    }
    BackHandler(onBack = onDismiss)
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Ink)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .safeDrawingPadding()
            .semantics { contentDescription = "Venue floor map" }
    ) {
        val compact = maxHeight < 500.dp
        val sidebarWidth = (maxWidth * .32f).coerceIn(170.dp, 288.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    AppText("Floor map", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (!compact)
                        AppText("Pinch to zoom · Drag to explore", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = { scaleTo(zoom / 1.4f) }, enabled = zoom > 1f) {
                    AppIcon(Icons.Outlined.Remove, "Zoom out map")
                }
                AppText(
                    "${(zoom * 100).roundToInt()}%",
                    fontSize = 12.sp,
                    modifier = Modifier.widthIn(min = 44.dp),
                    maxLines = 1,
                )
                IconButton(onClick = { scaleTo(zoom * 1.4f) }, enabled = zoom < 4f) {
                    AppIcon(Icons.Outlined.Add, "Zoom in map")
                }
                TextButton(
                    onClick = {
                        zoom = 1f
                        pan = Offset.Zero
                    },
                    modifier = Modifier.testTag("fitFloorMap"),
                ) {
                    AppText("Fit")
                }
                IconButton(onClick = onDismiss) { AppIcon(Icons.Outlined.Close, "Close floor map") }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TourArea.entries.forEach { floor ->
                    FilterChip(
                        selected = area == floor,
                        onClick = { areaName = floor.name },
                        modifier = Modifier.testTag("mapFloor-${floor.name}"),
                        label = {
                            AppText(
                                "${floor.floor} · ${floor.label}",
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
            Row(
                Modifier.weight(1f).fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Panel)
                ) {
                    val labelSize = with(LocalDensity.current) { 12.sp.toPx() }
                    val ink = Ink
                    Canvas(
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .testTag("floorMapViewport")
                            .clipToBounds()
                            .onSizeChanged {
                                viewport = Size(it.width.toFloat(), it.height.toFloat())
                                pan = bounded(pan, zoom)
                            }
                            .pointerInput(areaName) {
                                detectTransformGestures { centroid, delta, factor, _ ->
                                    val next = (zoom * factor).coerceIn(1f, 4f)
                                    val origin = Offset(size.width / 2f, size.height / 2f)
                                    val ratio = next / zoom
                                    pan =
                                        bounded(
                                            (pan + origin - centroid) * ratio + centroid - origin +
                                                delta,
                                            next,
                                        )
                                    zoom = next
                                }
                            }
                    ) {
                        // Rotate long floors to use the landscape screen, preserving
                        // proportions.
                        val sideways = size.width > size.height
                        val halfW = if (sideways) area.halfDepth else area.halfWidth
                        val halfH = if (sideways) area.halfWidth else area.halfDepth
                        val margin = (if (compact) 14.dp else 22.dp).toPx()
                        val scale =
                            min(
                                    (size.width - margin * 2) / (halfW * 2),
                                    (size.height - margin * 2) / (halfH * 2),
                                )
                                .coerceAtLeast(.1f) * zoom
                        fun point(x: Float, z: Float): Offset =
                            center + pan + (if (sideways) Offset(-z, x) else Offset(x, z)) * scale
                        fun room(
                            x: Float,
                            z: Float,
                            w: Float,
                            d: Float,
                            color: androidx.compose.ui.graphics.Color,
                        ) {
                            val a = point(x - w / 2, z - d / 2)
                            val b = point(x + w / 2, z + d / 2)
                            val top = Offset(min(a.x, b.x), min(a.y, b.y))
                            val extent = Size(abs(a.x - b.x), abs(a.y - b.y))
                            drawRect(color.copy(alpha = .18f), top, extent)
                            drawRect(
                                color.copy(alpha = .65f),
                                top,
                                extent,
                                style = Stroke(1.dp.toPx()),
                            )
                        }
                        room(0f, 0f, area.halfWidth * 2, area.halfDepth * 2, Muted)
                        furniture.forEach { room(it.x, it.z, it.w, it.d, Muted) }
                        if (area == TourArea.GAMING)
                            PrivateRoomPlan.rooms.forEach { room(it.x, it.z, 7f, 5.6f, Violet) }
                        if (area == TourArea.OUTDOOR)
                            KartTrack.points.forEachIndexed { index, p ->
                                val next = KartTrack.points[(index + 1) % KartTrack.points.size]
                                drawLine(
                                    Aqua.copy(alpha = .7f),
                                    point(p.x, p.z),
                                    point(next.x, next.z),
                                    max(3.dp.toPx(), 4f * scale),
                                )
                            }
                        WashroomDoors.rooms
                            .first { it.area == area }
                            .let { room ->
                                room(
                                    room.left + 3.5f,
                                    (room.back + room.front) / 2,
                                    7f,
                                    room.front - room.back,
                                    Aqua,
                                )
                            }
                        val paint =
                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                textSize = labelSize
                                textAlign = Paint.Align.CENTER
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                            }
                        VirtualCctv.cameras.filter { it.pose.area == area }.forEach { camera ->
                            val p = point(camera.pose.x, camera.pose.z)
                            val radius = 5.dp.toPx()
                            drawRect(Orange, p - Offset(radius, radius), Size(radius * 2, radius * 2))
                            val direction = (point(camera.pose.x + sin(camera.pose.yaw),
                                camera.pose.z - cos(camera.pose.yaw)) - p) / scale
                            drawLine(Orange, p, p + direction * 16.dp.toPx(), 2.dp.toPx())
                        }
                        // Numbers stay readable when zooming; full names live in the scrollable
                        // list.
                        for (index in stops.indices.sortedBy { if (it == selected) 1 else 0 }) {
                            val stop = stops[index]
                            val p = point(stop.pose.x, stop.pose.z)
                            drawCircle(if (index == selected) Aqua else ink, 12.dp.toPx(), p)
                            drawCircle(Aqua, 12.dp.toPx(), p, style = Stroke(1.dp.toPx()))
                            paint.color = (if (index == selected) ink else Aqua).toArgb()
                            drawContext.canvas.nativeCanvas.drawText(
                                "${index + 1}",
                                p.x,
                                p.y - (paint.ascent() + paint.descent()) / 2,
                                paint,
                            )
                        }
                        if (pose.area == area) {
                            val p = point(pose.x, pose.z)
                            val direction =
                                (point(pose.x + sin(pose.yaw), pose.z - cos(pose.yaw)) - p) / scale
                            val side = Offset(-direction.y, direction.x)
                            val radius = 13.dp.toPx()
                            drawCircle(ink, radius + 3.dp.toPx(), p)
                            drawPath(
                                Path().apply {
                                    val tip = p + direction * radius
                                    val left = p - direction * radius * .6f + side * radius * .7f
                                    val right = p - direction * radius * .6f - side * radius * .7f
                                    moveTo(tip.x, tip.y)
                                    lineTo(left.x, left.y)
                                    lineTo(right.x, right.y)
                                    close()
                                },
                                Lime,
                            )
                        }
                    }
                    if (!compact)
                        AppText(
                            if (pose.area == area) "▲ You are here · Pinch or use + to zoom"
                            else "Preview · You are on ${pose.area.floor} ${pose.area.label}",
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            color = Lime,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                }
                Column(Modifier.width(sidebarWidth).fillMaxHeight()) {
                    AppText("■ CCTV wall cameras", color = Orange, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 4.dp))
                    if (compact)
                        AppText(
                            if (pose.area == area) "▲ You are here · Pinch to zoom"
                            else "Preview · ${area.label}",
                            Modifier.padding(bottom = 4.dp),
                            color = Lime,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    ) {
                        stops.forEachIndexed { index, stop ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(bottom = 4.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (index == selected) Aqua.copy(alpha = .13f) else Panel
                                    )
                                    .clickable { selected = index }
                                    .padding(10.dp)
                                    .heightIn(min = 28.dp)
                                    .testTag("mapPlace-${stop.name}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(26.dp).border(1.dp, Aqua, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AppText("${index + 1}", color = Aqua, fontSize = 12.sp)
                                }
                                AppText(
                                    stop.name,
                                    Modifier.padding(start = 8.dp),
                                    fontSize = 14.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                    }
                    if (!racing)
                        Button(
                            onClick = { onGo(stops[selected]) },
                            modifier =
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mapGoHere"),
                        ) {
                            AppText("Go here", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                }
            }
        }
    }
}
