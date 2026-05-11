package io.github.gruni22.btdashboard.dashboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HaEntityStateTest {

    // ── domain extraction ────────────────────────────────────────────────────

    @Test
    fun `domain is the part before the first dot`() {
        assertEquals("light", HaEntityState("light.kitchen", "on").domain)
        assertEquals("binary_sensor", HaEntityState("binary_sensor.door", "off").domain)
    }

    // ── friendlyName falls back to entity_id ─────────────────────────────────

    @Test
    fun `friendlyName uses friendly_name attribute when present`() {
        val e = HaEntityState(
            entityId = "light.kitchen",
            state = "on",
            attributes = mapOf("friendly_name" to "Küchenlicht"),
        )
        assertEquals("Küchenlicht", e.friendlyName)
    }

    @Test
    fun `friendlyName falls back to entity_id when attribute is missing or blank`() {
        assertEquals("light.kitchen", HaEntityState("light.kitchen", "on").friendlyName)
        assertEquals(
            "light.kitchen",
            HaEntityState(
                entityId = "light.kitchen",
                state = "on",
                attributes = mapOf("friendly_name" to ""),
            ).friendlyName,
        )
    }

    // ── isActive ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource(
        // entityId,        state,        expected
        "light.x,           on,           true",
        "light.x,           off,          false",
        "switch.x,          on,           true",
        "switch.x,          unavailable,  false",
        "lock.x,            unlocked,     true",
        "lock.x,            locked,       false",
        "lock.x,            unlocking,    true",   // transitional → still active
        "cover.x,           open,         true",
        "cover.x,           opening,      true",
        "cover.x,           closed,       false",
        "climate.x,         heat,         true",
        "climate.x,         off,          false",
        "climate.x,         unknown,      false",
        "binary_sensor.x,   on,           true",   // default branch: "on" is in the active set
        "binary_sensor.x,   off,          false",
        "media_player.x,    playing,      true",
    )
    fun `isActive matches HA semantics`(entityId: String, state: String, expected: Boolean) {
        assertEquals(expected, HaEntityState(entityId, state).isActive)
    }

    // ── brightness math ──────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource(
        "0,    0",
        "127,  50",
        "128,  50",
        "255,  100",
    )
    fun `brightnessPercent rounds the raw 0-255 value to 0-100`(raw: Int, pct: Int) {
        val e = HaEntityState(
            entityId = "light.kitchen",
            state = "on",
            attributes = mapOf("brightness" to raw),
        )
        assertEquals(pct, e.brightnessPercent)
    }

    @Test
    fun `brightnessPercent is 0 when attribute is absent`() {
        assertEquals(0, HaEntityState("light.kitchen", "off").brightnessPercent)
    }

    // ── isControllable ───────────────────────────────────────────────────────

    @Test
    fun `controllable domains are recognised, sensors are not`() {
        assertTrue(HaEntityState("light.x", "on").isControllable)
        assertTrue(HaEntityState("vacuum.x", "docked").isControllable)
        assertTrue(HaEntityState("number.x", "12").isControllable)
        assertFalse(HaEntityState("sensor.x", "22.3").isControllable)
        assertFalse(HaEntityState("binary_sensor.x", "on").isControllable)
    }

    // ── slider-only domains are controllable but not tap-toggleable ──────────

    @Test
    fun `slider-only domains are excluded from tap-toggle`() {
        assertFalse(HaEntityState("number.x", "5").isTapToggleable)
        assertFalse(HaEntityState("input_number.x", "5").isTapToggleable)
        assertFalse(HaEntityState("select.x", "Off").isTapToggleable)
        assertFalse(HaEntityState("input_select.x", "Off").isTapToggleable)
        assertTrue(HaEntityState("light.x", "on").isTapToggleable)
    }

    // ── supportsBrightness ───────────────────────────────────────────────────

    @Test
    fun `supportsBrightness uses supported_color_modes when present`() {
        val withModes = HaEntityState(
            entityId = "light.x",
            state = "on",
            attributes = mapOf("supported_color_modes" to listOf("brightness")),
        )
        assertTrue(withModes.supportsBrightness)

        val onlyOnoff = HaEntityState(
            entityId = "light.x",
            state = "on",
            attributes = mapOf("supported_color_modes" to listOf("onoff")),
        )
        assertFalse(onlyOnoff.supportsBrightness)
    }

    @Test
    fun `supportsBrightness falls back to brightness attribute on older HA`() {
        val legacy = HaEntityState(
            entityId = "light.x",
            state = "on",
            attributes = mapOf("brightness" to 200),
        )
        assertTrue(legacy.supportsBrightness)
        assertFalse(HaEntityState("light.x", "off").supportsBrightness)
    }

    @Test
    fun `supportsBrightness is always false for non-light domains`() {
        val fan = HaEntityState(
            entityId = "fan.x",
            state = "on",
            attributes = mapOf("brightness" to 200),
        )
        assertFalse(fan.supportsBrightness)
    }

    // ── unit + numeric helpers ──────────────────────────────────────────────

    @Test
    fun `unit returns null when attribute is absent`() {
        assertNull(HaEntityState("sensor.x", "22.3").unit)
    }

    @Test
    fun `numberValue parses the state as Double`() {
        assertEquals(22.3, HaEntityState("sensor.x", "22.3").numberValue)
        assertNull(HaEntityState("sensor.x", "not-a-number").numberValue)
    }
}
