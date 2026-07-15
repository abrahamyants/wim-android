#!/usr/bin/env python3
"""
WiM Cross-Platform Parity Test

Feeds identical disfluent text into three pipelines simultaneously:
  1. WiM Android (via ADB → WimTestReceiver inject_raw)
  2. Лаврентий desktop (via HTTP → /api/reconstruct_test)
  3. WiM MCP server (via direct Python import — disfluency_filter + compute_wer)

Diffs outputs across all pipelines. Flags divergences.

Usage:
  python parity_test.py                          # Run built-in corpus
  python parity_test.py --corpus corpus.json      # Run custom corpus
  python parity_test.py --layers 2 3 4            # Test specific layers
  python parity_test.py --tones casual formal      # Test specific tones
  python parity_test.py --no-android               # Skip Android (no device connected)
  python parity_test.py --no-lavrentiy             # Skip Lavrentiy (not running)

Output:
  parity_report.json — full structured results
  stdout — divergence summary
"""

import argparse
import json
import subprocess
import sys
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path

try:
    import httpx
    HAS_HTTPX = True
except ImportError:
    HAS_HTTPX = False

# ── ADB helpers ──────────────────────────────────────────────────────────────

def adb(cmd: str, timeout: float = 10) -> str:
    """Run an ADB command, return stdout."""
    result = subprocess.run(
        f"adb {cmd}", shell=True, capture_output=True, text=True, timeout=timeout
    )
    return result.stdout.strip()


def adb_broadcast(action: str, extras: str = "") -> str:
    return adb(f'shell am broadcast -a com.wimlabs.wim.TEST -n com.wimlabs.wim/com.wim.app.WimTestReceiver --es action {action} {extras}')


def adb_read_result() -> dict | None:
    raw = adb("shell run-as com.wimlabs.wim cat files/test_result.json")
    if not raw or "No such file" in raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None


# ── Pipeline runners ─────────────────────────────────────────────────────────

def run_android(raw_text: str, tone: str, layer: int, situation: str) -> dict | None:
    """Feed text into WiM Android via WimTestReceiver, wait for result."""
    # Configure pipeline
    adb_broadcast("set_tone", f'--es tone {tone}')
    adb_broadcast("set_layer", f'--ei layer {layer}')
    adb_broadcast("set_situation", f'--es situation {situation}')
    time.sleep(0.3)

    # Inject raw text
    # Escape quotes for shell
    escaped = raw_text.replace('"', '\\"').replace("'", "'\\''")
    adb_broadcast("inject_raw", f'--es raw_text "{escaped}"')

    # Poll for result (pipeline takes 1-8s depending on layer)
    max_wait = 15 if layer >= 4 else 8
    for _ in range(max_wait * 2):
        time.sleep(0.5)
        result = adb_read_result()
        if result and result.get("status") == "ok" and result.get("raw_text") == raw_text:
            result["pipeline"] = "wim-android"
            return result
        if result and result.get("status") == "error":
            result["pipeline"] = "wim-android"
            return result

    return {"pipeline": "wim-android", "status": "timeout", "raw_text": raw_text}


def run_lavrentiy(raw_text: str, tone: str, layer: int, situation: str,
                  host: str = "localhost", port: int = 7878) -> dict | None:
    """Feed text into Лаврентий desktop via /api/reconstruct_test."""
    if not HAS_HTTPX:
        return {"pipeline": "lavrentiy", "status": "error", "error": "httpx not installed"}

    try:
        resp = httpx.post(
            f"http://{host}:{port}/api/reconstruct_test",
            json={"raw": raw_text, "tone": tone, "layer": layer, "situation": situation},
            timeout=30.0,
        )
        result = resp.json()
        result["pipeline"] = "lavrentiy"
        return result
    except Exception as e:
        return {"pipeline": "lavrentiy", "status": "error", "error": str(e)}


