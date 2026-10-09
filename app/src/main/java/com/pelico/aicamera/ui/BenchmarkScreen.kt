package com.pelico.aicamera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pelico.aicamera.BenchmarkResult
import com.pelico.aicamera.SceneViewModel
import kotlinx.coroutines.launch

@Composable
fun BenchmarkScreen(vm: SceneViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var result by remember { mutableStateOf<BenchmarkResult?>(null) }
    var running by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "性能实测",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = "在你这台机器上真跑，桌面 CPU 的数据只能作数量级参考。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = vm.policy.describe(),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "分析帧 ${vm.policy.analysisWidth}×${vm.policy.analysisHeight} · " +
                if (vm.poseAvailable) "姿态层已就绪" else "姿态层未启用（模型缺失或入门机关闭）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                running = true
                scope.launch {
                    vm.ensureEngines()
                    result = vm.runBenchmark()
                    running = false
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (running) "测试中…" else "开始测试")
        }

        result?.let { r ->
            Spacer(modifier = Modifier.height(20.dp))
            Text("设备：${r.device}", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "场景识别 Places365：%.1f ms/帧".format(r.sceneMs),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "美学评分 NIMA：%.1f ms/帧".format(r.scoreMs),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (r.poseMs > 0f) "姿态检测 Pose lite：%.1f ms/帧".format(r.poseMs)
                else "姿态检测：未启用",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            val sceneFps = if (r.sceneMs > 0f) 1000f / r.sceneMs else 0f
            val poseFps = if (r.poseMs > 0f) 1000f / r.poseMs else 0f
            Text(
                text = "理论上限：场景 %.1f fps / 姿态 %.1f fps。".format(sceneFps, poseFps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "实际调度按档位走（场景每 ${vm.policy.sceneIntervalMs} ms，姿态每 ${vm.policy.poseIntervalMs} ms），" +
                    "所以只要单次耗时低于间隔就不掉帧。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 模板角度的采样入口：摆好姿势 → 复制 → 贴回 pose_specs.json
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "姿势校准：在取景页把推荐姿势摆到位，回到这里把当前角度复制走，贴进 " +
                "pose_specs.json 对应条目，然后把 verified 改成 true。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val text = vm.anglesSnapshot()
                if (text == null) {
                    Toast.makeText(context, "还没有检测到人", Toast.LENGTH_SHORT).show()
                } else {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("pose_angles", text))
                    Toast.makeText(context, "已复制当前角度 JSON", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("复制当前角度（用于校准 pose_specs.json）")
        }

        vm.error?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
