package com.bashworks.cameraim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MonitoringProtocolTest {
    @Test
    fun normalizesServerUrl() {
        assertEquals("http://192.168.1.10:5187", MonitoringProtocol.normalizeBaseUrl(" http://192.168.1.10:5187/ "))
    }

    @Test
    fun rejectsNonHttpUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            MonitoringProtocol.normalizeBaseUrl("ftp://server")
        }
    }

    @Test
    fun createsDurableCursorWebSocketUrl() {
        assertEquals(
            "wss://cameraim.local/api/v1/stream?machineId=cnc-1&afterSequence=42",
            MonitoringProtocol.websocketUrl("https://cameraim.local", "cnc-1", 42)
        )
    }
}
