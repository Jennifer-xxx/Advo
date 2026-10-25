package com.gemmahack.symptomdiary

enum class TalkRole { PATIENT, ADVO }
enum class TalkPhase { IDLE, STARTING, LISTENING, THINKING, SPEAKING, PAUSED, FINISHING, FINISHED }
data class TalkTurn(val id: String = newId(), val role: TalkRole, val text: String, val at: String = now())
data class TalkSession(val id: String = newId(), val sourceId: String = newId(), val createdAt: String = now(),
    val turns: List<TalkTurn> = emptyList(), val finishedAt: String? = null)

/** Conversation text and the patient's dictation are deliberately different: assistant words are never patient evidence. */
object TalkRules {
    fun append(visit: Visit, sessionId: String, turn: TalkTurn): Visit {
        val session = visit.talks.first { it.id == sessionId }
        if (session.finishedAt != null || session.turns.any { it.id == turn.id } || turn.text.isBlank()) return visit
        val updated = session.copy(turns = session.turns + turn)
        val words = updated.turns.filter { it.role == TalkRole.PATIENT }.joinToString("\n\n") { it.text }
        return visit.copy(talks = visit.talks.map { if (it.id == sessionId) updated else it },
            sources = visit.sources.map { if (it.id == session.sourceId) it.copy(text = words, originalText = words,
                status = Processing.IDLE, summary = "", summaryStatus = Processing.IDLE) else it },
            title = if (visit.title == "New visit" && turn.role == TalkRole.PATIENT) turn.text.take(70).replace('\n', ' ') else visit.title)
    }

    fun finish(visit: Visit, sessionId: String): Visit = visit.copy(talks = visit.talks.map {
        if (it.id == sessionId && it.finishedAt == null) it.copy(finishedAt = now()) else it
    })

    fun isDoneRequest(text: String): Boolean = text.lowercase().trim().trimEnd('.', '!') in setOf(
        "i'm done", "i am done", "that's all", "that is all", "finish conversation", "end conversation", "save my summary")
}

/** Tokens invalidate late speech/model callbacks on pause, navigation, backgrounding or a new session. */
class TalkGuard {
    private var generation = 0
    var active = false; private set
    fun start(): Int { active = true; return ++generation }
    fun stop() { active = false; generation++ }
    fun accepts(token: Int): Boolean = active && token == generation
}
