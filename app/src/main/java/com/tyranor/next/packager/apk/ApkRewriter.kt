package com.tyranor.next.packager.apk

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
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
        require(isValidPackageName(newPackage)) { "包名格式非法：$newPackage" }
        manifest.setPackageName(newPackage)
    }

    /**
     * 修改应用显示名。
     *
     * 把 `<application android:label>` 改写为字面量字符串，
     * 覆盖原有的 `@string/...` 引用，因此无需改资源表。
     */
    fun setAppLabel(label: String) {
        val app = manifest.getOrCreateApplicationElement()
        val attr = app.getOrCreateAndroidAttribute(
            AndroidManifestBlock.NAME_label,
            AndroidManifestBlock.ID_label,
        )
        val stringItem = manifest.stringPool.getOrCreate(label)
        attr.setValueStringReference(stringItem.index)
    }

    /**
     * 替换启动器图标。
     *
     * 直接覆盖 `res/**` 下与原图标同名的条目，不新增资源 id，
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

    /** 把目录下所有文件递归写入 APK 的指定前缀路径（如 `assets/game/`）。 */
    fun putDirectory(prefix: String, dir: File): Int {
        if (!dir.isDirectory) return 0
        val normalizedPrefix = prefix.trimEnd('/') + "/"
        var count = 0
        dir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                putFile(normalizedPrefix + rel, file.readBytes())
                count++
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
        /** 与 Android 一致的包名规则（至少两段）。 */
        private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

        fun isValidPackageName(name: String): Boolean =
            PACKAGE_REGEX.matches(name) &&
                name.split('.').all { it.isNotEmpty() && !RESERVED.contains(it.lowercase()) }

        /** Java/Kotlin 保留字不能做包名段。 */
        private val RESERVED = setOf(
            "abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
            "int", "interface", "long", "native", "new", "package", "private", "protected",
            "public", "return", "short", "static", "strictfp", "super", "switch",
            "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "null", "true", "false",
        )

        private const val ICON_FALLBACK_PATH = "res/mipmap-xxhdpi/ic_launcher.png"

        /** 是否可能是启动器图标资源。 */
        private fun isLauncherIconPath(path: String): Boolean {
            if (!path.startsWith("res/")) return false
            if (!path.endsWith(".png") && !path.endsWith(".webp")) return false
            val name = path.substringAfterLast('/')
            return name.startsWith("ic_launcher") ||
                name.startsWith("launcher") ||
                name == "icon.png"
        }
    }
}