def _compute_wer_inline(raw: str, clean: str) -> float:
    """Inline WER computation (no external deps). Levenshtein on word arrays."""
    raw_words = raw.lower().split()
    clean_words = clean.lower().split()
    if not raw_words:
        return 0.0
    n, m = len(raw_words), len(clean_words)
    dp = list(range(m + 1))
    for i in range(1, n + 1):
        prev, dp[0] = dp[0], i
        for j in range(1, m + 1):
            cost = 0 if raw_words[i - 1] == clean_words[j - 1] else 1
            dp[j], prev = min(dp[j] + 1, dp[j - 1] + 1, prev + cost), dp[j]
    return round(dp[m] / n, 4)


def run_mcp_local(raw_text: str, tone: str, layer: int, situation: str) -> dict:
    """Run through MCP server's local disfluency filter + WER (no LLM, pure logic).

    Tries to import wim_mcp modules. Falls back to inline implementations if not installed.
    """
    try:
        try:
            from wim_mcp.disfluency_filter import filter_disfluencies
            from wim_mcp.phonetic_risk import predict_triggers_in_text
        except ImportError:
            # Fallback: minimal inline filter (covers common cases)
            import re
            def filter_disfluencies(text, fillers):
                if not text:
                    return text
                # Word repetitions
                cleaned = re.sub(r'\b(\w+)(?:\s+\1)+\b', r'\1', text, flags=re.IGNORECASE)
                # Stutter fragments
                cleaned = re.sub(r'(\b\w+)-\s+(?:\1-\s+)*', '', cleaned, flags=re.IGNORECASE)
                # Fillers
                filler_set = {f.lower() for f in fillers}
                words = cleaned.split()
                if len(words) > 1:
                    words = [w for w in words if w.lower().rstrip('.,!?;:') not in filler_set]
                cleaned = ' '.join(words).strip()
                cleaned = re.sub(r'\s{2,}', ' ', cleaned)
                return cleaned if cleaned else text
            def predict_triggers_in_text(text, *a, **kw):
                return []

        # Default filler words (EN + RU, matches ProfileManager defaults)
        fillers = ["um", "uh", "like", "you know", "I mean", "basically",
                   "actually", "literally", "right", "so",
                   "э", "эм", "ну", "вот", "типа", "значит"]

        filtered = filter_disfluencies(raw_text, fillers)
        wer = _compute_wer_inline(raw_text, filtered)
        triggers = predict_triggers_in_text(raw_text)

        return {
            "pipeline": "mcp-local",
            "raw": raw_text,
            "filtered": filtered,
            "clean": filtered,  # MCP local = disfluency filter only (no LLM)
            "tone": tone,
            "layer": layer,
            "situation": situation,
            "wer": wer,
            "triggers": [{"word": w, "risk": r} for w, r in triggers[:5]] if triggers else [],
            "status": "ok",
        }
    except Exception as e:
        return {"pipeline": "mcp-local", "status": "error", "error": str(e)}


# ── Diff engine ──────────────────────────────────────────────────────────────

@dataclass
class Divergence:
    test_id: str
    field: str
    android_value: str = ""
    lavrentiy_value: str = ""
    mcp_value: str = ""
    severity: str = "info"  # info, warn, critical


