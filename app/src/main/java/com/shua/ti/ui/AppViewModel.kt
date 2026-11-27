package com.shua.ti.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shua.ti.data.AppDatabase
import com.shua.ti.data.entity.BackupFile
import com.shua.ti.data.entity.PracticeProgress
import com.shua.ti.data.entity.Question
import com.shua.ti.data.entity.QuestionBank
import com.shua.ti.domain.BackupErrorCode
import com.shua.ti.domain.BankNames
import com.shua.ti.domain.BackupService
import com.shua.ti.domain.ExportResult
import com.shua.ti.domain.ImportErrorCode
import com.shua.ti.domain.ImportResult
import com.shua.ti.domain.QuestionBankImporter
import com.shua.ti.domain.QuizEngine
import com.shua.ti.domain.QuizErrorCode
import com.shua.ti.domain.RestoreResult
import com.shua.ti.domain.RoundStart
import com.shua.ti.domain.SubmitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class QuizMode { SINGLE, SCROLL }

data class RoundUi(
    val roundNo: Int,
    val bankId: Long,
    val requestedSize: Int,
    val questions: List<Question>,
    val answers: Map<Long, String> = emptyMap(),
    val index: Int = 0,
    val mode: QuizMode = QuizMode.SINGLE,
    val graded: SubmitResult.Graded? = null,
)

data class DialogUi(
    val title: String,
    val body: String,
    val positive: String = "确定",
    val onPositive: (() -> Unit)? = null,
)

data class RoundSummary(
    val roundNo: Int,
    val correct: Int,
    val total: Int,
)

