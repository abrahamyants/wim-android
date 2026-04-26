#!/usr/bin/env bash
# stutter_test_runner.sh — Run the same 4-script stutter reconstruction test
# that produced the Lavrentiy report, but on the actual WiM Android pipeline.
#
# Output: per-script JSON to /tmp + builds an HTML comparison report later.
# Usage: bash scripts/stutter_test_runner.sh
#
# Prereq: API keys must be set on the device before running.
#   - WimApp.apiKey()         (OpenAI, used by L4 fallback)
#   - WimApp.anthropicApiKey() (Anthropic, used by L4 Sonnet 4.6 ET)
# Set via the main app, or via test broadcast set_pref.

set -euo pipefail
export MSYS_NO_PATHCONV=1

ADB="C:/Users/georg/AppData/Local/Android/Sdk/platform-tools/adb.exe"
OUT_DIR="C:/Users/georg/AppData/Local/Temp/wim_stutter_test"
mkdir -p "$OUT_DIR"
RESULTS_JSON="$OUT_DIR/results.json"

broadcast() {
  "$ADB" shell am broadcast -a com.wim.app.TEST -n com.wim.app/.WimTestReceiver "$@" 2>&1 | tail -1 >/dev/null
}

pull_result() {
  "$ADB" shell "run-as com.wim.app cat files/test_result.json" 2>/dev/null
}

inject_text_via_file() {
  local local_text_file="$1"
  # /data/local/tmp/ bypasses scoped storage and the app can read it directly.
  # /sdcard/Android/data/<pkg>/files/ requires MediaProvider permission
  # the app doesn't hold. Don't use /sdcard/.
  local device_path="/data/local/tmp/wim_inject.txt"
  "$ADB" push "$local_text_file" "//data/local/tmp/wim_inject.txt" >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 "$ADB" shell am broadcast -a com.wim.app.TEST -n com.wim.app/.WimTestReceiver --es action inject_raw_file --es path "$device_path" >/dev/null 2>&1
}

# ── 0. Key check ───────────────────────────────────────────────────────────
echo "[0/6] Checking API keys on device..."
keys_xml=$("$ADB" shell "run-as com.wim.app cat /data/data/com.wim.app/shared_prefs/wim.xml" 2>/dev/null || true)
if ! echo "$keys_xml" | grep -q "anthropic_api_key"; then
  echo "  ⚠ anthropic_api_key not set — L4 Sonnet path will fail"
fi
if ! echo "$keys_xml" | grep -q '"api_key"\|"openai_api_key"'; then
  echo "  ⚠ OpenAI api_key not set — L4 fallback path will fail too"
  echo "  Set keys in main app or via test broadcast set_pref before running."
  echo "  Aborting to avoid wasting test cycles."
  exit 1
fi

# ── 1. Pre-flight ──────────────────────────────────────────────────────────
echo "[1/6] Pre-flight: bubble visible, layer=4, tone=professional, situation=high_stress"
broadcast --es action trigger_bubble --es bubble_action show
sleep 1
broadcast --es action set_layer --ei layer 4
broadcast --es action set_tone --es tone professional
broadcast --es action set_situation --es situation high_stress
sleep 1

# ── 2. Test scripts (verbatim from the Lavrentiy report inputs) ────────────
declare -a NAMES=("drew_lynch" "biden" "hendrickson" "clinical_order")

declare -a CLEAN
CLEAN[0]="Yes, my name is Drew. I have a stutter, that is what that is. I am a comedian. About four years ago, I did not want to always do comedy, but I had a sports injury. That is why I talk like this. Almost four years ago I was on a softball team. There was a grounder that took a bad hop and hit me in my throat, which damaged some nerves in my vocal cords."
CLEAN[1]="My plan makes a limit of co-pay to be one thousand dollars. Because we further support the ability to buy into the Obamacare plan. I do not remember the specific details. It is hard for me to remember. But you know, I have not stuttered in so long that it is hard for me to remember the specific techniques they taught me."
CLEAN[2]="I have only told a few people I am doing this piece. Every time I describe it, I get caught on the word stutter. So I was wondering if you could tell me about your experience growing up with a stutter, and how it affected your confidence in public speaking, because I think a lot of people would find that really meaningful."
CLEAN[3]="I want to order a hundred custom logo cookies for our company event next Friday. We need them to have our company logo printed on each one. Can you also include fifty cake pops in our brand colors? The delivery address is 500 Market Street, San Francisco. My name is Sarah Chen and my phone number is 415-555-0147."