def diff_results(test_id: str, android: dict | None, lavrentiy: dict | None, mcp: dict | None) -> list[Divergence]:
    """Compare outputs across pipelines. Return divergences."""
    divs: list[Divergence] = []

    def get(d: dict | None, key: str) -> str:
        if d is None:
            return "<skipped>"
        v = d.get(key, d.get("clean_text", ""))  # Android uses clean_text, Lavrentiy uses clean
        return str(v) if v is not None else ""

    # Compare clean text output (the most important check)
    a_clean = get(android, "clean_text") if android else get(android, "clean")
    l_clean = get(lavrentiy, "clean")
    m_clean = get(mcp, "clean")

    if android and lavrentiy:
        if a_clean and l_clean and a_clean.strip().lower() != l_clean.strip().lower():
            divs.append(Divergence(
                test_id=test_id, field="clean_text",
                android_value=a_clean, lavrentiy_value=l_clean, mcp_value=m_clean,
                severity="critical",
            ))

    # Compare filtered text (pre-GPT disfluency cleanup)
    a_filtered = get(android, "pre_cleaned")
    l_filtered = get(lavrentiy, "filtered")
    m_filtered = get(mcp, "filtered")

    if android and lavrentiy and a_filtered != "<skipped>" and l_filtered != "<skipped>":
        if a_filtered.strip().lower() != l_filtered.strip().lower():
            divs.append(Divergence(
                test_id=test_id, field="disfluency_filter",
                android_value=a_filtered, lavrentiy_value=l_filtered, mcp_value=m_filtered,
                severity="warn",
            ))

    # Compare Falcon approval
    a_falcon = get(android, "falcon_approved")
    l_falcon = get(lavrentiy, "falcon_ok")
    if android and lavrentiy and a_falcon != "<skipped>" and l_falcon != "<skipped>":
        if str(a_falcon).lower() != str(l_falcon).lower():
            divs.append(Divergence(
                test_id=test_id, field="falcon",
                android_value=a_falcon, lavrentiy_value=l_falcon,
                severity="critical",
            ))

    return divs


# ── Built-in test corpus ─────────────────────────────────────────────────────

BUILTIN_CORPUS = [
    # Stutter fragments
    {"id": "stutter_01", "raw": "I I I want to to go to the st- st- store",
     "expected_clean": "I want to go to the store"},
    {"id": "stutter_02", "raw": "Ca- ca- can you p- p- please send the re- report",
     "expected_clean": "Can you please send the report"},
    {"id": "stutter_03", "raw": "I was g- g- going to the meeting",
     "expected_clean": "I was going to the meeting"},

    # Filler words
    {"id": "filler_01", "raw": "So um uh like basically uh the thing is we need more time",
     "expected_clean": "We need more time"},
    {"id": "filler_02", "raw": "I mean like you know it's basically um a good idea",
     "expected_clean": "It's a good idea"},

    # Prolongations
    {"id": "prolong_01", "raw": "I was thinking about the sssssschedule for next week",
     "expected_clean": "I was thinking about the schedule for next week"},
    {"id": "prolong_02", "raw": "The mmmmeeting is at three",
     "expected_clean": "The meeting is at three"},

    # Word repetitions
    {"id": "repeat_01", "raw": "I I I want to to to go to the the meeting",
     "expected_clean": "I want to go to the meeting"},
    {"id": "repeat_02", "raw": "The the project is is going well",
     "expected_clean": "The project is going well"},

    # Phrase repetitions
    {"id": "phrase_01", "raw": "I want I want to go home",
     "expected_clean": "I want to go home"},

    # False starts
    {"id": "false_start_01", "raw": "I was going to-- I went to the store",
     "expected_clean": "I went to the store"},

    # Natural repeats (should NOT be stripped)
    {"id": "natural_01", "raw": "I had had enough of the delays",
     "expected_clean": "I had had enough of the delays"},
    {"id": "natural_02", "raw": "No no that's not right",
     "expected_clean": "No no that's not right"},

    # Block hallucinations (Whisper fabrication during silence)
    {"id": "block_01", "raw": "I need the... [silence]... computer from the office",
     "expected_clean": "I need the computer from the office"},

    # Covert avoidance / circumlocution
    {"id": "covert_01", "raw": "I need the b-... the document from yesterday",
     "expected_clean": "I need the document from yesterday"},

    # Mixed disfluencies
    {"id": "mixed_01",
     "raw": "So um I was I was trying to to um like tell you about the the p- p- project deadline",
     "expected_clean": "I was trying to tell you about the project deadline"},
    {"id": "mixed_02",
     "raw": "Can you um like basically uh send me the the the report by by Friday",
     "expected_clean": "Can you send me the report by Friday"},

    # Bilingual (Russian fillers)
    {"id": "bilingual_01", "raw": "Ну э типа I need to finish the project",
     "expected_clean": "I need to finish the project"},

    # Anti-censorship (should preserve profanity)
    {"id": "censor_01", "raw": "That meeting was a complete shit show",
     "expected_clean": "That meeting was a complete shit show"},
    {"id": "censor_02", "raw": "I don't give a fuck about the deadline",
     "expected_clean": "I don't give a fuck about the deadline"},

    # Critical tokens (numbers, $, %)
    {"id": "token_01", "raw": "The total is $500 plus 20% tax",
     "expected_clean": "The total is $500 plus 20% tax"},
    {"id": "token_02", "raw": "We need um like 3,000 units by March 15th",
     "expected_clean": "We need 3,000 units by March 15th"},

    # Short/edge cases
    {"id": "short_01", "raw": "Yes", "expected_clean": "Yes"},
    {"id": "short_02", "raw": "Um", "expected_clean": "Um"},
    {"id": "empty_01", "raw": "", "expected_clean": ""},
]


# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="WiM Cross-Platform Parity Test")
    parser.add_argument("--corpus", type=str, help="Path to custom corpus JSON file")
    parser.add_argument("--layers", type=int, nargs="+", default=[2], help="Layers to test (default: 2)")
    parser.add_argument("--tones", type=str, nargs="+", default=["casual"], help="Tones to test")
    parser.add_argument("--situations", type=str, nargs="+", default=["default"], help="Situations to test")
    parser.add_argument("--no-android", action="store_true", help="Skip Android pipeline")
    parser.add_argument("--no-lavrentiy", action="store_true", help="Skip Лаврентий pipeline")
    parser.add_argument("--lavrentiy-host", type=str, default="localhost", help="Лаврентий host")
    parser.add_argument("--lavrentiy-port", type=int, default=7878, help="Лаврентий port")
    parser.add_argument("--output", type=str, default="parity_report.json", help="Output file path")
    args = parser.parse_args()

    # Load corpus
    if args.corpus:
        with open(args.corpus) as f:
            corpus = json.load(f)
    else:
        corpus = BUILTIN_CORPUS

    print(f"WiM Parity Test")
    print(f"  Corpus: {len(corpus)} inputs")
    print(f"  Layers: {args.layers}")
    print(f"  Tones:  {args.tones}")
    print(f"  Situations: {args.situations}")
    print(f"  Android: {'OFF' if args.no_android else 'ON'}")
    print(f"  Лаврентий: {'OFF' if args.no_lavrentiy else 'ON'}")
    combos = len(corpus) * len(args.layers) * len(args.tones) * len(args.situations)
    print(f"  Total combos: {combos}")
    print()

    # Verify connectivity
    if not args.no_android:
        ping_result = adb_broadcast("ping")
        if "Broadcast completed" not in ping_result:
            print("ERROR: ADB broadcast failed. Is the device connected?")
            print(f"  adb output: {ping_result}")
            args.no_android = True
        else:
            ping_data = adb_read_result()
            if ping_data and ping_data.get("status") == "ok":
                print(f"  Android: connected (bubble={'ON' if ping_data.get('bubble_running') else 'OFF'}, a11y={'ON' if ping_data.get('a11y_running') else 'OFF'})")
            else:
                print("  Android: broadcast works but receiver not responding")
                args.no_android = True

    if not args.no_lavrentiy and HAS_HTTPX:
        try:
            resp = httpx.get(f"http://{args.lavrentiy_host}:{args.lavrentiy_port}/api/state", timeout=3.0)
            state = resp.json()
            print(f"  Лаврентий: connected (layer={state.get('layer')}, tone={state.get('tone')})")
        except Exception as e:
            print(f"  Лаврентий: not reachable ({e})")
            args.no_lavrentiy = True

    print()

    # Run tests
    all_results = []
    all_divergences = []
    tested = 0
    diverged = 0

    for entry in corpus:
        raw_text = entry["raw"]
        test_base_id = entry.get("id", f"test_{corpus.index(entry)}")

        for layer in args.layers:
            for tone in args.tones:
                for situation in args.situations:
                    test_id = f"{test_base_id}/L{layer}/{tone}/{situation}"
                    tested += 1

                    # Run all pipelines
                    android_result = None
                    lavrentiy_result = None
                    mcp_result = run_mcp_local(raw_text, tone, layer, situation)

                    if not args.no_android:
                        android_result = run_android(raw_text, tone, layer, situation)

                    if not args.no_lavrentiy:
                        lavrentiy_result = run_lavrentiy(
                            raw_text, tone, layer, situation,
                            args.lavrentiy_host, args.lavrentiy_port)

                    # Diff
                    divs = diff_results(test_id, android_result, lavrentiy_result, mcp_result)
                    if divs:
                        diverged += 1
                        all_divergences.extend(divs)
                        for d in divs:
                            severity_icon = {"critical": "🔴", "warn": "🟡", "info": "🔵"}[d.severity]
                            print(f"  {severity_icon} {test_id} [{d.field}]")
                            if d.android_value and d.android_value != "<skipped>":
                                print(f"      Android:   {d.android_value[:80]}")
                            if d.lavrentiy_value and d.lavrentiy_value != "<skipped>":
                                print(f"      Лаврентий: {d.lavrentiy_value[:80]}")
                            if d.mcp_value and d.mcp_value != "<skipped>":
                                print(f"      MCP:       {d.mcp_value[:80]}")
                    else:
                        sys.stdout.write(".")
                        sys.stdout.flush()

                    all_results.append({
                        "test_id": test_id,
                        "raw_text": raw_text,
                        "expected_clean": entry.get("expected_clean", ""),
                        "tone": tone,
                        "layer": layer,
                        "situation": situation,
                        "android": android_result,
                        "lavrentiy": lavrentiy_result,
                        "mcp": mcp_result,
                        "divergences": [asdict(d) for d in divs],
                    })

    print()
    print()

    # Summary
    critical = sum(1 for d in all_divergences if d.severity == "critical")
    warnings = sum(1 for d in all_divergences if d.severity == "warn")

    print("═" * 60)
    print(f"  PARITY REPORT")
    print(f"  Tests run:    {tested}")
    print(f"  Divergences:  {diverged} tests ({diverged/tested*100:.1f}%)" if tested else "")
    print(f"  Critical:     {critical}")
    print(f"  Warnings:     {warnings}")
    print("═" * 60)

    if critical > 0:
        print()
        print("CRITICAL DIVERGENCES (reconstruction output differs):")
        for d in all_divergences:
            if d.severity == "critical":
                print(f"  {d.test_id} [{d.field}]")
                if d.android_value and d.android_value != "<skipped>":
                    print(f"    Android:   {d.android_value[:120]}")
                if d.lavrentiy_value and d.lavrentiy_value != "<skipped>":
                    print(f"    Лаврентий: {d.lavrentiy_value[:120]}")

    # Write report
    report = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "config": {
            "layers": args.layers,
            "tones": args.tones,
            "situations": args.situations,
            "android_enabled": not args.no_android,
            "lavrentiy_enabled": not args.no_lavrentiy,
            "corpus_size": len(corpus),
        },
        "summary": {
            "tests_run": tested,
            "diverged": diverged,
            "critical": critical,
            "warnings": warnings,
            "parity_rate": round((tested - diverged) / tested * 100, 1) if tested else 0,
        },
        "divergences": [asdict(d) for d in all_divergences],
        "results": all_results,
    }

    output_path = Path(args.output)
    output_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\nFull report: {output_path.absolute()}")


if __name__ == "__main__":
    main()
