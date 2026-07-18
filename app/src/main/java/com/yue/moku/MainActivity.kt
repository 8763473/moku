package com.yue.moku

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yue.moku.ui.MoKuApp
import com.yue.moku.ui.MoKuTheme
import com.yue.moku.util.ApkInstaller

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        AppViewModel.Factory(
            (application as MoKuApplication).container,
            applicationContext,
        )
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 用户选择结果；Service 不依赖此权限仍能运行 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            val updateState by viewModel.updateState.collectAsStateWithLifecycle()
            LaunchedEffect(updateState) {
                (updateState as? AppViewModel.UpdateState.Ready)?.let { state ->
                    ApkInstaller.install(this@MainActivity, state.apkFile)
                    viewModel.consumeUpdateState()
                }
            }
            MoKuTheme { MoKuApp(viewModel) }
        }
    }
}
