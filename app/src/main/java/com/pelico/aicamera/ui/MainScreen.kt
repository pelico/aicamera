package com.pelico.aicamera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pelico.aicamera.CompositionViewModel

@Composable
fun MainScreen(vm: CompositionViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("取景引导", "拍后分析", "性能实测")

    Scaffold(
        topBar = {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (tab) {
                0 -> CameraScreen(vm)
                1 -> AnalyzeScreen(vm)
                2 -> BenchmarkScreen(vm)
            }
        }
    }
}
