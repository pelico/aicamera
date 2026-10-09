package com.pelico.aicamera.ui

import android.content.ContentValues
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/** 预览与分析都是 4:3，叠加层按这个宽高比算可视矩形 */
private const val CONTENT_ASPECT = 3f / 4f

@Composable
fun CameraScreen(vm: SceneViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val policy = vm.policy

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    var showGuides by remember { mutableStateOf(true) }
    var showSteps by remember { mutableStateOf(false) }
    var tilt by remember { mutableFloatStateOf(0f) }

    val pose = vm.currentPose
    val scene = vm.scene
    val person = vm.person
    val guidance = vm.guidance

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
            vm.refreshGuidance()
            delay(100)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(policy.analysisSize)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    var lastScene = 0L
                    var lastPose = 0L
                    analysis.setAnalyzer(analyzerExecutor) { proxy ->
                        val now = SystemClock.elapsedRealtime()
                        // 两条独立节奏：场景慢、姿态快，互不阻塞
                        val needScene = now - lastScene >= policy.sceneIntervalMs
                        val needPose = policy.poseEnabled &&
                            vm.poseAvailable &&
                            now - lastPose >= policy.poseIntervalMs
                        if (needScene) lastScene = now
                        if (needPose) lastPose = now
                        if (needScene || needPose) {
                            runCatching { vm.analyzeFrame(proxy.toRotatedBitmap(), needScene, needPose) }
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
            person = person,
            showThirds = showGuides,
            tiltDeg = tilt,
            srcAspect = CONTENT_ASPECT,
            progress = guidance?.progress ?: 0f,
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
                            text = "${scene.coarse.zh}  ${(scene.coarseProb * 100).toInt()}%",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White
                        )
                        Text(
                            text = scene.top.firstOrNull()?.zh ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    vm.lighting?.let {
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
            GuidanceBanner(guidance = guidance)

            if (pose != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            PoseFigure(
                                figure = pose.figure,
                                modifier = Modifier.padding(4.dp),
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pose.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White
                            )
                            Text(
                                text = "${pose.figure.facing.zh} · ${pose.camera.zh}" +
                                    if (vm.currentSpec?.verified == true) " · 角度已校准" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                        TextButton(onClick = { showSteps = !showSteps }) {
                            Text(
                                text = if (showSteps) "收起" else "步骤",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    if (showSteps) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 10.dp)
                                .height(120.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            pose.steps.forEachIndexed { index, step ->
                                Text(
                                    text = "${index + 1}. $step",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
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
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showGuides = !showGuides }) {
                    Text(
                        text = if (showGuides) "隐藏参考线" else "显示参考线",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = "场景 %.0fms · 姿态 %.0fms".format(vm.latencyMs, vm.poseLatencyMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
    }
}
