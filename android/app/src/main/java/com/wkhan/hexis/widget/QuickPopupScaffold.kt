package com.wkhan.hexis.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The shared chrome for the Quick-bar widget's translucent popups (task / note / habit / time / search /
 * voice): a tap-away scrim with a bottom sheet pinned to the bottom edge. Each popup supplies only its
 * own body as [content]; the few visual differences between them (corner radius, a dimmed scrim, a fixed
 * height, which insets to pad for) are parameters so one implementation serves all of them instead of
 * the scrim+Surface+tap-away block being copy-pasted into every activity.
 */
@Composable
@Suppress("LongParameterList") // cohesive popup-chrome config: a few small visual knobs with defaults
fun QuickPopupScaffold(
    onDismiss: () -> Unit,
    cornerRadius: Dp = 24.dp,
    tonalElevation: Dp = 3.dp,
    shadowElevation: Dp = 0.dp,
    dimScrim: Boolean = false,
    heightFraction: Float? = null,
    imePadding: Boolean = true,
    navBarsPadding: Boolean = false,
    content: @Composable () -> Unit,
) {
    var scrim = Modifier.fillMaxSize()
    if (dimScrim) scrim = scrim.background(Color.Black.copy(alpha = 0.32f))
    scrim = scrim.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }

    Box(scrim, contentAlignment = Alignment.BottomCenter) {
        var sheet = Modifier.fillMaxWidth()
        if (heightFraction != null) sheet = sheet.fillMaxHeight(heightFraction)
        if (imePadding) sheet = sheet.imePadding()
        if (navBarsPadding) sheet = sheet.navigationBarsPadding()
        // Consume taps on the sheet so they don't fall through to the dismiss scrim behind it.
        sheet = sheet.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}

        Surface(
            shape = RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
            modifier = sheet,
            content = content,
        )
    }
}
