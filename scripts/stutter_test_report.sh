#!/usr/bin/env bash
# stutter_test_report.sh — render results.json into a self-contained HTML report
# matching the Lavrentiy report layout. Open the HTML in a browser, print to PDF.

set -euo pipefail
RESULTS_JSON="${1:-C:/Users/georg/AppData/Local/Temp/wim_stutter_test/results.json}"
OUT_HTML="${2:-C:/Users/georg/AppData/Local/Temp/wim_stutter_test/WiM-Android-Stutter-Test-Results.html}"

python - "$RESULTS_JSON" "$OUT_HTML" <<'PY'
import sys, json, html, datetime
results_path, out_path = sys.argv[1], sys.argv[2]
with open(results_path, encoding="utf-8") as f:
    data = json.load(f)

def cell(text):
    return html.escape(text or "")

date_str = datetime.datetime.now().strftime("%B %d, %Y")
script_name_pretty = {
    "drew_lynch": "Script 1: Drew Lynch — AGT Season 10 Audition (2015)",
    "biden": "Script 2: Joe Biden — Detroit Debate + Atlantic Interview (2019-2020)",
    "hendrickson": "Script 3: John Hendrickson — The Atlantic Journalist (2020)",
    "clinical_order": "Script 4: Clinical Composite — ASHA + Stuttering Foundation",
}
script_source = {
    "drew_lynch": "YouTube auto-generated captions",
    "biden": "The Atlantic, Jan 2020 (verbatim)",
    "hendrickson": "Same Atlantic article (journalist who stutters)",
    "clinical_order": "Composite of ASHA + Stuttering Foundation clinical patterns",
}

rows_html = []
for r in data:
    name_pretty = script_name_pretty.get(r["name"], r["name"])
    source = script_source.get(r["name"], "")
    falcon = "PASS" if r.get("falcon_approved") else "FAIL"
    flags = r.get("risk_flags") or []
    flag_str = (" | flags: " + ", ".join(flags)) if flags else ""
    err = r.get("error")
    err_str = f" | error: {html.escape(str(err))}" if err else ""
    rows_html.append(f"""
    <h2>{cell(name_pretty)}</h2>
    <p class="meta">Source: {cell(source)} | Falcon: <b>{falcon}</b> | Layer: {r.get('layer','?')} | Tone: {cell(r.get('tone','?'))} | Situation: {cell(r.get('situation','?'))} | Recon: {r.get('recon_ms','?')}ms | Total: {r.get('total_ms','?')}ms{flag_str}{err_str}</p>
    <table>
      <thead><tr><th>Clean (Ground Truth)</th><th>Stuttered (Input)</th><th>Output (WiM Android)</th></tr></thead>
      <tbody><tr>
        <td class="clean">{cell(r.get('clean'))}</td>
        <td class="stutter">{cell(r.get('stuttered'))}</td>
        <td class="out">{cell(r.get('wim_output'))}</td>
      </tr></tbody>
    </table>
    """)

# Summary row
def yn(b): return "YES" if b else "NO"
summary_rows = []
for r in data:
    summary_rows.append(f"""
      <tr>
        <td>{cell(r['name'])}</td>
        <td>{'PASS' if r.get('falcon_approved') else 'FAIL'}</td>
        <td>{r.get('recon_ms','?')} ms</td>
        <td>{r.get('total_ms','?')} ms</td>
        <td>{', '.join(r.get('risk_flags') or []) or '—'}</td>
      </tr>
    """)

avg_recon = round(sum((r.get('recon_ms') or 0) for r in data) / max(len(data),1))
pass_count = sum(1 for r in data if r.get('falcon_approved'))

