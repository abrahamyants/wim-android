package com.wim.app

/**
 * Which capture path a recording takes, as a pure decision.
 *
 * Extracted from `BubbleService.startRecording` 2026-08-02. Three defects had
 * accumulated in that block, and all three were the kind a truth table catches
 * on sight:
 *
 *  - the CLOUD transcription setting was honoured only at layer 1, so a user
 *    who had paid for cloud transcription silently got the local English-only
 *    engine at L2, L3 and L4;
 *  - the documented L2/L3 cloud fallback had no branch, so a signed-in user
 *    whose local model had not downloaded could not dictate at all while a
 *    recorder was already capturing their voice — and the refusal never stopped
 *    that recorder, leaving the microphone open;
 *  - a stale CLOUD pick made while signed in and then signed out took the cloud
 *    branch with no recorder running, capturing silence.
 *
 * None of it was reachable from a test: the decision lived inside a 200-line
 * method on an Android Service, tangled with view state and coroutine
 * lifecycles. It is four booleans and an integer. Now it is testable.
 */
object AsrRoute {

    /**
     * @property useCloudRecorder start [AudioRecorder]; transcription happens
     *   after the take through the cloud model.
     * @property useLocalEngine run the on-device whisper-tiny engine live.
     * @property refuse take nothing — tell the user the model is still
     *   downloading and kick a retry.
     */
    sealed class Decision(
        val useCloudRecorder: Boolean,
        val useLocalEngine: Boolean,
        val refuse: Boolean,
    ) {
        /** L4 clinical: always cloud, because it needs verbose_json segments. */
        object ClinicalCloud : Decision(true, false, false)

        /** The speaker picked CLOUD and cloud is reachable. */
        object SelectedCloud : Decision(true, false, false)

        /** LOCAL, and the model is on disk. */
        object LocalEngine : Decision(false, true, false)

        /**
         * LOCAL, model missing, but the recorder is running because L2+ falls
         * back to cloud transcription. Documented behaviour that had no branch.
         */
        object CloudFallback : Decision(true, false, false)

        /** Nothing can capture. The caller shows the download state. */
        object Refuse : Decision(false, false, true)
    }

    /**
     * @param layer active reconstruction layer, 1..4.
     * @param cloudPreferred the user's TRANSCRIPTION setting is CLOUD.
     * @param localModelReady whisper-tiny.en is on disk and initialised.
     * @param cloudReachable an API key is present or the user is signed in.
     */
    fun decide(
        layer: Int,
        cloudPreferred: Boolean,
        localModelReady: Boolean,
        cloudReachable: Boolean,
    ): Decision {
        if (layer >= 4) {
            // L4 has no local option at all: the clinical prompt needs the
            // segment confidences only cloud Whisper returns.
            return if (cloudReachable) Decision.ClinicalCloud else Decision.Refuse
        }
        // A CLOUD pick only counts when cloud can actually be reached. Trusting
        // the pref alone is what produced a cloud branch with no recorder.
        if (cloudPreferred && cloudReachable) return Decision.SelectedCloud
        if (localModelReady) return Decision.LocalEngine
        // Model missing. L2+ may still transcribe in the cloud; L1 has nowhere
        // to go, because its whole contract is that no model runs.
        return if (layer >= 2 && cloudReachable) Decision.CloudFallback else Decision.Refuse
    }
}
