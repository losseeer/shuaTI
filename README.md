# shuaTI

本机运行的安卓刷题应用。导入 JSON 题库后按轮次练习，记录断点与历史，可把全部数据导出成单个文件备份。

## 功能

- **题库导入**：从本机文件或微信 / QQ「用其他应用打开」导入 JSON 题库。任一行不合法即整份拒绝，不写入半份数据。
- **刷题**：每轮可选 5 / 10 / 15 / 20 题；支持「一页一题」与「一页多题」两种模式；交卷后显示每题对错与正确数。
- **未作答确认**：有题未答时先确认，确认后该题计为答错。
- **进度**：一轮一存。交卷后记录断点与已完成轮次，下次从下一轮开始，历史轮次可回看。
- **多题库**：可导入多份题库、指定当前题库、重命名（重名自动加序号）、删除（二次确认，连带清除进度）。
- **备份**：导出为单个 JSON 文件；还原时新增题库，不覆盖现有数据。

## 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | Platform 35、Build-Tools 35.0.0 |
| 最低运行版本 | Android 8.0（API 26） |
| 目标版本 | Android 15（API 35） |

## 构建

```bash
# 指定 JDK 17 与 SDK 路径
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

安装到已开启 USB 调试的设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

单元测试覆盖导入解析、判分规则、备份编解码、命名规则，共 31 个用例。

`app/src/androidTest` 下另有全链路仪器测试（导入 → 刷题 → 交卷 → 导出 → 还原），需要设备或模拟器：

```bash
./gradlew :app:connectedDebugAndroidTest
```

> 部分国产 ROM（如 MIUI / HyperOS）会拦截测试包安装与 shell 注入触摸事件，仪器测试可能无法执行。

## 题库格式

支持两种顶层结构：对象或数组。

```json
{
  "schema_version": 1,
  "questions": [
    {
      "stem": "1 + 1 = ?",
      "option_a": "1",
      "option_b": "2",
      "option_c": "3",
      "option_d": "4",
      "answer": "B"
    }
  ]
}
```

也可以直接是数组：

```json
[
  {
    "stem": "1 + 1 = ?",
    "option_a": "1",
    "option_b": "2",
    "option_c": "3",
    "option_d": "4",
    "answer": "B"
  }
]
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `stem` | 是 | 题面，不可为空 |
| `option_a` ~ `option_d` | 是 | 四个选项，不可为空 |
| `answer` | 是 | 仅接受 `A` / `B` / `C` / `D`，大小写不敏感 |
| `schema_version` | 否 | 仅对象形式可用，缺省为 1 |

校验规则：

- 任一题缺字段、字段为空、或答案不在 A–D 内，整份文件拒绝导入，不写入任何题目。
- 来源文件名已存在时提示「来源文件已存在」，不自动覆盖。
- 同一文件 3 秒内重复触发只执行一次。

## 备份

导出得到单个 JSON 文件，包含所有题库、答题记录与进度。文件名默认「题库名-备份N.json」，可在系统保存对话框里修改。

还原不覆盖现有数据：备份里的每个题库都作为新题库插入，重名时加「(还原)」后缀，因此可以用「导出 → 再导入」验证备份是否可用。

备份文件由用户保管，应用只记录文件名与导出时间。文件被删除后，点击对应记录会提示并移除这条失效记录。

## 关于微信 / QQ 的聊天文件

聊天文件位于应用私有目录，系统文件选择器读不到。请在该文件上选择「用其他应用打开」→ shuaTI，应用会直接进入导入流程。

## 目录结构

```
app/src/main/java/com/shua/ti/
├── MainActivity.kt             入口，接收外部分享的文件
├── data/
│   ├── AppDatabase.kt          Room 数据库与迁移
│   ├── entity/                 QuestionBank / Question / AnswerRecord / PracticeProgress / BackupFile
│   └── dao/                    对应 DAO
├── domain/
│   ├── QuestionBankParser.kt   导入解析与校验（纯函数）
│   ├── QuestionBankImporter.kt 事务写入、重复检测、防抖
│   ├── QuizGrader.kt           判分（纯函数）
│   ├── QuizEngine.kt           开轮、交卷、进度写入
│   ├── BackupCodec.kt          备份编解码（纯函数）
│   ├── BackupService.kt        导出聚合、还原
│   └── BankNames.kt            命名与重名处理（纯函数）
└── ui/
    ├── App.kt                  四个 Tab 界面
    └── AppViewModel.kt         状态与业务编排
```

## 技术栈

Kotlin 2.0.21 · Jetpack Compose（BOM 2024.10.01）· Room 2.6.1 · kotlinx.serialization 1.7.3 · AGP 8.7.2
