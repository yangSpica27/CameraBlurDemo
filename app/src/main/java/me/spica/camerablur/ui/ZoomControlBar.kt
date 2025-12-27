package me.spica.camerablur.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 带高斯模糊背景的相机预览容器
 *
 * 通过在底部区域应用 RenderEffect 实现对相机预览的模糊效果
 * 需要 Android 12 (API 31) 及以上版本才支持真实模糊效果
 *
 * @param modifier Modifier
 * @param blurRadius 模糊半径
 * @param blurAreaHeight 模糊区域高度
 * @param content 内容（相机预览）
 * @param overlayContent 覆盖内容（控制条）
 */
@Composable
fun BlurredCameraContainer(
  modifier: Modifier = Modifier,
  blurRadius: Dp = 25.dp,
  blurAreaHeight: Dp = 160.dp,
  content: @Composable () -> Unit,
  overlayContent: @Composable () -> Unit
) {
  Box(modifier = modifier.fillMaxSize()) {
    // 相机预览内容
    content()

    // 底部模糊区域 + 覆盖内容
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(blurAreaHeight)
        .align(Alignment.BottomCenter)
    ) {
      // 模糊层 - 仅在 Android 12+ 上生效
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BlurredBackground(
          blurRadius = blurRadius.value,
          modifier = Modifier.matchParentSize()
        )
      }

      // 半透明遮罩层（作为 fallback 和额外的视觉效果）
      Box(
        modifier = Modifier
          .matchParentSize()
          .background(Color.Black.copy(alpha = 0.25f))
      )

      // 控制条内容
      overlayContent()
    }
  }
}

/**
 * 模糊背景层 (Android 12+)
 */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun BlurredBackground(
  blurRadius: Float,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .graphicsLayer {
        renderEffect = RenderEffect
          .createBlurEffect(blurRadius, blurRadius, Shader.TileMode.MIRROR)
          .asComposeRenderEffect()
      }
      .drawWithContent {
        // 通过绘制空内容，让 RenderEffect 作用于下层
        drawContent()
      }
  )
}

/**
 * 变焦控制条组件 - 使用实时模糊的相机帧作为背景
 *
 * @param zoomRatio 当前变焦倍率
 * @param minZoomRatio 最小变焦倍率
 * @param maxZoomRatio 最大变焦倍率
 * @param onZoomChange 变焦倍率变化回调
 * @param blurredBackground 模糊后的背景 Bitmap
 * @param modifier Modifier
 */
@Composable
fun ZoomControlBar(
  zoomRatio: Float,
  minZoomRatio: Float,
  maxZoomRatio: Float,
  onZoomChange: (Float) -> Unit,
  blurredBackground: android.graphics.Bitmap? = null,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp)
      .padding(bottom = 24.dp)
      .navigationBarsPadding()
  ) {
    // 模糊背景层 - 使用实时处理的相机帧
    Box(
      modifier = Modifier
        .matchParentSize()
        .clip(RoundedCornerShape(24.dp))
    ) {
      if (blurredBackground != null) {
        // 使用模糊后的相机帧作为背景
        Image(
          bitmap = blurredBackground.asImageBitmap(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize()
        )
      }

      // 半透明覆盖层增强可读性
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.25f))
      )
    }

    // 控制内容
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // 变焦倍率显示
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "变焦",
          color = Color.White,
          fontSize = 14.sp,
          fontWeight = FontWeight.Medium
        )
        Text(
          text = String.format("%.1fx", zoomRatio),
          color = Color.White,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold
        )
      }

      // 变焦滑块
      Slider(
        value = zoomRatio,
        onValueChange = onZoomChange,
        valueRange = minZoomRatio..maxZoomRatio,
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 8.dp),
        colors = SliderDefaults.colors(
          thumbColor = Color.White,
          activeTrackColor = Color.White,
          inactiveTrackColor = Color.White.copy(alpha = 0.3f)
        )
      )

      // 倍率刻度
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = String.format("%.1fx", minZoomRatio),
          color = Color.White.copy(alpha = 0.7f),
          fontSize = 12.sp
        )
        Text(
          text = String.format("%.1fx", maxZoomRatio),
          color = Color.White.copy(alpha = 0.7f),
          fontSize = 12.sp
        )
      }
    }
  }
}
