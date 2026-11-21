package com.tyranor.next.packager.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条打包历史记录。 */
data class BuildHistoryEntry(
    /** 输出 APK 文件名。 */
    val fileName: String,
    val packageName: String,
    val appLabel: String,
    /** 来源游戏标题。 */
    val gameTitle: String,
    /** 生成时间（毫秒）。 */
    val createdAt: Long,
    val sizeBytes: Long,
    /** 是否使用自定义签名。 */
    val customSigned: Boolean,
) {
    /** APK 的实际路径。 */
    fun apkFile(context: Context): File =
        File(File(context.filesDir, "packager/output"), fileName)

    /** 文件是否仍然存在。 */
    fun exists(context: Context): Boolean = apkFile(context).isFile
}

/**
 * 打包历史记录（本地持久化）。
 *
 * 用单个 JSON 文件保存，避免为此引入数据库。
 * 只记录成功生成且未被删除的产物。
 */
class BuildHistoryStore(private val context: Context) {

    private val file: File
        get() = File(File(context.filesDir, "packager"), "history.json")

    /** 读取全部记录（新的在前）。 */
    fun load(): List<BuildHistoryEntry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(
                        BuildHistoryEntry(
                            fileName = o.optString("fileName"),
                            packageName = o.optString("packageName"),
                            appLabel = o.optString("appLabel"),
                            gameTitle = o.optString("gameTitle"),
                            createdAt = o.optLong("createdAt"),
                            sizeBytes = o.optLong("sizeBytes"),
                            customSigned = o.optBoolean("customSigned"),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    /** 追加一条记录，保留最近 [MAX_ENTRIES] 条。 */
    fun append(entry: BuildHistoryEntry) {
        val current = load().toMutableList()
        current.removeAll { it.fileName == entry.fileName }
        current.add(0, entry)
        save(current.take(MAX_ENTRIES))
    }

    /** 删除一条记录（不删文件）。 */
    fun remove(fileName: String) {
        save(load().filterNot { it.fileName == fileName })
    }

    /** 删除记录并移除对应 APK 文件。 */
    fun removeWithFile(fileName: String) {
        runCatching {
            File(File(context.filesDir, "packager/output"), fileName).delete()
        }
        remove(fileName)
    }

    /** 清理文件已不存在的记录。 */
    fun pruneMissing(): List<BuildHistoryEntry> {
        val all = load()
        val alive = all.filter { File(File(context.filesDir, "packager/output"), it.fileName).isFile }
        if (alive.size != all.size) save(alive)
        return alive
    }

    private fun save(entries: List<BuildHistoryEntry>) {
        runCatching {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            entries.forEach { e ->
                array.put(
                    JSONObject().apply {
                        put("fileName", e.fileName)
                        put("packageName", e.packageName)
                        put("appLabel", e.appLabel)
                        put("gameTitle", e.gameTitle)
                        put("createdAt", e.createdAt)
                        put("sizeBytes", e.sizeBytes)
                        put("customSigned", e.customSigned)
                    },
                )
            }
            file.writeText(array.toString())
        }
    }

    companion object {
        private const val MAX_ENTRIES = 100
    }
}
