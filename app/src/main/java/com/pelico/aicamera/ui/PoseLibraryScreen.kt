package com.pelico.aicamera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pelico.aicamera.SceneViewModel
import com.pelico.aicamera.engine.PoseLibrary
import com.pelico.aicamera.engine.PoseTemplate
import com.pelico.aicamera.engine.SceneGroup

@Composable
fun PoseLibraryScreen(vm: SceneViewModel) {
    var group by remember { mutableStateOf<SceneGroup?>(null) }
    val list = remember(group) {
        val g = group
        if (g == null) PoseLibrary.ALL else PoseLibrary.byGroup(g)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            TextButton(onClick = { group = null }) {
                Text(
                    text = "全部 ${PoseLibrary.ALL.size}",
                    fontWeight = if (group == null) FontWeight.Medium else FontWeight.Normal,
                    color = if (group == null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PoseLibrary.groups.forEach { g ->
                TextButton(onClick = { group = if (group == g) null else g }) {
                    Text(
                        text = g.zh,
                        fontWeight = if (group == g) FontWeight.Medium else FontWeight.Normal,
                        color = if (group == g) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp,
                end = 12.dp,
                bottom = 24.dp
            )
        ) {
            items(list, key = { it.id }) { template ->
                PoseItem(
                    template = template,
                    onUse = { vm.previewPose(template) },
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun PoseItem(
    template: PoseTemplate,
    onUse: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(60.dp)
                ) {
                    PoseFigure(
                        figure = template.figure,
                        modifier = Modifier
                            .size(60.dp)
                            .padding(6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${template.group.zh} · ${template.figure.facing.zh}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = template.camera.zh,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onUse) {
                    Text("用它", style = MaterialTheme.typography.labelLarge)
                }
            }

            Column(modifier = Modifier.padding(top = 8.dp)) {
                template.steps.forEachIndexed { index, step ->
                    Text(
                        text = "${index + 1}. $step",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }

            Text(
                text = template.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
