package com.pelico.aicamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pelico.aicamera.ui.MainScreen
import com.pelico.aicamera.ui.theme.AiCameraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AiCameraTheme {
                MainScreen()
            }
        }
    }
}
