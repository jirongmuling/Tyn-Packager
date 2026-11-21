package com.tyranor.next.packager.data

/**
 * 从 Tyranor 游戏库读到的一条游戏记录（只读）。
 *
 * 字段与 Tyranor 侧 `GameLibraryProvider` 返回的列一一对应。
 */
data class TyranorGame(
    /** 游戏目录 SAF URI（主键）。 */
    val uri: String,
    /** 游戏目录真实路径；无法换算时为 null。 */
    val path: String?,
    /** 所属扫描根 URI。 */
    val rootUri: String?,
    /** 所属扫描根真实路径。 */
    val rootPath: String?,
    /** 游戏标题。 */
    val title: String,
    /** 引擎名，如 `KIRIKIRI`。 */
    val engine: String,
    /** 引擎显示名，如 `Kirikiri`。 */
    val engineDisplay: String,
    /** 启动目标（通常为 .xp3 文件名）。 */
    val launchTarget: String,
    /** 手动指定的启动文件。 */
    val launchFile: String?,
    /** 封面 URI。 */
    val coverUri: String?,
    /** 封面来源（vndb/bangumi/local）。 */
    val coverSource: String?,
    /** 元数据标题。 */
    val metadataTitle: String?,
    val discoveredAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long,
    /** 是否手动添加（PC/安卓游戏）。 */
    val isManual: Boolean,
) {
    /**
     * 是否可用来制作直装包：当前仅 Kirikiri 引擎。
     *
     * 判定依据是引擎标识本身，而不是路径是否可读——路径可读性在真正打包时再校验。
     */
    val canBuildInstaller: Boolean
        get() = engine.equals("KIRIKIRI", ignoreCase = true) && launchTarget.isNotBlank()
}
