package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {
    @Test fun anUpdateKeepsWhatSwipingDownDid() {
        assertEquals(Gestures.QUICK_SETTINGS, forkSwipeDown("quick_settings"))
        assertEquals(Gestures.HOME_MENU, forkSwipeDown("home_menu"))
        assertEquals(Gestures.NONE, forkSwipeDown("none"))
        assertEquals(Gestures.NOTIFICATIONS, forkSwipeDown("notifications"))
    }

    @Test fun neverSetSwipingDownPullsTheShade() {
        assertEquals(Gestures.NOTIFICATIONS, forkSwipeDown(null))
    }

    @Test fun systemActionsArentPanels() {
        for ((value, _) in Gestures.SYSTEM_ACTIONS) {
            assertTrue(isSystemAction(value))
            assertFalse(value.endsWith(".owl"))
        }
        assertFalse(isSystemAction("controls.owl"))
        assertFalse(isSystemAction(Gestures.NONE))
    }
}
