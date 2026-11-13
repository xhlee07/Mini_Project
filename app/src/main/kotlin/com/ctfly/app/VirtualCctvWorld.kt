package com.ctfly.app

import kotlin.math.*

data class VirtualVisitor(
    val id: String,
    val pose: TourPose,
    val kart: Boolean = false,
    val moving: Boolean = false,
    val ageMillis: Long = 0L,
)

data class VirtualCamera(
    val name: String,
    val pose: TourPose,
    val height: Float = 3.15f,
    val wallYaw: Float = (PI / 2).toFloat(),
)

data class VirtualCameraState(
    val index: Int,
    val yaw: Float,
    val pitch: Float,
    val remainingMillis: Long,
)

object VirtualCctv {
    const val TTL_MS = 12_000L
    val cameras =
        listOf(
            VirtualCamera("Entrance camera", TourPose(-13.25f, 29f, .95f, -.20f, TourArea.GAMING)),
            VirtualCamera(
                "PC arena camera",
                TourPose(13.25f, 20f, -.9f, -.24f, TourArea.GAMING),
                height = 3.5f,
                wallYaw = (-PI / 2).toFloat(),
            ),
            VirtualCamera(
                "Billiards camera",
                TourPose(-13.25f, 22f, .65f, -.20f, TourArea.BILLIARDS),
            ),
            VirtualCamera(
                "Lounge camera",
                TourPose(0f, 16.25f, 0f, -.20f, TourArea.LOUNGE),
                wallYaw = 0f,
            ),
            VirtualCamera(
                "Circuit camera",
                TourPose(-33.75f, 104.7f, .50f, -.10f, TourArea.OUTDOOR),
                2.6f,
            ),
        )

    fun constrained(camera: VirtualCamera, yaw: Float, pitch: Float): TourPose =
        camera.pose.copy(
            yaw = camera.wallYaw + normalizedYaw(yaw - camera.wallYaw).coerceIn(-1.35f, 1.35f),
            pitch = pitch.coerceIn(-1.05f, .12f),
        )

    fun scan(camera: VirtualCamera, seconds: Double): TourPose =
        constrained(
            camera,
            camera.pose.yaw + sin(seconds * .22).toFloat() * .32f,
            camera.pose.pitch,
        )

    fun normalizedYaw(value: Float): Float = atan2(sin(value), cos(value))

    fun valid(pose: TourPose) =
        listOf(pose.x, pose.z, pose.yaw, pose.pitch).all { it.isFinite() } &&
            abs(pose.x) < pose.area.halfWidth &&
            abs(pose.z) < pose.area.halfDepth

    fun aimed(camera: VirtualCamera, target: TourPose): TourPose {
        val dx = target.x - camera.pose.x
        val dz = camera.pose.z - target.z
        val distance = hypot(dx, dz).coerceAtLeast(.1f)
        return constrained(camera, atan2(dx, dz), atan2(1.1f - camera.height, distance))
    }
}

/** Play a short timestamped buffer, so network polling never becomes stop/start animation. */
class VirtualVisitorMotion {
    private data class Sample(val visitor: VirtualVisitor, val time: Long)

    private class Track(var latest: VirtualVisitor, var received: Long) {
        val samples = ArrayDeque<Sample>()
    }

    private val tracks = linkedMapOf<String, Track>()

    fun update(visitors: List<VirtualVisitor>, nowMillis: Long) {
        val valid =
            visitors.filter {
                VirtualCctv.valid(it.pose) && it.ageMillis in 0 until VirtualCctv.TTL_MS
            }
        tracks.keys.retainAll(valid.map { it.id }.toSet())
        valid.forEach { visitor ->
            val track = tracks.getOrPut(visitor.id) { Track(visitor, nowMillis) }
            if (track.latest.pose.area != visitor.pose.area || track.latest.kart != visitor.kart)
                track.samples.clear()
            track.latest = visitor
            track.received = nowMillis
            val time = nowMillis - visitor.ageMillis
            // Repeated polls of the same server sample must not restart interpolation.
            if (track.samples.lastOrNull()?.let { time <= it.time + 40 } != true) {
                track.samples.addLast(Sample(visitor, time))
                while (track.samples.size > 24) track.samples.removeFirst()
            }
        }
    }

    fun frame(nowMillis: Long): List<VirtualVisitor> =
        tracks.values.mapNotNull { track ->
            if (
                track.latest.ageMillis + (nowMillis - track.received).coerceAtLeast(0) >=
                    VirtualCctv.TTL_MS
            )
                return@mapNotNull null
            val time = nowMillis - 1000L
            while (track.samples.size > 2 && track.samples.elementAt(1).time <= time) track.samples
                .removeFirst()
            val first = track.samples.firstOrNull() ?: return@mapNotNull null
            val last = track.samples.last()
            val next = track.samples.firstOrNull { it.time >= time } ?: last
            val start = track.samples.lastOrNull { it.time <= time } ?: first
            val blend =
                if (next.time <= start.time) 1f
                else ((time - start.time).toFloat() / (next.time - start.time)).coerceIn(0f, 1f)
            val a = start.visitor.pose
            val b = next.visitor.pose
            next.visitor.copy(
                moving = if (time > last.time + 500) false else next.visitor.moving,
                pose =
                    b.copy(
                        x = a.x + (b.x - a.x) * blend,
                        z = a.z + (b.z - a.z) * blend,
                        yaw = a.yaw + VirtualCctv.normalizedYaw(b.yaw - a.yaw) * blend,
                    ),
            )
        }
}
