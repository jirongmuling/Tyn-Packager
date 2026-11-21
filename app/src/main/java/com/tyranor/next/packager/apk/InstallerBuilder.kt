package com.tyranor.next.packager.apk

import com.tyranor.next.packager.sign.ApkSignerWrapper
import com.tyranor.next.packager.sign.SignResult
import com.tyranor.next.packager.sign.SigningKey
import java.io.File

/** 打包参数。 */
data class BuildRequest(
    /** 源 APK（Tyranor 本体）。 */
    val sourceApk: File,
    /** 游戏所在目录（真实路径）。 */
    val gameDir: File,
    /** 输出 APK。 */
    val outputApk: File,
    /** 新包名。 */
    val packageName: String,
    /** 应用显示名。 */
    val appLabel: String,
    /** 自定义封面 PNG 字节；null 表示不换图标（与 [iconUri] 二选一，优先后者）。 */
    val iconPng: ByteArray? = null,
    /**
     * 自定义封面原图 URI。
     *
     * 提供时按 APK 内各密度目录分别生成对应尺寸，比统一缩放清晰。
     * 需要 Context 解码，故由调用方传入已处理好的多尺寸 map 时用 [iconByDensity]。
     */
    val iconByDensity: Map<String, ByteArray>? = null,
    /** 游戏资源在 APK 内的存放前缀。 */
    val assetPrefix: String = DEFAULT_ASSET_PREFIX,
    /** 启动目标文件（如 data.xp3），写入元数据供运行时使用。 */
    val launchTarget: String = "",
    /** 是否排除备份/机翻补丁等无关文件（默认排除，见 [AssetFilter]）。 */
    val excludeBackups: Boolean = true,
    /** 签名密钥。 */
    val signingKey: SigningKey,
) {
    companion object {
        const val DEFAULT_ASSET_PREFIX = "assets/game"
    }
}

/** 打包阶段，用于 UI 展示进度。 */
enum class BuildStage(val display: String) {
    PREPARING("准备中"),
    REWRITING("改造 APK"),
    EMBEDDING("内置游戏资源"),
    SIGNING("签名"),
    DONE("完成"),
}

/** 打包结果。 */
sealed interface BuildResult {
    data class Success(
        val apk: File,
        val embeddedFiles: Int,
        /** 使用的签名方案，如 ["v2","v3"]。 */
        val signingSchemes: List<String> = emptyList(),
    ) : BuildResult
    data class Failure(val stage: BuildStage, val message: String, val cause: Throwable? = null) : BuildResult
}

/** 密度目录 → 建议像素。 */
private val DENSITY_PIXELS = mapOf(
    "mipmap-mdpi" to 48,
    "mipmap-hdpi" to 72,
    "mipmap-xhdpi" to 96,
    "mipmap-xxhdpi" to 144,
    "mipmap-xxxhdpi" to 192,
)

/** 从多密度结果中挑选最接近目标像素的一张。 */
private fun nearestDensityBytes(byDensity: Map<String, ByteArray>, targetPx: Int): ByteArray? {
    val candidates = byDensity.mapNotNull { (dir, bytes) ->
        val px = DENSITY_PIXELS.entries.firstOrNull { dir.contains(it.key) }?.value
            ?: return@mapNotNull null
        px to bytes
    }
    return candidates.minByOrNull { kotlin.math.abs(it.first - targetPx) }?.second
        ?: byDensity.values.maxByOrNull { it.size }
}

/**
 * 直装包构建编排。
 *
 * 流程：读取源 APK → 改包名/名称/图标 → 内置游戏资源 → 输出未签名 APK → 签名。
 *
 * 说明：
 * - 采用**原地改包**而非 aapt 反编译重打包，最大限度保留引擎 native 库；
 * - 游戏资源以原始文件形式写入 APK 的 `assets/game/` 下，由运行时按需解出。
 */
