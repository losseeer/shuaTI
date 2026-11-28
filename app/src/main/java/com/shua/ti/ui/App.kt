package com.shua.ti.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.shua.ti.data.entity.BackupFile
import com.shua.ti.data.entity.PracticeProgress
import com.shua.ti.data.entity.Question
import com.shua.ti.data.entity.QuestionBank
import com.shua.ti.domain.GradedRow
import com.shua.ti.domain.RoundKind

private val TABS = listOf("题库", "刷题", "进度", "设置")

@Composable
fun App(
    sharedUri: Uri? = null,
    onSharedHandled: () -> Unit = {},
    vm: AppViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var showHelp by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        val message = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.clearToast()
    }

    // 从微信 / QQ「用其他应用打开」或分享进来的题库文件，直接走导入。
    LaunchedEffect(sharedUri) {
        val uri = sharedUri ?: return@LaunchedEffect
        val name = fileNameOf(context, uri) ?: "shared.json"
        val content = withContext(Dispatchers.IO) { readText(context, uri) }
        tab = 0
        vm.importOrRestore(name, content)
        onSharedHandled()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.importOrRestore(fileNameOf(context, uri) ?: "imported.json", readText(context, uri))
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            vm.cancelExport()
        } else {
            val saved = fileNameOf(context, uri) ?: vm.state.value.pendingExport?.fileName
            vm.exportTo(uri, saved ?: "backup.json")
        }
    }

    // 备份内容生成完成后才打开保存对话框：先 launch 会拿到空内容，写出 0B 文件。
    LaunchedEffect(state.pendingExport) {
        val pending = state.pendingExport ?: return@LaunchedEffect
        exportLauncher.launch(pending.fileName)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(label.take(1)) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showHelp) {
                HelpScreen(onBack = { showHelp = false }, onNotify = vm::notify)
            } else when {
                state.questionDetail != null -> QuestionDetailScreen(
                    row = state.questionDetail!!,
                    isFav = state.questionDetail!!.questionId in state.favIds,
                    onToggleFav = { vm.toggleFavoriteInDetail() },
                    onBack = vm::closeQuestionDetail,
                )
                state.roundDetail != null -> RoundDetailScreen(
                    detail = state.roundDetail!!,
                    favIds = state.favIds,
                    onFilter = vm::setRoundFilter,
                    onOpenQuestion = vm::openQuestionDetail,
                    onReplay = vm::replayRound,
                    onToggleFav = vm::toggleFavoriteAsync,
                    onBack = vm::closeRoundDetail,
                )
                state.manage != null -> ManageScreen(
                    manage = state.manage!!,
                    onRemove = vm::removeQuestion,
                    onExclude = vm::excludeFromWrong,
                    onUnfavorite = vm::unfavorite,
                    onBack = vm::closeManage,
                )
                else -> when (tab) {
                    0 -> LibraryScreen(
                        banks = state.banks,
                        activeId = state.activeBankId,
                        specialBankId = state.specialBankId,
                        wrongCount = state.wrongCount,
                        favCount = state.favCount,
                        menuBank = state.menuBank,
                        onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "text/*")) },
                        onSetCurrent = vm::selectBank,
                        onOpenMenu = vm::openBankMenu,
                        onDismissMenu = vm::dismissBankMenu,
                        onRename = vm::renameBank,
                        onManage = vm::openManage,
                        onClearProgress = vm::clearProgress,
                        onDelete = vm::deleteBank,
                    )
                    1 -> QuizScreen(
                        round = state.round,
                        hasBank = state.activeBankId != null,
                        isSpecial = state.activeBankId != null && state.activeBankId == state.specialBankId,
                        wrongCount = state.wrongCount,
                        favCount = state.favCount,
                        favIds = state.favIds,
                        onStart = vm::startRound,
                        onPick = vm::pick,
                        onPrev = vm::prevQuestion,
                        onNext = vm::nextQuestion,
                        onMode = vm::setMode,
                        onSubmit = vm::submit,
                        onNextRound = vm::nextRound,
                        onToggleFav = vm::toggleFavoriteAsync,
                    )
                    2 -> ProgressScreen(
                        bank = state.banks.firstOrNull { it.bankId == state.activeBankId },
                        progress = state.progress,
                        history = state.history,
                        onOpenRound = vm::openRound,
                    )
                    else -> if (showBackup) {
                        BackupScreen(
                            backups = state.backups,
                            onExport = vm::requestExport,
                            onBackupClick = vm::onBackupClicked,
                            onBack = { showBackup = false },
                        )
                    } else SettingsScreen(
                        currentVersion = vm.currentVersion(),
                        onOpenBackup = { showBackup = true },
                        onOpenHelp = { showHelp = true },
                        onCheckUpdate = vm::checkUpdate,
                    )
                }
            }
        }
    }

    state.dialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text(dialog.title) },
            text = { Text(dialog.body) },
            confirmButton = {
                TextButton(onClick = {
                    val action = dialog.onPositive
                    vm.dismissDialog()
                    action?.invoke()
                }) { Text(dialog.positive) }
            },
        )
    }

    state.update?.let { update ->
        UpdateDialog(
            update = update,
            onDownload = vm::downloadUpdate,
            onCancelDownload = vm::cancelDownload,
            onRetry = vm::checkUpdate,
            onDismiss = vm::dismissUpdate,
        )
    }

    state.backupAction?.let { file ->
        AlertDialog(
            onDismissRequest = vm::dismissBackupAction,
            title = {
                Text(file.fileName.ifBlank { "备份 ${formatTime(file.exportedAt)}" })
            },
            text = {
                Text("${formatTime(file.exportedAt)} · ${file.questionCount} 题 · ${file.roundCount} 轮")
            },
            confirmButton = {
                TextButton(onClick = { vm.importBackupRecord(file) }) { Text("导入") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.deleteBackupRecord(file) }) { Text("删除记录") }
                    TextButton(onClick = vm::dismissBackupAction) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun LibraryScreen(
    banks: List<QuestionBank>,
    activeId: Long?,
    specialBankId: Long?,
    wrongCount: Int,
    favCount: Int,
    menuBank: QuestionBank?,
    onImport: () -> Unit,
    onSetCurrent: (Long) -> Unit,
    onOpenMenu: (Long) -> Unit,
    onDismissMenu: () -> Unit,
    onRename: (Long, String) -> Unit,
    onManage: (Long) -> Unit,
    onClearProgress: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf<QuestionBank?>(null) }
    var confirmingClear by remember { mutableStateOf<QuestionBank?>(null) }
    var renameTarget by remember { mutableStateOf<QuestionBank?>(null) }
    var renameInput by remember { mutableStateOf("") }

    renameTarget?.let { bank ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名题库") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    label = { Text("题库名") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameInput
                    renameTarget = null
                    onDismissMenu()
                    onRename(bank.bankId, name)
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }
    confirmingDelete?.let { bank ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("删除题库") },
            text = { Text("将删除「${bank.sourceFileName}」及其答题记录与进度，此操作无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = null
                    onDismissMenu()
                    onDelete(bank.bankId)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("取消") }
            },
        )
    }
    confirmingClear?.let { bank ->
        AlertDialog(
            onDismissRequest = { confirmingClear = null },
            title = { Text("清空进度") },
            text = { Text("将清空「${bank.sourceFileName}」的全部作答记录与进度。当前错题将自动收藏。此操作无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = null
                    onDismissMenu()
                    onClearProgress(bank.bankId)
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = null }) { Text("取消") }
            },
        )
    }

    menuBank?.let { bank ->
        AlertDialog(
            onDismissRequest = onDismissMenu,
            title = { Text(bank.sourceFileName) },
            text = {
                Text(
                    if (bank.isSpecial) "错题 $wrongCount · 收藏 $favCount"
                    else "${bank.questionCount} 题",
                )
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        renameInput = bank.sourceFileName
                        renameTarget = bank
                    }) { Text("重命名") }
                    TextButton(onClick = {
                        onDismissMenu()
                        onManage(bank.bankId)
                    }) { Text("管理（移除题目）") }
                    if (!bank.isSpecial) {
                        TextButton(onClick = { confirmingClear = bank }) { Text("清空进度") }
                        TextButton(onClick = { confirmingDelete = bank }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = onDismissMenu) { Text("取消") }
                }
            },
            dismissButton = {},
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("我的题库", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onImport) { Text("导入") }
        }
        Spacer(Modifier.height(12.dp))

        val special = banks.firstOrNull { it.isSpecial }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            special?.let { sp ->
                item(key = "special") {
                    val active = sp.bankId == activeId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenMenu(sp.bankId) },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("错题与收藏题库", style = MaterialTheme.typography.titleMedium)
                                    if (active) {
                                        Text("当前", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Text(
                                    "错题 $wrongCount · 收藏 $favCount",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!active) {
                                OutlinedButton(onClick = { onSetCurrent(sp.bankId) }) {
                                    Text("设为当前")
                                }
                            }
                        }
                    }
                }
            }
            if (banks.none { it.isSpecial }) {
                item(key = "empty-hint") {
                    Text(
                        "还没有题库，点「导入」开始",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(banks.filterNot { it.isSpecial }, key = { it.bankId }) { bank ->
                val active = bank.bankId == activeId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenMenu(bank.bankId) },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(bank.sourceFileName, style = MaterialTheme.typography.titleMedium)
                                if (active) {
                                    Text("当前", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text(
                                "${bank.questionCount} 题",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!active) {
                            OutlinedButton(onClick = { onSetCurrent(bank.bankId) }) {
                                Text("设为当前")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuizScreen(
    round: RoundUi?,
    hasBank: Boolean,
    isSpecial: Boolean,
    wrongCount: Int,
    favCount: Int,
    favIds: Set<Long>,
    onStart: (Int) -> Unit,
    onPick: (Long, String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onMode: (QuizMode) -> Unit,
    onSubmit: () -> Unit,
    onNextRound: () -> Unit,
    onToggleFav: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("刷题", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        if (round == null) {
            if (isSpecial) {
                Text("当前题库：错题与收藏题库", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("开始刷题", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "错题 $wrongCount",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "收藏 $favCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(5, 10, 15, 20).forEach { size ->
                                OutlinedButton(
                                    onClick = { onStart(size) },
                                    enabled = wrongCount + favCount > 0,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 10.dp),
                                ) {
                                    Text("$size 题", maxLines = 1)
                                }
                            }
                        }
                    }
                }
                return@Column
            }
            Text(
                if (hasBank) "本轮题量" else "尚未指定题库，请先导入",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(5, 10, 15, 20).forEach { size ->
                    OutlinedButton(
                        onClick = { onStart(size) },
                        enabled = hasBank,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 10.dp),
                    ) {
                        Text("$size 题", maxLines = 1)
                    }
                }
            }
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("第 ${round.roundNo} 轮${round.tag?.let { " · $it" } ?: ""} · ${round.questions.size} 题")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = round.mode == QuizMode.SINGLE,
                    onClick = { onMode(QuizMode.SINGLE) },
                    label = { Text("一页一题") },
                )
                FilterChip(
                    selected = round.mode == QuizMode.SCROLL,
                    onClick = { onMode(QuizMode.SCROLL) },
                    label = { Text("一页多题") },
                )
            }
        }

        val graded = round.graded
        if (graded != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "答对 ${graded.correct} / ${graded.size}",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                val byId = round.questions.associateBy { it.questionId }
                items(graded.rows, key = { it.questionId }) { row ->
                    ResultRow(row, byId[row.questionId])
                }
            }
            Button(onClick = onNextRound, modifier = Modifier.fillMaxWidth()) {
                Text("下一轮")
            }
            return@Column
        }

        Spacer(Modifier.height(12.dp))
        if (round.mode == QuizMode.SINGLE) {
            val q = round.questions[round.index]
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${round.index + 1} / ${round.questions.size}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onToggleFav(q.questionId) }) {
                        Text(
                            if (q.questionId in favIds) "★ 已收藏" else "☆ 收藏",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(q.stem, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                OptionList(q, round.answers[q.questionId]) { letter -> onPick(q.questionId, letter) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onPrev, enabled = round.index > 0) { Text("‹") }
                Text("第 ${round.index + 1} 题")
                OutlinedButton(
                    onClick = onNext,
                    enabled = round.index < round.questions.lastIndex,
                ) { Text("›") }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(round.questions, key = { it.questionId }) { q ->
                    val index = round.questions.indexOf(q)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${index + 1} / ${round.questions.size}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onToggleFav(q.questionId) }) {
                                Text(
                                    if (q.questionId in favIds) "★ 已收藏" else "☆ 收藏",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Text(q.stem, style = MaterialTheme.typography.titleSmall)
                        OptionList(q, round.answers[q.questionId]) { letter -> onPick(q.questionId, letter) }
                    }
                    HorizontalDivider()
                }
            }
        }

        Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth()) { Text("交卷") }
    }
}