data class UiState(
    val banks: List<QuestionBank> = emptyList(),
    val activeBankId: Long? = null,
    val round: RoundUi? = null,
    val progress: PracticeProgress? = null,
    val history: List<RoundSummary> = emptyList(),
    val backups: List<BackupFile> = emptyList(),
    val toast: String? = null,
    val isError: Boolean = false,
    val dialog: DialogUi? = null,
    val pendingExport: ExportResult.Success? = null,
    val backupAction: BackupFile? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val importer = QuestionBankImporter(
        db = db,
        bankDao = db.questionBankDao(),
        questionDao = db.questionDao(),
    )
    private val engine = QuizEngine(
        db = db,
        bankDao = db.questionBankDao(),
        questionDao = db.questionDao(),
        answerRecordDao = db.answerRecordDao(),
        progressDao = db.practiceProgressDao(),
    )
    private val backup = BackupService(
        db = db,
        bankDao = db.questionBankDao(),
        questionDao = db.questionDao(),
        answerRecordDao = db.answerRecordDao(),
        progressDao = db.practiceProgressDao(),
        backupFileDao = db.backupFileDao(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            db.questionBankDao().observeAll().collect { banks ->
                _state.value = _state.value.copy(banks = banks)
                val active = _state.value.activeBankId
                if (active == null && banks.isNotEmpty()) {
                    selectBank(banks.first().bankId)
                } else if (active != null && banks.none { it.bankId == active }) {
                    _state.value = _state.value.copy(activeBankId = null, round = null)
                }
            }
        }
        viewModelScope.launch {
            db.backupFileDao().observeAll().collect { list ->
                _state.value = _state.value.copy(backups = list)
            }
        }
    }

    fun import(fileName: String, content: String?) {
        viewModelScope.launch {
            when (val result = importer.import(fileName, content)) {
                is ImportResult.Success -> {
                    toast("成功导入 ${result.importedCount} 题，已设为当前题库")
                    selectBank(result.bankId)
                }
                is ImportResult.Failure -> {
                    val where = result.location?.let { "（$it）" } ?: ""
                    toast("${result.message}$where", isError = true)
                }
            }
        }
    }

    fun selectBank(bankId: Long) {
        viewModelScope.launch {
            val switching = _state.value.round?.graded == null && _state.value.round != null
            _state.value = _state.value.copy(
                activeBankId = bankId,
                round = null,
                progress = db.practiceProgressDao().find(bankId),
            )
            loadHistory(bankId)
            if (switching) {
                toast("切换题库，当前轮次作废", isError = true)
            }
        }
    }

    fun startRound(size: Int) {
        val bankId = _state.value.activeBankId ?: run {
            toast("尚未指定题库，请先导入", isError = true)
            return
        }
        viewModelScope.launch {
            when (val result = engine.startRound(bankId, size)) {
                is RoundStart.Started -> {
                    if (result.insufficient) {
                        toast("剩余题不足，本轮按 ${result.actualSize} 题进行")
                    }
                    _state.value = _state.value.copy(
                        round = RoundUi(
                            roundNo = result.roundNo,
                            bankId = bankId,
                            requestedSize = size,
                            questions = result.questions,
                        ),
                    )
                }
                is RoundStart.Failed -> toast(result.message, isError = true)
            }
        }
    }

    fun pick(questionId: Long, letter: String) {
        val round = _state.value.round ?: return
        if (round.graded != null) return
        val answers = round.answers + (questionId to letter)
        val nextIndex = if (round.mode == QuizMode.SINGLE && round.index < round.questions.lastIndex) {
            round.index + 1
        } else {
            round.index
        }
        _state.value = _state.value.copy(round = round.copy(answers = answers, index = nextIndex))
    }

    fun prevQuestion() {
        val round = _state.value.round ?: return
        if (round.index > 0) {
            _state.value = _state.value.copy(round = round.copy(index = round.index - 1))
        }
    }

    fun nextQuestion() {
        val round = _state.value.round ?: return
        if (round.index < round.questions.lastIndex) {
            _state.value = _state.value.copy(round = round.copy(index = round.index + 1))
        }
    }

    fun setMode(mode: QuizMode) {
        val round = _state.value.round ?: return
        _state.value = _state.value.copy(round = round.copy(mode = mode))
    }

    fun submit() {
        val round = _state.value.round ?: return
        viewModelScope.launch {
            when (val result = engine.submit(
                bankId = round.bankId,
                roundNo = round.roundNo,
                questions = round.questions,
                answers = round.answers,
                confirmedUnanswered = false,
            )) {
                is SubmitResult.NeedConfirm -> {
                    _state.value = _state.value.copy(
                        dialog = DialogUi(
                            title = "有未作答题",
                            body = "本轮有 ${result.unanswered} 道未作答题。确认后计为答错。",
                            positive = "确认交卷",
                            onPositive = { confirmSubmit() },
                        ),
                    )
                }
                is SubmitResult.Graded -> onGraded(result)
                is SubmitResult.Failed -> toast(result.message, isError = true)
            }
        }
    }

    fun confirmSubmit() {
        val round = _state.value.round ?: return
        viewModelScope.launch {
            val result = engine.submit(
                bankId = round.bankId,
                roundNo = round.roundNo,
                questions = round.questions,
                answers = round.answers,
                confirmedUnanswered = true,
            )
            if (result is SubmitResult.Graded) onGraded(result)
            else if (result is SubmitResult.Failed) toast(result.message, isError = true)
        }
    }

    private suspend fun onGraded(result: SubmitResult.Graded) {
        val round = _state.value.round ?: return
        _state.value = _state.value.copy(
            round = round.copy(graded = result),
            progress = db.practiceProgressDao().find(round.bankId),
        )
        loadHistory(round.bankId)
        toast("答对 ${result.correct} / ${result.size}；进度已保存")
    }

    private suspend fun loadHistory(bankId: Long) {
        val records = db.answerRecordDao().findAllByBank(bankId)
        val history = records
            .groupBy { it.roundNo }
            .map { (round, list) ->
                RoundSummary(
                    roundNo = round,
                    correct = list.count { it.isCorrect },
                    total = list.size,
                )
            }
            .sortedByDescending { it.roundNo }
        _state.value = _state.value.copy(history = history)
    }

    fun nextRound() {
        _state.value = _state.value.copy(round = null)
    }

    /**
     * 重命名题库。重名时自动加 (n)，并在提示里说明最终名字，
     * 避免用户以为没生效。
     */
    fun renameBank(bankId: Long, rawName: String) {
        val input = rawName.trim()
        if (input.isEmpty()) {
            toast("题库名不能为空", isError = true)
            return
        }
        viewModelScope.launch {
            val current = db.questionBankDao().findById(bankId) ?: run {
                toast("题库已删除", isError = true)
                return@launch
            }
            val desired = BankNames.withOriginalExtension(input, current.sourceFileName)
            if (desired == current.sourceFileName) {
                toast("名称未改变")
                return@launch
            }
            val others = db.questionBankDao().findAll()
                .filter { it.bankId != bankId }
                .map { it.sourceFileName }
                .toSet()
            val finalName = BankNames.unique(desired, others)
            db.questionBankDao().rename(bankId, finalName)
            if (finalName == desired) {
                toast("已重命名为 $finalName")
            } else {
                toast("已重命名为 $finalName（重名，已自动加序号）")
            }
        }
    }

    fun deleteActiveBank() {
        val bankId = _state.value.activeBankId ?: return
        viewModelScope.launch {
            engine.deleteBank(bankId)
            _state.value = _state.value.copy(activeBankId = null, round = null, progress = null)
            toast("题库已删除，进度一并清除")
        }
    }

    /**
     * 导出只生成内容并返回给界面，真正写文件在界面层用系统文件选择器完成。
     */
    fun requestExport() {
        viewModelScope.launch {
            val current = _state.value
            val bank = current.banks.firstOrNull { it.bankId == current.activeBankId }
            val base = bank?.sourceFileName
                ?.removeSuffix(".json")
                ?.takeIf { it.isNotBlank() }
                ?: "题库"
            val defaultName = "$base-备份${current.backups.size + 1}.json"
            when (val result = backup.export(defaultName)) {
                is ExportResult.Success -> {
                    _state.value = _state.value.copy(pendingExport = result)
                }
                is ExportResult.Failure -> toast(result.message, isError = true)
            }
        }
    }

    /**
     * 把已经生成好的备份内容写到用户选定的位置。
     * 写文件在 IO 线程执行；失败必须可见，不允许出现 0B 文件还当成功。
     */
    fun exportTo(uri: Uri, fileName: String) {
        val pending = _state.value.pendingExport ?: run {
            toast("导出内容尚未生成，请重试", isError = true)
            return
        }
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver
                        .openOutputStream(uri)
                        ?.bufferedWriter()
                        ?.use { it.write(pending.content) }
                        ?: error("无法打开输出流")
                }.isSuccess
            }
            if (!written) {
                _state.value = _state.value.copy(pendingExport = null)
                toast("写入失败：目标位置未能写入，文件可能为空，请重试", isError = true)
                return@launch
            }
            // 申请持久读权限：否则 App 重启后即便文件还在也读不了。
            withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            backup.recordExported(uri.toString(), fileName, pending)
            _state.value = _state.value.copy(
                pendingExport = null,
                dialog = DialogUi(
                    title = "备份已生成",
                    body = "$fileName\n${pending.questionCount} 题 · ${pending.roundCount} 轮\n\n${pending.verificationHint}",
                    positive = "知道了",
                ),
            )
        }
    }

    fun cancelExport() {
        _state.value = _state.value.copy(pendingExport = null)
    }

    fun reportExportWriteFailed() {
        toast("写入失败：存储空间不足或写入被拒绝，目标位置未生成文件", isError = true)
    }

    fun restore(content: String?) {
        viewModelScope.launch {
            if (content == null) {
                toast("文件不可读，已保留本机现有数据", isError = true)
                return@launch
            }
            when (val result = backup.restore(content)) {
                is RestoreResult.Success -> {
                    toast("备份已还原：${result.bankCount} 个题库、${result.questionCount} 题、${result.roundCount} 轮")
                }
                is RestoreResult.Failure -> {
                    val prefix = if (result.code == BackupErrorCode.RESTORE_PARSE_FAILED) {
                        "还原失败，已保留本机现有数据："
                    } else {
                        ""
                    }
                    toast(prefix + result.message, isError = true)
                }
            }
        }
    }

    /**
     * 点备份列表项时先确认文件还在。文件被系统或用户删掉时，
     * 提示并移除这条失效记录，不留下点不开的条目。
     */
    fun onBackupClicked(file: BackupFile) {
        viewModelScope.launch {
            val readable = withContext(Dispatchers.IO) { canRead(file.fileUri) }
            if (readable) {
                _state.value = _state.value.copy(backupAction = file)
            } else {
                db.backupFileDao().deleteById(file.backupId)
                _state.value = _state.value.copy(backupAction = null)
                toast("备份文件已不在手机上，已移除这条记录", isError = true)
            }
        }
    }

    fun importBackupRecord(file: BackupFile) {
        viewModelScope.launch {
            _state.value = _state.value.copy(backupAction = null)
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver
                        .openInputStream(Uri.parse(file.fileUri))
                        ?.bufferedReader()
                        ?.use { it.readText() }
                }.getOrNull()
            }
            restore(content)
        }
    }

    fun deleteBackupRecord(file: BackupFile) {
        viewModelScope.launch {
            db.backupFileDao().deleteById(file.backupId)
            _state.value = _state.value.copy(backupAction = null)
            toast("已移除备份记录，手机上的文件未删除")
        }
    }

    fun dismissBackupAction() {
        _state.value = _state.value.copy(backupAction = null)
    }

    private fun canRead(uriString: String): Boolean = runCatching {
        getApplication<Application>().contentResolver
            .openFileDescriptor(Uri.parse(uriString), "r")
            ?.use { true }
            ?: false
    }.getOrDefault(false)

    fun dismissDialog() {
        _state.value = _state.value.copy(dialog = null)
    }

    fun clearToast() {
        _state.value = _state.value.copy(toast = null, isError = false)
    }

    private fun toast(message: String, isError: Boolean = false) {
        _state.value = _state.value.copy(toast = message, isError = isError)
    }
}
