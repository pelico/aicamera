package com.pelico.aicamera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pelico.aicamera.contract.GuidanceSource
import com.pelico.aicamera.contract.Guidance
import com.pelico.aicamera.contract.Severity

private fun Severity.tone(): Color = when (this) {
    Severity.BLOCKER -> Color(0xFFE57373)
    Severity.WARN -> Color(0xFFEFB13F)
    Severity.HINT -> Color(0xFF8AB4F8)
    Severity.OK -> Color(0xFF5DDC9A)
}

/**
 * 一次只显示一条指令。
 *
 * 上一版把三个姿势卡片并列给用户，结果是每条都看不完 —— 这条横幅是整个 L4 的对外形态：
 * 一句话说明当前最该改什么，下面一行给出可测量的数值，进度条表示离目标还有多远。
 */
@Composable
fun GuidanceBanner(
    guidance: Guidance?,
    modifier: Modifier = Modifier
) {
    val g = guidance ?: return
    val tone = g.severity.tone()

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.62f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(tone, RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = g.text,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                if (g.source != GuidanceSource.READY) {
                    Text(
                        text = g.source.zh,
                        style = MaterialTheme.typography.labelSmall,
                        color = tone
                    )
                }
            }
            g.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            LinearProgressIndicator(
                progress = { g.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(4.dp),
                color = tone,
                trackColor = Color.White.copy(alpha = 0.15f)
            )
        }
    }
}
