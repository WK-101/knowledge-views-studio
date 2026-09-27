package app.parley.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeypadTonesTest {
    @Test fun tones_follow_ringer_and_system_setting() {
        assertTrue(KeypadFeedback.playTone(appSetting = true, systemDialpadTones = true, ringerNormal = true))
        assertFalse(KeypadFeedback.playTone(appSetting = true, systemDialpadTones = true, ringerNormal = false))
        assertFalse(KeypadFeedback.playTone(appSetting = true, systemDialpadTones = false, ringerNormal = true))
        assertFalse(KeypadFeedback.playTone(appSetting = false, systemDialpadTones = true, ringerNormal = true))
    }
}
