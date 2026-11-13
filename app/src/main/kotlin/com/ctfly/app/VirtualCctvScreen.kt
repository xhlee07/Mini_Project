package com.ctfly.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun VirtualCctvScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var cameraIndex by rememberSaveable { mutableIntStateOf(0) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var reset by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("loading") }
    var connected by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var visitors by remember { mutableStateOf<List<VirtualVisitor>>(emptyList()) }
    var following by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = VirtualCctv.cameras[cameraIndex.coerceIn(VirtualCctv.cameras.indices)]
    val surface = remember {
        TourSurface(
                context,
                camera.pose,
                {},
                { status = it },
                TourQuality.SMOOTH,
                surveillance = true,
            )
            .apply { contentDescription = "Virtual CCTV 3D view" }
    }
    val cameraSync = VirtualCameraSync(vm, surface, status, control = true)
    BackHandler { vm.set("page", "Modules") }
    DisposableEffect(surface, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surface.onResume()
                Lifecycle.Event.ON_PAUSE -> surface.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) surface.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            surface.onPause()
            surface.detachCallbacks()
        }
    }
    LaunchedEffect(vm.userId, lifecycle, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (isActive) {
                    try {
                        visitors = vm.virtualVisitors()
                        connected = true
                        error = ""
                        if (following != null && visitors.none { it.id == following })
                            following = null
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        connected = false
                        visitors = emptyList()
                        error =
                            if (
                                e is ApiFailure &&
                                    (e.code in listOf("PGRST202", "42883") ||
                                        e.message.orEmpty().contains("Cloud setup"))
                            )
                                "Virtual CCTV setup is pending. Run the provided SQL in Supabase."
                            else "Virtual tour connection lost. Reconnecting…"
                    }
                    delay(if (connected) 250 else 4000)
                }
            } finally {
                connected = false
                visitors = emptyList()
            }
        }
    }
    LaunchedEffect(cameraIndex, reset) {
        surface.queueEvent {
            surface.tour.monitorCamera(camera, zoom)
            surface.tour.followMonitor(following)
        }
    }
    LaunchedEffect(following) { surface.queueEvent { surface.tour.followMonitor(following) } }
    LaunchedEffect(zoom) { surface.queueEvent { surface.tour.zoomMonitor(zoom) } }
    LaunchedEffect(visitors) { surface.queueEvent { surface.tour.showVisitors(visitors) } }
    val target = visitors.firstOrNull { it.id == following }
    LaunchedEffect(following, target?.pose?.area) {
        if (target != null) {
            val next =
                VirtualCctv.cameras
                    .indexOfFirst { it.pose.area == target.pose.area }
                    .coerceAtLeast(0)
            if (cameraIndex != next) cameraIndex = next
            val mount = VirtualCctv.cameras[next]
            surface.queueEvent {
                surface.tour.monitorCamera(mount, zoom)
                surface.tour.followMonitor(target.id)
            }
        }
    }
    fun pan(yaw: Float, pitch: Float) {
        following = null
        surface.addLook(yaw, pitch)
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Ink).safeDrawingPadding().testTag("virtual-cctv-screen")
    ) {
        val wide = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.set("page", "Modules") }) {
                    AppIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to modules")
                }
                Column(Modifier.weight(1f)) {
                    AppText("Virtual CCTV", style = MaterialTheme.typography.titleLarge)
                    AppText("Live activity in the 3D tour", color = Muted, fontSize = 11.sp)
                }
                AppText(
                    if (connected) "Connected" else "Connecting…",
                    color = if (connected) Aqua else Orange,
                    fontSize = 11.sp,
                )
            }
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VirtualCctv.cameras.forEachIndexed { index, item ->
                    FilterChip(
                        selected = cameraIndex == index,
                        modifier = Modifier.testTag("cctv-camera-$index"),
                        onClick = {
                            following = null
                            cameraIndex = index
                            reset++
                        },
                        label = { AppText(item.name, fontSize = 12.sp) },
                    )
                }
            }
            @Composable
            fun feed(modifier: Modifier) {
                Box(modifier.clip(RoundedCornerShape(16.dp)).background(Panel)) {
                    AndroidView(
                        factory = { surface },
                        modifier = Modifier.fillMaxSize().testTag("virtual-cctv-feed"),
                    )
                    Box(
                        Modifier.fillMaxSize().pointerInput(surface) {
                            detectDragGestures { change, amount ->
                                change.consume()
                                pan(amount.x / density * .006f, -amount.y / density * .006f)
                            }
                        }
                    )
                    Row(
                        Modifier.align(Alignment.TopStart)
                            .padding(10.dp)
                            .background(Ink.copy(alpha = .8f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AppText(camera.name, color = Aqua, fontSize = 11.sp)
                        AppText(
                            "${visitors.count { it.pose.area == camera.pose.area }}",
                            localize = false,
                            fontSize = 11.sp,
                        )
                        AppText("visitors in this area", fontSize = 11.sp)
                    }
                    if (status == "loading")
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else if (status != "ready")
                        AppText(
                            status,
                            Modifier.align(Alignment.Center).padding(20.dp),
                            color = Orange,
                        )
                    if (error.isNotBlank()) {
                        Column(
                            Modifier.align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Ink.copy(alpha = .9f))
                                .padding(12.dp)
                        ) {
                            AppText(error, color = Orange, fontSize = 12.sp)
                            TextButton(onClick = { retry++ }) { AppText("Retry connection") }
                        }
                    } else if (connected && visitors.none { it.pose.area == camera.pose.area })
                        AppText(
                            "No visitors in this area",
                            Modifier.align(Alignment.BottomCenter)
                                .background(Ink.copy(alpha = .8f))
                                .padding(10.dp),
                            fontSize = 12.sp,
                        )
                }
            }
            @Composable
            fun controls(modifier: Modifier) {
                Column(
                    modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppText("Camera controls", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = { pan(-.30f, 0f) },
                            modifier = Modifier.testTag("cctv-pan-left"),
                        ) {
                            AppIcon(Icons.Outlined.ChevronLeft, "Pan left")
                        }
                        IconButton(onClick = { pan(.30f, 0f) }) {
                            AppIcon(Icons.Outlined.ChevronRight, "Pan right")
                        }
                        IconButton(onClick = { pan(0f, .15f) }) {
                            AppIcon(Icons.Outlined.KeyboardArrowUp, "Tilt up")
                        }
                        IconButton(onClick = { pan(0f, -.15f) }) {
                            AppIcon(Icons.Outlined.KeyboardArrowDown, "Tilt down")
                        }
                    }
                    AppText("Zoom", fontSize = 12.sp)
                    Slider(
                        value = zoom,
                        onValueChange = { zoom = it },
                        valueRange = 1f..3f,
                        modifier = Modifier.testTag("cctv-zoom"),
                    )
                    TextButton(
                        onClick = {
                            following = null
                            zoom = 1f
                            surface.queueEvent { surface.tour.homeMonitor() }
                        }
                    ) {
                        AppText("Reset view")
                    }
                    AppText(
                        "Drag the picture to turn the virtual camera.",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    AppText(
                        "Wall-mounted PTZ · Returns to patrol after 15 seconds",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    if (cameraSync.isNotBlank())
                        AppText(cameraSync, color = Orange, fontSize = 11.sp)
                    HorizontalDivider()
                    AppText("Visitors exploring now", style = MaterialTheme.typography.titleMedium)
                    if (connected && visitors.isEmpty())
                        AppText(
                            "No one is exploring the 3D tour right now.",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    visitors.forEach { visitor ->
                        val name = tr("Visitor") + " " + visitor.id.take(6)
                        Column {
                            AppText(name, localize = false, fontSize = 12.sp)
                            AppText(visitor.pose.area.label, color = Muted, fontSize = 11.sp)
                            TextButton(
                                onClick = {
                                    following = if (following == visitor.id) null else visitor.id
                                },
                                modifier = Modifier.testTag("cctv-follow-${visitor.id}"),
                            ) {
                                AppText(
                                    if (following == visitor.id) "Stop following"
                                    else "Follow visitor"
                                )
                            }
                        }
                    }
                    AppText(
                        "Virtual visitors only. This does not use phone cameras or real-world location.",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
            }
            if (wide)
                Row(
                    Modifier.weight(1f).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    feed(Modifier.weight(1f).fillMaxHeight())
                    controls(Modifier.width(224.dp).fillMaxHeight())
                }
            else
                Column(Modifier.weight(1f).padding(12.dp)) {
                    feed(Modifier.fillMaxWidth().weight(1f))
                    controls(Modifier.fillMaxWidth().heightIn(max = 290.dp))
                }
        }
    }
}
