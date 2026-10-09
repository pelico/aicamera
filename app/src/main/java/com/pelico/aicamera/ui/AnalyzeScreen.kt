package com.pelico.aicamera.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pelico.aicamera.SceneViewModel

/**
 * 拍后分析：给一张已拍的照片识别场景并打分，然后回答"下次在这儿该怎么拍"。
 * 不做裁剪建议——真实构图建议交给取景页实时给。
 */
@Composable
fun AnalyzeScreen(vm: SceneViewModel) {
    val context = LocalContext.current
    var photo by remember { mutableStateOf<Bitmap?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bmp = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()
        if (bmp != null) {
            photo = bmp
            vm.analyzePhoto(bmp)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Button(onClick = { launcher.launch("image/*") }) {
            Text("从相册选一张照片")
        }

        photo?.let { bmp ->
            Spacer(modifier = Modifier.height(12.dp))
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "选中的照片",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
                contentScale = ContentScale.Fit
            )
        }

            val scene = vm.scene
            if (scene != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "识别场景：${scene.coarse.zh}（${(scene.coarseProb * 100).toInt()}%）",
                    style = MaterialTheme.typography.titleMedium
                )
                scene.top.firstOrNull()?.let { top ->
                    Text(
                        text = "细粒度 ${top.zh} · ${top.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                vm.person?.let { p ->
                    Text(
                        text = "人物：${p.shotSize.zh} · ${p.facing.zh} · 占画面 %.0f%%".format(p.heightRatio * 100),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                vm.lighting?.let {
                    Text(
                        text = it.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                scene.top.firstOrNull()?.let { top ->
                    com.pelico.aicamera.engine.SceneLabels.localHint(top.label)?.let { hint ->
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (vm.aesthetic > 0f) {
                    Text(
                        text = "美学评分 %.2f / 10".format(vm.aesthetic),
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "如果重拍这张，最该改的是：",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                vm.guidance?.let { g ->
                    GuidanceBanner(
                        guidance = g,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
                Text(
                    text = "下次在这儿可以这样拍：",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                vm.poses.forEach { scored ->
                    PoseCard(scored = scored, modifier = Modifier.padding(bottom = 10.dp))
                }
        } else if (photo != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Text("正在分析…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
