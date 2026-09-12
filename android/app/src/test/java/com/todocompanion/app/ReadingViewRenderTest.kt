package com.todocompanion.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.todocompanion.app.ui.components.MarkdownText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reproduces the reading-view crash: render the exact table+markdown body the user reported. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReadingViewRenderTest {
    @get:Rule val rule = createComposeRule()

    private val body = "| Test1 | Test2 |\n| --- | --- |\n| 12 | 34 |\n| 45 | 56 |\n---\n**dhdychchc****\n**vjccjchch****\ncudxuf"

    @Test fun renders_table_in_vertical_scroll() {
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MarkdownText(body, Modifier)
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun renders_table_in_selection_container_scroll() {
        rule.setContent {
            MaterialTheme {
                androidx.compose.foundation.text.selection.SelectionContainer(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    MarkdownText(body, Modifier)
                }
            }
        }
        rule.waitForIdle()
    }
}
