package com.pelico.aicamera.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pelico.aicamera.CompositionViewModel
import com.pelico.aicamera.util.ImageSaver

@Composable
fun AnalyzeScreen(vm: CompositionViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { vm.analyzeStatic(it) }
    }

    val result by vm.analysis.collectAsState()
    val ready by vm.ready.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("选一张已拍的照片，AI 会给出建议的构图区域与二次裁剪框。")

        Button(onClick = { launcher.launch("image/*") }) {
            Text("从相册选择照片")
        }

        if (!ready) {
            Text("模型加载中…")
        }

        result?.let { r ->
            Box(modifier = Modifier.fillMaxWidth()) {
                Image(
                    bitmap = r.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
                Canvas(modifier = Modifier.matchParentSize()) {
                    val w = size.width
                    val h = size.height
                    val box = r.box
                    drawRect(
                        color = Color(0xFF22C55E),
                        topLeft = Offset(box.left * w, box.top * h),
                        size = ComposeSize((box.right - box.left) * w, (box.bottom - box.top) * h),
                        style = Stroke(width = 4f)
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("美学评分：%.2f / 10".format(r.score))
                    Text("建议裁剪区域占原图 %.0f%%".format(r.box.w * r.box.h * 100f))
                    Text("建议：${r.guidance.text}")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val cropped = ImageSaver.crop(r.bitmap, r.box)
                    if (cropped == null) {
                        Toast.makeText(context, "裁剪失败", Toast.LENGTH_SHORT).show()
                    } else {
                        val uri = ImageSaver.save(context, cropped, "crop")
                        val msg = if (uri == null) "保存失败" else "裁剪结果已保存到相册"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        if (cropped !== r.bitmap) cropped.recycle()
                    }
                }) {
                    Text("保存裁剪结果")
                }
                OutlinedButton(onClick = { vm.clearAnalysis() }) {
                    Text("清除")
                }
            }
        }
    }
}
