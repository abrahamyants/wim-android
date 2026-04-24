package com.wim.app

import org.json.JSONObject

/**
 * Language-aware accessors for the L4 speech-disfluency reconstruction prompt.
 *
 * Lang pack JSON lives at `app/src/main/assets/lang_packs/{code}.json` and mirrors
 * `lavrentiy/lang_packs/{code}.json`. The two sets must stay in sync.
 *
 * English is treated as the implicit baseline — the historical L4 prompt was
 * written for English, so there is no `en.json`. Accessors return the English
 * defaults hardcoded here when the lang code normalizes to "en" or is unknown.
 */
object LangPackHelper {

    // Lazy, lockless cache: JSON loaded once per language code per process
    @Volatile private var packCache: Map<String, JSONObject?> = emptyMap()

    /** Normalize "en-US" → "en", "es-ES" → "es", "zh-Hans-CN" → "zh". */
    fun normalizeLangCode(raw: String?): String {
        if (raw.isNullOrBlank()) return "en"
        val head = raw.trim().substringBefore('-').substringBefore('_').lowercase()
        return if (head.isBlank()) "en" else head
    }

    private fun loadPack(code: String): JSONObject? {
        val normalized = normalizeLangCode(code)
        packCache[normalized]?.let { return it }
        // Not cached. Attempt load. English never has a pack.
        if (normalized == "en") {
            packCache = packCache + (normalized to null)
            return null
        }
        val loaded = try {
            WimApp.instance.assets.open("lang_packs/$normalized.json").use { stream ->
                JSONObject(stream.bufferedReader().readText())
            }
        } catch (_: Throwable) {
            null
        }
        packCache = packCache + (normalized to loaded)
        return loaded
    }

    private fun pack(code: String): JSONObject? = loadPack(code)

    private fun stringArray(pack: JSONObject?, key: String): List<String> {
        if (pack == null) return emptyList()
        val arr = pack.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
    }

    /** Human-friendly language name used in the prompt. Falls back to the code. */
    fun languageName(code: String): String {
        val normalized = normalizeLangCode(code)
        if (normalized == "en") return "English"
        return pack(normalized)?.optString("language_name_en")?.ifBlank { normalized } ?: normalized
    }

    /** Fillers to strip for this language. English returns empty list (baseline handles it). */
    fun getLangFillers(code: String): List<String> = stringArray(pack(code), "fillers")

    /** Natural emphatic reduplications that must NOT be collapsed. */
    fun getLangNaturalRepeats(code: String): List<String> = stringArray(pack(code), "natural_repeats")

