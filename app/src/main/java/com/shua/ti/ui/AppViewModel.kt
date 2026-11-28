package com.shua.ti.ui

import android.app.Application
import androidx.core.content.FileProvider
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shua.ti.BuildConfig
import com.shua.ti.data.AppDatabase
import com.shua.ti.data.entity.BackupFile
import com.shua.ti.data.entity.PracticeProgress
import com.shua.ti.data.entity.Question
import com.shua.ti.data.entity.QuestionBank
import com.shua.ti.domain.BackupCodec
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
import com.shua.ti.domain.ReviewService
import com.shua.ti.domain.RoundKind
import com.shua.ti.domain.RoundStart
import com.shua.ti.domain.SubmitResult
import com.shua.ti.domain.UpdateChecker
import com.shua.ti.domain.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
    val roundKind: RoundKind = RoundKind.NORMAL,
) {
    /** 轮次标签：错题与收藏 / 重刷；顺序轮无标签。 */
    val tag: String? get() = when (roundKind) {
        RoundKind.SPECIAL -> "错题与收藏"
        RoundKind.REPLAY -> "重刷"
        RoundKind.NORMAL -> null
    }
}

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
    val kind: RoundKind = RoundKind.NORMAL,
) {
    val tag: String? get() = when (kind) {
        RoundKind.SPECIAL -> "错题与收藏"
        RoundKind.REPLAY -> "重刷"
        RoundKind.NORMAL -> null
    }
}

/** 轮次回看的题目行：题目可能已被移除，question 为 null 时界面显示占位。 */
data class ReviewRowUi(
    val questionId: Long,
    val question: Question?,
    val chosen: String,
    val answer: String,
    val correct: Boolean,
    val explanation: String,
)

data class RoundDetailUi(
    val bankId: Long,
    val roundNo: Int,
    val tag: String?,
    val correct: Int,
    val total: Int,
    val rows: List<ReviewRowUi>,
    val filterWrongOnly: Boolean = false,
) {
    val visibleRows: List<ReviewRowUi>
        get() = if (filterWrongOnly) rows.filterNot { it.correct } else rows
}

/** 管理页数据：普通库列题目，特殊库分错题与收藏两组。 */
data class ManageUi(
    val bankId: Long,
    val special: Boolean,
    val questions: List<Question> = emptyList(),
    val wrong: List<Question> = emptyList(),
    val favorites: List<Question> = emptyList(),
    val favIds: Set<Long> = emptySet(),
)

/** 更新检查与下载的状态机；下载失败必须可见（可重试）。 */
sealed interface UpdateUi {
    data object Checking : UpdateUi

    data class Available(
        val latestVersion: String,
        val notes: String,
        val downloadUrl: String,
        val download: DownloadState = DownloadState.Idle,
    ) : UpdateUi

    data object UpToDate : UpdateUi

