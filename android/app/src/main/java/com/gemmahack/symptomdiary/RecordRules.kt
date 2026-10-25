package com.gemmahack.symptomdiary

/** Daily symptom entries and real appointments are distinct, even though they share encrypted storage primitives. */
object RecordRules {
    fun symptoms(store: HealthStore): List<Visit> = store.visits.filter { it.kind == RecordKind.SYMPTOM &&
        it.sources.any { source -> source.text.isNotBlank() || source.blob != null } }
    fun visits(store: HealthStore): List<Visit> = store.visits.filter { it.kind == RecordKind.VISIT }
    fun selected(store: HealthStore, ids: List<String>): List<Visit> {
        val records = symptoms(store).filter { it.id in ids }
        require(records.size == ids.distinct().size) { "A selected symptom record is no longer available. Choose your records again." }
        return records.sortedBy { it.createdAt }
    }
    fun fingerprint(records: List<Visit>): String = stableId(*records.sortedBy { it.id }.map {
        "${it.id}:${it.updatedAt}:${it.sources.joinToString { source -> source.text }}"
    }.toTypedArray())
    fun context(visit: Visit, records: List<Visit>): Visit = visit.copy(
        sources = (visit.sources + records.flatMap { it.sources }).distinctBy { it.id },
        facts = (visit.facts + records.flatMap { it.currentFacts }).distinctBy { it.id },
        questions = (visit.questions + records.flatMap { it.questions.filter { q -> q.selected } }).distinctBy { it.id },
        generations = (visit.generations + records.flatMap { it.generations }).distinctBy { it.id },
    )
}
