package com.ctfly.app

import kotlin.math.PI
import org.junit.Assert.*
import org.junit.Test

class VirtualCctvRulesTest {
    @Test
    fun reportsExpireAndDepartedVisitorsDisappearImmediately() {
        val motion = VirtualVisitorMotion()
        motion.update(listOf(VirtualVisitor("one", TourPose(), ageMillis = 2000)), 1000)
        assertEquals(1, motion.frame(10999).size)
        assertTrue(motion.frame(11000).isEmpty())
        motion.update(listOf(VirtualVisitor("two", TourPose())), 12000)
        motion.update(emptyList(), 12001)
        assertTrue(motion.frame(12001).isEmpty())
    }

    @Test
    fun positionsEaseAndYawTakesTheShortPathAtPi() {
        val motion = VirtualVisitorMotion()
        motion.update(listOf(VirtualVisitor("one", TourPose(x = 0f, yaw = 3.1f))), 0)
        motion.update(listOf(VirtualVisitor("one", TourPose(x = 6f, yaw = -3.1f))), 1000)
        val halfway = motion.frame(1500).single().pose
        assertEquals(3f, halfway.x, .001f)
        assertEquals(PI.toFloat(), halfway.yaw, .01f)
    }

    @Test
    fun repeatedPollsDoNotRestartMovementAndNetworkJitterIsBuffered() {
        val motion = VirtualVisitorMotion()
        motion.update(listOf(VirtualVisitor("one", TourPose(x = 0f), moving = true)), 0)
        motion.update(
            listOf(VirtualVisitor("one", TourPose(x = 2f), moving = true, ageMillis = 100)),
            600,
        )
        motion.update(
            listOf(VirtualVisitor("one", TourPose(x = 2f), moving = true, ageMillis = 350)),
            850,
        )
        motion.update(
            listOf(VirtualVisitor("one", TourPose(x = 4f), moving = true, ageMillis = 200)),
            1200,
        )
        assertEquals(1f, motion.frame(1250).single().pose.x, .001f)
        assertEquals(2f, motion.frame(1500).single().pose.x, .001f)
        assertEquals(3f, motion.frame(1750).single().pose.x, .001f)
        assertEquals(4f, motion.frame(3000).single().pose.x, .001f)
        assertFalse("Never run in place on a stale report", motion.frame(3000).single().moving)
    }

    @Test
    fun wallMountsPanInsideTheRoomAndPatrolIsContinuous() {
        for (camera in VirtualCctv.cameras) {
            for (angle in listOf(-100f, 0f, 100f)) {
                val aim = VirtualCctv.constrained(camera, angle, angle)
                assertTrue(
                    kotlin.math.abs(VirtualCctv.normalizedYaw(aim.yaw - camera.wallYaw)) <= 1.351f
                )
                assertTrue(aim.pitch in -1.05f..0.12f)
            }
            val a = VirtualCctv.scan(camera, 1234.0)
            val b = VirtualCctv.scan(camera, 1234.016)
            assertTrue(kotlin.math.abs(a.yaw - b.yaw) < .002f)
        }
    }

    @Test
    fun floorChangesSnapAndInvalidCoordinatesNeverRender() {
        val motion = VirtualVisitorMotion()
        motion.update(listOf(VirtualVisitor("one", TourPose())), 0)
        motion.update(
            listOf(VirtualVisitor("one", TourPose(10f, 0f, area = TourArea.LOUNGE))),
            1000,
        )
        assertEquals(TourArea.LOUNGE, motion.frame(1001).single().pose.area)
        assertEquals(10f, motion.frame(1001).single().pose.x, .001f)
        motion.update(listOf(VirtualVisitor("invalid", TourPose(Float.NaN, 0f))), 2000)
        assertTrue(motion.frame(2001).isEmpty())
    }

    @Test
    fun everyFloorHasACameraAndAimTargetsTheVisitor() {
        TourArea.entries.forEach { area ->
            assertTrue(VirtualCctv.cameras.any { it.pose.area == area })
        }
        VirtualCctv.cameras.forEach {
            assertTrue(VirtualCctv.valid(it.pose))
            assertTrue(it.height > 1.65f)
        }
        val camera = VirtualCamera("test", TourPose(0f, 0f), 3f)
        val aim = VirtualCctv.aimed(camera, TourPose(5f, 0f))
        assertEquals((PI / 2).toFloat(), aim.yaw, .001f)
        assertTrue(aim.pitch < 0)
    }
}
