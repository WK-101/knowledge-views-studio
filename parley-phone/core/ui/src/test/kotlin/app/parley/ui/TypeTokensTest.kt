package app.parley.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The type roles in [ParleyType]: heavier leading words, and digits that don't wobble. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class TypeTokensTest {
    private val type = Typography()

    @Test fun leading_words_are_heavier_at_the_same_size() {
        listOf(
            type.headlineLarge to ParleyType.callerName(type),
            type.headlineSmall to ParleyType.callerNameCompact(type),
            type.displayMedium to ParleyType.posterName(type),
            type.titleMedium to ParleyType.callTimer(type),
            type.headlineSmall to ParleyType.homeTitle(type),
        ).forEach { (plain, role) ->
            assertEquals(plain.fontSize, role.fontSize)
            assertTrue("$role", (role.fontWeight ?: FontWeight.Normal) > (plain.fontWeight ?: FontWeight.Normal))
        }
        assertEquals("tnum", ParleyType.callTimer(type).fontFeatureSettings)
    }

    @Test fun tabular_keeps_the_style_and_adds_tabular_figures() {
        val base = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium)
        val t = base.tabular()
        assertEquals("tnum", t.fontFeatureSettings)
        assertEquals(base.fontSize, t.fontSize)
        assertEquals(base.fontWeight, t.fontWeight)
    }

    @Test fun keypad_digits_are_light_tabular_and_sized() {
        val d = ParleyType.keypadDigit(34.sp)
        assertEquals("tnum", d.fontFeatureSettings)
        assertEquals(FontWeight.Light, d.fontWeight)
        assertEquals(34.sp, d.fontSize)
        assertEquals(34.sp, d.lineHeight)
    }
}
