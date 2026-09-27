package app.parley.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Parley's spacing scale (a 4 dp grid). Screens use these instead of one-off values so paddings line up between
 * screens; [listInset] is where list text and section headers start.
 */
object Spacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /** Start of list text, section headers and inset cards (lines up with ListItem's own padding). */
    val listInset: Dp = 16.dp

    /** Space between the groups of a settings page. */
    val groupGap: Dp = 12.dp
}

/**
 * Named corner shapes, read from the theme's shape scale ([ParleyTheme] sets the radii), so a screen says what a
 * surface is (a tag, a card, a sheet) rather than how round it is.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object ParleyShapes {
    /** Tags, badges and small status chips (8 dp). */
    val tag: CornerBasedShape
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes.extraSmall

    /** Text fields, thumbnails and small controls (12 dp). */
    val control: CornerBasedShape
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes.small

    /** Tiles and option previews (16 dp). */
    val tile: CornerBasedShape
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes.medium

    /** Cards and grouped lists, the outer corner of a segmented group (20 dp). */
    val card: CornerBasedShape
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes.largeIncreased

    /** Large panels and big buttons (24 dp). */
    val panel: CornerBasedShape
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes.large

    /** Sheets, dialogs and the call cards (28 dp). */
    val sheet: CornerBasedShape
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes.extraLarge

    /** Hero surfaces: the photo header, the dial pad panel (32 dp). */
    val hero: CornerBasedShape
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.shapes.extraLargeIncreased

    /** Fully round ends (pills, drag handles, the call button). */
    val pill: Shape get() = CircleShape

    /** The fast-scroll bubble: round, with a small corner pointing at the rail. */
    val bubble: Shape = RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomStartPercent = 50, bottomEndPercent = 8)
}

/** This shape with square bottom corners (a sheet or panel attached to the bottom edge). */
fun CornerBasedShape.topOnly(): CornerBasedShape = copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)

/** This shape with square top corners. */
fun CornerBasedShape.bottomOnly(): CornerBasedShape = copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize)

/**
 * A rounded shape whose radius is computed at run time (an animated corner). Fixed radii come from [ParleyShapes].
 */
fun animatedCorners(radius: Dp): CornerBasedShape = RoundedCornerShape(CornerSize(radius.coerceAtLeast(0.dp)))
