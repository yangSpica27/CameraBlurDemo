package me.spica.camerablur.blur

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

/**
 * 高斯模糊处理器
 *
 * 使用多种策略实现高斯模糊：
 * - Android 12+: 使用 RenderEffect
 * - 低版本: 使用 StackBlur 算法
 */
object BlurProcessor {

  /**
   * 对 Bitmap 进行高斯模糊
   *
   * @param source 源图像
   * @param radius 模糊半径 (1-25)
   * @param scale 缩放因子，用于提高性能 (0.1-1.0)
   * @param saturation 饱和度调整 (1.0 = 原始, >1.0 = 更饱和)
   * @param brightness 亮度调整 (0 = 原始, >0 = 更亮)
   * @param tintColor 混色颜色 (ARGB 格式，默认半透明白色)
   * @param tintAlpha 混色透明度 (0-255)
   * @return 模糊后的 Bitmap
   */
  fun blur(
    source: Bitmap,
    radius: Float = 25f,
    scale: Float = 0.25f,
    saturation: Float = 1.65f,  // iOS 风格略微增加饱和度
    brightness: Float = 05f,   // iOS 风格略微提亮
    tintColor: Int = 0xFFFFFF, // 浅色混色 (白色)
    tintAlpha: Int = 150        // 混色透明度 (0-255)
  ): Bitmap {
    val scaledWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
    val scaledHeight = (source.height * scale).roundToInt().coerceAtLeast(1)

    // 缩小图像以提高性能
    val scaledBitmap = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

    val blurredBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      blurWithRenderEffect(scaledBitmap, radius)
    } else {
      stackBlur(scaledBitmap, radius.roundToInt().coerceIn(1, 25))
    }

    // 应用 iOS 风格的颜色增强
    val enhancedBitmap = applyColorEnhancement(blurredBitmap, saturation, brightness)

    // 应用浅色混色效果
    val tintedBitmap = applyTintOverlay(enhancedBitmap, tintColor, tintAlpha)

