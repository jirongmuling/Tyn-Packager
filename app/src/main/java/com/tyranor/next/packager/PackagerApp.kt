package com.tyranor.next.packager

import android.app.Application
import com.tyranor.next.packager.data.TyranorLibraryClient
import java.io.File

class PackagerApp : Application() {

    val libraryClient: TyranorLibraryClient by lazy { TyranorLibraryClient(this) }

    /**
     * 打包工作目录（临时解包、输出 APK）。
     *
     * 放在 `filesDir` 而非缓存目录：输出产物不应被系统随时清理。
     * 临时文件由打包流程自行清理。
     */
    val workDir: File by lazy {
        File(filesDir, "packager").apply { mkdirs() }
    }

    /** 输出 APK 目录。 */
    val outputDir: File by lazy {
        File(workDir, "output").apply { mkdirs() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: PackagerApp
            private set
    }
}
