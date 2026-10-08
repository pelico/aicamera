package com.pelico.aicamera.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pelico.aicamera.CompositionViewModel
import com.pelico.aicamera.LiveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CameraScreen(vm: CompositionViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    if (!granted) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("需要相机权限才能做实时取景引导")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授权相机")
                }
            }
        }
        return
    }

    val previewView = remember { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val analyzer = remember {
        ImageAnalysis.Analyzer { proxy ->
            val bitmap = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            proxy.close()
            vm.onFrame(bitmap, rotation)
        }
    }

    DisposableEffect(lifecycleOwner) {
        var provider: ProcessCameraProvider? = null
        val job = scope.launch {
            val cameraProvider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }
            provider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(Size(480, 640))
                .build()
                .also { it.setAnalyzer(executor, analyzer) }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
                imageCapture
            )
        }

        onDispose {
            job.cancel()
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }

    val live by vm.live.collectAsState()
    val ready by vm.ready.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        live?.let { CompositionOverlay(it) }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            live?.let { result ->
                Surface(color = Color.Black.copy(alpha = 0.55f)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(result.guidance.text, color = Color.White)
                        Text(
                            "美学分 %.2f · 推理 %d ms".format(result.score, result.inferenceMs),
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
            if (!ready) {
                Surface(color = Color.Black.copy(alpha = 0.55f)) {
                    Text(
                        "模型加载中…",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
            Button(onClick = { capture(context, imageCapture) }) {
                Text("拍照")
            }
        }
    }
}

@Composable
private fun CompositionOverlay(result: LiveResult) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val box = result.box

        // 三分参考线
        val guideLine = Color.White.copy(alpha = 0.22f)
        drawLine(guideLine, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 1.5f)
        drawLine(guideLine, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth = 1.5f)
        drawLine(guideLine, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 1.5f)
        drawLine(guideLine, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth = 1.5f)

        // 建议构图框
        val boxColor = if (result.guidance.aligned) Color(0xFF22C55E) else Color(0xFFFACC15)
        drawRect(
            color = boxColor,
            topLeft = Offset(box.left * w, box.top * h),
            size = ComposeSize((box.right - box.left) * w, (box.bottom - box.top) * h),
            style = Stroke(width = 4f)
        )

        // 画面中心
        drawCircle(
            color = Color.White.copy(alpha = 0.75f),
            radius = 6f,
            center = Offset(w / 2f, h / 2f)
        )

        // 从画面中心指向目标构图中心的引导箭头
        if (!result.guidance.aligned) {
            val from = Offset(w / 2f, h / 2f)
            val to = Offset(box.cx * w, box.cy * h)
            drawLine(boxColor, from, to, strokeWidth = 3f)
            drawArrowHead(this, from, to, boxColor)
        }
    }
}

private fun drawArrowHead(scope: DrawScope, from: Offset, to: Offset, color: Color) {
    val angle = atan2(to.y - from.y, to.x - from.x)
    val length = 26f
    val spread = 0.42f
    listOf(angle + PI.toFloat() - spread, angle + PI.toFloat() + spread).forEach { a ->
        scope.drawLine(
            color = color,
            start = to,
            end = Offset(to.x + length * cos(a), to.y + length * sin(a)),
            strokeWidth = 3f
        )
    }
}

private fun capture(context: Context, imageCapture: ImageCapture) {
    val name = "AIC_${System.currentTimeMillis()}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/AiCamera"
            )
        }
    }
    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values
    ).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
            }

            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(context, "拍照失败：${exception.message}", Toast.LENGTH_SHORT).show()
            }
        }
    )
}
