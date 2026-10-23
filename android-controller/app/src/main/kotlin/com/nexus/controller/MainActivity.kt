package com.nexus.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.nexus.controller.ui.NexusApp
import com.nexus.controller.ui.NexusTheme

class MainActivity : ComponentActivity() {
    private val vm: NexusViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { NexusTheme { NexusApp(vm) } }
    }

    override fun onPause() {
        vm.manager.releaseAll() // never leave a control held while the app is in the background
        super.onPause()
    }
}
