package com.suseoaa.locationspoofer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.suseoaa.locationspoofer.data.model.RoutePlanStage
import com.suseoaa.locationspoofer.ui.screen.BlockingScreen
import com.suseoaa.locationspoofer.ui.screen.FullScreenMapPage
import com.suseoaa.locationspoofer.ui.screen.InitializingScreen
import com.suseoaa.locationspoofer.ui.screen.SpoofingScreen
import com.suseoaa.locationspoofer.ui.theme.AppColorSchemeDark
import com.suseoaa.locationspoofer.ui.theme.AppColorSchemeLight
import com.suseoaa.locationspoofer.viewmodel.MainViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 悬浮窗权限（系统特殊权限，不走 requestPermissions 流程）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val isDark = isSystemInDarkTheme()
            val colorScheme = if (isDark) AppColorSchemeDark else AppColorSchemeLight

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(viewModel = viewModel, uiState = uiState, isDark = isDark)
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    uiState: com.suseoaa.locationspoofer.data.model.AppState,
    isDark: Boolean
) {
    val context = LocalContext.current
    var isFullScreenMap by remember { mutableStateOf(false) }

    // ---- 权限检查 ----
    val requiredPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var permissionsGranted by remember { mutableStateOf(hasAllPermissions()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        // 授权位置权限后，额外申请后台位置权限（Android 10+ 需单独申请）
        if (permissionsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // 后台位置不阻塞主流程，静默发起
            }
        }
    }

    // 启动时自动发起权限申请
    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    // ---- 导航逻辑 ----

    fun closeFullScreenMap() {
        if (uiState.routePlanStage == RoutePlanStage.SELECTING ||
            uiState.routePlanStage == RoutePlanStage.READY) {
            viewModel.cancelRoutePlanning()
        }
        isFullScreenMap = false
    }

    BackHandler(enabled = isFullScreenMap) { closeFullScreenMap() }

    AnimatedContent(
        targetState = isFullScreenMap,
        transitionSpec = {
            slideInVertically(tween(400)) { it } togetherWith slideOutVertically(tween(400)) { -it }
        },
        label = "fullscreen_transition"
    ) { fullScreen ->
        if (fullScreen) {
            FullScreenMapPage(
                viewModel = viewModel,
                uiState = uiState,
                isDark = isDark,
                onClose = { closeFullScreenMap() }
            )
        } else {
            when {
                !permissionsGranted -> BlockingScreen(
                    icon = Icons.Rounded.LocationOff,
                    title = "需要位置权限",
                    message = "本应用需要「精确位置」和「通知」权限才能运行。\n请点击系统弹窗中的「允许」，或前往设置手动开启。",
                    isDark = isDark,
                    onAction = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
                    actionLabel = "重新申请权限"
                )
                uiState.isInitializing -> InitializingScreen(isDark)
                !uiState.hasRootAccess -> BlockingScreen(
                    icon = Icons.Rounded.Lock,
                    title = "需要 Root 权限",
                    message = "本应用需要 Root 权限才能运行。\n请在 KernelSU 或 Magisk 中授权后重启应用。",
                    isDark = isDark
                )
                !uiState.isLSPosedActive -> BlockingScreen(
                    icon = Icons.Rounded.Extension,
                    title = "LSPosed 模块未激活",
                    message = "请在 LSPosed 管理器中启用本模块，\n勾选需要欺骗的应用后重启目标应用。",
                    isDark = isDark
                )
                else -> SpoofingScreen(
                    viewModel = viewModel,
                    uiState = uiState,
                    isDark = isDark,
                    onExpandMap = { isFullScreenMap = true }
                )
            }
        }
    }
}
