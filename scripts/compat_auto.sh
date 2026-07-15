#!/bin/bash
#
# WiM automated compatibility runner.
#
# For each installed target app:
#   1. Launch via monkey intent
#   2. Wait for UI to settle
#   3. Dump accessibility tree
#   4. Find the first visible editable node (if any)
#   5. Tap its center to focus it
#   6. Fire WimTestReceiver.inject_raw with a test phrase
#   7. Re-dump and verify the phrase landed in the field
#   8. Append result to compat_results/results.md
#
# Apps whose main activity doesn't present an editable field immediately
# (most messaging apps that open to a chat list) are marked SKIPPED. Those
# need navigation that can't be automated generically — re-run compat_test.sh
# for those manually.

set -u

ADB="${WIM_ADB:-/c/Users/georg/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/../compat_results"
RESULTS_MD="$RESULTS_DIR/results.md"
PHRASE="hello wim compat zulu foxtrot bravo"

mkdir -p "$RESULTS_DIR"

declare -A APPS=(
    ["gmail"]="com.google.android.gm"
    ["google-messages"]="com.google.android.apps.messaging"
    ["whatsapp"]="com.whatsapp"
    ["telegram"]="org.telegram.messenger"
    ["signal"]="org.thoughtcrime.securesms"
    ["discord"]="com.discord"
    ["fb-messenger"]="com.facebook.orca"
    ["instagram"]="com.instagram.android"
    ["linkedin"]="com.linkedin.android"
    ["reddit"]="com.reddit.frontpage"
    ["outlook"]="com.microsoft.office.outlook"
    ["samsung-messages"]="com.samsung.android.messaging"
)

# Filter to apps actually installed
INSTALLED_LIST=$("$ADB" shell pm list packages 2>&1 | sed 's/package://' | tr -d '\r')

# Initialize results file if missing
if [[ ! -f "$RESULTS_MD" ]]; then
    {
        echo "# WiM Compatibility Matrix"
        echo ""
        echo "| When | App | Status | Detail |"
        echo "|---|---|---|---|"
    } > "$RESULTS_MD"
fi

test_app() {
    local label="$1"
    local pkg="$2"
    local ts=$(date +%Y%m%d-%H%M%S)
    local dump_file="$RESULTS_DIR/${ts}_${label}_tree.xml"

    echo ""
    echo "=== $label ($pkg) ==="

    # Launch
    "$ADB" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 3

    # Dump tree
    "$ADB" shell uiautomator dump /sdcard/tree.xml >/dev/null 2>&1
    "$ADB" pull /sdcard/tree.xml "$dump_file" >/dev/null 2>&1

    # Find first visible editable node with bounds
    local bounds
    bounds=$(python -c "
import re
with open(r'$dump_file', encoding='utf-8', errors='ignore') as f:
    content = f.read()
# Find EditText-like nodes with editable=true
pattern = re.compile(r'<node[^>]*?editable=\"true\"[^>]*?bounds=\"(\[\d+,\d+\]\[\d+,\d+\])\"[^>]*?visibility=')
m = pattern.search(content) or re.search(r'<node[^>]*?class=\"[^\"]*EditText[^\"]*\"[^>]*?bounds=\"(\[\d+,\d+\]\[\d+,\d+\])\"', content)
if m:
    print(m.group(1))
" 2>/dev/null)

    if [[ -z "$bounds" ]]; then
        echo "  SKIP: no editable field on main screen"
        printf '| %s | %s | **SKIP** | no editable field on launch — needs navigation |\n' \
            "$(date +%Y-%m-%d\ %H:%M)" "$label" >> "$RESULTS_MD"
        return
    fi

    # Parse bounds → center coordinates. bounds format: [l,t][r,b]
    local cx cy
    IFS=',' read -r cx cy < <(python -c "
import re, sys
b = '$bounds'
nums = list(map(int, re.findall(r'\d+', b)))
cx = (nums[0] + nums[2]) // 2
cy = (nums[1] + nums[3]) // 2
print(f'{cx},{cy}')
")

    echo "  editable field at ($cx, $cy), tapping..."
    "$ADB" shell input tap "$cx" "$cy" >/dev/null
    sleep 2

    # Clear logcat, then inject
    "$ADB" logcat -c 2>/dev/null
    "$ADB" shell am broadcast -a com.wimlabs.wim.TEST \
        -n com.wimlabs.wim/com.wim.app.WimTestReceiver \
        --es action inject_raw --es raw_text "$PHRASE" >/dev/null 2>&1
    sleep 3

    # Re-dump and verify
    "$ADB" shell uiautomator dump /sdcard/tree2.xml >/dev/null 2>&1
    "$ADB" pull /sdcard/tree2.xml "${dump_file%.xml}_after.xml" >/dev/null 2>&1

    local clean_phrase
    clean_phrase=$(echo "$PHRASE" | tr '[:upper:]' '[:lower:]')

    local status detail
    if grep -q "$clean_phrase" "${dump_file%.xml}_after.xml" 2>/dev/null; then
        status="PASS"
        detail="phrase landed in UI"
    else
        # Check logcat for specific failure signals
        local logcat
        logcat=$("$ADB" logcat -d -t 80 2>/dev/null | grep -iE "WiM|toast|injectText" | tail -20)
        if echo "$logcat" | grep -q "Re-found focused editable"; then
            status="FAIL"
            detail="window-walker found a field but text didn't land"
        elif echo "$logcat" | grep -q "toast_tap_field"; then
            status="FAIL"
            detail="no focused editable found in any window"
        else
            status="INCONCLUSIVE"
            detail="no clear signal — check logcat/dump"
        fi
    fi

    echo "  → $status: $detail"
    printf '| %s | %s | **%s** | %s |\n' \
        "$(date +%Y-%m-%d\ %H:%M)" "$label" "$status" "$detail" >> "$RESULTS_MD"
}

# Make sure bubble + a11y are up
"$ADB" shell am broadcast -a com.wimlabs.wim.TEST -n com.wimlabs.wim/com.wim.app.WimTestReceiver --es action ping >/dev/null
sleep 1
ping_result=$("$ADB" shell "run-as com.wimlabs.wim cat files/test_result.json" 2>&1)
if ! echo "$ping_result" | grep -q '"bubble_running": true'; then
    echo "Bubble not running — aborting"
    exit 1
fi
if ! echo "$ping_result" | grep -q '"a11y_running": true'; then
    echo "Accessibility service not running — aborting"
    exit 1
fi

for label in "${!APPS[@]}"; do
    pkg="${APPS[$label]}"
    if ! echo "$INSTALLED_LIST" | grep -q "^$pkg$"; then
        continue  # not installed on this device
    fi
    test_app "$label" "$pkg"
done

echo ""
echo "=== DONE ==="
echo "Full results: $RESULTS_MD"
