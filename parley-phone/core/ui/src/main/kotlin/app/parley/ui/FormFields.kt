package app.parley.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizes of Parley's forms (the contact editor and other long forms): compact tonal fields stacked as one segmented
 * block per group, one icon per group in a start gutter, and an end column for the row's remove or expand button.
 * Dense but not cramped: fields are 48 dp (Material's dense text field) and every control keeps a 48 dp target.
 */
object FormTokens {
    /** Width of the start gutter that holds a group's icon (24 dp icon + 12 dp gap). */
    val gutter: Dp = 36.dp

    /** Width of the end column (a 48 dp icon button). */
    val endColumn: Dp = 48.dp

    /** Height of a single-line field (label inside, above the value), and so of the gutter icon's line. */
    val fieldHeight: Dp = 48.dp

    /** Gap between the fields of one group: reads as a hairline divider, not as separate boxes. */
    val segmentGap: Dp = 2.dp

    /** Gap between groups: the page background shows through, enough to tell groups apart. */
    val groupGap: Dp = 8.dp

    /** Corner of a group's outer edges and of the joins inside it. */
    val outerCorner: Dp = 16.dp
    val innerCorner: Dp = 4.dp

    /** The photo in a form's header, beside the name fields. */
    val headerPhoto: Dp = 80.dp

    /** Widest a value's type selector ("Mobile ▾") gets inside its field before it ellipsises. */
    val typeMaxWidth: Dp = 96.dp
}

/** Which part of a line a field fills: the whole line, or its start or end half when two fields share it. */
enum class FieldSide { Whole, Start, End }

/**
 * The shape of the field on line [index] of [count] in a segmented group: round outer corners on the first and last
 * line, small ones where lines meet. For two fields on one line, [side] rounds only that field's outer edge.
 */
fun formFieldShape(index: Int, count: Int, side: FieldSide = FieldSide.Whole): Shape {
    val o = FormTokens.outerCorner
    val i = FormTokens.innerCorner
    val top = if (index == 0) o else i
    val bottom = if (index == count - 1) o else i
    return RoundedCornerShape(
        topStart = if (side == FieldSide.End) i else top,
        topEnd = if (side == FieldSide.Start) i else top,
        bottomStart = if (side == FieldSide.End) i else bottom,
        bottomEnd = if (side == FieldSide.Start) i else bottom,
    )
}

/**
 * A calm, filled-tonal text field, 48 dp high: no underline or outline at rest, the label inside (small, above the
 * value once there is one), a 2 dp ring while focused (or in error). [supporting] sits under the field in
 * [supportingColor] (the theme's variant colour when null), so a group of fields keeps reading as one block. It grows
 * with the font size and with [minLines].
 */
@Composable
fun ParleyFormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    shape: Shape = formFieldShape(0, 1),
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    prefix: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    supportingColor: Color? = null,
    isError: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null,
    /** Numbers, addresses and links read left to right in every language. */
    forceLtr: Boolean = false,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val active = focused && !readOnly
    val cs = MaterialTheme.colorScheme
    val ring by animateDpAsState(if (active || isError) 2.dp else 0.dp, ParleyMotion.fastSpatial(), label = "ring")
    val ringColor by animateColorAsState(if (isError) cs.error else cs.primary, ParleyMotion.fastEffects(), label = "ringColor")
    val container = if (active) cs.surfaceContainerHigh else cs.surfaceContainer
    val colors = tonalColors(container)
    val base = LocalTextStyle.current.copy(color = cs.onSurface)
    val style = if (forceLtr) base.copy(textDirection = TextDirection.Ltr) else base
    Column(modifier.animateContentSize(ParleyMotion.fastSpatial())) {
        // BasicTextField with the filled decoration, so the padding can be Material's dense one (4 dp above the
        // label, 4 dp under the value): 48 dp instead of TextField's fixed 56 dp minimum.
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = FormTokens.fieldHeight)
                .then(if (ring > 0.dp) Modifier.border(ring, ringColor, shape) else Modifier),
            readOnly = readOnly,
            textStyle = style,
            cursorBrush = SolidColor(if (isError) cs.error else cs.primary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            minLines = minLines,
            visualTransformation = visualTransformation,
            interactionSource = source,
        ) { inner ->
            TextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = inner,
                enabled = true,
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                interactionSource = source,
                isError = isError,
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                placeholder = textSlot(placeholder),
                trailingIcon = trailing,
                prefix = textSlot(prefix),
                shape = shape,
                colors = colors,
                contentPadding = densePadding(minLines),
            )
        }
        if (supporting != null) SupportingLine(supporting, supportingColor ?: if (isError) cs.error else cs.onSurfaceVariant)
    }
}

/** Material's dense padding (4 dp above the label, 4 dp under the value); a little more for multi-line notes. */
private fun densePadding(minLines: Int): PaddingValues {
    val v = if (minLines > 1) Spacing.s else Spacing.xs
    return TextFieldDefaults.contentPaddingWithLabel(start = Spacing.l, end = Spacing.m, top = v, bottom = v)
}

private fun textSlot(text: String?): (@Composable () -> Unit)? = text?.let { t -> { Text(t) } }

@Composable
private fun SupportingLine(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.xs, bottom = Spacing.xs),
    )
}

/** Filled colours without the indicator line: the container alone carries the field. */
@Composable
private fun tonalColors(container: Color) = TextFieldDefaults.colors(
    focusedContainerColor = container,
    unfocusedContainerColor = container,
    disabledContainerColor = container,
    errorContainerColor = container,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
)

/**
 * One line of a form: the group's [icon] in the start gutter (only on a group's first line; TalkBack reads it as the
 * group's [groupTitle]), the [content], and an [end] slot (a remove or expand button) whose column is kept even when
 * empty, so every field's end edge lines up down the page.
 */
@Composable
fun FormRow(
    icon: ImageVector?,
    groupTitle: String?,
    modifier: Modifier = Modifier,
    end: (@Composable () -> Unit)? = null,
    reserveEnd: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(FormTokens.gutter).heightIn(min = FormTokens.fieldHeight), contentAlignment = Alignment.CenterStart) {
            if (icon != null) {
                Icon(
                    icon, null, Modifier.size(24.dp).semantics { if (groupTitle != null) { contentDescription = groupTitle; heading() } },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f), content = content)
        if (end != null || reserveEnd) {
            Box(Modifier.width(FormTokens.endColumn).heightIn(min = FormTokens.fieldHeight), contentAlignment = Alignment.Center) { end?.invoke() }
        }
    }
}
