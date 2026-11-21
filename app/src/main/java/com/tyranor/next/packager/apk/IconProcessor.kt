package com.tyranor.next.packager.apk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** 图标尺寸规格。 */
enum class IconSize(val px: Int) {
    MDPI(48), HDPI(72), XHDPI(96), XXHDPI(144), XXXHDPI(192);

    companion object {
        /** Tyranor 图标实际使用的规格；替换时统一用这一档。 */
        val DEFAULT = XXHDPI
    }
}

/**
 * 图标处理：把用户选择的图片处理成适合内嵌到 APK 的 PNG。
 *
 * 处理要点：
 * - 居中裁剪为正方形（避免拉伸变形）；
 * - 缩放到目标尺寸；
 * - 输出 PNG 并限制体积，防止 APK 无谓膨胀。
 */
object IconProcessor {

    private const val MAX_PNG_BYTES = 1024 * 1024 // 1 MB 上限

    /**
     * 从 [uri] 读取图片并处理成指定尺寸的 PNG 字节。
     *
     * @return 处理后的 PNG；读取失败返回 null
     */
    fun loadAndResize(
        context: Context,
        uri: Uri,
        size: IconSize = IconSize.DEFAULT,
    ): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return@runCatching null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        // 先按 2 的幂降采样，避免大图直接加载 OOM
        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, size.px)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return@runCatching null

        val squared = centerSquare(decoded, size.px)
        encodePng(squared)
    }.getOrNull()

    /** 计算降采样倍数。 */
    private fun calculateSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return max(1, sample)
    }

    /** 居中裁剪成正方形并缩放。 */
    private fun centerSquare(src: Bitmap, target: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, target, target, true)
        if (cropped !== scaled && !cropped.isRecycled) cropped.recycle()
        return scaled
    }

    /**
     * 按目标密度分别生成图标字节。
     *
     * 从原始图片重新解码并按每种密度缩放，比「统一缩放后再放大」清晰得多。
     *
     * @param densities 需要生成的密度，键为 mipmap 目录名（如 mipmap-xxhdpi）
     * @return 目录名 → PNG 字节；解码失败返回空 map
     */
    fun loadPerDensity(
        context: Context,
        uri: Uri,
        densities: Map<String, Int>,
    ): Map<String, ByteArray> = runCatching {
        val out = LinkedHashMap<String, ByteArray>()
        densities.forEach { (dir, px) ->
            loadAndResize(context, uri, px.toIconSizeOrNearest())?.let { out[dir] = it }
        }
        out
    }.getOrDefault(emptyMap())

    /** 把任意像素值映射到最近的 [IconSize]。 */
    private fun Int.toIconSizeOrNearest(): IconSize =
        IconSize.entries.minByOrNull { kotlin.math.abs(it.px - this) } ?: IconSize.DEFAULT

    /** 编码为 PNG；超出体积上限时逐级降质（PNG 无损，通过降尺寸实现）。 */
    private fun encodePng(bitmap: Bitmap, attempt: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        if (bytes.size <= MAX_PNG_BYTES || attempt >= 2 || bitmap.width <= 72) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return bytes
        }
        val smaller = Bitmap.createScaledBitmap(
            bitmap,
            bitmap.width * 3 / 4,
            bitmap.height * 3 / 4,
            true,
        )
        if (!bitmap.isRecycled) bitmap.recycle()
        return encodePng(smaller, attempt + 1)
    }
}
