package me.spica.camerablur.blur

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/**
 * 相机帧分析器
 *
 * 实时捕获相机帧，进行高斯模糊处理，
 * 并裁切底部区域作为控制条背景
 */
class BlurredFrameAnalyzer(
  private val executor: Executor
) : ImageAnalysis.Analyzer {

  private val _blurredBitmap = MutableStateFlow<Bitmap?>(null)
  val blurredBitmap: StateFlow<Bitmap?> = _blurredBitmap.asStateFlow()

  // 视图尺寸（用于计算正确的裁切区域）
  @Volatile
  private var viewSize: Size = Size(1080, 1920)

  // 控制条在屏幕上的位置（像素）- 使用 volatile 保证线程安全
  @Volatile
  private var controlBarTop: Int = 0
  @Volatile
  private var controlBarHeight: Int = 0

  // 模糊参数 - 增大模糊半径以减少抖动感知
  var blurRadius: Float = 8f
  var blurScale: Float = .8f  // 缩小比例以提高处理速度

  // 帧率控制 - 降低帧率以减少抖动
  private var lastProcessTime = 0L
  private val minFrameInterval = 8L // 约 20fps，降低帧率减少抖动

  @OptIn(ExperimentalGetImage::class)
  override fun analyze(imageProxy: ImageProxy) {
    val currentTime = System.currentTimeMillis()

    // 帧率限制
    if (currentTime - lastProcessTime < minFrameInterval) {
      imageProxy.close()
      return
    }

    lastProcessTime = currentTime
    val rotationDegrees = imageProxy.imageInfo.rotationDegrees

    try {
      val bitmap = imageProxyToBitmap(imageProxy)
      if (bitmap != null) {
        // 旋转图像以匹配预览方向
        val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)

        // 根据控制条实际位置计算裁切区域
        val blurredCrop = cropAndBlurForControlBar(rotatedBitmap)

        _blurredBitmap.value = blurredCrop

        // 回收中间 bitmap
        if (rotatedBitmap != bitmap) {
          rotatedBitmap.recycle()
        }
        bitmap.recycle()
      }
    } catch (e: Exception) {
      e.printStackTrace()
    } finally {
      imageProxy.close()
    }
  }

  /**
   * 根据控制条位置裁切并模糊
   *
   * 关键：Preview 使用 FILL 模式时会居中裁切图像以填满视图
   * 我们需要计算相同的裁切逻辑来保证背景对齐
   */
  private fun cropAndBlurForControlBar(source: Bitmap): Bitmap {
    val imageWidth = source.width
    val imageHeight = source.height
    
    // 复制当前值，避免多线程问题
    val currentViewSize = viewSize
    val currentBarTop = controlBarTop
    val currentBarHeight = controlBarHeight
    
    val viewWidth = currentViewSize.width
    val viewHeight = currentViewSize.height

    if (currentBarHeight <= 0 || viewHeight <= 0) {
      // Fallback: 裁切底部区域
      return BlurProcessor.cropAndBlur(
        source = source,
        cropRect = BlurProcessor.CropRect(0f, 0.75f, 1f, 0.25f),
        radius = blurRadius,
        scale = blurScale
      )
    }

    // 计算 Preview FILL 模式下的缩放和裁切
    val imageAspect = imageWidth.toFloat() / imageHeight
    val viewAspect = viewWidth.toFloat() / viewHeight

    val srcX: Int
    val srcY: Int
    val srcWidth: Int
    val srcHeight: Int

    if (imageAspect > viewAspect) {
      // 图像更宽，水平方向会被裁切
      val scaledWidth = imageHeight * viewAspect
      srcX = ((imageWidth - scaledWidth) / 2).toInt()
      srcY = 0
      srcWidth = scaledWidth.toInt()
      srcHeight = imageHeight
    } else {
      // 图像更高，垂直方向会被裁切
      val scaledHeight = imageWidth / viewAspect
      srcX = 0
      srcY = ((imageHeight - scaledHeight) / 2).toInt()
      srcWidth = imageWidth
      srcHeight = scaledHeight.toInt()
    }

    // 计算控制条在原始图像中的对应区域
    val scaleY = srcHeight.toFloat() / viewHeight

    // 使用整数运算减少舍入抖动
    val cropX = srcX
    val cropY = srcY + (currentBarTop * scaleY).toInt()
    val cropWidth = srcWidth
    val cropHeight = (currentBarHeight * scaleY).toInt()

    // 确保裁切区域在有效范围内
    val safeX = cropX.coerceIn(0, imageWidth - 1)
    val safeY = cropY.coerceIn(0, imageHeight - 1)
    val safeWidth = cropWidth.coerceIn(1, imageWidth - safeX)
    val safeHeight = cropHeight.coerceIn(1, imageHeight - safeY)

    return try {
      val cropped = Bitmap.createBitmap(source, safeX, safeY, safeWidth, safeHeight)
      BlurProcessor.blur(cropped, blurRadius, blurScale)
    } catch (e: Exception) {
      // Fallback
      BlurProcessor.cropAndBlur(
        source = source,
        cropRect = BlurProcessor.CropRect(0f, 0.75f, 1f, 0.25f),
        radius = blurRadius,
        scale = blurScale
      )
    }
  }

  /**
   * 将 ImageProxy 转换为 Bitmap
   */
  private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    val image = imageProxy.image ?: return null

    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
    val imageBytes = out.toByteArray()

    return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
  }

  /**
   * 旋转 Bitmap
   */
  private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap

    val matrix = Matrix().apply {
      postRotate(degrees.toFloat())
    }

    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  }

  /**
   * 更新控制条位置信息
   * @param viewWidth 预览视图宽度
   * @param viewHeight 预览视图高度
   * @param barTop 控制条顶部位置（相对于预览视图）
   * @param barHeight 控制条高度
   */
  fun updateControlBarPosition(viewWidth: Int, viewHeight: Int, barTop: Int, barHeight: Int) {
    viewSize = Size(viewWidth, viewHeight)
    controlBarTop = barTop
    controlBarHeight = barHeight
  }
}
