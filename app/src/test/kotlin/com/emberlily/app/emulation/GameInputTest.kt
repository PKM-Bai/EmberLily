package com.emberlily.app.emulation

import org.junit.Assert.assertEquals
import org.junit.Test

class GameInputTest {
    @Test fun releasingOneSourcePreservesAnotherHeldKey() {
        val input = GameInput()
        input.set("physical", 1)
        input.set("touch", 1)
        input.set("touch", 0)
        assertEquals(1, input.keys())
        input.set("stick", 16)
        assertEquals(17, input.keys())
        input.releaseAll()
        assertEquals(0, input.keys())
    }

    @Test fun disconnectedDeviceReleasesOnlyItsOwnSources() {
        val input = GameInput()
        input.set("key:7:96", 1)
        input.set("stick:7", 16)
        input.set("key:8:97", 2)
        input.releaseDevice(7)
        assertEquals(2, input.keys())
    }
}
