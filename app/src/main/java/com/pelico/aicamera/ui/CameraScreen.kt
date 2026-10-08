package com.pelico.aicamera.ui

import android.content.ContentValues
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pelico.aicamera.SceneViewModel
import com.pelico.aicamera.util.toRotatedBitmap
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/** 场景变化很慢，1.5 秒一次完全够用，也省电 */
private const val SCENE_INTERVAL_MS = 1500L

@Composable
fun CameraScreen(vm: SceneViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    var showGuides by remember { mutableStateOf(true) }
    var tilt by remember { mutableFloatStateOf(0f) }

    val pose = vm.currentPose
    val scene = vm.currentScene
    val lighting = vm.lighting

    LaunchedEffect(Unit) {
        vm.ensureEngines()
        vm.motion.start()
    }

    DisposableEffect(Unit) {
        onDispose { vm.motion.stop() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            tilt = vm.motion.tiltDeg
            delay(80)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(320, 240))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    var lastRun = 0L
                    analysis.setAnalyzer(analyzerExecutor) { proxy ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastRun >= SCENE_INTERVAL_MS) {
                            lastRun = now
                            runCatching { vm.analyze(proxy.toRotatedBitmap()) }
                        }
                        proxy.close()
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                        analysis
                    )
                    imageCapture = capture
                }
            },
            ContextCompat.getMainExecutor(context)
        )

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            analyzerExecutor.shutdown()
        }
    }

    fun shoot() {
        val capture = imageCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "aicam_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(context, "拍摄失败：${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        SceneOverlay(
            placement = pose?.placement,
            figure = pose?.figure,
            showThirds = showGuides,
            tiltDeg = tilt,
            modifier = Modifier.fillMaxSize()
        )

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (scene == null) {
                    Text(
                        text = "正在识别场景…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                } else {
                    Column {
                        Text(
                            text = "${scene.zh}  ${(scene.prob * 100).toInt()}%",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White
                        )
                        Text(
                            text = scene.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    lighting?.let {
                        Text(
                            text = it.describe(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
        ) {
            if (pose != null) {
                val scored = vm.poses.getOrNull(vm.selected)
                if (scored != null) {
                    PoseCard(scored = scored, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "等待场景识别结果…",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { vm.cycle(-1) }) {
                    Text("上一个", color = Color.White)
                }

                FloatingActionButton(
                    onClick = { shoot() },
                    modifier = Modifier.size(64.dp)
                ) {
                    Text("拍", style = MaterialTheme.typography.titleMedium)
                }

                TextButton(onClick = { vm.cycle(1) }) {
                    Text("下一个", color = Color.White)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = { showGuides = !showGuides }) {
                    Text(
                        text = if (showGuides) "隐藏参考线" else "显示参考线",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (vm.latencyMs > 0f) {
                    Text(
                        text = "识别 %.0f ms".format(vm.latencyMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp)
                    )
                }
            }
        }
    }
}
