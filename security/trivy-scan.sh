#!/usr/bin/env bash
# Scan an already-built LOCAL Docker image before pushing it to a registry.
set -euo pipefail

# 1. Require an explicit image. No guessed registry or implicit latest tag.
if [[ ${1:-} == --help ]]; then
    echo 'Usage: bash security/trivy-scan.sh IMAGE_REFERENCE'
    echo 'Optional env: TRIVY_FAIL_SEVERITY (default CRITICAL; empty = report only)'
    exit 0
fi
if (( $# != 1 )) || [[ -z $1 || $1 == -* ]]; then
    echo 'ERROR: supply one local image reference; use --help.' >&2
    exit 2
fi
readonly IMAGE_REF="$1"
readonly FAIL_SEVERITY="${TRIVY_FAIL_SEVERITY-CRITICAL}"
if [[ -n $FAIL_SEVERITY && ! $FAIL_SEVERITY =~ ^(UNKNOWN|LOW|MEDIUM|HIGH|CRITICAL)(,(UNKNOWN|LOW|MEDIUM|HIGH|CRITICAL))*$ ]]; then
    echo 'ERROR: TRIVY_FAIL_SEVERITY must be comma-separated uppercase severity names.' >&2
    exit 2
fi
for tool in docker trivy python3; do
    command -v "$tool" >/dev/null || { echo "ERROR: install $tool first." >&2; exit 2; }
done

# 2. Resolve the local immutable image ID; do not silently pull another image.
readonly PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPORT_DIR="$PROJECT_ROOT/target/security-reports/trivy"
cd "$PROJECT_ROOT"
mkdir -p "$REPORT_DIR"
rm -f -- "$REPORT_DIR/trivy-report.json" "$REPORT_DIR/trivy-report.txt" "$REPORT_DIR/gate-summary.json"
image_id=$(docker image inspect --format '{{.Id}}' "$IMAGE_REF") || {
    echo "ERROR: cannot inspect local image $IMAGE_REF; build it and check Docker access." >&2
    exit 1
}
[[ -n $image_id ]] || { echo 'ERROR: Docker returned an empty image ID.' >&2; exit 1; }
trivy --version
echo "Scanning $IMAGE_REF ($image_id)"

# 3. Scan once, keeping ALL severities and unfixed findings in the JSON report.
# exit-code 0 affects findings only; scanner/download errors still fail the script.
trivy image --image-src docker --scanners vuln \
    --severity UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL --ignore-unfixed=false \
    --timeout 10m --no-progress --exit-code 0 \
    --format json --output "$REPORT_DIR/trivy-report.json" "$image_id"
trivy convert --format table --exit-code 0 \
    --output "$REPORT_DIR/trivy-report.txt" "$REPORT_DIR/trivy-report.json"
echo "Reports: $REPORT_DIR"

# 4. Evaluate the SAME JSON. Dependency-Check exceptions are not reused for Trivy.
python3 security/gate.py trivy "$REPORT_DIR/trivy-report.json" \
    --severity "$FAIL_SEVERITY" --expected-image-id "$image_id"
