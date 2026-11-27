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
        vm.import(name, content)
        onSharedHandled()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.import(fileNameOf(context, uri) ?: "imported.json", readText(context, uri))
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
            } else {
            when (tab) {
                0 -> LibraryScreen(
                    banks = state.banks,
                    activeId = state.activeBankId,
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "text/*")) },
                    onSelect = vm::selectBank,
                    onRename = vm::renameBank,
                    onDelete = vm::deleteActiveBank,
                )
                1 -> QuizScreen(
                    round = state.round,
                    hasBank = state.activeBankId != null,
                    onStart = vm::startRound,
                    onPick = vm::pick,
                    onPrev = vm::prevQuestion,
                    onNext = vm::nextQuestion,
                    onMode = vm::setMode,
                    onSubmit = vm::submit,
                    onNextRound = vm::nextRound,
                )
                2 -> ProgressScreen(
                    bank = state.banks.firstOrNull { it.bankId == state.activeBankId },
                    progress = state.progress,
                    history = state.history,
                )
                else -> SettingsScreen(
                    backups = state.backups,
                    onExport = vm::requestExport,
                    onBackupClick = vm::onBackupClicked,
                    onOpenHelp = { showHelp = true },
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
    onImport: () -> Unit,
    onSelect: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
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
                    onRename(bank.bankId, name)
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除题库") },
            text = { Text("将删除该题库及其答题记录与进度，此操作无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
            },
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
        if (banks.isEmpty()) {
            Text(
                "还没有题库，点「导入」开始",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(banks, key = { it.bankId }) { bank ->
                    val active = bank.bankId == activeId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
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
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (!active) {
                                    OutlinedButton(onClick = { onSelect(bank.bankId) }) {
                                        Text("设为当前")
                                    }
                                }
                                TextButton(onClick = {
                                    renameTarget = bank
                                    renameInput = bank.sourceFileName
                                }) { Text("重命名") }
                                if (active) {
                                    TextButton(onClick = { confirmingDelete = true }) { Text("删除") }
                                }
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
    onStart: (Int) -> Unit,
    onPick: (Long, String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onMode: (QuizMode) -> Unit,
    onSubmit: () -> Unit,
    onNextRound: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("刷题", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        if (round == null) {
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
            Text("第 ${round.roundNo} 轮 · ${round.questions.size} 题")
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
                Text("${round.index + 1} / ${round.questions.size}", style = MaterialTheme.typography.bodySmall)
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
                        Text("${index + 1} / ${round.questions.size}", style = MaterialTheme.typography.bodySmall)
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
private fun SettingsScreen(
    backups: List<BackupFile>,
    onExport: () -> Unit,
    onBackupClick: (BackupFile) -> Unit,
    onOpenHelp: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onExport) { Text("导出备份") }
        Spacer(Modifier.height(12.dp))
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
