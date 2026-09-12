package com.todocompanion.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.todocompanion.app.ui.components.RichNoteView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P4 — render the Notes reading view (the rich renderer that expands headings, wiki-links, task
 * checkboxes, quotes and tables) under Robolectric, so the whole Notes rendering pipeline is exercised
 * in CI without a device. Complements ReadingViewRenderTest (which pins the table-in-scroll crash).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotesRenderTest {
    @get:Rule val rule = createComposeRule()

    private val body = """
        # Weekly review

        A note that pulls the app in: [[Ship it]] and #focus.

        - [ ] open task
        - [x] done task

        > A quote to render.

        | Col A | Col B |
        | --- | --- |
        | 1 | 2 |
    """.trimIndent()

    @Test fun renders_rich_note_without_crashing() {
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    RichNoteView(markdown = body, images = emptyMap())
                }
            }
        }
        // The whole heading / wiki-link / checkbox / quote / table pipeline composes and lays out
        // without throwing (the reader used to crash on a table inside a scroll — see ReadingViewRenderTest).
        rule.waitForIdle()
    }

    @Test fun renders_empty_note_without_crashing() {
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) { RichNoteView(markdown = "", images = emptyMap()) }
            }
        }
        rule.waitForIdle()
    }
}
