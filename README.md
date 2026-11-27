# shuaTI

[![CI](https://github.com/losseeer/shuaTI/actions/workflows/ci.yml/badge.svg)](https://github.com/losseeer/shuaTI/actions/workflows/ci.yml)

本机运行的安卓刷题应用。导入 JSON 题库后按轮次练习，记录断点与历史，可把全部数据导出成单个文件备份。

无网络请求，无账号，数据只存在设备本地。

## 功能

- 导入 JSON 题库，任一行不合法即整份拒绝，不写入半份数据
- 每轮 5 / 10 / 15 / 20 题，支持「一页一题」与「一页多题」
- 交卷后显示每题对错与正确数，未作答题需确认后计为答错
- 一轮一存，记录断点与已完成轮次，历史轮次可回看
- 多题库管理：指定当前题库、重命名、删除
- 备份导出为单个 JSON，还原时新增题库、不覆盖现有数据
- 支持从微信 / QQ 等应用「用其他应用打开」直接导入

## 快速开始

环境要求：JDK 17、Android SDK Platform 35 与 Build-Tools 35.0.0。

```bash
git clone https://github.com/losseeer/shuaTI.git
cd shuaTI

export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

运行单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

## 使用说明

题库格式、导入方式、刷题与备份的完整操作见 [USAGE.md](USAGE.md)。

## 技术栈

| 组件 | 版本 |
|---|---|
| Kotlin | 2.0.21 |
| Jetpack Compose | BOM 2024.10.01 |
| Room | 2.6.1 |
| kotlinx.serialization | 1.7.3 |
| Android Gradle Plugin | 8.7.2 |
| minSdk / targetSdk | 26 / 35 |

## 项目结构

```
app/src/main/java/com/shua/ti/
├── MainActivity.kt             入口，接收外部分享的文件
├── data/
│   ├── AppDatabase.kt          Room 数据库与迁移
│   ├── entity/                 五张业务表的实体
│   └── dao/                    对应 DAO
├── domain/
│   ├── QuestionBankParser.kt   导入解析与校验
│   ├── QuestionBankImporter.kt 事务写入、重复检测、防抖
│   ├── QuizGrader.kt           判分
│   ├── QuizEngine.kt           开轮、交卷、进度写入
│   ├── BackupCodec.kt          备份编解码
│   ├── BackupService.kt        导出与还原
│   └── BankNames.kt            命名与重名处理
└── ui/
    ├── App.kt                  四个 Tab 界面
    └── AppViewModel.kt         状态与业务编排
```

## 测试

单元测试 31 个用例，覆盖导入解析、判分规则、备份编解码、命名规则：

```bash
./gradlew :app:testDebugUnitTest
```

`app/src/androidTest` 下另有全链路仪器测试（导入 → 刷题 → 交卷 → 导出 → 还原），需要设备或模拟器：

```bash
./gradlew :app:connectedDebugAndroidTest
```

> 部分国产 ROM 会拦截测试包安装与 shell 注入触摸事件，仪器测试可能无法执行。

## 已知限制

- 仅支持四选一单选，不支持多选、判断、填空
- 微信 / QQ 聊天文件无法被系统文件选择器读取，需通过「用其他应用打开」导入
- 备份文件由用户保管，应用只记录文件名与导出时间

## 贡献

提交前请确保 `./gradlew :app:testDebugUnitTest` 通过，CI 会对每个 PR 跑同样的检查。
