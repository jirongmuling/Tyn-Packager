package com.tyranor.next.packager.apk

/**
 * Android 包名规则校验。
 *
 * 规则与 Android 清单校验一致：
 * - 至少两段，用 `.` 分隔；
 * - 每段以字母开头，只含字母、数字、下划线；
 * - 每段不能是 Java/Kotlin 保留字（否则运行时反射会出问题）。
 */
object ApkPackageRules {

    private val REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    private val RESERVED = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
        "int", "interface", "long", "native", "new", "package", "private", "protected",
        "public", "return", "short", "static", "strictfp", "super", "switch",
        "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "null", "true", "false",
    )

    /** 校验包名是否合法。 */
    fun isValid(name: String): Boolean {
        if (!REGEX.matches(name)) return false
        val segments = name.split('.')
        // Android 要求至少两段
        if (segments.size < 2) return false
        return segments.all { seg ->
            seg.isNotEmpty() && !RESERVED.contains(seg.lowercase())
        }
    }

    /**
     * 由任意文本生成合法包名段。
     *
     * 只保留 ASCII 字母数字，其余替换为下划线，确保不出现非法字符。
     */
    fun toSegment(text: String, fallback: String = "game"): String {
        val raw = text.map { ch ->
            if (ch.code < 128 && (ch.isLetterOrDigit())) ch else '_'
        }.joinToString("")
            .trim('_')
            .replace(Regex("_+"), "_")
            .lowercase()

        val safe = raw.ifEmpty { fallback }
        return if (safe.first().isLetter()) safe else "g_$safe"
    }
}
