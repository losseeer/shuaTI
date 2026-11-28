package com.shua.ti.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

private const val USAGE_URL = "https://github.com/losseeer/shuaTI/blob/main/USAGE.md"

/**
 * 复制给用户自己改的题库模板，只保留最小结构。
 */
private val QUESTION_TEMPLATE = """
{
  "questions": [
    {
      "stem": "把题干写在这里",
      "option_a": "选项 A",
      "option_b": "选项 B",
      "option_c": "选项 C",
      "option_d": "选项 D",
      "answer": "A",
      "explanation": "解析写在这里，不需要就删掉这一行"
    }
  ]
}
""".trimIndent()

@Composable
fun HelpScreen(onBack: () -> Unit, onNotify: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Spacer(Modifier.height(0.dp))
            Text("使用说明", style = MaterialTheme.typography.titleLarge)
        }

        HelpSection(
            title = "导入题库",
            body = "题库页点右上角「导入」，选一个 JSON 文件即可。\n\n" +
                "之前导出的备份文件也从这里选，会自动认出来并还原。\n\n" +
                "微信或 QQ 里收到的文件，先在聊天里点开，选「用其他应用打开」，再选 shuaTI，会直接导入。",
        )

        HelpSection(
            title = "开始刷题",
            body = "刷题页选每轮题数（5 / 10 / 15 / 20）就开始了。\n\n" +
                "「一页一题」适合专注，选完自动跳下一题，底部可以前后翻；「一页多题」能看到整轮全貌。两种模式随时切换，已选的答案不会丢。",
        )

        HelpSection(
            title = "交卷",
            body = "答完点「交卷」，会显示答对多少、每题对错和正确答案。\n\n" +
                "题库里带解析的题，解析会显示在对应题目下面；答题时看不到，交卷后才有。\n\n" +
                "有题没答时会先问你一次，确认后这些题算答错。结果立刻存下来，退出应用也不会丢。",
        )

        HelpSection(
            title = "回看与重刷",
            body = "「进度」页能看到已经完成几轮、下一轮从第几题开始，以及每轮答对多少。\n\n点任意一轮可以逐题回看：你的选择、正确答案和解析都在，还能只看错题。轮次详情里点「重刷这轮」可以用同样的题再练一遍，不影响顺序进度。",
        )

        HelpSection(
            title = "题库菜单与收藏",
            body = "题库页可以导入多份题库，随时切换当前题库。点任意题库会弹出菜单：重命名、管理、清空进度、删除。\n\n" +
                "答题页和回看里可以点「☆ 收藏」收藏题目。「错题与收藏题库」是常驻题库，自动聚合答错的题和你收藏的题——答对的错题会自动移出。把它设为当前就能刷错题和收藏。\n\n" +
                "「管理」可以逐题移除普通题库的题目；在错题与收藏题库里则可以把题移出错题集或取消收藏。\n\n" +
                "「清空进度」会清掉该题库的作答记录和进度，当前错题会自动收藏，不会丢。",
        )

        HelpSection(
            title = "备份",
            body = "「设置」页点「导出备份」，会生成一个文件，里面包含全部题库、答题记录、进度和收藏。\n\n" +
                "备份文件请自己保管好。点备份列表里的任意一条可以导入还原，还原不会覆盖现有题库。",
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                clipboard.setText(AnnotatedString(QUESTION_TEMPLATE))
                onNotify("题库格式已复制，粘贴到记事本里改成自己的题目")
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("一键复制题库格式")
        }

        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = {
                val opened = runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(USAGE_URL)),
                    )
                }.isSuccess
                if (!opened) {
                    onNotify("没有可用的浏览器，无法打开说明页面")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("查看完整说明")
        }

        Text(
            "完整说明包含题库格式的详细字段与校验规则。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun HelpSection(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
