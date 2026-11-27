package com.shua.ti.domain

/**
 * 导入的错误码，与 IF-001 的 error_case 一一对应。
 * 界面按 code 展示提示，不允许静默吞掉。
 */
enum class ImportErrorCode {
    INVALID_JSON,
    MISSING_FIELD,
    INVALID_ANSWER,
    DUPLICATE_IMPORT,
    FILE_UNREADABLE,
    REPEATED_TRIGGER,
}

sealed interface ImportResult {

    data class Success(
        val bankId: Long,
        val importedCount: Int,
        val questionIds: List<Long>,
    ) : ImportResult

    /**
     * @param location 形如「第 3 题 · option_c」，能定位到具体字段；
     *                 整份文件级错误时为 null。
     */
    data class Failure(
        val code: ImportErrorCode,
        val message: String,
        val location: String? = null,
    ) : ImportResult
}
