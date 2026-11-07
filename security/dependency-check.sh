#!/usr/bin/env bash
# Scan Maven dependencies. Run with Bash on Linux/WSL, from any directory.
set -euo pipefail

# 1. Inputs: gate v1 blocks CVSS >= 9; use 11 explicitly for report-only mode.
if [[ ${1:-} == --help ]]; then
    echo 'Usage: bash security/dependency-check.sh'
    echo 'Required env: NVD_API_KEY (workaround for the pinned 13.0.0 no-key bug)'
    echo 'Optional env: DC_FAIL_ON_CVSS (default 9; 11 = report only)'
    exit 0
fi
if (( $# != 0 )); then
    echo 'ERROR: unexpected arguments; use --help.' >&2
    exit 2
fi
readonly PLUGIN_VERSION='13.0.0'
readonly FAIL_ON_CVSS="${DC_FAIL_ON_CVSS:-9}"
if [[ ! $FAIL_ON_CVSS =~ ^([0-9](\.[0-9]+)?|10(\.0+)?|11)$ ]]; then
    echo 'ERROR: DC_FAIL_ON_CVSS must be 0..10, or 11 for report-only mode.' >&2
    exit 2
fi
# Dependency-Check 13.0.0 has an upstream regression for anonymous NVD access.
# https://github.com/dependency-check/DependencyCheck/issues/8715
if [[ -z ${NVD_API_KEY:-} ]]; then
    echo 'ERROR: NVD_API_KEY is required with pinned Dependency-Check 13.0.0 (upstream issue #8715).' >&2
    echo 'Request/activate a key at https://nvd.nist.gov/developers/request-an-api-key and export it.' >&2
    exit 2
fi
if [[ $NVD_API_KEY == *[[:space:]]* ]]; then
    echo 'ERROR: NVD_API_KEY contains whitespace; re-enter the key without spaces/newlines.' >&2
    exit 2
fi

# 2. Resolve paths relative to this script, not the caller's current directory.
readonly PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPORT_DIR="$PROJECT_ROOT/target/security-reports/dependency-check"
cd "$PROJECT_ROOT"
command -v python3 >/dev/null || { echo 'ERROR: install Python 3 first.' >&2; exit 2; }
[[ -f mvnw && -f pom.xml ]] || { echo 'ERROR: Maven project not found.' >&2; exit 2; }
mkdir -p "$REPORT_DIR"
# Remove only our previous reports so a failed scan cannot look like a fresh one.
rm -f -- "$REPORT_DIR/dependency-check-report.html" "$REPORT_DIR/dependency-check-report.json" "$REPORT_DIR/gate-summary.json"

# 3. An array preserves each argument even when paths contain spaces.
# Pass the NAME of the environment variable, never the actual API key in argv.
args=(
    --batch-mode --no-transfer-progress
    "org.owasp:dependency-check-maven:${PLUGIN_VERSION}:check"
    "-Dodc.outputDirectory=$REPORT_DIR"
    '-Dformats=HTML,JSON'
    # Keep the original report intact; gate.py applies scoped exceptions later.
    '-DfailBuildOnCVSS=11'
    '-DfailOnError=true'
    '-DnvdApiKeyEnvironmentVariable=NVD_API_KEY'
)

# 4. Preserve Maven's failure status; never turn a scanner failure into success.
echo "Dependency-Check $PLUGIN_VERSION; CVSS gate: $FAIL_ON_CVSS (11 = report only)"
if bash ./mvnw "${args[@]}"; then
    [[ -s "$REPORT_DIR/dependency-check-report.html" && -s "$REPORT_DIR/dependency-check-report.json" ]] || {
        echo 'ERROR: scan returned success but expected reports are missing.' >&2
        exit 1
    }
    echo "Scan completed; original reports: $REPORT_DIR"
    python3 security/gate.py dependency-check "$REPORT_DIR/dependency-check-report.json" --cvss "$FAIL_ON_CVSS"
else
    status=$?
    echo "ERROR: Dependency-Check scan failed (exit $status); vulnerability gate was not evaluated." >&2
    echo "Inspect Maven output and any reports in: $REPORT_DIR" >&2
    exit "$status"
fi
