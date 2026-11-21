package com.tyranor.next.packager.data

import android.content.Context

/**
 * 首次启动引导状态。
 *
 * 只记录「是否看过引导」，用独立 prefs，避免与其它设置耦合。
 */
class OnboardingStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 是否已完成引导。 */
    fun isDone(): Boolean = prefs.getBoolean(KEY_DONE, false)

    /** 标记已看完引导。 */
    fun markDone() {
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }

    /** 重置（调试用）。 */
    fun reset() {
        prefs.edit().remove(KEY_DONE).apply()
    }

    companion object {
        private const val PREFS_NAME = "tyn_packager_onboarding"
        private const val KEY_DONE = "onboarding_done"
    }
}
