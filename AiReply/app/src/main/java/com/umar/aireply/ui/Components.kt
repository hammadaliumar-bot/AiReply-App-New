package com.umar.aireply.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umar.aireply.ui.theme.AppColors

/** Full-screen dark gradient with a faint mint glow in the top corner. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AppColors.InkSoft, AppColors.Ink, Color(0xFF05080F))))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(AppColors.Mint.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.9f, 0f),
                        radius = size.width * 0.95f
                    )
                )
            },
        content = content
    )
}

/** The metallic gold signature badge: "<name> Edition". */
@Composable
fun BrandBadge(name: String, modifier: Modifier = Modifier) {
    val gold = Brush.linearGradient(
        listOf(AppColors.GoldLight, AppColors.Gold, AppColors.GoldDark, AppColors.Gold, AppColors.GoldLight)
    )
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(AppColors.Gold.copy(alpha = 0.10f))
            .border(1.dp, gold, shape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(AppColors.Mint))
        Text(
            text = "$name Edition",
            style = MaterialTheme.typography.labelLarge.copy(brush = gold, letterSpacing = 0.6.sp)
        )
    }
}

@Composable
fun BrandFooter(name: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HorizontalDivider(color = AppColors.Outline.copy(alpha = 0.6f))
        Text(
            text = "Developed by $name",
            style = MaterialTheme.typography.labelLarge,
            color = AppColors.TextSecondary
        )
        BrandBadge(name)
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(AppColors.SurfaceHigh, AppColors.Surface)))
            .border(1.dp, (accent ?: AppColors.Outline).copy(alpha = 0.55f), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
    }
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** Pulsing indicator: rings radiate while auto-reply is live, a still grey dot when it is off. */
@Composable
fun PulseOrb(active: Boolean, modifier: Modifier = Modifier.size(64.dp)) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    val color = if (active) AppColors.Mint else AppColors.TextSecondary.copy(alpha = 0.55f)
    Canvas(modifier) {
        val c = center
        val base = size.minDimension * 0.24f
        if (active) {
            for (offset in listOf(0f, 0.5f)) {
                val p = (phase + offset) % 1f
                drawCircle(
                    color = color.copy(alpha = (1f - p) * 0.5f),
                    radius = base + size.minDimension * 0.26f * p,
                    center = c,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color, color.copy(alpha = 0.35f)),
                center = c,
                radius = base * 1.5f
            ),
            radius = base,
            center = c
        )
    }
}

@Composable
fun appFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppColors.Mint,
    unfocusedBorderColor = AppColors.Outline,
    focusedLabelColor = AppColors.Mint,
    unfocusedLabelColor = AppColors.TextSecondary,
    cursorColor = AppColors.Mint,
    focusedTextColor = AppColors.TextPrimary,
    unfocusedTextColor = AppColors.TextPrimary,
    focusedSupportingTextColor = AppColors.TextSecondary,
    unfocusedSupportingTextColor = AppColors.TextSecondary
)

@Composable
fun appSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = AppColors.Ink,
    checkedTrackColor = AppColors.Mint,
    uncheckedThumbColor = AppColors.TextSecondary,
    uncheckedTrackColor = AppColors.SurfaceHigh,
    uncheckedBorderColor = AppColors.Outline
)

/**
 * Text field that keeps its own text so typing never jumps, and reports every change.
 * [password] adds a show/hide toggle.
 */
@Composable
fun SettingTextField(
    label: String,
    initial: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    supporting: String? = null,
    password: Boolean = false
) {
    var text by remember { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(!password) }

    val trailing: (@Composable () -> Unit)? = if (password) {
        {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                    tint = AppColors.TextSecondary
                )
            }
        }
    } else null

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onCommit(it)
        },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        maxLines = if (singleLine) 1 else 12,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = trailing,
        supportingText = if (supporting != null) ({ Text(supporting) }) else null,
        shape = RoundedCornerShape(14.dp),
        colors = appFieldColors(),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = appSwitchColors())
    }
}

@Composable
fun LabeledSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                color = AppColors.Mint
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = AppColors.Mint,
                activeTrackColor = AppColors.Mint,
                inactiveTrackColor = AppColors.Outline
            )
        )
    }
}
