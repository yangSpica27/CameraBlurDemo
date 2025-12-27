package me.spica.camerablur

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.spica.camerablur.blur.BlurredFrameAnalyzer
import java.util.concurrent.Executors

class CameraPreviewViewModel : ViewModel() {

  private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
  val surfaceRequest: StateFlow<SurfaceRequest?> = _surfaceRequest

  // 变焦相关状态
  private val _zoomRatio = MutableStateFlow(1f)
  val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

  private val _minZoomRatio = MutableStateFlow(1f)
  val minZoomRatio: StateFlow<Float> = _minZoomRatio.asStateFlow()

  private val _maxZoomRatio = MutableStateFlow(1f)
  val maxZoomRatio: StateFlow<Float> = _maxZoomRatio.asStateFlow()

  // 模糊背景
  private val analysisExecutor = Executors.newSingleThreadExecutor()
  private val blurredFrameAnalyzer = BlurredFrameAnalyzer(analysisExecutor)
  val blurredBackground: StateFlow<Bitmap?> = blurredFrameAnalyzer.blurredBitmap

  private var camera: Camera? = null

  private val cameraPreviewUseCase = Preview.Builder().build().apply {
    setSurfaceProvider { newSurfaceRequest ->
      _surfaceRequest.update { newSurfaceRequest }
    }
  }

  // ImageAnalysis 用于获取帧并进行模糊处理
  private val imageAnalysisUseCase = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
    .build().apply {
      setAnalyzer(analysisExecutor, blurredFrameAnalyzer)
    }

  suspend fun bindToCamera(appContext: Context, lifecycleOwner: LifecycleOwner) {
    val processCameraProvider = ProcessCameraProvider.awaitInstance(appContext)
    camera = processCameraProvider.bindToLifecycle(
      lifecycleOwner,
      DEFAULT_BACK_CAMERA,
      cameraPreviewUseCase,
      imageAnalysisUseCase
    )

    // 获取变焦范围
    camera?.cameraInfo?.zoomState?.value?.let { zoomState ->
      _minZoomRatio.value = zoomState.minZoomRatio
      _maxZoomRatio.value = zoomState.maxZoomRatio
      _zoomRatio.value = zoomState.zoomRatio
    }

    try { awaitCancellation() } finally {
      processCameraProvider.unbindAll()
      analysisExecutor.shutdown()
    }
  }

  fun setZoomRatio(ratio: Float) {
    camera?.cameraControl?.setZoomRatio(ratio)
    _zoomRatio.value = ratio
  }

  /**
   * 更新控制条位置信息，用于精确裁切模糊区域
   * @param viewWidth 预览视图宽度
   * @param viewHeight 预览视图高度
   * @param barTop 控制条顶部位置（相对于预览视图）
   * @param barHeight 控制条高度
   */
  fun updateControlBarPosition(viewWidth: Int, viewHeight: Int, barTop: Int, barHeight: Int) {
    blurredFrameAnalyzer.updateControlBarPosition(viewWidth, viewHeight, barTop, barHeight)
  }

  override fun onCleared() {
    super.onCleared()
    analysisExecutor.shutdown()
  }
}
