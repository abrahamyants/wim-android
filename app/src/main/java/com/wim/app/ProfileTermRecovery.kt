package com.wim.app

import org.apache.commons.codec.language.DoubleMetaphone

/**
 * Conservative Layer 3 recovery of profile corrections and vocabulary.
 *
 * Port of `lavrentiy/wim/api/profile_terms.py` (commit 65065e5) — the signed-in
 * backend already runs this; this port serves the device-direct path. Keep the
 * two in sync.
 *
 * ASR often returns a phonetic neighbor instead of the exact saved correction
 * key: `cloth -> Claude` is useless when the recognizer emits `count`.
 * Multiword preferred terms have the same problem: token-by-token matching
 * cannot recover `yellow flag -> YOLO flag`.
 *
 * This matches whole phrases with Double Metaphone. Approximate matching is
 * deliberately limited to explicit correction pairs inside ASR-marked
 * low-confidence text. Preferred vocabulary requires an exact phonetic match
 * unless the literal term is already present. A blocklist of common function
 * words (could, the, that, …) can never be replaced approximately — that is
 * what keeps `could` from becoming `Claude` mid-sentence.
 */
object ProfileTermRecovery {

    data class Match(val heard: String, val term: String, val kind: String) {
        override fun toString(): String = "\"$heard\" -> \"$term\" [$kind]"
    }

    // maxCodeLen 12 (not PhoneticMatcher's 4): whole phrases are encoded in
    // one call, and 4 characters of code cannot distinguish multiword terms.
    private val dm = DoubleMetaphone().apply { maxCodeLen = 12 }

    private val WORD_RE = Regex("""[\p{L}\p{N}]+(?:['’-][\p{L}\p{N}]+)?""")

    private val APPROXIMATE_BLOCKLIST = setOf(
        "a", "an", "and", "are", "be", "been", "being", "but", "can", "could",
        "did", "do", "does", "for", "from", "had", "has", "have", "he", "her",
        "him", "his", "i", "if", "in", "is", "it", "its", "may", "me", "might",
        "must", "my", "no", "not", "of", "on", "or", "our", "shall", "she",
        "should", "so", "that", "the", "their", "them", "they", "this", "those",
        "to", "us", "was", "we", "were", "will", "with", "would", "you", "your",
        // High-frequency CONTENT words. The list above is function words only,
        // which left every everyday verb open to phonetic capture by a profile
        // term. Measured on device 2026-07-29: "I need you to send the Henderson
        // invoice" came back as "I need you to Git the Henderson invoice" — a
        // vocabulary entry ate an ordinary verb, in the middle of a sentence
        // that had nothing to do with it. That is the same failure as
        // could→Claude, and the same fix.
        //
        // This only blocks APPROXIMATE matching. An exact match against a
        // vocabulary term still applies, so a user whose vocabulary genuinely
        // contains one of these words loses nothing.
        "get", "gets", "getting", "got", "give", "gives", "given", "go", "goes",
        "going", "gone", "went", "come", "comes", "coming", "came", "take",
        "takes", "taking", "took", "make", "makes", "making", "made", "need",
        "needs", "needed", "want", "wants", "wanted", "know", "knows", "knew",
        "think", "thinks", "thought", "say", "says", "said", "see", "sees",
        "saw", "seen", "look", "looks", "looking", "tell", "tells", "told",
        "ask", "asks", "asked", "work", "works", "working", "call", "calls",
        "called", "try", "tries", "tried", "put", "puts", "keep", "keeps",
        "let", "lets", "help", "helps", "show", "shows", "run", "runs",
        "move", "moves", "hold", "holds", "bring", "brings", "write", "writes",
        "send", "sends", "sent", "sending", "read", "reads", "find", "finds",
        "found", "use", "uses", "used", "mean", "means", "meant", "set",
        "sets", "start", "starts", "stop", "stops", "here", "there", "then",
        "than", "when", "what", "who", "how", "why", "where", "all", "any",
        "just", "like", "now", "only", "out", "over", "some", "such", "up",
        "very", "well", "back", "down", "off", "one", "two", "day", "days",
        "time", "thing", "things", "good", "new", "old", "same", "next",
        "last", "first", "about", "after", "before", "again", "still",
    )