declare -a STUTTERED
STUTTERED[0]="yes my name my my name is Drew uh I I have a I have I have a stutter that is what that is that is what that is. I am a I am a comedian. For for about four years ago I I I did I did not want to uh always do comedy but uh I had a I had a sport Sports Injury that is that is that is why I talk talk like talk like this. Almost four years years ago I was on a s softball team there was a grounder that took a took a bad hop and uh hit me in my throat which damaged some nerves in my vocal cords."
STUTTERED[1]="My plan makes a limit of co-pay to be One. Thousand. Dollars. Because we-- we f-f-f-f-further support-- the uh-uh-uh-uh-- the-uh, the ability to buy into the Obamacare plan. I-I-I do not remember. I-I-I do not remember the specific-- it is hhhhard for me to remember. But, you know, I have not stuttered in so long that it is hhhhard for me to remember the specific-- the specific techniques they, um, they taught me."
STUTTERED[2]="I have only... told a few people I am... d-doing this piece. Every time I... describe it, I get... caught on the w-word-uh stuh-tuh-tuh-tutter. So I was wondering if you could, um, t-t-tell me about your experience g-g-growing up with a, with a stutter, and how it, how it affected your, um, your confidence in, in p-p-public speaking, because I think a lot of, a lot of people would find that really, uh, really meaningful."
STUTTERED[3]="I w-w-want to order a, a hundred custom l-l-logo cookies for our, for our company event next, next Friday. We need them to, to have our c-c-company logo p-p-printed on each, on each one. Can you also, um, also include f-f-fifty cake, cake pops in our, in our brand colors? The, the delivery address is, is f-f-five hundred Market Street, San, San Francisco. My, my name is, is Sarah, uh, Sarah Chen and my, my phone number is, um, four-one-five, five-five-five, zero-one-four-seven."

# ── 3. Run each script via file-based inject (avoids am --es space-truncation)
echo "[2/6] Injecting ${#NAMES[@]} stuttered scripts through L4 pipeline"
ENVELOPES=()
for i in "${!NAMES[@]}"; do
  name="${NAMES[$i]}"
  stuttered="${STUTTERED[$i]}"
  clean="${CLEAN[$i]}"
  echo "  → $name (${#stuttered} chars)"
  # Write the stuttered text to a temp file, push, inject from file
  text_file="$OUT_DIR/${name}_stuttered.txt"
  printf '%s' "$stuttered" > "$text_file"
  inject_text_via_file "$text_file"
  # L4 = Sonnet 4.6 ET, can take 8-15s. Poll up to 30s for result.
  start_ms=$(date +%s%3N)
  result_json=""
  for attempt in {1..30}; do
    sleep 1
    broadcast --es action get_last_result
    sleep 0.3
    raw=$(pull_result)
    if echo "$raw" | grep -q '"status": "ok"' && echo "$raw" | grep -q '"clean_text"'; then
      result_json="$raw"
      break
    fi
    # Also break on permanent error
    if echo "$raw" | grep -q '"status": "error"'; then
      result_json="$raw"
      break
    fi
  done
  if [ -z "$result_json" ]; then
    result_json=$(pull_result)
  fi
  end_ms=$(date +%s%3N)
  elapsed=$((end_ms - start_ms))
  # Build envelope via Python (clean JSON handling)
  envelope=$(python - "$name" "$clean" "$stuttered" "$elapsed" "$result_json" <<'PY'
import sys, json
name, clean, stuttered, elapsed_str, raw_json = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
elapsed = int(elapsed_str)
try:
    res = json.loads(raw_json) if raw_json.strip() else {}
except Exception:
    res = {"error": "parse_failed", "raw": raw_json[:300]}
envelope = {
    "name": name,
    "clean": clean,
    "stuttered": stuttered,
    "wim_output": res.get("clean_text", ""),
    "raw_text_seen_by_pipeline": res.get("raw_text", ""),
    "asr_ms": res.get("asr_ms", 0),
    "recon_ms": res.get("recon_ms", 0),
    "total_ms": res.get("total_ms", elapsed),
    "wall_ms": elapsed,
    "layer": res.get("layer", -1),
    "tone": res.get("tone", "?"),
    "situation": res.get("situation", "?"),
    "falcon_approved": res.get("falcon_approved", None),
    "risk_flags": res.get("risk_flags", []),
    "status": res.get("status", "?"),
    "error": res.get("error", None),
}
print(json.dumps(envelope, indent=2))
PY
)
  ENVELOPES+=("$envelope")
done

# Assemble final JSON array
python - "$RESULTS_JSON" "${ENVELOPES[@]}" <<'PY'
import sys, json
out_path = sys.argv[1]
envs = [json.loads(s) for s in sys.argv[2:]]
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(envs, f, indent=2)
print(f"[3/6] {len(envs)} scripts captured. Saved to {out_path}")
PY

echo "[4/6] JSON results: $RESULTS_JSON"
echo "[5/6] Build report: bash scripts/stutter_test_report.sh"
echo "[6/6] Done."
