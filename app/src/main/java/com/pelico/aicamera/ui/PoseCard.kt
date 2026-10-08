package com.pelico.aicamera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pelico.aicamera.engine.ScoredPose

@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = Color.Unspecified,
    content: Color = Color.Unspecified
) {
    val bg = if (container == Color.Unspecified) MaterialTheme.colorScheme.secondaryContainer else container
    val fg = if (content == Color.Unspecified) MaterialTheme.colorScheme.onSecondaryContainer else content
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        modifier = modifier
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg
        )
    }
}

@Composable
fun PoseCard(scored: ScoredPose, modifier: Modifier = Modifier) {
    val t = scored.template
    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(66.dp)
                ) {
                    PoseFigure(
                        figure = t.figure,
                        modifier = Modifier
                            .size(66.dp)
                            .padding(6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = t.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${t.figure.facing.zh} · ${t.camera.zh}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${t.group.zh} · 匹配度 %.2f".format(scored.score),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (scored.reasons.isNotEmpty() || t.prop != null) {
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    scored.reasons.forEach { reason ->
                        Chip(text = reason)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    t.prop?.let { Chip(text = "道具 · $it") }
                }
            }

            Column(modifier = Modifier.padding(top = 10.dp)) {
                t.steps.forEachIndexed { index, step ->
                    Text(
                        text = "${index + 1}. $step",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }

            Text(
                text = t.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}
