package com.karelisio.fichio.search

import com.karelisio.fichio.trash.TrashPurgeWorker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Battery policy of the background jobs (full index scans and trash purge). */
class WorkConstraintsTest {
    @Test fun periodicFullScanWaitsForChargingAndAHealthyBattery() {
        val c = IndexScheduling.periodicRequest().workSpec.constraints
        assertTrue(c.requiresBatteryNotLow())
        assertTrue(c.requiresCharging())
        assertTrue(c.requiresStorageNotLow())
    }

    @Test fun oneShotFullScanNeedsABatteryNotLowOnly() {
        val c = IndexScheduling.oneShotRequest().workSpec.constraints
        assertTrue(c.requiresBatteryNotLow())
        assertFalse("first scan must not wait for the charger", c.requiresCharging())
    }

    @Test fun trashPurgeNeverRunsOnALowBattery() {
        val c = TrashPurgeWorker.request().workSpec.constraints
        assertTrue(c.requiresBatteryNotLow())
    }
}