class InstallerBuilder(
    private val onStage: (BuildStage) -> Unit = {},
    private val onEmbedProgress: (Int, String) -> Unit = { _, _ -> },
) {

    fun build(request: BuildRequest): BuildResult {
        // 用不可变局部引用，避免 var 导致智能转换失效（进而所有成员调用报未解析）
        var closable: ApkRewriter? = null
        var unsigned: File? = null

        return try {
            onStage(BuildStage.PREPARING)
            require(request.sourceApk.isFile) { "源 APK 不存在：${request.sourceApk}" }
            require(request.gameDir.isDirectory) { "游戏目录不存在：${request.gameDir}" }
            val dirCheck = GameDirInspector.inspect(request.gameDir)
            require(dirCheck.valid) { dirCheck.message }
            require(ApkPackageRules.isValid(request.packageName)) {
                "包名非法：${request.packageName}"
            }

            onStage(BuildStage.REWRITING)
            val rewriter = ApkRewriter(request.sourceApk)
            closable = rewriter

            rewriter.setPackageName(request.packageName)
            request.appLabel.takeIf { it.isNotBlank() }?.let { rewriter.setAppLabel(it) }
            when {
                !request.iconByDensity.isNullOrEmpty() -> {
                    // 多密度：按每个条目所在目录分别写入对应尺寸
                    val entries = rewriter.launcherIconEntries()
                    if (entries.isEmpty()) {
                        request.iconByDensity.values.firstOrNull()
                            ?.let { rewriter.replaceLauncherIcon(it) }
                    } else {
                        val byEntry = LinkedHashMap<String, ByteArray>()
                        entries.forEach { entry ->
                            val px = rewriter.densityPixelsOf(entry)
                            val bytes = if (px == null) {
                                // 未知目录：取最大尺寸那张
                                request.iconByDensity.values.maxByOrNull { it.size }
                            } else {
                                nearestDensityBytes(request.iconByDensity, px)
                            }
                            if (bytes != null) byEntry[entry] = bytes
                        }
                        rewriter.replaceLauncherIconsByEntry(byEntry)
                    }
                }
                request.iconPng != null -> rewriter.replaceLauncherIcon(request.iconPng)
            }

            onStage(BuildStage.EMBEDDING)
            rewriter.removeDirectory(request.assetPrefix)
            val embedded = rewriter.putDirectory(
                prefix = request.assetPrefix,
                dir = request.gameDir,
                onProgress = { n, name -> onEmbedProgress(n, name) },
                filter = { rel, file -> !request.excludeBackups || !AssetFilter.shouldExclude(rel, file) },
            )

            // 写入直装包元数据，供运行时识别游戏信息
            rewriter.putFile(
                InstallerMetadata.ASSET_PATH,
                InstallerMetadata(
                    title = request.appLabel,
                    engine = "KIRIKIRI",
                    launchTarget = request.launchTarget,
                    packageName = request.packageName,
                    assetPrefix = request.assetPrefix,
                    builtAt = System.currentTimeMillis(),
                    originalPath = request.gameDir.absolutePath,
                    embeddedFiles = embedded,
                ).toJsonBytes(),
            )

            // 校验改造结果：包名必须已生效，否则产物会与他人包冲突
            val actualPackage = rewriter.originalPackageName()
            require(actualPackage == request.packageName) {
                "包名改写失败：期望 ${request.packageName}，实际 $actualPackage"
            }

            val tmp = File(request.outputApk.parentFile, "unsigned.tmp.apk")
            unsigned = tmp
            rewriter.writeTo(tmp)

            onStage(BuildStage.SIGNING)
            when (val signed = ApkSignerWrapper.sign(tmp, request.outputApk, request.signingKey)) {
                is SignResult.Success -> {
                    onStage(BuildStage.DONE)
                    BuildResult.Success(signed.output, embedded, signed.schemes)
                }
                is SignResult.Failure -> BuildResult.Failure(
                    BuildStage.SIGNING, signed.error, signed.cause,
                )
            }
        } catch (e: Exception) {
            // 失败时清理半成品，避免用户拿到损坏的 APK
            runCatching { request.outputApk.delete() }
            BuildResult.Failure(BuildStage.DONE, e.message ?: e.javaClass.simpleName, e)
        } finally {
            runCatching { closable?.close() }
            runCatching { unsigned?.delete() }
        }
    }
}
