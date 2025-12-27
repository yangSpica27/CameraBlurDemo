package me.spica.camerablur

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.compose.CameraXViewfinder
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import me.spica.camerablur.ui.ZoomControlBar
import me.spica.camerablur.ui.theme.CameraBlurTheme

class MainActivity : ComponentActivity() {

  private lateinit var viewModel: CameraPreviewViewModel

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    viewModel = ViewModelProvider(this).get(CameraPreviewViewModel::class)
    setContent {
      CameraBlurTheme {
        CameraPreviewScreen()
      }
    }
  }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraPreviewScreen(modifier: Modifier = Modifier) {
  val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
  if (cameraPermissionState.status.isGranted) {
    CameraPreviewContent(
      viewModel = ViewModelProvider(LocalContext.current as ComponentActivity)
        .get(CameraPreviewViewModel::class.java),
      modifier = modifier
    )
  } else {
    Column(
      modifier = modifier
        .fillMaxSize()
        .wrapContentSize()
        .widthIn(max = 480.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      val textToShow = if (cameraPermissionState.status.shouldShowRationale) {
        "没有权限"
      } else {
        "彩虹小马 ✨\n" +
            "友谊就是魔法! \uD83C\uDF89"
      }
      Text(textToShow, textAlign = TextAlign.Center)
      Spacer(Modifier.height(16.dp))
      Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
        Text("授予权限")
      }
    }
  }
}

@Composable
fun CameraPreviewContent(
  viewModel: CameraPreviewViewModel,
  modifier: Modifier = Modifier,
  lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
  val surfaceRequest by viewModel.surfaceRequest.collectAsStateWithLifecycle()
  val zoomRatio by viewModel.zoomRatio.collectAsStateWithLifecycle()
  val minZoomRatio by viewModel.minZoomRatio.collectAsStateWithLifecycle()
  val maxZoomRatio by viewModel.maxZoomRatio.collectAsStateWithLifecycle()
  val blurredBackground by viewModel.blurredBackground.collectAsStateWithLifecycle()

  val context = LocalContext.current
  LaunchedEffect(lifecycleOwner) {
    viewModel.bindToCamera(context.applicationContext, lifecycleOwner)
  }

  // 跟踪容器和控制条的位置
  val containerHeight = remember { mutableIntStateOf(0) }
  val containerWidth = remember { mutableIntStateOf(0) }
  val controlBarTop = remember { mutableIntStateOf(0) }
  val controlBarHeight = remember { mutableIntStateOf(0) }

  // 当位置变化时更新 ViewModel
  LaunchedEffect(containerWidth.intValue, containerHeight.intValue, controlBarTop.intValue, controlBarHeight.intValue) {
    if (containerHeight.intValue > 0 && controlBarHeight.intValue > 0) {
      viewModel.updateControlBarPosition(
        viewWidth = containerWidth.intValue,
        viewHeight = containerHeight.intValue,
        barTop = controlBarTop.intValue,
        barHeight = controlBarHeight.intValue
      )
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .onGloballyPositioned { coordinates ->
        containerWidth.intValue = coordinates.size.width
        containerHeight.intValue = coordinates.size.height
      }
  ) {
    // 相机预览
    surfaceRequest?.let { request ->
      CameraXViewfinder(
        surfaceRequest = request,
        modifier = Modifier.fillMaxSize()
      )
    }

    // 底部变焦控制条（带模糊背景）
    ZoomControlBar(
      zoomRatio = zoomRatio,
      minZoomRatio = minZoomRatio,
      maxZoomRatio = maxZoomRatio,
      onZoomChange = { viewModel.setZoomRatio(it) },
      blurredBackground = blurredBackground,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .onGloballyPositioned { coordinates ->
          controlBarTop.intValue = coordinates.positionInParent().y.toInt()
          controlBarHeight.intValue = coordinates.size.height
        }
    )
  }
}


