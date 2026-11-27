package com.shua.ti.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ParsedQuestion(
    val stem: String,
    val optionA: String,
    val optionB: String,
    val optionC: String,
    val optionD: String,
    val answer: String,
    val position: Int,
)

data class ParsedBank(
    val schemaVersion: Int,
    val questions: List<ParsedQuestion>,
)

sealed interface ParseOutcome {
    data class Ok(val bank: ParsedBank) : ParseOutcome
    data class Err(
        val code: ImportErrorCode,
        val message: String,
        val location: String? = null,
    ) : ParseOutcome
}

/**
 * 纯解析与校验，不碰数据库，便于 JVM 单测。
 *
 * 支持两种顶层形状：
 * 1. `[ {...}, {...} ]`
 * 2. `{ "schema_version": 1, "questions": [ ... ] }`
 *
 * 每题字段：stem / option_a / option_b / option_c / option_d / answer。
 * answer 只接受 A/B/C/D（大小写不敏感，统一转大写）。
 *
 * 任一行不合法即整份拒绝，不做部分导入——对应 REQ-001 的「不写入任何题目」。
 */
object QuestionBankParser {

    private const val DEFAULT_SCHEMA_VERSION = 1
    private val VALID_ANSWERS = setOf("A", "B", "C", "D")

    fun parse(content: String): ParseOutcome {
        val root: JsonElement = try {
            Json.parseToJsonElement(content)
        } catch (e: Exception) {
            return ParseOutcome.Err(
                ImportErrorCode.INVALID_JSON,
                "文件不是合法 JSON：${e.message ?: "解析失败"}",
            )
        }

        val schemaVersion: Int
        val questionsNode: JsonArray
        when (root) {
            is JsonArray -> {
                schemaVersion = DEFAULT_SCHEMA_VERSION
                questionsNode = root
            }
            is JsonObject -> {
                schemaVersion = (root["schema_version"] as? JsonPrimitive)
                    ?.content?.toIntOrNull() ?: DEFAULT_SCHEMA_VERSION
                questionsNode = root["questions"] as? JsonArray
                    ?: return ParseOutcome.Err(
                        ImportErrorCode.MISSING_FIELD,
                        "缺少 questions 数组",
                    )
            }
            else -> return ParseOutcome.Err(
                ImportErrorCode.MISSING_FIELD,
                "顶层必须是题目数组或包含 questions 的对象",
            )
        }

        if (questionsNode.isEmpty()) {
            return ParseOutcome.Err(
                ImportErrorCode.MISSING_FIELD,
                "questions 为空，没有可导入的题目",
            )
        }

        val parsed = ArrayList<ParsedQuestion>(questionsNode.size)
        questionsNode.forEachIndexed { index, element ->
            val no = index + 1
            val obj = element as? JsonObject
                ?: return ParseOutcome.Err(
                    ImportErrorCode.MISSING_FIELD,
                    "第 $no 题不是对象",
                    "第 $no 题",
                )

            val stem = obj.requiredText("stem") ?: return missing("stem", no)
            val optionA = obj.requiredText("option_a") ?: return missing("option_a", no)
            val optionB = obj.requiredText("option_b") ?: return missing("option_b", no)
            val optionC = obj.requiredText("option_c") ?: return missing("option_c", no)
            val optionD = obj.requiredText("option_d") ?: return missing("option_d", no)
            val rawAnswer = obj.requiredText("answer") ?: return missing("answer", no)

            val answer = rawAnswer.trim().uppercase()
            if (answer !in VALID_ANSWERS) {
                return ParseOutcome.Err(
                    ImportErrorCode.INVALID_ANSWER,
                    "第 $no 题答案取值为「$rawAnswer」，只允许 A/B/C/D",
                    "第 $no 题 · answer",
                )
            }

            parsed += ParsedQuestion(
                stem = stem,
                optionA = optionA,
                optionB = optionB,
                optionC = optionC,
                optionD = optionD,
                answer = answer,
                position = no,
            )
        }

        return ParseOutcome.Ok(ParsedBank(schemaVersion, parsed))
    }

    private fun missing(field: String, no: Int): ParseOutcome.Err =
        ParseOutcome.Err(
            ImportErrorCode.MISSING_FIELD,
            "第 $no 题缺少 $field",
            "第 $no 题 · $field",
        )

    private fun JsonObject.requiredText(key: String): String? {
        val value = (this[key] as? JsonPrimitive)?.content?.trim()
        return value?.takeIf { it.isNotEmpty() }
    }
}
