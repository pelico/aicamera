package com.pelico.aicamera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pelico.aicamera.CompositionViewModel

@Composable
fun BenchmarkScreen(vm: CompositionViewModel) {
    var report by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val ready by vm.ready.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("在你的机型上实测两个模型的单帧延迟。桌面 CPU 的数据只能作数量级参考，真机说了算。")

        Button(
            enabled = ready && !running,
            onClick = {
                running = true
                report = "测试中…"
                vm.runBenchmark { text ->
                    report = text
                    running = false
                }
            }
        ) {
            Text(if (ready) "开始测试" else "模型加载中…")
        }

        if (running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (report.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = report,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