@Composable
private fun OptionList(q: Question, chosen: String?, onPick: (String) -> Unit) {
    val options = listOf("A" to q.optionA, "B" to q.optionB, "C" to q.optionC, "D" to q.optionD)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (letter, text) ->
            if (chosen == letter) {
                Button(onClick = { onPick(letter) }, modifier = Modifier.fillMaxWidth()) {
                    Text("$letter. $text")
                }
            } else {
                OutlinedButton(onClick = { onPick(letter) }, modifier = Modifier.fillMaxWidth()) {
                    Text("$letter. $text")
                }
            }
        }
    }
}

@Composable
private fun ResultRow(row: GradedRow, question: Question?) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("第 ${question?.position ?: row.questionId} 题 · 你选 ${row.chosen} · 正确 ${row.answer}")
            Text(
                if (row.correct) "对" else "错",
                color = if (row.correct) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }
        val explanation = question?.explanation
        if (!explanation.isNullOrBlank()) {
            Text(
                explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ProgressScreen(
    bank: QuestionBank?,
    progress: PracticeProgress?,
    history: List<RoundSummary>,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("练习进度", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        if (bank == null) {
            Text("尚未指定题库", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text(bank.sourceFileName, style = MaterialTheme.typography.titleMedium)
        Text(
            "已完成 ${progress?.completedRounds ?: 0} 轮 · 下一轮从第 ${progress?.nextRoundStart ?: 1} 题",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (history.isEmpty()) {
            Text("还没有完成的轮次", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history, key = { it.roundNo }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("第 ${item.roundNo} 轮", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "答对 ${item.correct} / ${item.total}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateDialog(
    update: UpdateUi,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (update) {
        is UpdateUi.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text("检查更新") },
            text = { Text("正在检查…") },
            confirmButton = {},
        )
        is UpdateUi.Available -> {
            val download = update.download
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("发现新版本 ${update.latestVersion}") },
                text = {
                    Column {
                        if (update.notes.isNotBlank()) {
                            Text(
                                update.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        when (download) {
                            DownloadState.Idle -> Text("覆盖安装会保留本机题库与记录。")
                            is DownloadState.Downloading -> {
                                val progress = download.progress
                                if (progress != null) {
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "下载中 ${(progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(6.dp))
                                    Text("正在下载…", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    "可点击空白处收起，下载会继续",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            DownloadState.Ready -> Text("下载完成，正在打开安装器…")
                            is DownloadState.Failed -> Text(
                                download.message,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    if (download is DownloadState.Downloading) {
                        TextButton(onClick = onCancelDownload) { Text("取消更新") }
                    } else {
                        TextButton(onClick = onDownload) {
                            Text(
                                if (download is DownloadState.Failed) "重试下载"
                                else if (download == DownloadState.Ready) "重新打开安装器"
                                else "下载并安装",
                            )
                        }
                    }
                },
                dismissButton = {
                    if (download !is DownloadState.Downloading) {
                        TextButton(onClick = onDismiss) { Text("稍后") }
                    }
                },
            )
        }
        is UpdateUi.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("检查更新") },
            text = { Text("已是最新版本") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        )
        is UpdateUi.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("检查更新失败") },
            text = { Text(update.message) },
            confirmButton = { TextButton(onClick = onRetry) { Text("重试") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )
    }
}

@Composable
private fun SettingsScreen(
    currentVersion: String,
    onOpenBackup: () -> Unit,
    onOpenHelp: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenBackup() },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("备份与恢复", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "导出备份、还原备份文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenHelp() },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("使用说明", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "导入、刷题、备份的简要说明",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckUpdate() },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("检查更新", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "当前版本 $currentVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BackupScreen(
    backups: List<BackupFile>,
    onExport: () -> Unit,
    onBackupClick: (BackupFile) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹") }
            Text("备份与恢复", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("导出备份") }
        Spacer(Modifier.height(16.dp))
        if (backups.isEmpty()) {
            Text("暂无备份", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(backups, key = { it.backupId }) { file ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onBackupClick(file) },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(
                                file.fileName.ifBlank { "备份 ${formatTime(file.exportedAt)}" },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${formatTime(file.exportedAt)} · ${file.questionCount} 题 · ${file.roundCount} 轮",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressScreen(
    bank: QuestionBank?,
    progress: PracticeProgress?,
    history: List<RoundSummary>,
    onOpenRound: (Long, Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("练习进度", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        if (bank == null) {
            Text("尚未指定题库", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text(bank.sourceFileName, style = MaterialTheme.typography.titleMedium)
        Text(
            "已完成 ${progress?.completedRounds ?: 0} 轮 · 下一轮从第 ${progress?.nextRoundStart ?: 1} 题",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (history.isEmpty()) {
            Text("还没有完成的轮次", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history, key = { it.roundNo }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRound(bank.bankId, item.roundNo) },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("第 ${item.roundNo} 轮", style = MaterialTheme.typography.titleSmall)
                                item.tag?.let { tag ->
                                    Text(
                                        tag,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "答对 ${item.correct} / ${item.total}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text("  ›", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundDetailScreen(
    detail: RoundDetailUi,
    favIds: Set<Long>,
    onFilter: (Boolean) -> Unit,
    onOpenQuestion: (ReviewRowUi) -> Unit,
    onReplay: (Long, Int) -> Unit,
    onToggleFav: (Long) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹") }
            Text("第 ${detail.roundNo} 轮", style = MaterialTheme.typography.titleLarge)
            detail.tag?.let { tag ->
                Spacer(Modifier.width(8.dp))
                Text(tag, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "${detail.correct} / ${detail.total}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text("答对题数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { onReplay(detail.bankId, detail.roundNo) }) { Text("重刷这轮") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !detail.filterWrongOnly,
                onClick = { onFilter(false) },
                label = { Text("全部") },
            )
            FilterChip(
                selected = detail.filterWrongOnly,
                onClick = { onFilter(true) },
                label = { Text("只看错题") },
            )
        }
        Spacer(Modifier.height(8.dp))
        if (detail.visibleRows.isEmpty()) {
            Text(
                if (detail.filterWrongOnly) "这一轮没有错题" else "没有作答记录",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(detail.visibleRows, key = { it.questionId }) { row ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenQuestion(row) },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    if (row.correct) "对" else "错",
                                    color = if (row.correct) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    row.question?.let { "第 ${it.position} 题" } ?: "题目已移除",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            row.question?.let { q ->
                                Text(
                                    q.stem,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 4.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                "你选 ${row.chosen} · 正确 ${row.answer}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionDetailScreen(
    row: ReviewRowUi,
    isFav: Boolean,
    onToggleFav: () -> Unit,
    onBack: () -> Unit,
) {
    val q = row.question
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹") }
                Text("题目详情", style = MaterialTheme.typography.titleLarge)
            }
            if (q != null) {
                TextButton(onClick = onToggleFav) {
                    Text(if (isFav) "★ 已收藏" else "☆ 收藏", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (q == null) {
            Text("题目已移除，仅作答记录保留", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (row.correct) "对" else "错",
                color = if (row.correct) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "第 ${q.position} 题",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(q.stem, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        listOf("A" to q.optionA, "B" to q.optionB, "C" to q.optionC, "D" to q.optionD)
            .forEach { (letter, text) ->
                val isAnswer = letter == row.answer
                val isChosen = letter == row.chosen
                val border = when {
                    isAnswer -> MaterialTheme.colorScheme.primary
                    isChosen -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$letter. $text", modifier = Modifier.weight(1f))
                    if (isAnswer) {
                        Text(
                            "（正确答案）",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else if (isChosen) {
                        Text(
                            "（你的选择，错误）",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                HorizontalDivider()
            }
        if (row.chosen == "U") {
            Text(
                "该题未作答，计为答错",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (row.explanation.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("解析", style = MaterialTheme.typography.titleSmall)
            Text(
                row.explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ManageScreen(
    manage: ManageUi,
    onRemove: (Long, Long) -> Unit,
    onExclude: (Long, Long) -> Unit,
    onUnfavorite: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf<Question?>(null) }
    confirmRemove?.let { q ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("移除题目") },
            text = { Text("将把第 ${q.position} 题从题库中移除。该题的作答记录保留，此操作无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = confirmRemove
                    confirmRemove = null
                    target?.let { onRemove(manage.bankId, it.questionId) }
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("取消") }
            },
        )
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹") }
            Text("管理", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(10.dp))
        if (manage.special) {
            Text("错题 ${manage.wrong.size}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            if (manage.wrong.isEmpty()) {
                Text("暂无错题", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(manage.wrong, key = { it.questionId }) { q ->
                        ManageQuestionCard(q) {
                            TextButton(onClick = { onExclude(manage.bankId, q.questionId) }) {
                                Text("移出错题集")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("收藏 ${manage.favorites.size}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            if (manage.favorites.isEmpty()) {
                Text("暂无收藏", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(manage.favorites, key = { it.questionId }) { q ->
                        ManageQuestionCard(q) {
                            TextButton(onClick = { onUnfavorite(q.questionId) }) {
                                Text("取消收藏")
                            }
                        }
                    }
                }
            }
        } else {
            if (manage.questions.isEmpty()) {
                Text("题库为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(manage.questions, key = { it.questionId }) { q ->
                        ManageQuestionCard(q) {
                            TextButton(onClick = { confirmRemove = q }) {
                                Text("移除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageQuestionCard(q: Question, action: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "第 ${q.position} 题",
                    style = MaterialTheme.typography.titleSmall,
                )
                action()
            }
            Text(
                q.stem,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun readText(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

private fun fileNameOf(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

private fun formatTime(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}
