package com.shua.ti.domain

/**
 * 题库命名的纯规则，便于单测。
 *
 * 重名时在扩展名之前插入序号：`数学.json` → `数学 (1).json`；
 * 已经是 `数学 (1).json` 时继续递增到 `数学 (2).json`，
 * 不会叠成 `数学 (1) (1).json`。
 */
object BankNames {

    private val SEQUENCE = Regex("""^(.*) \((\d+)\)$""")

    /** 扩展名以字母开头，避免把 `1.1` 的 `.1` 当成后缀。 */
    private val EXTENSION = Regex("""^[A-Za-z][A-Za-z0-9]{0,9}$""")

    private fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return name to ""
        val ext = name.substring(dot + 1)
        return if (EXTENSION.matches(ext)) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    /**
     * 用户输入不带扩展名时，沿用原名里的扩展名，避免重命名后看不出文件类型。
     */
    fun withOriginalExtension(desired: String, originalName: String): String {
        val trimmed = desired.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.contains('.')) return trimmed
        val (_, ext) = splitExtension(originalName)
        return if (ext.isEmpty()) trimmed else trimmed + ext
    }

    /**
     * 返回一个未被占用的名字。existing 不应包含被重命名的那条自身。
     */
    fun unique(desired: String, existing: Set<String>): String {
        if (desired !in existing) return desired

        val (base, ext) = splitExtension(desired)

        val matched = SEQUENCE.matchEntire(base)
        val root = matched?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: base
        var index = (matched?.groupValues?.get(2)?.toIntOrNull() ?: 0) + 1

        while (true) {
            val candidate = "$root ($index)$ext"
            if (candidate !in existing) return candidate
            index++
        }
    }
}