    /**
     * Hard-onset → difficulty-weight map for this language.
     * Strips slashes from pack keys ("/p/" → "p") to match [PhoneticRisk.extractOnset] output.
     * Returns empty when `hard_onsets.quality_note == "unavailable"` (zh/hi/ar/ko) or lang unknown.
     */
    fun getLangHardOnsets(code: String): Map<String, Double> {
        val p = pack(code) ?: return emptyMap()
        val ho = p.optJSONObject("hard_onsets") ?: return emptyMap()
        val quality = ho.optString("quality_note", "")
        if (quality == "unavailable") return emptyMap()
        val data = ho.optJSONArray("data") ?: return emptyMap()
        val out = mutableMapOf<String, Double>()
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val onset = entry.optString("onset").trim().trim('/').lowercase()
            val weight = entry.optDouble("difficulty_weight", 0.0)
            if (onset.isNotBlank() && weight > 0.0) out[onset] = weight
        }
        return out
    }

    /** True for languages whose lang pack flags onset research as unavailable (zh, hi, ar, ko). */
    fun hasNoOnsetResearch(code: String): Boolean {
        val normalized = normalizeLangCode(code)
        if (normalized == "en") return false
        val ho = pack(normalized)?.optJSONObject("hard_onsets") ?: return false
        return ho.optString("quality_note", "") == "unavailable"
    }

    // ── Per-language prompt fragments — sourced from memo Section 4.2 / 4.3 ─────────

    /** Example of part-word repetition rendered in-language. */
    fun getLangPartWordExample(code: String): String = when (normalizeLangCode(code)) {
        "es" -> "'p-p-pues entonces qu-qu-quiero ir al trabajo' → 'Pues, quiero ir al trabajo'"
        "fr" -> "'P-p-peut-être qu'on devrait...' → 'Peut-être qu'on devrait...'"
        "de" -> "'P-p-Peter hat mir gesagt...' → 'Peter hat mir gesagt...'"
        "it" -> "'P-p-posso andare a-a-adesso?' → 'Posso andare adesso?'"
        "pt" -> "'P-p-posso te falar a-a-agora?' → 'Posso te falar agora?'"
        "ja" -> "'か-か-かく に つ い て は...' → 'かくについては...' (mora-level repetition)"
        "zh" -> "'我-我-我 想 去' → '我想去'"
        "hi" -> "'म-म-मैं जाना चाहता हूँ' → 'मैं जाना चाहता हूँ'"
        "ar" -> "'ب-ب-بدي أروح' → 'بدي أروح'"
        "ko" -> "'ㄱ-ㄱ-가고 싶어요' → '가고 싶어요'"
        else -> "'b-b-b-buy' → 'buy', 'Ca-ca-ca-can' → 'Can'"
    }

    /** Example of prolongation rendered in-language. */
    fun getLangProlongationExample(code: String): String = when (normalizeLangCode(code)) {
        "es" -> "'Sssssseñor, necesito...' → 'Señor, necesito...'"
        "fr" -> "'Ssssalut, je voulais...' → 'Salut, je voulais...'"
        "de" -> "'Mmmmontag kommt er zurück' → 'Montag kommt er zurück'"
        "it" -> "'Mmmmmamma mia' → 'Mamma mia'"
        "pt" -> "'Sssssenhor, preciso...' → 'Senhor, preciso...'"
        "ja" -> "prolonged initial mora: 'かーーーく' → 'かく'"
        else -> "'mmmmaybe' → 'maybe', 'Sssssscience' → 'Science'"
    }

    /**
     * Epenthetic-vowel note: in many languages, a failed onset cluster inserts a
     * language-specific transitional vowel (Spanish /e/, Hindi short /a/) — not the
     * English schwa /ə/. Empty string means the baseline schwa rule applies.
     */
    fun getLangEpenthesisNote(code: String): String = when (normalizeLangCode(code)) {
        "es" -> "Spanish epenthetic /e/ during blocked clusters (e.g. 'p-eh-lanta' for 'planta', 't-eh-rabajo' for 'trabajo') — NOT English schwa /ə/"
        "fr" -> "French epenthetic /ə/ during blocked clusters, but this is the French 'e muet', not the neutral English schwa"
        "de" -> "German epenthetic /ə/ possible at morpheme boundaries in compounds"
        "it" -> "Italian may insert a transitional /i/ or /e/ at blocked clusters"
        "pt" -> "Portuguese may insert a transitional /i/ (BP) or /ə/ (EP) at blocked clusters"
        "hi" -> "Hindi: inherent /a/ vowel of Devanagari consonants can be extended during blocks"
        else -> "schwa substitution: 'guh-guh-goat' → 'goat' (neutral /ə/ inserted in repeated clusters)"
    }

    /**
     * Dialect/register avoidance note — speakers may shift register to avoid a feared
     * onset. Marked speculative in the memo for DE/JA/KO; omitted for languages without
     * such a register distinction.
     */
    fun getLangDialectAvoidanceNote(code: String): String = when (normalizeLangCode(code)) {
        "es" -> "- Spanish dialect avoidance: if speaker uses 'vos tenés' but context suggests 'tú tienes', they may be using voseo to avoid the /t/ onset. Do NOT reconstruct voseo into tuteo — preserve the speaker's dialect."
        "fr" -> "- French tu/vous register: an unexpected vous-form when tu is established may be avoidance of a /t/ onset. Do NOT reconstruct vous into tu."
        "de" -> "- German du/Sie register: an unexpected Sie-form when du is established MAY indicate avoidance of a /d/ onset (speculative — not established in German stuttering literature). Preserve the speaker's chosen form."
        "ja" -> "- Japanese casual/keigo register: an unexpected keigo form on a content word MAY indicate avoidance of a feared initial consonant (speculative — not established in Japanese stuttering literature). Preserve the speaker's chosen form."
        "ko" -> "- Korean 반말/존댓말 register: an unexpected register shift MAY indicate phonemic avoidance (speculative — not established in Korean stuttering literature). Preserve the speaker's chosen form."
        "ar" -> "- Arabic diglossia: an unexpected colloquial→MSA (عامية→فصحى) shift on a specific word MAY indicate register avoidance (speculative — not established in Arabic stuttering literature). Preserve the speaker's chosen register."
        else -> ""
    }

    /**
     * Prosodic-timing caveat: pre-word pauses carry different diagnostic weight in
     * stress-timed (English, German) vs syllable-timed (Spanish, French, Italian,
     * Portuguese) vs mora-timed (Japanese) languages.
     */
    fun getLangSyllableTimingNote(code: String): String = when (normalizeLangCode(code)) {
        "es", "fr", "it", "pt" -> "- ${languageName(code)} is syllable-timed — anticipatory pauses are less prosodically marked than in English. Require both a filler cluster AND a hard-onset match before treating a pause as anticipatory."
        "de" -> "- German is stress-timed — anticipatory pauses have predictive weight similar to English."
        "ja" -> "- Japanese is mora-timed — stuttering occurs at mora boundaries, not English-style word-initial position."
        else -> ""
    }

    /** Language-specific Whisper corruption artifact to watch for. */
    fun getLangEpenthesisCorruptionNote(code: String): String = when (normalizeLangCode(code)) {
        "es" -> "EPENTHETIC VOWEL CORRUPTION: blocked clusters (/tr/, /pl/, /pr/) → Whisper may transcribe 'p-eh-lanta' as 'Pelanta', 't-eh-rabajo' as 'Terabajo' — these are hallucinations of epenthesis, reconstruct to the target word"
        "fr" -> "LIAISON HALLUCINATION: Whisper may insert liaison consonants mid-block (e.g. transcribing a /t/ the speaker was blocked on as the onset of the next vowel-initial word)"
        "de" -> "COMPOUND BOUNDARY CORRUPTION: blocks at morpheme boundaries of long compounds (e.g. 'Arbeits…losigkeit') may be transcribed as two separate words or as a different compound"
        "it" -> "GEMINATE CONFUSION: stuttered /p/ may be transcribed as the Italian geminate /pp/ ('la papa' vs. 'la pappa') — geminates are phonemic, verify against context"
        "pt" -> "EPENTHETIC /i/ CORRUPTION: blocked clusters in BP may be transcribed with an intrusive /i/ (e.g. 'p-i-lanta' for 'planta')"
        "ja" -> "MORA CORRUPTION: repeated moras ('か-か-かく') may collapse into a single mora or a different katakana/hiragana character"
        "zh" -> "TONE CORRUPTION: repeated syllables in Mandarin may be transcribed with a wrong tone, producing a different word (same pinyin, different character)"
        else -> "SCHWA CORRUPTION: neutral vowel in repeated clusters → transcribed as a real word (e.g. 'buh-buh-blue' → 'but but blue' or 'above blue')"
    }

    /** Onset-reliability caveat. Only included when the language has cited onset data. */
    fun getLangOnsetCaveat(code: String): String = when (normalizeLangCode(code)) {
        "es" -> "Onset weights cite Howell et al. 2004 and are extrapolated from English aspiration data — may be slightly inflated for Spanish unaspirated plosives. Additionally, the trilled /rr/ (perro, ratón) is a high-frequency block site not captured in these weights."
        "fr" -> "Onset weights cite Dworzynski et al. 2003. French is unaspirated — treat with moderate extrapolation risk relative to English."
        "de" -> "Onset weights cite Natke et al. 2004. Only /p/ is documented as a primary hard onset in German — do NOT apply English-derived /t/ difficulty weighting."
        "it" -> "Onset weights cite Zmarich et al. 2004. Italian is unaspirated — moderate extrapolation risk."
        "pt" -> "Onset weights cite Juste et al. 2007, a pediatric study — adult onset weights are extrapolated; treat with lower confidence for adult users."
        "ja" -> "Onset weights cite Umezaki et al. 1999. /k/ is the documented hard onset; words beginning with か行 (ka, ki, ku, ke, ko) are elevated-risk."
        else -> ""
    }

    /** Compact 4-example few-shot block (memo Section 4.4). */
    fun getLangFewShotExamples(code: String): String = when (normalizeLangCode(code)) {
        "es" -> """
DISFLUENCY EXAMPLES (Spanish):
  REPETITION:  'P-p-pues este... quiero ir a la re-re-reunión'
  RESULT:      'Quiero ir a la reunión'

  EMPHATIC (PRESERVE):  'No no, eso no me parece bien'
  RESULT:      'No no, eso no me parece bien'

  WHISPER /rr/ BLOCK:  'Necesito el r-r-... el documento del er rrr... del jefe'
  RESULT:      'Necesito el documento del jefe'

  CIRCUMLOCUTION:  'Quiero hablar con el... con la persona que lleva los números'
  RESULT:      'Quiero hablar con el contador'  [if context makes this clear — else preserve circumlocution literally]
""".trimIndent()
        "fr" -> """
DISFLUENCY EXAMPLES (French):
  REPETITION:  'Je je je voulais te te te dire quelque chose'
  RESULT:      'Je voulais te dire quelque chose'

  EMPHATIC (PRESERVE):  'Non non, ce n'est pas correct'
  RESULT:      'Non non, ce n'est pas correct'

  BLOCK + LIAISON:  'Les... euh... [block]... petits enfants arrivent'
  RESULT:      'Les petits enfants arrivent'

  CIRCUMLOCUTION:  'J'ai besoin du... de la chose qu'on met sur la tête'
  RESULT:      'J'ai besoin du chapeau'  [if context makes this clear]
""".trimIndent()
        "de" -> """
DISFLUENCY EXAMPLES (German):
  REPETITION:  'P-p-Peter, ich brauche den Ber-ber-Bericht bis Freitag'
  RESULT:      'Peter, ich brauche den Bericht bis Freitag'

  EMPHATIC (PRESERVE):  'Nein nein, das ist nicht richtig'
  RESULT:      'Nein nein, das ist nicht richtig'

  COMPOUND BLOCK:  'Ich brauche die Ar-ar-Arbeits... die Bescheinigung'
  RESULT:      'Ich brauche die Arbeitsbescheinigung'

  WHISPER HALLUCINATION:  'Ich dachte dass... thank you... der Termin morgen ist'
  RESULT:      'Ich dachte, der Termin ist morgen'  [discard 'thank you' — Whisper hallucination]
""".trimIndent()
        "it" -> """
DISFLUENCY EXAMPLES (Italian):
  REPETITION:  'P-p-posso andare a-a-adesso?'
  RESULT:      'Posso andare adesso?'

  EMPHATIC (PRESERVE):  'Sì sì, è proprio così'
  RESULT:      'Sì sì, è proprio così'

  GEMINATE DISAMBIGUATION:  'La p-p-papa parla' (stuttered /p/)
  RESULT:      'Il papa parla'  [stuttered /p/, NOT the geminate 'pappa' (porridge)]

  CIRCUMLOCUTION:  'Ho bisogno del... di quella cosa per scrivere'
  RESULT:      'Ho bisogno della penna'  [if context is clear]
""".trimIndent()
        "pt" -> """
DISFLUENCY EXAMPLES (Portuguese):
  REPETITION:  'P-p-posso te falar a-a-agora sobre o pro-projeto?'
  RESULT:      'Posso te falar agora sobre o projeto?'

  EMPHATIC (PRESERVE):  'Não não, isso não está certo'
  RESULT:      'Não não, isso não está certo'

  DIALECT PRESERVATION:  'Vou pegar o ônibus' (BP) — do NOT normalize to 'autocarro' (EP)
  RESULT:      'Vou pegar o ônibus'

  CIRCUMLOCUTION:  'Preciso do... daquilo que a gente assina na entrada'
  RESULT:      'Preciso do formulário da entrada'
""".trimIndent()
        "ja" -> """
DISFLUENCY EXAMPLES (Japanese):
  MORA REPETITION:  'か-か-かく に つ い て は...'
  RESULT:      'かくについては...'

  EMPHATIC (PRESERVE):  'そうそう、それが正しいです'
  RESULT:      'そうそう、それが正しいです'

  /k/ BLOCK:  'きょう の... えーと... かいぎ は なんじ ですか'
  RESULT:      'きょうのかいぎはなんじですか'
""".trimIndent()
        "zh" -> """
DISFLUENCY EXAMPLES (Mandarin):
  REPETITION:  '我-我-我 想 去 开-开-开会'
  RESULT:      '我想去开会'

  EMPHATIC (PRESERVE):  '对对，这个就是正确的'
  RESULT:      '对对，这个就是正确的'

  ASPECTUAL REDUPLICATION (PRESERVE):  '我去看看' (take a look — NOT stutter)
  RESULT:      '我去看看'

  TONE-LEVEL SUBSTITUTION RISK: suspect single-syllable content-word substitutions
""".trimIndent()
        "hi" -> """
DISFLUENCY EXAMPLES (Hindi):
  REPETITION:  'म-म-मैं जाना चाहता हूँ'
  RESULT:      'मैं जाना चाहता हूँ'

  EMPHATIC (PRESERVE):  'नहीं नहीं, यह सही नहीं है'
  RESULT:      'नहीं नहीं, यह सही नहीं है'

  CIRCUMLOCUTION:  'मुझे वो... जो लिखने के लिए होता है... चाहिए'
  RESULT:      'मुझे पेन चाहिए'  [if context is clear]
""".trimIndent()
        "ar" -> """
DISFLUENCY EXAMPLES (Arabic):
  REPETITION:  'ب-ب-بدي أ-أ-أروح'
  RESULT:      'بدي أروح'

  EMPHATIC (PRESERVE):  'لا لا، هذا مش صحيح'
  RESULT:      'لا لا، هذا مش صحيح'

  WHISPER ENGLISH HALLUCINATION:  'كنت أفكر... thank you... في الموعد'
  RESULT:      'كنت أفكر في الموعد'  [discard 'thank you' — Whisper hallucination]
""".trimIndent()
        "ko" -> """
DISFLUENCY EXAMPLES (Korean):
  REPETITION:  '가-가-가고 싶-싶-싶어요'
  RESULT:      '가고 싶어요'

  EMPHATIC (PRESERVE):  '아니 아니, 그게 맞지 않아요'
  RESULT:      '아니 아니, 그게 맞지 않아요'

  CIRCUMLOCUTION:  '그... 쓰는 거 있잖아요... 필요해요'
  RESULT:      '펜 필요해요'  [if context is clear]
""".trimIndent()
        else -> """
DISFLUENCY EXAMPLES (English):
  BLOCK:       'I need the... [silence]... computer from the office'
  RESULT:      'I need the computer from the office'

  REPETITION:  'Ca-ca-ca-can you p-p-please send the re-report'
  RESULT:      'Can you please send the report'

  WORD REPS:   'I I I want to to to go to the the meeting'
  RESULT:      'I want to go to the meeting'

  WHISPER:     'I was trying to come put her the file'
  RESULT:      'I was trying to get the computer file'
""".trimIndent()
    }
}