    // 放大回原始尺寸
    return Bitmap.createScaledBitmap(tintedBitmap, source.width, source.height, true)
  }

  /**
   * 应用浅色混色叠加 - iOS 毛玻璃通透效果
   *
   * @param source 源图像
   * @param tintColor 混色颜色 (RGB)
   * @param alpha 混色透明度 (0-255)
   * @return 混色后的 Bitmap
   */
  private fun applyTintOverlay(
    source: Bitmap,
    tintColor: Int,
    alpha: Int
  ): Bitmap {
    val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)

    // 先绘制原图
    canvas.drawBitmap(source, 0f, 0f, null)

    // 叠加半透明浅色层
    val overlayPaint = Paint().apply {
      color = (alpha shl 24) or (tintColor and 0xFFFFFF)
      style = Paint.Style.FILL
    }
    canvas.drawRect(0f, 0f, source.width.toFloat(), source.height.toFloat(), overlayPaint)

    return result
  }

  /**
   * 应用颜色增强 - iOS 毛玻璃风格
   *
   * @param source 源图像
   * @param saturation 饱和度 (1.0 = 原始)
   * @param brightness 亮度偏移
   * @return 增强后的 Bitmap
   */
  private fun applyColorEnhancement(
    source: Bitmap,
    saturation: Float,
    brightness: Float
  ): Bitmap {
    val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 创建饱和度矩阵
    val saturationMatrix = ColorMatrix().apply {
      setSaturation(saturation)
    }

    // 创建亮度矩阵
    val brightnessMatrix = ColorMatrix(
      floatArrayOf(
        1f, 0f, 0f, 0f, brightness,
        0f, 1f, 0f, 0f, brightness,
        0f, 0f, 1f, 0f, brightness,
        0f, 0f, 0f, 1f, 0f
      )
    )

    // 合并矩阵：先饱和度，后亮度
    val combinedMatrix = ColorMatrix().apply {
      postConcat(saturationMatrix)
      postConcat(brightnessMatrix)
    }

    paint.colorFilter = ColorMatrixColorFilter(combinedMatrix)
    canvas.drawBitmap(source, 0f, 0f, paint)

    return result
  }

  /**
   * 裁切并模糊图像的指定区域
   *
   * @param source 源图像
   * @param cropRect 裁切区域（相对于源图像的比例，0-1）
   * @param radius 模糊半径
   * @param scale 缩放因子
   * @return 裁切并模糊后的 Bitmap
   */
  fun cropAndBlur(
    source: Bitmap,
    cropRect: CropRect,
    radius: Float = 5f,
    scale: Float = 0.3f
  ): Bitmap {
    val x = (source.width * cropRect.left).roundToInt().coerceIn(0, source.width - 1)
    val y = (source.height * cropRect.top).roundToInt().coerceIn(0, source.height - 1)
    val width = (source.width * cropRect.width).roundToInt().coerceIn(1, source.width - x)
    val height = (source.height * cropRect.height).roundToInt().coerceIn(1, source.height - y)

    val croppedBitmap = Bitmap.createBitmap(source, x, y, width, height)
    return blur(croppedBitmap, radius, scale)
  }

  /**
   * 使用 RenderEffect 进行模糊 (Android 12+)
   */
  @RequiresApi(Build.VERSION_CODES.S)
  private fun blurWithRenderEffect(source: Bitmap, radius: Float): Bitmap {
    val renderNode = RenderNode("blur")
    renderNode.setPosition(0, 0, source.width, source.height)

    val renderEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR)
    renderNode.setRenderEffect(renderEffect)

    val canvas = renderNode.beginRecording()
    canvas.drawBitmap(source, 0f, 0f, null)
    renderNode.endRecording()

    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val hardwareRenderer = android.graphics.HardwareRenderer()
    hardwareRenderer.setSurface(android.view.Surface(android.graphics.SurfaceTexture(0).apply {
      setDefaultBufferSize(source.width, source.height)
    }))
    hardwareRenderer.setContentRoot(renderNode)
    hardwareRenderer.createRenderRequest().syncAndDraw()

    // Fallback to stack blur for RenderEffect issues
    return stackBlur(source, radius.roundToInt().coerceIn(1, 25))
  }

  /**
   * StackBlur 算法实现
   * 基于 Mario Klingemann 的 StackBlur 算法
   */
  private fun stackBlur(source: Bitmap, radius: Int): Bitmap {
    val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    val w = bitmap.width
    val h = bitmap.height
    val pix = IntArray(w * h)
    bitmap.getPixels(pix, 0, w, 0, 0, w, h)

    val wm = w - 1
    val hm = h - 1
    val wh = w * h
    val div = radius + radius + 1

    val r = IntArray(wh)
    val g = IntArray(wh)
    val b = IntArray(wh)
    var rsum: Int
    var gsum: Int
    var bsum: Int
    var x: Int
    var y: Int
    var i: Int
    var p: Int
    var yp: Int
    var yi: Int
    var yw: Int

    val vmin = IntArray(w.coerceAtLeast(h))
    var divsum = (div + 1) shr 1
    divsum *= divsum
    val dv = IntArray(256 * divsum)
    i = 0
    while (i < 256 * divsum) {
      dv[i] = i / divsum
      i++
    }

    yi = 0
    yw = 0

    val stack = Array(div) { IntArray(3) }
    var stackpointer: Int
    var stackstart: Int
    var sir: IntArray
    var rbs: Int
    val r1 = radius + 1
    var routsum: Int
    var goutsum: Int
    var boutsum: Int
    var rinsum: Int
    var ginsum: Int
    var binsum: Int

    y = 0
    while (y < h) {
      bsum = 0
      gsum = 0
      rsum = 0
      boutsum = 0
      goutsum = 0
      routsum = 0
      binsum = 0
      ginsum = 0
      rinsum = 0
      i = -radius
      while (i <= radius) {
        p = pix[yi + (i.coerceIn(0, wm))]
        sir = stack[i + radius]
        sir[0] = (p and 0xff0000) shr 16
        sir[1] = (p and 0x00ff00) shr 8
        sir[2] = p and 0x0000ff
        rbs = r1 - kotlin.math.abs(i)
        rsum += sir[0] * rbs
        gsum += sir[1] * rbs
        bsum += sir[2] * rbs
        if (i > 0) {
          rinsum += sir[0]
          ginsum += sir[1]
          binsum += sir[2]
        } else {
          routsum += sir[0]
          goutsum += sir[1]
          boutsum += sir[2]
        }
        i++
      }
      stackpointer = radius

      x = 0
      while (x < w) {
        r[yi] = dv[rsum]
        g[yi] = dv[gsum]
        b[yi] = dv[bsum]

        rsum -= routsum
        gsum -= goutsum
        bsum -= boutsum

        stackstart = stackpointer - radius + div
        sir = stack[stackstart % div]

        routsum -= sir[0]
        goutsum -= sir[1]
        boutsum -= sir[2]

        if (y == 0) {
          vmin[x] = (x + radius + 1).coerceAtMost(wm)
        }
        p = pix[yw + vmin[x]]

        sir[0] = (p and 0xff0000) shr 16
        sir[1] = (p and 0x00ff00) shr 8
        sir[2] = p and 0x0000ff

        rinsum += sir[0]
        ginsum += sir[1]
        binsum += sir[2]

        rsum += rinsum
        gsum += ginsum
        bsum += binsum

        stackpointer = (stackpointer + 1) % div
        sir = stack[stackpointer % div]

        routsum += sir[0]
        goutsum += sir[1]
        boutsum += sir[2]

        rinsum -= sir[0]
        ginsum -= sir[1]
        binsum -= sir[2]

        yi++
        x++
      }
      yw += w
      y++
    }

    x = 0
    while (x < w) {
      bsum = 0
      gsum = 0
      rsum = 0
      boutsum = 0
      goutsum = 0
      routsum = 0
      binsum = 0
      ginsum = 0
      rinsum = 0
      yp = -radius * w
      i = -radius
      while (i <= radius) {
        yi = 0.coerceAtLeast(yp) + x
        sir = stack[i + radius]
        sir[0] = r[yi]
        sir[1] = g[yi]
        sir[2] = b[yi]
        rbs = r1 - kotlin.math.abs(i)
        rsum += r[yi] * rbs
        gsum += g[yi] * rbs
        bsum += b[yi] * rbs
        if (i > 0) {
          rinsum += sir[0]
          ginsum += sir[1]
          binsum += sir[2]
        } else {
          routsum += sir[0]
          goutsum += sir[1]
          boutsum += sir[2]
        }
        if (i < hm) {
          yp += w
        }
        i++
      }
      yi = x
      stackpointer = radius
      y = 0
      while (y < h) {
        pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
        rsum -= routsum
        gsum -= goutsum
        bsum -= boutsum
        stackstart = stackpointer - radius + div
        sir = stack[stackstart % div]
        routsum -= sir[0]
        goutsum -= sir[1]
        boutsum -= sir[2]
        if (x == 0) {
          vmin[y] = (y + r1).coerceAtMost(hm) * w
        }
        p = x + vmin[y]
        sir[0] = r[p]
        sir[1] = g[p]
        sir[2] = b[p]
        rinsum += sir[0]
        ginsum += sir[1]
        binsum += sir[2]
        rsum += rinsum
        gsum += ginsum
        bsum += binsum
        stackpointer = (stackpointer + 1) % div
        sir = stack[stackpointer]
        routsum += sir[0]
        goutsum += sir[1]
        boutsum += sir[2]
        rinsum -= sir[0]
        ginsum -= sir[1]
        binsum -= sir[2]
        yi += w
        y++
      }
      x++
    }

    bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    return bitmap
  }

  /**
   * 裁切区域定义（使用比例 0-1）
   */
  data class CropRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
  )
}
