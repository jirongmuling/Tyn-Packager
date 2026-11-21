package com.tyranor.next.packager.apk

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.FileInputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.zip.ZipEntry

/**
 * Tyranor APK 改造引擎。
 *
 * 直接在原有 APK 上**原地修改**（不经过 aapt 反编译），从而：
 * - 保留全部 native 库与 dex，不会破坏引擎运行时；
 * - 只改动包名、图标、内置游戏资源等必要项；
 * - 输出后交给 apksig 重新签名。
 *
 * 所有操作通过 ARSCLib 直接读写二进制 `AndroidManifest.xml` 与 `resources.arsc`。
 */
class ApkRewriter(private val sourceApk: File) : java.io.Closeable {

    private val module: ApkModule = ApkModule.loadApkFile(sourceApk)

    private val manifest: AndroidManifestBlock
        get() = requireNotNull(module.androidManifestBlock) {
            "源 APK 缺少 AndroidManifest.xml"
        }

    /** 原 APK 的包名。 */
    fun originalPackageName(): String? = runCatching { manifest.packageName }.getOrNull()

    /** 原 APK 的版本名。 */
    fun originalVersionName(): String? = runCatching { manifest.versionName }.getOrNull()

    /** 原 APK 的 minSdkVersion；签名方案选择依赖该值。 */
    fun minSdkVersion(): Int? = runCatching { manifest.minSdkVersion }.getOrNull()

    /** 原 APK 的 targetSdkVersion。 */
    fun targetSdkVersion(): Int? = runCatching { manifest.targetSdkVersion }.getOrNull()

    /**
     * 修改包名。
     *
     * 只改写清单的 `package` 属性；Android 会据此推导 applicationId，
     * 无需另行修改其它位置。
     */
    fun setPackageName(newPackage: String) {
        require(ApkPackageRules.isValid(newPackage)) { "包名格式非法：$newPackage" }
        manifest.setPackageName(newPackage)
    }

    /**
     * 修改应用显示名。
     *
     * 把 `<application android:label>` 改写为字面量字符串，
     * 覆盖原有的 `@string/...` 引用，因此无需改资源表。
     */
    fun setAppLabel(label: String) {
        // AndroidManifestBlock.setApplicationLabel(String) 是 ARSCLib 的公开 API，
        // 内部会正确处理 @string/ 引用与字符串池，无需手工构造属性。
        manifest.setApplicationLabel(label)
    }

    /**
     * 替换启动器图标。
     *
     * 直接覆盖 res 目录下与原图标同名的条目，不新增资源 id，
     * 因而不破坏资源表引用。
     *
     * @param iconPng 已按目标尺寸处理好的 PNG 字节
     */
    fun replaceLauncherIcon(iconPng: ByteArray) {
        val targets = module.zipEntryMap.listInputSources()
            .map { it.name }
            .filter { isLauncherIconPath(it) }

        if (targets.isEmpty()) {
            putFile(ICON_FALLBACK_PATH, iconPng)
        } else {
            // 所有密度目录统一写入同一张图：由系统按需缩放。
            // 不做分密度重采样，是为了避免依赖 Bitmap，让该层保持纯 IO。
            targets.forEach { putFile(it, iconPng) }
        }
    }

    /** 当前 APK 中所有可能的启动器图标条目。 */
    fun launcherIconEntries(): List<String> =
        module.zipEntryMap.listInputSources()
            .map { it.name }
            .filter { isLauncherIconPath(it) }

    /**
     * 按条目分别写入图标（用于多密度）。
     *
     * @param byEntry 条目路径 → PNG 字节；未覆盖的条目保持原样
     */
    fun replaceLauncherIconsByEntry(byEntry: Map<String, ByteArray>) {
        byEntry.forEach { (path, bytes) -> putFile(path, bytes) }
    }

    /**
     * 推断条目所属密度目录对应的建议像素。
     *
     * 仅识别常见 mipmap 目录名；无法识别时返回 null。
     */
    fun densityPixelsOf(entry: String): Int? {
        val dir = entry.substringBeforeLast('/', "")
        return when {
            dir.contains("mipmap-mdpi") -> 48
            dir.contains("mipmap-hdpi") -> 72
            dir.contains("mipmap-xhdpi") -> 96
            dir.contains("mipmap-xxhdpi") -> 144
            dir.contains("mipmap-xxxhdpi") -> 192
            else -> null
        }
    }

