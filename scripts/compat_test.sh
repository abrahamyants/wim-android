#!/bin/bash
#
# WiM compatibility test runner
# Usage: ./compat_test.sh <app-label> [phrase]
#        ./compat_test.sh --matrix          # runs the full preset matrix
#        ./compat_test.sh --report          # print the accumulated results table
#
# For each app you want to test:
#   1. Open the app on the phone
#   2. Navigate to any text-entry / compose view
#   3. Tap into the field so the cursor is blinking
#   4. Run this script with the app label
# The script injects a test phrase via WimTestReceiver.inject_raw and checks
# whether the phrase landed in the UI via uiautomator dump. Also captures
# logcat to catch accessibility-service toast failures.
#
# Requires ADB at the path defined below; phone connected via USB with debug on.

set -u

ADB="${WIM_ADB:-/c/Users/georg/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
RESULTS_DIR="$(dirname "$0")/../compat_results"
RESULTS_MD="$RESULTS_DIR/results.md"
PHRASE_DEFAULT="hello wim compat zulu foxtrot bravo"

mkdir -p "$RESULTS_DIR"

# ── Preset matrix: label -> hint for the user ──
# Launch intents are deliberately not automated — many apps require a specific
# chat / doc / compose context that can't be launched by package alone.
declare -A PRESETS=(
    ["google-messages"]="com.google.android.apps.messaging — tap a conversation → compose field"
    ["gmail"]="com.google.android.gm — Compose → body field"
    ["whatsapp"]="com.whatsapp — any chat → message field"
    ["telegram"]="org.telegram.messenger — any chat → message field"
    ["signal"]="org.thoughtcrime.securesms — any chat → message field"
    ["slack"]="com.Slack — any channel → message field"
    ["discord"]="com.discord — any channel → message field"
    ["teams"]="com.microsoft.teams — any chat → message field"
    ["fb-messenger"]="com.facebook.orca — any chat → message field"
    ["instagram-dm"]="com.instagram.android — DM → message field"
    ["linkedin-dm"]="com.linkedin.android — Messaging → message field"
    ["google-chat"]="com.google.android.apps.dynamite — any chat → message field"
    ["twitter-x"]="com.twitter.android — Compose tweet"
    ["reddit"]="com.reddit.frontpage — Compose post"
    ["google-docs"]="com.google.android.apps.docs.editors.docs — open a doc → tap body"
    ["google-keep"]="com.google.android.keep — new note → body"
    ["notion"]="notion.id — any page → text block"
    ["notes-samsung"]="com.samsung.android.app.notes — new note → body"
)

cmd_single() {
    local label="$1"
    local phrase="${2:-$PHRASE_DEFAULT}"
    local clean_phrase
    clean_phrase=$(echo "$phrase" | tr '[:upper:]' '[:lower:]')

    local hint="${PRESETS[$label]:-}"
    echo ""
    echo "=== Testing: $label ==="
    [[ -n "$hint" ]] && echo "Target: $hint"
    echo "Phrase: '$phrase'"
    echo ""
    read -rp "Open the app, tap into a text field so the cursor blinks. Press Enter to run: " _

    # Drop logcat buffer so we only capture NEW events
    "$ADB" logcat -c 2>/dev/null

    local ts
    ts=$(date +%Y%m%d-%H%M%S)
    local dump_before="$RESULTS_DIR/${ts}_${label}_before.xml"
    local dump_after="$RESULTS_DIR/${ts}_${label}_after.xml"
    local logcat_out="$RESULTS_DIR/${ts}_${label}_logcat.txt"

    # Dump BEFORE
    "$ADB" shell uiautomator dump /sdcard/before.xml >/dev/null 2>&1
    "$ADB" pull /sdcard/before.xml "$dump_before" >/dev/null 2>&1

    # Inject via WimTestReceiver
    "$ADB" shell am broadcast -a com.wimlabs.wim.TEST \
        -n com.wimlabs.wim/com.wim.app.WimTestReceiver \
        --es action inject_raw --es raw_text "$phrase" >/dev/null 2>&1

    sleep 3

    # Dump AFTER
    "$ADB" shell uiautomator dump /sdcard/after.xml >/dev/null 2>&1
    "$ADB" pull /sdcard/after.xml "$dump_after" >/dev/null 2>&1

    # Capture logcat
    "$ADB" logcat -d -t 100 2>/dev/null > "$logcat_out"

    # Analysis
    local status="UNKNOWN"
    local detail=""

    if grep -q "$clean_phrase" "$dump_after" 2>/dev/null; then
        status="PASS"
        detail="phrase landed in UI tree"
    else
        # Look for known failure signals in logcat
        if grep -q "toast_tap_field\|Missing 'raw_text'\|BubbleService not running" "$logcat_out" 2>/dev/null; then
            status="FAIL"
            detail="accessibility service could not find text field (toast_tap_field)"
        elif grep -q "toast_field_lost" "$logcat_out" 2>/dev/null; then
            status="FAIL"
            detail="stored text field went stale and re-find also failed"
        elif grep -qi "wimaccessibility\|WiM.*injectText" "$logcat_out" 2>/dev/null; then
            status="FAIL"
            detail="injection ran but phrase not found in UI (possible wrong field)"
        else
            status="INCONCLUSIVE"
            detail="no clear signal in logcat + phrase not in dump"
        fi
    fi

    echo ""
    echo "Result: $status — $detail"
    echo "  dump before: $dump_before"
    echo "  dump after:  $dump_after"
    echo "  logcat:      $logcat_out"

    # Append to results.md
    if [[ ! -f "$RESULTS_MD" ]]; then
        {
            echo "# WiM Compatibility Matrix"
            echo ""
            echo "| When | App | Status | Detail |"
            echo "|---|---|---|---|"
        } > "$RESULTS_MD"
    fi
    printf "| %s | %s | **%s** | %s |\n" "$(date +%Y-%m-%d\ %H:%M)" "$label" "$status" "$detail" >> "$RESULTS_MD"
}

cmd_matrix() {
    echo "Running preset matrix: ${#PRESETS[@]} apps"
    echo "Follow each prompt — open the app, tap a text field, press Enter."
    echo "Skip any app with Ctrl+C during its prompt (they're independent runs)."
    echo ""
    for label in "${!PRESETS[@]}"; do
        cmd_single "$label" "$PHRASE_DEFAULT"
    done
    echo ""
    echo "Matrix complete. Results in: $RESULTS_MD"
}

cmd_report() {
    if [[ -f "$RESULTS_MD" ]]; then
        cat "$RESULTS_MD"
    else
        echo "No results yet. Run: $0 <app-label> first."
    fi
}

case "${1:-}" in
    --matrix) cmd_matrix ;;
    --report) cmd_report ;;
    --help|-h|"")
        echo "Usage:"
        echo "  $0 <app-label> [phrase]     Run a single test"
        echo "  $0 --matrix                 Run full preset matrix (interactive)"
        echo "  $0 --report                 Print accumulated results table"
        echo ""
        echo "Preset labels:"
        for k in "${!PRESETS[@]}"; do
            echo "  $k"
        done | sort
        ;;
    *) cmd_single "$@" ;;
esac
