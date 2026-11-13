package com.ctfly.app

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.*

/** Presence exists only while the signed-in customer has this 3D tour in the foreground. */
@Composable
fun VirtualTourSync(vm: AppViewModel, surface: TourSurface, renderStatus: String): String {
    VirtualCameraSync(vm, surface, renderStatus)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestStatus by rememberUpdatedState(renderStatus)
    var label by remember { mutableStateOf("Connecting virtual visit…") }
    LaunchedEffect(vm.userId, surface, lifecycle) {
        if (BuildConfig.STAFF_APP || !vm.hasAccount || vm.role != "customer") return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val session = UUID.randomUUID().toString()
            var sequence = 0L
            var last: TourPose? = null
            var sentAt = 0L
            var lastKart = false
            var lastMoving = false
            try {
                while (isActive) {
                    if (latestStatus != "ready") {
                        delay(250)
                        continue
                    }
                    val pose = surface.tour.pose
                    val kart = surface.tour.race != null
                    val now = android.os.SystemClock.elapsedRealtime()
                    val previous = last
                    val moving =
                        previous != null &&
                            previous.area == pose.area &&
                            hypot(pose.x - previous.x, pose.z - previous.z) > .015f
                    val turning =
                        previous != null &&
                            abs(VirtualCctv.normalizedYaw(pose.yaw - previous.yaw)) > .02f
                    if (
                        previous == null ||
                            previous.area != pose.area ||
                            moving ||
                            turning ||
                            moving != lastMoving ||
                            kart != lastKart ||
                            now - sentAt >= 3000
                    ) {
                        try {
                            vm.publishVirtualVisit(session, ++sequence, pose, kart, moving)
                            label = "Virtual visit visible to staff"
                            last = pose
                            lastKart = kart
                            lastMoving = moving
                            sentAt = now
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            label = "Virtual visit sync unavailable"
                            delay(3000)
                        }
                    }
                    delay(200)
                }
            } finally {
                if (sequence > 0)
                    withContext(NonCancellable) {
                        withTimeoutOrNull(2000) {
                            try {
                                vm.publishVirtualVisit(
                                    session,
                                    ++sequence,
                                    surface.tour.pose,
                                    surface.tour.race != null,
                                    false,
                                    true,
                                )
                            } catch (_: Exception) {
                                /* A lost connection expires on the server after 12 seconds. */
                            }
                        }
                    }
            }
        }
    }
    return label
}

/** Camera mechanics are shared; customer sessions only read the physical head direction. */
@Composable
fun VirtualCameraSync(
    vm: AppViewModel,
    surface: TourSurface,
    status: String,
    control: Boolean = false,
): String {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestStatus by rememberUpdatedState(status)
    var error by remember { mutableStateOf("") }
    LaunchedEffect(vm.userId, surface, lifecycle) {
        if (!vm.hasAccount) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last: VirtualCameraState? = null
            var sentAt = 0L
            var readAt = 0L
            while (isActive) {
                if (latestStatus != "ready") {
                    delay(200)
                    continue
                }
                try {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val direction = surface.tour.monitorDirection
                    if (
                        control &&
                            surface.tour.monitorControlled &&
                            direction != null &&
                            (last == null ||
                                now - sentAt >= 1000 ||
                                last.index != direction.index ||
                                abs(VirtualCctv.normalizedYaw(last.yaw - direction.yaw)) > .01f ||
                                abs(last.pitch - direction.pitch) > .01f)
                    ) {
                        vm.turnVirtualCamera(direction)
                        last = direction
                        sentAt = now
                    }
                    if (now - readAt >= 1000) {
                        val (states, stamp) = vm.virtualCameraStates()
                        surface.queueEvent { surface.tour.showCameraStates(states, stamp) }
                        readAt = now
                    }
                    error = ""
                    delay(350)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    error = "Camera direction sync unavailable"
                    delay(4000)
                }
            }
        }
    }
    return error
}