html_doc = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>WiM Android — Stuttering Reconstruction Test Results</title>
<style>
  :root {{
    --bg: #fafafa; --ink: #1a1a1a; --rule: #e0a72d;
    --clean-bg: #f0f8f0; --clean-ink: #1a4a1a;
    --stutter-bg: #fdf3f3; --stutter-ink: #6a1a1a;
    --out-bg: #fff; --out-ink: #1a1a1a;
  }}
  body {{ font-family: 'Inter', -apple-system, system-ui, sans-serif; background: var(--bg); color: var(--ink); max-width: 1200px; margin: 2em auto; padding: 0 2em; line-height: 1.5; }}
  h1 {{ border-bottom: 3px solid var(--rule); padding-bottom: 0.4em; }}
  h2 {{ border-bottom: 2px solid #c2185b; padding-bottom: 0.2em; margin-top: 2.5em; color: #6a1b3a; }}
  .meta {{ font-size: 0.85em; color: #555; margin-top: -0.4em; }}
  table {{ width: 100%; border-collapse: collapse; margin: 1em 0; }}
  th {{ background: #2d1b3a; color: #fff; padding: 0.6em 1em; text-align: left; font-weight: 600; }}
  td {{ padding: 1em; vertical-align: top; border-bottom: 1px solid #e0e0e0; width: 33%; }}
  td.clean {{ background: var(--clean-bg); color: var(--clean-ink); }}
  td.stutter {{ background: var(--stutter-bg); color: var(--stutter-ink); }}
  td.out {{ background: var(--out-bg); color: var(--out-ink); font-weight: 500; }}
  .summary {{ background: #2d1b3a; color: #fff; padding: 1em; border-radius: 4px; margin-top: 1em; }}
  .summary table {{ background: rgba(255,255,255,0.06); }}
  .summary th, .summary td {{ color: #fff; border-color: #555; }}
  .config {{ font-size: 0.8em; color: #777; margin-top: 3em; padding-top: 1em; border-top: 1px solid #ccc; }}
  .stat {{ display: inline-block; background: #fff; border: 1px solid #ddd; padding: 0.4em 0.9em; margin-right: 0.6em; border-radius: 4px; }}
  .stat b {{ color: #c2185b; }}
</style>
</head><body>

<h1>WiM Android — Stuttering Reconstruction Test Results</h1>
<p>Voice-to-Intent Reconstruction Pipeline Validation — <b>actual WiM Android pipeline</b>, not Lavrentiy desktop.</p>
<p class="meta">Date: {date_str} | Platform: WiM Android (SM-F731U) | Pipeline: Layer 4 (Speech Disfluency) → Sonnet 4.6 ET → Falcon (skipped at L4 by design) | Tone: Professional | Situation: High Stress 1.5x | Input method: <code>inject_raw</code> via test broadcast (text only, ASR bypassed)</p>

<p><span class="stat">Scripts: <b>{len(data)}</b></span><span class="stat">Falcon PASS: <b>{pass_count}/{len(data)}</b></span><span class="stat">Avg recon: <b>{avg_recon}ms</b></span></p>

{''.join(rows_html)}

<h2>Summary</h2>
<div class="summary">
<table>
  <thead><tr><th>Script</th><th>Falcon</th><th>Recon ms</th><th>Total ms</th><th>Risk flags</th></tr></thead>
  <tbody>{''.join(summary_rows)}</tbody>
</table>
</div>

<p class="config"><b>Config:</b> Sonnet 4.6 ET (Anthropic) | L4 reconstruction | high_stress 1.5x | professional | Falcon disabled at L4 (Sonnet ET self-validates) | WiM Android v1.0 build {datetime.datetime.now().strftime('%Y-%m-%d')}<br>
<b>Inputs:</b> Same 4 stuttered scripts as the Lavrentiy report (Drew Lynch AGT S10, Biden Detroit/Atlantic, Hendrickson Atlantic, ASHA + Stuttering Foundation composite). Text injected directly via debug-only test receiver, bypassing the ASR step (Moonshine/Whisper) so the reconstruction layer is isolated.<br>
<b>Note:</b> Earlier reports labeled "WiM" were actually Lavrentiy desktop outputs in WiM branding. This is the first run on the actual Android pipeline.</p>

</body></html>"""

with open(out_path, "w", encoding="utf-8") as f:
    f.write(html_doc)
print(f"Report: {out_path}")
PY