    /**
     * 写入一个文件（覆盖同名条目）。
     *
     * 先显式 remove 再 add：依赖 add 的覆盖语义虽然可行，
     * 但显式删除能让行为确定，也避免依赖库实现细节。
     */
    fun putFile(path: String, bytes: ByteArray) {
        val map = module.zipEntryMap
        runCatching { map.remove(path) }
        map.add(ByteInputSource(bytes, path).apply { setMethod(ZipEntry.DEFLATED) })
    }

    /**
     * 把目录下所有文件递归写入 APK 的指定前缀路径（如 `assets/game/`）。
     *
     * **使用 [FileInputSource] 流式写入**，不把文件读进内存：
     * Kirikiri 游戏的 xp3 动辄数百 MB 乃至 GB，一次性 readBytes 必然 OOM。
     *
     * @param onProgress 每写入一个文件回调一次（已写入数, 当前文件名）
     */
    fun putDirectory(
        prefix: String,
        dir: File,
        onProgress: ((Int, String) -> Unit)? = null,
        filter: ((relativePath: String, file: File) -> Boolean)? = null,
    ): Int {
        if (!dir.isDirectory) return 0
        val normalizedPrefix = prefix.trimEnd('/') + "/"
        var count = 0
        dir.walkTopDown()
            .onEnter { it != dir && (filter == null || filter(it.name + "/", it)) }
            .filter { it.isFile }
            .filter { file ->
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                filter?.invoke(rel, file) ?: true
            }
            .forEach { file ->
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                val entryName = normalizedPrefix + rel
                val map = module.zipEntryMap
                runCatching { map.remove(entryName) }
                map.add(
                    FileInputSource(file, entryName).apply {
                        // 已压缩格式（xp3/zip/png 等）再 deflate 几乎无收益，还拖慢打包：
                        // 直接 STORED。其余文件用 DEFLATED。
                        setMethod(if (isPrecompressed(rel)) ZipEntry.STORED else ZipEntry.DEFLATED)
                    },
                )
                count++
                onProgress?.invoke(count, rel)
            }
        return count
    }

    /** 删除指定前缀下的所有条目。 */
    fun removeDirectory(prefix: String) {
        val normalizedPrefix = prefix.trimEnd('/') + "/"
        val map = module.zipEntryMap
        map.listInputSources()
            .map { it.name }
            .filter { it.startsWith(normalizedPrefix) }
            .forEach { map.remove(it) }
    }

    /** 输出为新的 APK（未签名）。 */
    fun writeTo(output: File) {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        module.writeApk(output)
    }

    /** 释放底层资源。 */
    override fun close() {
        runCatching { module.close() }
    }

    companion object {
        private const val ICON_FALLBACK_PATH = "res/mipmap-xxhdpi/ic_launcher.png"

        /**
         * 是否属于「已压缩」格式。
         *
         * 这类文件再次 deflate 收益极低（往往 <1%），却要付出完整压缩耗时，
         * 大游戏目录下差异可达数十秒。用 STORED 直接存储更划算。
         */
        internal fun isPrecompressed(name: String): Boolean {
            val lower = name.lowercase()
            return PRECOMPRESSED_EXT.any { lower.endsWith(it) }
        }

        private val PRECOMPRESSED_EXT = listOf(
            ".xp3", ".zip", ".7z", ".rar", ".gz", ".xz", ".bz2",
            ".png", ".jpg", ".jpeg", ".webp", ".gif",
            ".mp3", ".ogg", ".m4a", ".opus", ".flac",
            ".mp4", ".webm", ".mkv",
        )

        /** 是否可能是启动器图标资源。 */
        internal fun isLauncherIconPath(path: String): Boolean {
            if (!path.startsWith("res/")) return false
            if (!path.endsWith(".png") && !path.endsWith(".webp")) return false
            val name = path.substringAfterLast('/')
            return name.startsWith("ic_launcher") ||
                name.startsWith("launcher") ||
                name == "icon.png"
        }
    }
}
