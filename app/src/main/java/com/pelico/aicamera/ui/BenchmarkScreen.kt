package com.pelico.aicamera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pelico.aicamera.BenchmarkResult
import com.pelico.aicamera.SceneViewModel
import kotlinx.coroutines.launch

@Composable
fun BenchmarkScreen(vm: SceneViewModel) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<BenchmarkResult?>(null) }
    var running by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            Spacer(modifier = Modifier.height(16.dp))
            val fps = if (r.sceneMs > 0f) 1000f / r.sceneMs else 0f
            Text(
                text = "理论最快 %.1f fps。实际取景按 1.5 秒一次跑，所以这个数字只要低于 1500 ms 就完全够用。".format(fps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
