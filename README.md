# 症状日记 · Gemma 端侧 starter

慢性病症状记录助手：用户用自己的话（打字或语音）描述不舒服，Gemma 4 在手机上把它整理成固定字段，代码决定追问什么，最后生成一页给医生看的报告。全部推理在手机上完成，app 没有联网权限。

```
gemma-symptom-diary/
├── shared/                 两边共用的一份内容（改这里，Python 和 app 同时生效）
│   ├── prompts/            抽取、追问改写、报告陈述、语音转写的 prompt
│   ├── knowledge/          追问问题、病种伴随症状、危险信号（必须人工核对）
│   ├── eval/               36 条中英文抽取测试句
│   └── demo/               14 天合成演示数据（python/make_demo_data.py 生成）
├── python/                 在 Mac 上迭代 prompt、跑评测、预览报告（只用于开发，不是 demo）
└── android/                Kotlin + Jetpack Compose + LiteRT-LM 的 app
```

## 1. Python：在 Mac 上迭代（不需要装任何 pip 包）

先在 Mac 上起一个本地模型服务（二选一）。LiteRT-LM 和手机上用的是同一个 `.litertlm` 文件，评测结果更接近手机。

```bash
# A. LiteRT-LM（推荐）
uv tool install litert-lm
litert-lm serve --help            # 看清楚端口和加载模型的参数，再启动

# B. llama.cpp
llama-server -hf unsloth/gemma-4-E2B-it-qat-GGUF:UD-Q4_K_XL --port 8080
```

```bash
cd python
python3 tests/test_core.py                         # 不需要模型，确认代码没问题
export GEMMA_BASE_URL=http://localhost:8080/v1     # 换成你的服务端口
python3 gemma_client.py                            # 冒烟测试
python3 eval_extraction.py --tag e2b               # 跑 36 条评测；换 E4B 后用 --tag e4b 再跑一次
python3 interview.py                               # 在终端里走一遍完整追问
python3 report.py --lang en --out report_en.html   # 用演示数据生成英文报告
```

评测输出每个字段的准确率或 F1、幻觉数（没说却填了）和漏填数，结果存在 `python/results/`。测试集格式写在 `eval_extraction.py` 开头。

## 2. Android：第一次跑起来

1. 装最新版 Android Studio，用 **File → Open** 打开 `android/` 文件夹，等 Gradle sync 完成（第一次会下载几 GB，务必在家做）。
   - 提示升级 Android Gradle Plugin：可以接受。
   - 报错说 Kotlin metadata 版本不兼容：把 `android/build.gradle.kts` 里两处 `2.3.0` 改成报错建议的版本。
   - 想在命令行编译：先 `brew install gradle`，在 `android/` 里跑一次 `gradle wrapper`，之后用 `./gradlew installDebug`。
2. 手机：设置 → 关于手机 → 连点「版本号」开启开发者选项 → 打开 USB 调试。Mac 上 `adb devices` 能看到手机。
3. 点 Run ▶ 装到手机并打开一次。状态栏会显示「找不到模型文件」，这是正常的，这一步只是为了创建 app 的文件夹。
4. 下载模型并推到手机：

   ```bash
   hf download litert-community/gemma-4-E2B-it-litert-lm gemma-4-E2B-it.litertlm --local-dir ~/gemma
   adb push ~/gemma/gemma-4-E2B-it.litertlm /sdcard/Android/data/com.gemmahack.symptomdiary/files/
   ```

5. 关掉 app 再打开，状态栏应显示「Gemma 已就绪 · GPU」（GPU 失败会自动退到 CPU）。
6. 冒烟测试：「历史」→ 加载演示数据 →「报告」→ 生成报告 → 导出 PDF；再到「记录」里打一句话，看追问是否出现。
7. 确认没有联网权限：Android Studio → Build → Analyze APK → 打开 `AndroidManifest.xml`，里面不应有 `INTERNET`。

## 3. 第一次 build 最可能遇到的问题

