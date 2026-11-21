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
class ApkRewriter(private val sourceApk: File) {

    private val module: ApkModule = ApkModule.loadApkFile(sourceApk)

    private val manifest: AndroidManifestBlock
        get() = requireNotNull(module.androidManifestBlock) {
            "源 APK 缺少 AndroidManifest.xml"
        }

    /** 原 APK 的包名。 */
    fun originalPackageName(): String? = runCatching { manifest.packageName }.getOrNull()

    /** 原 APK 的版本名。 */
    fun originalVersionName(): String? = runCatching { manifest.versionName }.getOrNull()

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
        val names = module.zipEntryMap.listInputSources().map { it.name }
        val targets = names.filter { isLauncherIconPath(it) }
        if (targets.isEmpty()) {
            putFile(ICON_FALLBACK_PATH, iconPng)
        } else {
            targets.forEach { putFile(it, iconPng) }
        }
    }

    /** 写入一个文件（覆盖同名条目）。 */
    fun putFile(path: String, bytes: ByteArray) {
        module.zipEntryMap.add(
            ByteInputSource(bytes, path).apply { setMethod(ZipEntry.DEFLATED) },
        )
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
    ): Int {
        if (!dir.isDirectory) return 0
        val normalizedPrefix = prefix.trimEnd('/') + "/"
        var count = 0
        dir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                val entryName = normalizedPrefix + rel
                module.zipEntryMap.add(
                    FileInputSource(file, entryName).apply { setMethod(ZipEntry.DEFLATED) },
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
    fun close() {
        runCatching { module.close() }
    }

    companion object {
        private const val ICON_FALLBACK_PATH = "res/mipmap-xxhdpi/ic_launcher.png"

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
