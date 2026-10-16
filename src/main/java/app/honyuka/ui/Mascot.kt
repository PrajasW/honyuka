package app.honyuka.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

enum class Mood { HAPPY, YUM, DIZZY, SLEEPY, WINK }

private val Ink = Color(0xFF3A3335)

/** Speech-bubble body with its tail bottom-left (same path as the app icon). */
private val BodyPath: Path = svgPath(
    "M50 12 C76 12 92 27 92 45 C92 63 76 77 52 77 C47 77 43 76.5 39 75.5 L22 90 " +
        "C21.2 90.7 20 90 20.4 89 L26 72.5 C15 67 8 57 8 45 C8 27 24 12 50 12 Z",
)
private val ShinePath: Path = svgPath("M24 34 Q30 22 44 19")
private val BadgeLetterPath: Path = svgPath("M80.8 24.5 L85 15 L89.2 24.5 M82.5 21.2 L87.5 21.2")

private fun svgPath(d: String): Path = PathParser().parsePathString(d).toPath()

/**
 * Fuki-chan (吹き出し, "speech bubble"): Honyuka's mascot, a speech bubble with a
 * small "A" badge for the translated side. Drawn in a 100×100 box like the app icon.
 */
@Composable
fun Mascot(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    mood: Mood = Mood.HAPPY,
    bounce: Boolean = false,
) {
    val c = Kawaii.colors
    val offset = if (bounce) {
        val transition = rememberInfiniteTransition(label = "mascot-bounce")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = -0.12f,
            animationSpec = infiniteRepeatable(tween(380, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "mascot-y",
        ).value
    } else {
        0f
    }

    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension / 100f
        translate(top = offset * this.size.height) {
            scale(unit, unit, pivot = Offset.Zero) {
                drawOval(c.shadow, topLeft = Offset(22f, 92.6f - offset * 100f), size = Size(60f, 6.8f))
                drawPath(BodyPath, c.rice)
                drawPath(BodyPath, Ink, style = Stroke(width = 2.8f, join = StrokeJoin.Round))
                drawPath(ShinePath, Color.White.copy(alpha = 0.9f), style = Stroke(width = 2.6f, cap = StrokeCap.Round))
                drawOval(c.sakura.copy(alpha = 0.6f), topLeft = Offset(22f, 47.4f), size = Size(12f, 7.2f))
                drawOval(c.sakura.copy(alpha = 0.6f), topLeft = Offset(66f, 47.4f), size = Size(12f, 7.2f))
                // The original face art sits ~6 units lower; lift it into the bubble.
                translate(top = -6f) { drawFace(mood, c.sakura) }
                drawCircle(c.sora, radius = 10f, center = Offset(85f, 20f))
                drawCircle(Ink, radius = 10f, center = Offset(85f, 20f), style = Stroke(width = 2.2f))
                drawPath(
                    BadgeLetterPath,
                    Color.White,
                    style = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

private fun DrawScope.drawFace(mood: Mood, pink: Color) {
    val stroke = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(d: String) = drawPath(svgPath(d), Ink, style = stroke)
    fun dotEye(x: Float) {
        drawCircle(Ink, radius = 3f, center = Offset(x, 49f))
        drawCircle(Color.White, radius = 1f, center = Offset(x + 1f, 48f))
    }
    when (mood) {
        Mood.HAPPY -> {
            line("M36 50 Q40 45 44 50")
            line("M56 50 Q60 45 64 50")
            line("M46 56 Q50 60 54 56")
        }
        Mood.YUM -> {
            dotEye(40f)
            dotEye(60f)
            drawPath(svgPath("M45 55 Q50 62 55 55 Z"), Ink)
            drawOval(pink, topLeft = Offset(49.1f, 56.9f), size = Size(4.8f, 3.2f))
        }
        Mood.DIZZY -> {
            line("M37 46 L43 52 M43 46 L37 52")
            line("M57 46 L63 52 M63 46 L57 52")
            line("M44 58 Q47 55 50 58 Q53 61 56 58")
        }
        Mood.SLEEPY -> {
            line("M36 50 L44 50")
            line("M56 50 L64 50")
            drawCircle(Ink, radius = 2.2f, center = Offset(50f, 57f), style = stroke)
        }
        Mood.WINK -> {
            line("M36 50 Q40 45 44 50")
            dotEye(60f)
            line("M46 56 Q50 60 54 56")
        }
    }
}

private val LoaderLines = listOf(
    "Reading the speech bubbles… 👀",
    "Polishing every line… ✨",
    "Asking the manga spirits… 📖",
    "Fitting words into bubbles… 💬",
    "Almost there, hang tight… 🍙",
)

/** Bouncing mascot with rotating status lines (changes every ~2 s). */
@Composable
fun KawaiiLoader(
    modifier: Modifier = Modifier,
    mascotSize: Dp = 72.dp,
) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_200)
            index = (index + 1) % LoaderLines.size
        }
    }
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Mascot(size = mascotSize, mood = Mood.YUM, bounce = true)
        Text(
            text = LoaderLines[index],
            style = MaterialTheme.typography.titleMedium,
            color = Kawaii.colors.text,
            textAlign = TextAlign.Center,
        )
        Text(text = "しょうしょうお待ちください", style = JpTextStyle)
    }
}
