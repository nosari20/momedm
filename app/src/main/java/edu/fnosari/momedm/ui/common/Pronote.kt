package edu.fnosari.momedm.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.fnosari.momedm.ui.theme.pastelOf

/**
 * Pronote-style diagonal stripe band along the left edge.
 * Apply to a clipped card/box; draws under the content.
 */
fun Modifier.stripeEdge(color: Color, bandWidth: Float = 34f): Modifier =
    clipToBounds().drawBehind {
        val stroke = 9f
        val gap = 13f
        var y = -bandWidth
        while (y < size.height + bandWidth) {
            drawLine(
                color = color,
                start = Offset(0f, y + bandWidth),
                end = Offset(bandWidth, y),
                strokeWidth = stroke,
                cap = StrokeCap.Butt,
            )
            y += stroke + gap
        }
    }

/** Pastel pill label, Pronote chip style ("Labo 2", "103"). */
@Composable
fun AccentPill(text: String, accent: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = accent,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(pastelOf(accent), CircleShape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Uppercase small section label ("EMPLOI DU TEMPS"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // A real heading for TalkBack: without it a long page (DeviceScreen) offers no heading
        // navigation and must be swiped through row by row.
        modifier = modifier.semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun pronoteTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.primary,
    titleContentColor = MaterialTheme.colorScheme.onPrimary,
    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
)