| 现象 | 处理 |
| --- | --- |
| `GemmaEngine.kt` 里 `Contents`、`Backend.CPU()`、`EngineConfig` 参数报 unresolved | 只改这一个文件。代码按 `litertlm-android:0.18.0` 的文档写，文件开头列了旧版本的写法；也可以对照[官方 Android 指南](https://ai.google.dev/edge/litert-lm/android)或 Gallery 源码 |
| 找不到 `litertlm-android:0.18.0` | 把 `android/app/build.gradle.kts` 里的版本改成 `latest.release`，再按上一行对照 API |
| Kotlin metadata 版本不兼容 | 提高 `android/build.gradle.kts` 里的 Kotlin 版本 |
| `checkAarMetadata` 要求更高的 compileSdk | 把 `compileSdk` 改成报错里的数字 |
| 「找不到模型文件」 | 先打开过一次 app；文件名必须是 `gemma-4-E2B-it.litertlm` 或 `gemma-4-E4B-it.litertlm` |
| 录音后崩溃或转写为空 | 看 Logcat 里 `GemmaEngine` 的报错；确认 `EngineConfig` 的 `audioBackend` 写法；不行就先只用文字输入 |
| 内存不足、app 被杀 | 用 E2B；关掉其他 app |
| JSON 经常不合法 | 先在 Mac 上用 `eval_extraction.py` 改 prompt；改完 `shared/prompts` 需要重新 build app |

遇到报错时，把 Logcat 或 Gradle 的完整报错贴给 Claude Code，在 `android/` 目录里让它直接改。

## 4. 想改什么，改哪里

| 想改 | 文件 |
| --- | --- |
| prompt | `shared/prompts/*.txt` |
| 追问问题、选项、追问顺序、最多几轮 | `shared/knowledge/symptom_knowledge.json` 的 `slot_questions`、`slot_order`、`max_followups` |
| 病种和伴随症状清单 | 同上，`conditions` |
| 危险信号 | 同上，`red_flags`（必须对照 MedlinePlus、NHS 等公开资料核对） |
| 评测集 | `shared/eval/extraction_testset.jsonl` |
| 演示数据 | `python/make_demo_data.py`，改完运行一次 |

Python 和 Kotlin 是一一对应的：`extraction.py` / `interview.py` ↔ `Interview.kt`，`report.py` ↔ `Report.kt`。改了一边的逻辑，记得同步另一边。

## 5. 语言和语音

- 右上角按钮切换整个 app 的语言（中文 / English）。切换后会开一条新记录，界面、追问、选项、语音识别都只用这一种语言。
- 报告页可以单独选报告语言。记录是另一种语言时，app 会先让 Gemma 把患者原话翻译过来；原话保留在括号里。
- 演示数据有中英文两套（`shared/demo/demo_entries.json` 和 `demo_entries_en.json`），按当前语言加载。
- 语音有两种引擎，在记录页底部切换：「手机离线识别」用 Android 自带的离线听写，中文更准；「Gemma 听写」直接把录音交给 Gemma。两种都不联网，理解、追问和报告始终由 Gemma 完成。手机离线识别第一次使用某个语言时可能要下载语音包，需要联网，请提前完成。

## 6. 已知限制

- Kotlin 代码还没有被编译过（写的时候没有 Android SDK），第一次 build 大概率要修几处 API 名字。Python 部分有单元测试，并用模拟的本地服务端到端跑通过。
- 危险信号是关键词规则加简单的否定判断（例如「没有发烧」不算），宁可多报。
- 记录存在 app 私有目录的一个 JSON 文件里，没有加密。
- 单段录音最长 30 秒。模型或手机不支持音频时，app 会自动关闭语音，只用文字。
- LiteRT-LM 有一个公开 issue（#3849）报告模型输出某些全角符号时原生层崩溃；中文输出时如果遇到闪退，先看 Logcat 是否是这个原因。
- 报告样式可以直接看 `docs/sample_report_zh.html` 和 `docs/sample_report_en.html`（演示数据，陈述段是占位文字）。
- 内容为演示用途，不是医疗器械，不做诊断。