    private fun normalizedWords(text: String): String =
        WORD_RE.findAll(text).joinToString(" ") { it.value.lowercase() }

    private fun codes(text: String): Set<String> {
        val out = mutableSetOf<String>()
        try {
            dm.doubleMetaphone(text)?.let { out.add(it) }
            dm.doubleMetaphone(text, true)?.let { out.add(it) }
        } catch (_: Exception) {
            return emptySet()
        }
        return out.map { it.uppercase().replace(Regex("[^A-Z0-9]"), "") }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** Whether two short phonetic codes differ by at most one edit. */
    internal fun oneEditOrLess(leftIn: String, rightIn: String): Boolean {
        if (leftIn == rightIn) return true
        if (kotlin.math.abs(leftIn.length - rightIn.length) > 1) return false
        var left = leftIn
        var right = rightIn
        if (left.length > right.length) {
            val t = left; left = right; right = t
        }
        var i = 0; var j = 0; var edits = 0
        while (i < left.length && j < right.length) {
            if (left[i] == right[j]) { i++; j++; continue }
            edits++
            if (edits > 1) return false
            if (left.length == right.length) i++
            j++
        }
        if (i < left.length || j < right.length) edits++
        return edits <= 1
    }

    private fun phoneticRelation(
        heard: String,
        probes: List<String>,
        allowApproximate: Boolean,
    ): String? {
        val heardCodes = codes(heard)
        if (heardCodes.isEmpty()) return null
        val probeCodes = probes.flatMap { codes(it) }.toSet()
        if (probeCodes.isEmpty()) return null
        if (heardCodes.intersect(probeCodes).isNotEmpty()) return "phonetic"
        if (!allowApproximate) return null
        for (heardCode in heardCodes) {
            for (probeCode in probeCodes) {
                if (heardCode.length >= 3 && probeCode.length >= 3 &&
                    heardCode.first() == probeCode.first() &&
                    heardCode.last() == probeCode.last() &&
                    oneEditOrLess(heardCode, probeCode)
                ) {
                    return "approximate_phonetic"
                }
            }
        }
        return null
    }

    private fun isLowConfidencePhrase(heard: String, lowConfTexts: List<String>): Boolean {
        val heardNorm = normalizedWords(heard)
        if (heardNorm.isBlank()) return false
        return lowConfTexts.any { it.isNotBlank() && heardNorm in normalizedWords(it) }
    }

    private fun isDistinctiveVocabulary(term: String): Boolean =
        term.any { it.isUpperCase() } ||
            " " in term ||
            term.any { !(it.isLetterOrDigit() || it.isWhitespace()) }

    private fun replaceExactCorrections(
        text: String,
        corrections: Map<String, String>,
        matches: MutableList<Match>,
    ): String {
        var result = text
        for ((wrongRaw, rightRaw) in corrections) {
            val wrong = wrongRaw.trim()
            val right = rightRaw.trim()
            if (wrong.isBlank() || right.isBlank() || wrong.lowercase() == right.lowercase()) continue
            val pattern = Regex(
                """(?<!\w)""" + Regex.escape(wrong) + """(?!\w)""",
                RegexOption.IGNORE_CASE,
            )
            if (pattern.containsMatchIn(result)) {
                matches.add(Match(heard = wrong, term = right, kind = "exact_correction"))
                result = pattern.replace(result, Regex.escapeReplacement(right))
            }
        }
        return result
    }

    private fun replaceCandidate(
        text: String,
        target: String,
        probes: List<String>,
        kind: String,
        lowConfTexts: List<String>,
    ): Pair<String, List<Match>> {
        // Plain List operations only — SortedSet.reversed() is a JDK 21
        // member that shadows the Kotlin extension and NoSuchMethodErrors
        // on the JDK 17 CI runner (and is absent from android.jar).
        val wordCounts = ( listOf(target) + probes )
            .filter { it.isNotBlank() }
            .map { WORD_RE.findAll(it).count() }
            .distinct()
            .sortedDescending()
        val tokens = WORD_RE.findAll(text).toList()
        data class Repl(val start: Int, val end: Int, val target: String, val heard: String, val relation: String)
        val replacements = mutableListOf<Repl>()
        val occupied = mutableSetOf<Int>()

        for (count in wordCounts) {
            if (count <= 0) continue
            for (index in 0..(tokens.size - count)) {
                val tokenIndexes = (index until index + count).toSet()
                if (occupied.intersect(tokenIndexes).isNotEmpty()) continue
                val start = tokens[index].range.first
                val end = tokens[index + count - 1].range.last + 1
                val heard = text.substring(start, end)
                val heardNorm = normalizedWords(heard)
                if (heardNorm.isBlank() || heardNorm == normalizedWords(target)) continue

                val uncertain = isLowConfidencePhrase(heard, lowConfTexts)

                // Ordinary English words are never phonetic-recovery targets,
                // on EITHER path. The blocklist used to guard corrections only,
                // on the theory that vocabulary needs an exact phonetic-code
                // match and therefore can't misfire. It can: Double Metaphone
                // maps "get" and "Git" to the same code (KT), and "could" and
                // "Claude" to the same code (KLT). So an exact match was enough
                // to swap a common verb for a profile term. Measured on device
                // 2026-07-29 — "send the Henderson invoice" came back as "Git
                // the Henderson invoice".
                //
                // Explicitly saved corrections still apply: replaceExactCorrections
                // runs before this loop and is a literal match, not phonetic.
                if (heardNorm in APPROXIMATE_BLOCKLIST) continue

                val relation: String?
                if (kind == "correction") {
                    if (!uncertain) continue
                    relation = phoneticRelation(heard, probes, allowApproximate = true)
                } else {
                    if (!(uncertain || isDistinctiveVocabulary(target))) continue
                    relation = phoneticRelation(heard, probes, allowApproximate = false)
                }
                if (relation == null) continue

                replacements.add(Repl(start, end, target, heard, relation))
                occupied.addAll(tokenIndexes)
            }
        }

        if (replacements.isEmpty()) return text to emptyList()

        var result = text
        val applied = mutableListOf<Match>()
        for (r in replacements.sortedByDescending { it.start }) {
            result = result.substring(0, r.start) + r.target + result.substring(r.end)
            applied.add(Match(heard = r.heard, term = r.target, kind = "${kind}_${r.relation}"))
        }
        applied.reverse()
        return result to applied
    }

    /**
     * Returns (correctedText, matches) for Layer 3 profile terms.
     *
     * Order of authority (spec 2026-07-29): exact saved corrections first,
     * then phonetic candidates with explicit corrections outranking
     * vocabulary and longer phrases outranking shorter ones.
     */
    fun apply(
        text: String,
        corrections: Map<String, String>,
        vocabulary: List<String>,
        lowConfTexts: List<String> = emptyList(),
    ): Pair<String, List<Match>> {
        if (text.isBlank()) return text to emptyList()
        val matches = mutableListOf<Match>()
        var result = replaceExactCorrections(text, corrections, matches)

        data class Candidate(val kind: String, val target: String, val probes: List<String>)
        val candidates = mutableListOf<Candidate>()
        for ((wrongRaw, rightRaw) in corrections) {
            val wrong = wrongRaw.trim()
            val right = rightRaw.trim()
            if (wrong.isNotBlank() && right.isNotBlank()) {
                candidates.add(Candidate("correction", right, listOf(wrong, right)))
            }
        }
        for (termRaw in vocabulary) {
            val term = termRaw.trim()
            if (term.isNotBlank()) {
                candidates.add(Candidate("vocabulary", term, listOf(term)))
            }
        }

        // Explicit corrections win ambiguous matches. Longer vocabulary
        // phrases run before shorter ones so "YOLO flag" is one preferred term.
        candidates.sortWith(
            compareBy(
                { if (it.kind == "correction") 0 else 1 },
                { -WORD_RE.findAll(it.target).count() },
            )
        )
        val seenTargets = mutableSetOf<String>()
        for (candidate in candidates) {
            val targetKey = candidate.target.lowercase()
            if (targetKey in seenTargets) continue
            seenTargets.add(targetKey)
            val (updated, applied) = replaceCandidate(
                result, candidate.target, candidate.probes, candidate.kind, lowConfTexts
            )
            result = updated
            matches.addAll(applied)
        }
        return result to matches
    }
}
