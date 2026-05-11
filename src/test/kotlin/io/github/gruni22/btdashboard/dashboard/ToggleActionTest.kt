package io.github.gruni22.btdashboard.dashboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Verifies the single source of truth for "what service does the primary tap
 * call" — used by both the Compose dashboard and the Android Auto row toggle.
 * If this drifts, the two surfaces stop behaving consistently.
 */
class ToggleActionTest {

    @ParameterizedTest
    @CsvSource(
        // entity_id,            state,    expected_domain, expected_service
        "light.kitchen,          on,       light,           turn_off",
        "light.kitchen,          off,      light,           turn_on",
        "switch.coffee,          on,       switch,          turn_off",
        "switch.coffee,          off,      switch,          turn_on",
        "lock.door,              locked,   lock,            unlock",
        "lock.door,              unlocked, lock,            lock",
        "media_player.tv,        playing,  media_player,    media_pause",
        "media_player.tv,        paused,   media_player,    media_play",
        "scene.movie,            scening,  scene,           turn_on",
        "script.bedtime,         off,      script,          turn_on",
        "automation.coffee,      on,       automation,      turn_off",
        "fan.bedroom,            on,       fan,             turn_off",
    )
    fun `toggleAction maps state to the right service`(
        entityId: String, state: String,
        expectedDomain: String, expectedService: String,
    ) {
        val action = HaEntityState(entityId, state).toggleAction()
        assertEquals(expectedDomain to expectedService, action)
    }

    @ParameterizedTest
    @CsvSource(
        "cover.blinds,  open,    cover,  close_cover",
        "cover.blinds,  opening, cover,  close_cover",
        "cover.blinds,  closed,  cover,  open_cover",
        "cover.blinds,  closing, cover,  open_cover",
    )
    fun `cover always inverts the current motion`(
        entityId: String, state: String,
        expectedDomain: String, expectedService: String,
    ) {
        assertEquals(
            expectedDomain to expectedService,
            HaEntityState(entityId, state).toggleAction(),
        )
    }

    @ParameterizedTest
    @CsvSource(
        "vacuum.x,  cleaning,   vacuum,  pause",
        "vacuum.x,  returning,  vacuum,  stop",
        "vacuum.x,  paused,     vacuum,  start",
        "vacuum.x,  docked,     vacuum,  start",
        "vacuum.x,  idle,       vacuum,  start",
    )
    fun `vacuum state machine`(
        entityId: String, state: String,
        expectedDomain: String, expectedService: String,
    ) {
        assertEquals(
            expectedDomain to expectedService,
            HaEntityState(entityId, state).toggleAction(),
        )
    }

    @Test
    fun `slider-only domains return null`() {
        assertNull(HaEntityState("number.brightness", "50").toggleAction())
        assertNull(HaEntityState("input_number.x", "5").toggleAction())
        assertNull(HaEntityState("select.x", "Off").toggleAction())
        assertNull(HaEntityState("input_select.x", "Off").toggleAction())
    }
}
