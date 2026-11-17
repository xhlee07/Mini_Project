package com.ctfly.app

import com.ctfly.app.domain.historyDateLabel
import com.ctfly.app.domain.onStaffDay
import com.ctfly.app.domain.venueDay
import com.ctfly.app.ui.staff.navigation.staffBackDestination
import com.ctfly.app.ui.staff.operations.StaffQrKind
import com.ctfly.app.ui.staff.operations.staffQrKind
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class StaffHistoryRulesTest {
    @Test
    fun moduleBackHasOneConsistentHierarchy() {
        for (page in listOf("Transactions", "Customer history", "Feedback", "Events")) assertEquals(
            "Modules",
            staffBackDestination(page),
        )
        assertEquals("Operations", staffBackDestination("Modules"))
        assertNull(staffBackDestination("Operations"))
    }

    @Test
    fun historyDayUsesMalaysiaMidnight() {
        assertTrue(onStaffDay("2026-10-15T16:00:00Z", "2026-10-16"))
        assertFalse(onStaffDay("2026-10-15T15:59:59Z", "2026-10-16"))
        assertFalse(onStaffDay("2026-10-16T16:00:00Z", "2026-10-16"))
        assertTrue(onStaffDay("2026-10-16", "2026-10-16"))
        assertFalse(onStaffDay("invalid", "2026-10-16"))
        assertTrue(onStaffDay("", ""))
        assertEquals(LocalDate.of(2026, 10, 16), venueDay("2026-10-16T00:00:00+08:00"))
        assertTrue(historyDateLabel("2025-01-01T00:00:00Z").contains("2025"))
    }

    @Test
    fun sharedScannerRoutesOnlyKnownCodes() {
        val id = "22222222-2222-4222-8222-222222222222"
        assertEquals(StaffQrKind.ENTRY, staffQrKind("ctfly:$id"))
        assertEquals(StaffQrKind.ENTRY, staffQrKind(" $id "))
        assertEquals(StaffQrKind.PICKUP, staffQrKind("ctfly-pickup:$id"))
        for (code in
            listOf(
                "",
                "https://example.test",
                "ctfly-pickup:----",
                "ctfly-pickup:$id/extra",
            )) assertEquals(StaffQrKind.UNSUPPORTED, staffQrKind(code))
    }
}
