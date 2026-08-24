package edu.fnosari.momedm.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * The moon and a scatter of stars, for the bedtime screen.
 *
 * Drawn rather than shipped as an asset: the project takes no new dependencies, and the core Material
 * icon set has no moon in it. Drawing it also lets the phase be the real one for tonight, which is a
 * small thing a child can check against their window.
 *
 * Deliberately still. A locked phone is not the place for something that dances, and this screen may
 * sit lit on a cheap handset for a while.
 */
@Composable
fun NightSky(
    phase: Float,
    moonColor: Color,
    starColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
) {
    Canvas(modifier.size(size)) { drawNightSky(phase, moonColor, starColor) }
}

/** Fixed star positions as fractions of the canvas, with a radius in dp. A random scatter would flicker. */
private val STARS = listOf(
    Triple(0.12f, 0.16f, 1.7f), Triple(0.83f, 0.12f, 2.1f), Triple(0.24f, 0.76f, 1.4f),
    Triple(0.92f, 0.62f, 1.6f), Triple(0.06f, 0.47f, 1.2f), Triple(0.70f, 0.88f, 1.9f),
    Triple(0.42f, 0.05f, 1.3f), Triple(0.78f, 0.34f, 1.1f),
)

/**
 * [phase] runs 0f..1f over one lunation: 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter.
 *
 * The lit shape is a disc with a second disc cleared out of it, offset to taste — the same trick as
 * sliding one coin across another. Cheaper than intersecting paths, and it reads correctly at this
 * size. Needs its own layer, because BlendMode.Clear would otherwise punch through the background.
 */
private fun DrawScope.drawNightSky(phase: Float, moonColor: Color, starColor: Color) {
    for ((fx, fy, sr) in STARS) {
        drawCircle(starColor, radius = sr.dp.toPx(), center = Offset(size.width * fx, size.height * fy))
    }

    val r = size.minDimension * 0.30f
    val centre = Offset(size.width * 0.5f, size.height * 0.46f)
    val k = cos(2f * PI.toFloat() * phase)   // +1 at new moon, -1 at full
    val waxing = phase < 0.5f

    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), Paint())
        drawCircle(moonColor, radius = r, center = centre, style = Fill)
        when {
            // Full: nothing to take away.
            k <= -0.995f -> Unit
            // New: no lit crescent at all, so leave a faint disc rather than an empty sky.
            k >= 0.995f -> {
                drawCircle(Color.Transparent, radius = r * 1.02f, center = centre, blendMode = BlendMode.Clear)
                drawCircle(moonColor.copy(alpha = 0.20f), radius = r, center = centre, style = Fill)
            }
            else -> {
                // Waxing moons are lit on the right (northern hemisphere), so the shadow disc slides
                // in from the left; waning is the mirror. Getting this backwards produces a moon that
                // is a real phase on the wrong side, which is worse than no moon at all — a child who
                // looks out of the window would see the app is lying.
                val dx = r * 2f * (if (waxing) k else -k)
                drawCircle(
                    Color.Transparent, radius = r * 1.02f,
                    center = Offset(centre.x + dx, centre.y),
                    blendMode = BlendMode.Clear,
                )
            }
        }
        canvas.restore()
    }
    // Guard against the crescent vanishing entirely at the extremes of the offset above.
    if (abs(k) < 0.995f) Unit
}

/**
 * The moon's phase at [epochMs], as 0f..1f.
 *
 * A mean-lunation approximation rather than an ephemeris — good to well under a day, which is more
 * than a picture on a bedtime screen asks for. Reference new moon: 2000-01-06 18:14 UTC.
 */
fun moonPhaseAt(epochMs: Long): Float {
    val synodicDays = 29.530588853
    val referenceNewMoon = 947182440000L
    val days = (epochMs - referenceNewMoon) / 86_400_000.0
    val p = (days / synodicDays) % 1.0
    return (if (p < 0) p + 1.0 else p).toFloat()
}
