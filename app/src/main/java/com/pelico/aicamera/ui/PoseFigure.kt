package com.pelico.aicamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.pelico.aicamera.engine.BodyFacing
import com.pelico.aicamera.engine.FigurePose
import com.pelico.aicamera.engine.Placement
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 用 Canvas 画人形剪影。
 *
 * 不依赖任何图片资源：APK 不涨体积、没有版权问题、任何分辨率下都清晰，
 * 姿势参数改一下就能出新的模板，比维护一堆参考图省事得多。
 */
fun DrawScope.drawFigure(figure: FigurePose, box: Rect, color: Color) {
    val h = box.height
    if (h <= 0f) return

    val cx = box.left + box.width * 0.5f
    val footY = box.bottom - h * 0.02f
    val hipY = footY - h * 0.48f
    val shoulderY = footY - h * 0.80f
    val headCY = footY - h * 0.915f
    val headR = h * 0.055f
    val armLen = h * 0.30f
    val strokeW = h * 0.040f

    val shoulderX = cx + figure.lean * h * 0.10f
    val headX = shoulderX + figure.headTurn * h * 0.035f
    val hip = Offset(cx, hipY)
    val shoulder = Offset(shoulderX, shoulderY)

    val lineColor = color.copy(alpha = 0.92f)

    // 腿
    val spread = figure.legSpread.coerceIn(0f, 1f)
    val footDX = spread * h * 0.20f
    val kneeY = (hipY + footY) / 2f + h * 0.012f
    drawLine(
        color = lineColor,
        start = hip,
        end = Offset(cx + spread * h * 0.15f, kneeY),
        strokeWidth = strokeW,
        cap = StrokeCap.Round
    )
    drawLine(
        color = lineColor,
        start = Offset(cx + spread * h * 0.15f, kneeY),
        end = Offset(cx + footDX, footY),
        strokeWidth = strokeW,
        cap = StrokeCap.Round
    )
    drawLine(
        color = lineColor,
        start = hip,
        end = Offset(cx - spread * h * 0.15f, kneeY),
        strokeWidth = strokeW,
        cap = StrokeCap.Round
    )
    drawLine(
        color = lineColor,
        start = Offset(cx - spread * h * 0.15f, kneeY),
        end = Offset(cx - footDX, footY),
        strokeWidth = strokeW,
        cap = StrokeCap.Round
    )

    // 躯干
    drawLine(
        color = lineColor,
        start = hip,
        end = shoulder,
        strokeWidth = strokeW * 1.7f,
        cap = StrokeCap.Round
    )

    // 手臂：正角度表示向画面右侧抬起，负角度表示向左侧
    drawArm(shoulder, armLen, figure.armNearDeg, +1f, h, strokeW, lineColor)
    drawArm(shoulder, armLen, figure.armFarDeg, -1f, h, strokeW, lineColor)

    // 脖子
    drawLine(
        color = lineColor,
        start = shoulder,
        end = Offset(headX, headCY + headR * 0.9f),
        strokeWidth = strokeW * 0.8f,
        cap = StrokeCap.Round
    )

    // 头
    val headCenter = Offset(headX, headCY)
    val facingBack = figure.facing == BodyFacing.BACK || figure.facing == BodyFacing.LOOK_BACK
    if (facingBack) {
        drawCircle(color = lineColor, radius = headR, center = headCenter)
    } else {
        drawCircle(
            color = lineColor,
            radius = headR,
            center = headCenter,
            style = Stroke(width = strokeW * 0.55f)
        )
    }

    when (figure.facing) {
        BodyFacing.FRONT -> {
            val eyeR = headR * 0.12f
            drawCircle(
                color = lineColor,
                radius = eyeR,
                center = Offset(headX - headR * 0.34f, headCY - headR * 0.08f)
            )
            drawCircle(
                color = lineColor,
                radius = eyeR,
                center = Offset(headX + headR * 0.34f, headCY - headR * 0.08f)
            )
        }

        BodyFacing.SIDE_45, BodyFacing.SIDE_90 -> {
            drawLine(
                color = lineColor,
                start = Offset(headX + headR * 0.86f, headCY - headR * 0.12f),
                end = Offset(headX + headR * 1.18f, headCY + headR * 0.06f),
                strokeWidth = strokeW * 0.55f,
                cap = StrokeCap.Round
            )
        }

        BodyFacing.BACK -> Unit
        BodyFacing.LOOK_BACK -> {
            drawLine(
                color = lineColor,
                start = Offset(headX - headR * 0.86f, headCY - headR * 0.12f),
                end = Offset(headX - headR * 1.18f, headCY + headR * 0.06f),
                strokeWidth = strokeW * 0.55f,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun DrawScope.drawArm(
    shoulder: Offset,
    armLen: Float,
    angleDeg: Float,
    side: Float,
    h: Float,
    strokeW: Float,
    color: Color
) {
    val rad = Math.toRadians(angleDeg.toDouble())
    val dx = side * (armLen * sin(rad)).toFloat()
    val dy = (armLen * cos(rad)).toFloat()
    val hand = Offset(shoulder.x + dx, shoulder.y + dy)
    val elbow = Offset(
        shoulder.x + dx * 0.5f + side * h * 0.018f,
        shoulder.y + dy * 0.5f
    )
    drawLine(color = color, start = shoulder, end = elbow, strokeWidth = strokeW * 0.85f, cap = StrokeCap.Round)
    drawLine(color = color, start = elbow, end = hand, strokeWidth = strokeW * 0.85f, cap = StrokeCap.Round)
}

@Composable
fun PoseFigure(
    figure: FigurePose,
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    Canvas(modifier = modifier) {
        drawFigure(figure, Rect(0f, 0f, size.width, size.height), color)
    }
}

/**
 * 取景叠加层：三分参考线 + 站位框 + 人形剪影 + 水平仪。
 * 全部是确定性几何，不会像回归式构图框那样抖动。
 */
@Composable
fun SceneOverlay(
    placement: Placement?,
    figure: FigurePose?,
    showThirds: Boolean,
    tiltDeg: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (showThirds) {
            val guide = Color.White.copy(alpha = 0.22f)
            val sw = 1.5f
            drawLine(guide, Offset(w / 3f, 0f), Offset(w / 3f, h), sw)
            drawLine(guide, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), sw)
            drawLine(guide, Offset(0f, h / 3f), Offset(w, h / 3f), sw)
            drawLine(guide, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), sw)
        }

        placement?.let { p ->
            val boxH = p.height * h
            val boxW = boxH * 0.42f
            val left = p.cx * w - boxW / 2f
            val top = p.footY * h - boxH

            drawRect(
                color = Color(0xFF7CE8C4).copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                style = Stroke(
                    width = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), 0f)
                )
            )

            figure?.let { drawFigure(it, Rect(left, top, left + boxW, top + boxH), Color(0xFF7CE8C4)) }
        }

        // 水平仪：虚线是参考水平，实线跟着机身转，两条重合就是正的
        val cx = w / 2f
        val y = h * 0.13f
        val len = w * 0.16f
        drawLine(
            color = Color.White.copy(alpha = 0.35f),
            start = Offset(cx - len, y),
            end = Offset(cx + len, y),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        )
        val rad = Math.toRadians(tiltDeg.toDouble())
        val dx = (len * cos(rad)).toFloat()
        val dy = (len * sin(rad)).toFloat()
        drawLine(
            color = if (abs(tiltDeg) < 3f) Color(0xFF5DDC9A) else Color(0xFFEFB13F),
            start = Offset(cx - dx, y - dy),
            end = Offset(cx + dx, y + dy),
            strokeWidth = 3.5f,
            cap = StrokeCap.Round
        )
    }
}