    data class Failed(val message: String) : UpdateUi
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Downloading : DownloadState
    data object Ready : DownloadState
    data class Failed(val message: String) : DownloadState
}

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
    /** 常驻错题库 bank_id；启动时确保存在。 */
    val specialBankId: Long? = null,
    /** 错题集题数（实时推导）。 */
    val wrongCount: Int = 0,
    /** 收藏总数。 */
    val favCount: Int = 0,
    /** 收藏中的题目 id。 */
    val favIds: Set<Long> = emptySet(),
    /** 题库操作菜单目标。 */
    val menuBank: QuestionBank? = null,
    /** 轮次回看详情。 */
    val roundDetail: RoundDetailUi? = null,
    /** 单题回看详情。 */
    val questionDetail: ReviewRowUi? = null,
    /** 管理页目标题库。 */
    val manage: ManageUi? = null,
    /** 检查更新的状态；null 表示未触发。 */
    val update: UpdateUi? = null,
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
        favoriteDao = db.favoriteDao(),
        excludedQuestionDao = db.excludedQuestionDao(),
    )
    private val review = ReviewService(
        db = db,
        bankDao = db.questionBankDao(),
        questionDao = db.questionDao(),
        answerRecordDao = db.answerRecordDao(),
        progressDao = db.practiceProgressDao(),
        favoriteDao = db.favoriteDao(),
        excludedQuestionDao = db.excludedQuestionDao(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 常驻错题库：首次启动创建，之后始终存在。
            val special = review.ensureSpecialBank()
            _state.value = _state.value.copy(specialBankId = special.bankId)
            refreshCounts()
        }
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
        viewModelScope.launch {
            db.favoriteDao().observeAllIds().collect { ids ->
                _state.value = _state.value.copy(favIds = ids.toSet(), favCount = ids.size)
            }
        }
    }

    /** 错题数依赖作答记录与移出记录，两者变化点后手动刷新。 */
    private suspend fun refreshCounts() {
        val wrong = review.wrongQuestions()
        _state.value = _state.value.copy(wrongCount = wrong.size)
    }

    /** 特殊库当前 bank_id，未就绪时返回 null。 */
    private fun specialId(): Long? = _state.value.specialBankId

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

    /**
     * 导入入口的统一分流：备份文件走还原，题库文件走导入。
     * 用户不需要区分两种文件，从文件管理器、微信、QQ 送进来的都走这里。
     */
    fun importOrRestore(fileName: String, content: String?) {
        if (content != null && BackupCodec.looksLikeBackup(content)) {
            restore(content)
        } else {
            import(fileName, content)
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
        if (bankId == specialId()) {
            startSpecialRound(size)
            return
        }
        startNormalRound(bankId, size, RoundKind.NORMAL)
    }

    private fun startNormalRound(bankId: Long, size: Int, kind: RoundKind) {
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
                            roundKind = kind,
                        ),
                    )
                }
                is RoundStart.Failed -> toast(result.message, isError = true)
            }
        }
    }

    /** 特殊库合集轮：错题 + 收藏去重后按题号顺序出题。 */
    private fun startSpecialRound(size: Int) {
        val bankId = specialId() ?: return
        viewModelScope.launch {
            val all = review.specialRoundQuestions()
            if (all.isEmpty()) {
                toast("暂无错题与收藏", isError = true)
                return@launch
            }
            val actual = minOf(size, all.size)
            if (actual < size) {
                toast("可用 ${all.size} 题，本轮按 $actual 题进行")
            }
            when (val result = engine.startRoundFromQuestions(bankId, all.take(actual), RoundKind.SPECIAL)) {
                is RoundStart.Started -> {
                    _state.value = _state.value.copy(
                        round = RoundUi(
                            roundNo = result.roundNo,
                            bankId = bankId,
                            requestedSize = actual,
                            questions = result.questions,
                            roundKind = RoundKind.SPECIAL,
                        ),
                    )
                }
                is RoundStart.Failed -> toast(result.message, isError = true)
            }
        }
    }

    /** 整轮重刷：以该轮的题目集合开一轮新记录，顺序进度不推进。 */
    fun replayRound(bankId: Long, roundNo: Int) {
        viewModelScope.launch {
            val rows = review.roundRows(bankId, roundNo)
            val questions = rows.mapNotNull { it.question }
            if (questions.isEmpty()) {
                toast("该轮题目已不存在，无法重刷", isError = true)
                return@launch
            }
            when (val result = engine.startRoundFromQuestions(bankId, questions, RoundKind.REPLAY)) {
                is RoundStart.Started -> {
                    _state.value = _state.value.copy(
                        round = RoundUi(
                            roundNo = result.roundNo,
                            bankId = bankId,
                            requestedSize = questions.size,
                            questions = result.questions,
                            roundKind = RoundKind.REPLAY,
                        ),
                        roundDetail = null,
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
                kind = round.roundKind,
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
                kind = round.roundKind,
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
        refreshCounts()
        val tip = when (result.roundKind) {
            RoundKind.NORMAL -> "答对 ${result.correct} / ${result.size}；进度已保存"
            else -> "答对 ${result.correct} / ${result.size}；答对的错题已移出错题集"
        }
        toast(tip)
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
                    kind = list.first().roundKind.let { kindName ->
                        RoundKind.entries.firstOrNull { it.name == kindName } ?: RoundKind.NORMAL
                    },
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
        deleteBank(bankId)
    }

    /** 题库菜单的删除入口；常驻错题库不可删除（界面层不放该选项）。 */
    fun deleteBank(bankId: Long) {
        viewModelScope.launch {
            if (bankId == specialId()) {
                toast("错题与收藏题库无法删除", isError = true)
                return@launch
            }
            engine.deleteBank(bankId)
            if (_state.value.manage?.bankId == bankId) {
                _state.value = _state.value.copy(manage = null)
            }
            _state.value = _state.value.copy(
                activeBankId = _state.value.activeBankId?.takeIf { it != bankId },
                round = null,
            )
            toast("题库已删除，进度一并清除")
        }
    }

    /* ===== 收藏 ===== */

    /** @return 切换后是否已收藏。 */
    suspend fun toggleFavorite(questionId: Long): Boolean {
        val nowFav = review.toggleFavorite(questionId)
        refreshCounts()
        return nowFav
    }

    fun toggleFavoriteAsync(questionId: Long) {
        viewModelScope.launch { toggleFavorite(questionId) }
    }

    /* ===== 题库菜单 ===== */

    fun openBankMenu(bankId: Long) {
        val bank = _state.value.banks.firstOrNull { it.bankId == bankId } ?: return
        _state.value = _state.value.copy(menuBank = bank)
    }

    fun dismissBankMenu() {
        _state.value = _state.value.copy(menuBank = null)
    }

    /** 清空一个题库的进度；当前错题自动收藏（用户已确认方案）。 */
    fun clearProgress(bankId: Long) {
        viewModelScope.launch {
            if (bankId == specialId()) {
                toast("错题与收藏题库不支持清空进度", isError = true)
                return@launch
            }
            val added = review.clearProgress(bankId)
            if (_state.value.activeBankId == bankId) {
                _state.value = _state.value.copy(
                    progress = null,
                    history = emptyList(),
                )
            }
            refreshCounts()
            toast("已清空进度")
            loadHistory(bankId)
        }
    }

    /* ===== 回看 ===== */

    fun openRound(bankId: Long, roundNo: Int) {
        viewModelScope.launch {
            val rows = review.roundRows(bankId, roundNo)
            val correct = rows.count { it.record.isCorrect }
            val kind = rows.firstOrNull()?.record?.roundKind?.let { name ->
                RoundKind.entries.firstOrNull { it.name == name }
            } ?: RoundKind.NORMAL
            val tag = when (kind) {
                RoundKind.SPECIAL -> "错题与收藏"
                RoundKind.REPLAY -> "重刷"
                RoundKind.NORMAL -> null
            }
            _state.value = _state.value.copy(
                roundDetail = RoundDetailUi(
                    bankId = bankId,
                    roundNo = roundNo,
                    tag = tag,
                    correct = correct,
                    total = rows.size,
                    rows = rows.map { row ->
                        ReviewRowUi(
                            questionId = row.record.questionId,
                            question = row.question,
                            chosen = row.record.chosenOption,
                            answer = row.question?.answer ?: "",
                            correct = row.record.isCorrect,
                            explanation = row.question?.explanation ?: "",
                        )
                    },
                ),
            )
        }
    }

    fun setRoundFilter(wrongOnly: Boolean) {
        val detail = _state.value.roundDetail ?: return
        _state.value = _state.value.copy(roundDetail = detail.copy(filterWrongOnly = wrongOnly))
    }

    fun closeRoundDetail() {
        _state.value = _state.value.copy(roundDetail = null)
    }

    fun openQuestionDetail(row: ReviewRowUi) {
        _state.value = _state.value.copy(questionDetail = row)
    }

    fun closeQuestionDetail() {
        _state.value = _state.value.copy(questionDetail = null)
    }

    fun toggleFavoriteInDetail() {
        val row = _state.value.questionDetail ?: return
        viewModelScope.launch {
            val nowFav = review.toggleFavorite(row.questionId)
            refreshCounts()
            _state.value = _state.value.copy(
                questionDetail = row.copy(
                    question = row.question, // 题目对象不变，收藏态由 favIds 渲染
                ),
                favIds = db.favoriteDao().findAllIds().toSet(),
            )
        }
    }

    /* ===== 管理 ===== */

    fun openManage(bankId: Long) {
        viewModelScope.launch {
            val bank = db.questionBankDao().findById(bankId) ?: return@launch
            val special = bank.isSpecial
            val favIds = db.favoriteDao().findAllIds().toSet()
            val manage = if (special) {
                ManageUi(
                    bankId = bankId,
                    special = true,
                    wrong = review.wrongQuestions(),
                    favorites = review.favoriteQuestions(),
                    favIds = favIds,
                )
            } else {
                ManageUi(
                    bankId = bankId,
                    special = false,
                    questions = db.questionDao().page(bankId, Int.MAX_VALUE, 0),
                    favIds = favIds,
                )
            }
            _state.value = _state.value.copy(manage = manage)
        }
    }

    fun closeManage() {
        _state.value = _state.value.copy(manage = null)
    }

    /** 普通库：移除题目。作答记录保留。 */
    fun removeQuestion(bankId: Long, questionId: Long) {
        viewModelScope.launch {
            review.removeQuestion(bankId, questionId)
            refreshCounts()
            toast("已移除 1 题")
            openManage(bankId)
        }
    }

    /** 特殊库：把题移出错题集。 */
    fun excludeFromWrong(bankId: Long, questionId: Long) {
        viewModelScope.launch {
            review.excludeFromWrong(bankId, questionId)
            refreshCounts()
            toast("已移出错题集")
            openManage(bankId)
        }
    }

    /** 特殊库：取消收藏。 */
    fun unfavorite(questionId: Long) {
        viewModelScope.launch {
            review.toggleFavorite(questionId)
            refreshCounts()
            toast("已取消收藏")
            _state.value.manage?.let { openManage(it.bankId) }
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

    /* ===== 检查更新 ===== */

    fun checkUpdate() {
        if (_state.value.update == UpdateUi.Checking) return
        _state.value = _state.value.copy(update = UpdateUi.Checking)
        viewModelScope.launch {
            val result = UpdateChecker.check(BuildConfig.VERSION_NAME)
            _state.value = _state.value.copy(
                update = when (result) {
                    is UpdateResult.Available -> UpdateUi.Available(
                        latestVersion = result.latestVersion,
                        notes = result.notes,
                        downloadUrl = result.downloadUrl,
                    )
                    is UpdateResult.UpToDate -> UpdateUi.UpToDate
                    is UpdateResult.Failed -> UpdateUi.Failed(result.message)
                },
            )
        }
    }

    fun dismissUpdate() {
        _state.value = _state.value.copy(update = null)
    }

    /** 下载 APK 并拉起系统安装器；失败保留对话框并给出原因。 */
    fun downloadUpdate() {
        val current = _state.value.update as? UpdateUi.Available ?: return
        if (current.download == DownloadState.Downloading) return
        _state.value = _state.value.copy(
            update = current.copy(download = DownloadState.Downloading),
        )
        viewModelScope.launch {
            val dir = File(getApplication<Application>().cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "shuaTI-${current.latestVersion}.apk")
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(current.downloadUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 60_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "shuaTI-update-check")
                    }
                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) error("服务器返回 HTTP $code")
                        conn.inputStream.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (target.length() < 10_000) error("下载内容不完整")
                    } finally {
                        conn.disconnect()
                    }
                }.exceptionOrNull()
            }
            if (error != null) {
                val message = "下载失败：${error.message ?: "网络中断"}"
                _state.value = _state.value.copy(
                    update = current.copy(download = DownloadState.Failed(message)),
                )
                return@launch
            }
            _state.value = _state.value.copy(
                update = current.copy(download = DownloadState.Ready),
            )
            launchInstaller(target)
        }
    }

    /** 交给系统安装器；覆盖安装保留本机数据（签名一致才允许）。 */
    private fun launchInstaller(apk: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                getApplication(),
                "${getApplication<Application>().packageName}.updates",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(intent)
        }.onFailure {
            _state.value = _state.value.copy(
                update = (_state.value.update as? UpdateUi.Available)?.copy(
                    download = DownloadState.Failed("无法打开安装器：${it.message ?: "未知原因"}"),
                ),
            )
        }
    }

    /** 设置页展示的当前版本。 */
    fun currentVersion(): String = BuildConfig.VERSION_NAME

    fun dismissDialog() {
        _state.value = _state.value.copy(dialog = null)
    }

    fun clearToast() {
        _state.value = _state.value.copy(toast = null, isError = false)
    }

    fun notify(message: String) {
        toast(message)
    }

    private fun toast(message: String, isError: Boolean = false) {
        _state.value = _state.value.copy(toast = message, isError = isError)
    }
}
