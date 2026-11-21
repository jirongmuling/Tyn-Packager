package com.tyranor.next.packager

import android.app.Application
import com.tyranor.next.packager.data.TyranorLibraryClient

class PackagerApp : Application() {

    val libraryClient: TyranorLibraryClient by lazy { TyranorLibraryClient(this) }

    /** 打包工作目录（输出 APK、临时解包文件都放这里）。 */
    val workDir: java.io.File by lazy {
        java.io.File(cacheDir, "packager").apply { mkdirs() }
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
