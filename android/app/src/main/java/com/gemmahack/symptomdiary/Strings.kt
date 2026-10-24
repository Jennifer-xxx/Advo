package com.gemmahack.symptomdiary

/** Every user-facing string, in both languages. The app shows one language at a time. */
class Texts(val lang: String) {
    private val zh = lang == "zh"
    private fun t(zhText: String, enText: String) = if (zh) zhText else enText

    val listSep = t("、", ", ")
    val answerSep = t("；", "; ")

    // top bar
    val appTitle = t("症状日记 · 完全离线，无联网权限", "Symptom Diary · fully offline, no network access")
    val switchLang = t("English", "中文")
    val loading = t("正在加载模型…", "Loading model…")
    fun ready(backend: String, seconds: Double) =
        t("Gemma 已就绪 · %s · 加载 %.1f 秒", "Gemma ready · %s · loaded in %.1f s").format(backend, seconds)
    fun loadFailed(msg: String?) = t("模型加载失败：", "Model failed to load: ") + msg
    fun modelNotFound(paths: List<String>) =
        t("找不到模型文件。请用 adb push 到以下任一位置：\n", "Model file not found. Push it with adb to one of:\n") + paths.joinToString("\n")
    fun timing(kind: String, seconds: Double) = when (kind) {
        "asr" -> t("语音转写 %.1f 秒", "Transcription %.1f s")
        "report" -> t("报告 %.1f 秒", "Report %.1f s")
        else -> t("上次推理 %.1f 秒", "Last inference %.1f s")
    }.format(seconds)

    // tabs
    val tabRecord = t("记录", "Log")
    val tabHistory = t("历史", "History")
    val tabReport = t("报告", "Report")

    // record screen
    val greeting = t(
        "今天感觉怎么样？哪里不舒服，用自己的话说就行，说不清也没关系。",
        "How are you feeling today? Describe it in your own words. It's fine if it's hard to put into words.",
    )
    val inputHint = t("用自己的话描述，或点选上面的选项", "Describe it in your own words, or tap an option above")
    val send = t("发送", "Send")
    val speak = t("🎤 说出来", "🎤 Speak")
    val stop = t("⏹ 说完了", "⏹ Done")
    val skip = t("跳过", "Skip")
    val save = t("保存", "Save")
    val newEntry = t("新记录", "New entry")
    val thinking = t("Gemma 正在思考…", "Gemma is thinking…")
    val listening = t("正在听，说完点「说完了」", "Listening… tap Done when you finish")
    val complete = t("这条记录已经比较完整了。点「保存」存下来，或者继续补充。", "This entry looks complete. Tap Save, or keep adding details.")
    fun saved(line: String) = t("已保存：", "Saved: ") + line
    val withWord = t("伴 ", "with ")
    fun notReady() = t("模型还没就绪，请稍等", "The model isn't ready yet. Please wait.")
    fun error(msg: String?) = t("出错了：", "Something went wrong: ") + msg

    // voice
    val voiceLabel = t("语音识别：", "Voice: ")
    val engineSystem = t("手机离线识别", "Phone (offline)")
    val engineGemma = t("Gemma 听写", "Gemma")
    val systemUnavailable = t("这台手机没有离线语音识别，已切换到 Gemma 听写。", "This phone has no offline speech recognition. Switched to Gemma.")
    fun micError(msg: String?) = t("无法录音：", "Can't record: ") + msg
    val noAudio = t("没有录到声音，再试一次", "No sound was recorded. Try again.")
    val notHeard = t("没有听清，再说一次试试", "Didn't catch that. Try again.")
    fun packReady(tag: String) = t("离线语音包已就绪（$tag）", "Offline speech pack ready ($tag)")
    fun packDownloading(tag: String, pct: Int?) =
        t("正在下载离线语音包（$tag）", "Downloading offline speech pack ($tag)") + (pct?.let { " · $it%" } ?: "…")
    val packStillDownloading = t(
        "系统还在后台下载这个语言的语音包，稍后再点一次「说出来」。也可以先切到 Gemma 听写。",
        "The system is still downloading this language's pack. Tap Speak again in a bit, or switch to Gemma for now.",
    )
    val packDone = t("语音包下载完成，点「说出来」开始。", "Speech pack installed. Tap Speak to start.")
    val packScheduled = t(
        "系统已安排下载（可能要等连上 Wi-Fi 或充电时）。过一会儿再点「说出来」检查。",
        "The system scheduled the download (it may wait for Wi-Fi or charging). Tap Speak again later to check.",
    )
    fun packFailed(code: Int) = t("语音包下载失败（错误 $code），请先用 Gemma 听写。", "Speech pack download failed (error $code). Use Gemma for now.")
    val packUnsupported = t(
        "这台手机的离线识别不支持当前语言，已切换到 Gemma 听写。",
        "On-device recognition doesn't support this language on this phone. Switched to Gemma.",
    )

    // history
    val loadDemo = t("加载演示数据", "Load demo data")
    val clear = t("清空", "Clear")
    fun entryCount(n: Int) = t("$n 条记录 · 只保存在这台手机上", "$n entries · stored only on this phone")
    val demoTag = t(" · 演示", " · demo")

    // report
    fun days(n: Int) = t("$n 天", "$n days")
    val reportLanguage = t("报告语言", "Report language")
    val generate = t("生成报告", "Create report")
    val generating = t("生成中…", "Working…")
    val exportPdf = t("导出 PDF", "Export PDF")
    val narrativeLabel = t("病情陈述（可修改）", "Summary for the doctor (editable)")
    val questionsLabel = t("想问医生的问题（每行一个）", "Questions for the doctor (one per line)")
    val apply = t("应用修改", "Apply changes")
    val reportEmpty = t("先在「历史」里加载演示数据，或保存几条记录，再生成报告。", "Load the demo data in History, or save a few entries, then create a report.")
    val placeholderNarrative = t("（模型未就绪，这里是占位陈述）", "(Model not ready; placeholder summary.)")
}
