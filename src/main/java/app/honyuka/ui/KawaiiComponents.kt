package app.honyuka.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val PillShape = RoundedCornerShape(999.dp)
private val CardShape = RoundedCornerShape(24.dp)

/** English label with a small, soft Japanese subtitle underneath. */
@Composable
fun BilingualLabel(
    english: String,
    japanese: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = english,
            style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            color = Kawaii.colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = japanese, style = JpTextStyle)
    }
}

/** Radius-24 card with a hairline border and a warm, faint shadow. */
@Composable
fun KawaiiCard(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Kawaii.colors
    val haptics = LocalHapticFeedback.current
    val background = tint ?: c.card
    val borderColor = tint ?: c.border
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = CardShape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(CardShape)
            .background(background)
            .border(1.dp, borderColor, CardShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

enum class PillStyle { PRIMARY, SOFT, GHOST }

/** Pill button: sakura primary, sakura-soft, or text-only ghost. Presses scale to 0.98 with a light haptic. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.PRIMARY,
    enabled: Boolean = true,
    compact: Boolean = false,
    accent: Color = Kawaii.colors.sakura,
    accentSoft: Color = Kawaii.colors.sakuraSoft,
) {
    val c = Kawaii.colors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "pill-scale")
    val (fill, textColor) = when (style) {
        PillStyle.PRIMARY -> accent to c.onAccent
        PillStyle.SOFT -> accentSoft to c.text
        PillStyle.GHOST -> Color.Transparent to c.textSoft
    }
    Box(
        modifier = modifier
            .scale(scale)
            .heightIn(min = if (compact) 44.dp else 52.dp)
            .clip(PillShape)
            .background(if (enabled) fill else c.bgSoft)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = if (compact) 18.dp else 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) textColor else c.textFaint,
            maxLines = 1,
        )
    }
}

/** Pill chip with a 1.5 dp border; fills with the accent when selected. */
@Composable
fun KawaiiChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Kawaii.colors.sakura,
) {
    val c = Kawaii.colors
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(PillShape)
            .background(if (selected) accent else c.card)
            .border(1.5.dp, if (selected) accent else c.border, PillShape)
            .clickable(role = Role.RadioButton) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) c.onAccent else c.text,
        )
    }
}

/** Segmented pill control on a bgSoft track. */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Kawaii.colors
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(c.bgSoft)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(PillShape)
                    .background(if (isSelected) c.card else Color.Transparent)
                    .clickable(role = Role.Tab) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(option)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) c.text else c.textSoft,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Soft progress bar on a tinted track. */
@Composable
fun SoftProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Kawaii.colors.sakura,
    track: Color = Kawaii.colors.sakuraSoft,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "soft-progress")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(PillShape)
            .background(track),
    ) {
        if (animated > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .clip(PillShape)
                    .background(color),
            )
        }
    }
}

/** A row with a weighted label column and trailing content. */
@Composable
fun KawaiiRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
