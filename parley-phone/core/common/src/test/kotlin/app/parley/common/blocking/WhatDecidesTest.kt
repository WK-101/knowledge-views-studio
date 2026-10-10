package app.parley.common.blocking

import app.parley.common.OffHours
import app.parley.common.ScreeningSettings
import app.parley.common.calls.LockScreenCaller
import app.parley.common.circle.CircleConfig
import app.parley.common.circle.PeopleCardChoice
import app.parley.common.sync.shared.ShieldMode
import app.parley.common.ux.LockButton
import app.parley.common.ux.SalesLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatDecidesTest {
    @Test fun what_decides_lists_the_four_sources_with_their_state() {
        val off = WhatDecides.rows(0, 0, 0, ScreeningSettings(learnFromCalls = false), emptyList())
        assertEquals(WhatDecides.Source.entries, off.map { it.source })
        assertTrue(off.none { it.on })
        val on = WhatDecides.rows(
            2, 1, 3, ScreeningSettings(learnFromCalls = true, silenceSalesLines = true),
            listOf(WhatDecides.Shield("Neighbours", ShieldMode.BLOCK), WhatDecides.Shield("Family", ShieldMode.WARN)),
        )
        assertTrue(on.all { it.on })
        assertEquals(2, on[0].count)
        assertEquals(1, on[0].allowCount)
        assertEquals(3, on[1].count)
        assertEquals(SalesLines.TAG_AND_SILENCE, on[2].sales)
        assertEquals(listOf("Family", "Neighbours"), on[3].shields.map { it.label })
        // An allow rule alone is a rule of yours that decides.
        assertTrue(WhatDecides.rows(0, 1, 0, ScreeningSettings(), emptyList())[0].on)
    }

    @Test fun repeat_callers_matter_only_once_unknown_callers_are_silenced() {
        assertFalse(WhatDecides.silencesUnknown(ScreeningSettings()))
        assertTrue(WhatDecides.silencesUnknown(ScreeningSettings(blockNonContacts = true)))
        assertTrue(WhatDecides.silencesUnknown(ScreeningSettings(offHours = OffHours(enabled = true))))
    }

    /** The older lock-screen notes switch folds into "Caller on the lock screen": never more than the person chose. */
    @Test fun the_lock_screen_notes_switch_folds_into_one_rule() {
        assertEquals(LockScreenCaller.NAME_AND_NOTES, LockScreenCaller.folded(LockScreenCaller.NAME, notesSwitch = true))
        assertEquals(LockScreenCaller.NAME, LockScreenCaller.folded(LockScreenCaller.NAME, notesSwitch = false))
        // Initials or nothing stay so: the switch no longer adds notes under them.
        assertEquals(LockScreenCaller.INITIALS, LockScreenCaller.folded(LockScreenCaller.INITIALS, notesSwitch = true))
        assertEquals(LockScreenCaller.NONE, LockScreenCaller.folded(LockScreenCaller.NONE, notesSwitch = true))
        assertEquals(LockScreenCaller.NAME_AND_NOTES, app.parley.common.AppSettings().withLockScreenNotes(notesSwitch = true).lockScreenCaller)
    }

    @Test fun the_people_card_is_one_choice() {
        assertEquals(PeopleCardChoice.ON_WITH_FIRST_MOVER, PeopleCardChoice.of(CircleConfig()))
        PeopleCardChoice.entries.forEach { assertEquals(it, PeopleCardChoice.of(it.applyTo(CircleConfig()))) }
        assertEquals(PeopleCardChoice.ON, PeopleCardChoice.of(CircleConfig(firstMover = false)))
        assertEquals(PeopleCardChoice.OFF, PeopleCardChoice.of(CircleConfig(peopleCard = false)))
    }

    @Test fun the_contacts_header_has_one_lock_button() {
        assertEquals(LockButton.NONE, LockButton.of(privateUnlocked = false, appLock = false))
        assertEquals(LockButton.PRIVATE_CONTACTS, LockButton.of(privateUnlocked = true, appLock = false))
        assertEquals(LockButton.PARLEY, LockButton.of(privateUnlocked = false, appLock = true))
        assertEquals(LockButton.BOTH, LockButton.of(privateUnlocked = true, appLock = true))
    }
}
