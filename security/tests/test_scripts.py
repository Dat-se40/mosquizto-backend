"""Run real Bash wrappers against fake scanners, never using network or credentials."""
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


@unittest.skipUnless(os.name == "posix" and shutil.which("bash"), "requires Linux/WSL Bash")
class ScriptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mosquizto scripts ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        source = Path(__file__).resolve().parents[1]
        shutil.copytree(source, self.root / "security", ignore=shutil.ignore_patterns("tests", "__pycache__"))
        (self.root / "pom.xml").touch()
        (self.root / "bin").mkdir()
        self.env = dict(os.environ, PATH=str(self.root / "bin") + os.pathsep + os.environ["PATH"],
                        NVD_API_KEY="mock-key-not-a-secret", MOCK_ROOT=str(self.root), PYTHONDONTWRITEBYTECODE="1")
        (self.root / "mvnw").write_text('''#!/usr/bin/env bash
set -eu
printf '%s\\n' "$@" > "$MOCK_ROOT/maven-args.txt"
[[ ${MOCK_SCAN_ERROR:-0} == 0 ]] || exit 7
for arg in "$@"; do
    case "$arg" in -Dodc.outputDirectory=*) out=${arg#*=};; esac
done
cp "$MOCK_ROOT/dc.json" "$out/dependency-check-report.json"
printf '<html>mock</html>' > "$out/dependency-check-report.html"
''')
        self.command("docker", '''#!/usr/bin/env bash
[[ ${MOCK_DOCKER_ERROR:-0} == 0 ]] || exit 1
printf 'sha256:local-image\\n'
''')
        self.command("trivy", '''#!/usr/bin/env bash
set -eu
[[ $1 != --version ]] || { echo 'Version: 0.76.2'; exit 0; }
[[ ${MOCK_SCAN_ERROR:-0} == 0 ]] || exit 7
previous=''
for arg in "$@"; do
    [[ $previous != --output ]] || out=$arg
    previous=$arg
done
if [[ $1 == image ]]; then cp "$MOCK_ROOT/trivy.json" "$out"; else echo 'mock table' > "$out"; fi
''')
        self.dc = {"scanInfo": {"engineVersion": "13.0.0"}, "projectInfo": {"reportDate": "2026-11-07"},
                   "dependencies": [{"fileName": "other-1.jar", "vulnerabilities": [
                       {"name": "CVE-2099-1234", "severity": "CRITICAL", "cvssv3": {"baseScore": 9.8}}]}]}
        self.trivy = {"SchemaVersion": 2, "ArtifactName": "demo:1", "ArtifactType": "container_image",
                      "Metadata": {"ImageID": "sha256:local-image"}, "Results": [{"Vulnerabilities": [
                          {"VulnerabilityID": "CVE-2099-1234", "Severity": "CRITICAL", "PkgName": "other"}]}]}
        (self.root / "dc.json").write_text(json.dumps(self.dc))
        (self.root / "trivy.json").write_text(json.dumps(self.trivy))

    def command(self, name, script):
        path = self.root / "bin" / name
        path.write_text(script)
        path.chmod(0o700)

    def run_script(self, name, *arguments, **environment):
        result = subprocess.run(["bash", str(self.root / "security" / name), *arguments],
                                cwd="/tmp", env=dict(self.env, **environment), capture_output=True, text=True)
        return result

    def test_dependency_scan_preserves_raw_report_and_enforces_gate(self):
        result = self.run_script("dependency-check.sh")
        self.assertEqual(result.returncode, 10, result.stdout + result.stderr)
        args = (self.root / "maven-args.txt").read_text()
        self.assertIn("-DfailBuildOnCVSS=11", args)
        self.assertIn("-DnvdApiKeyEnvironmentVariable=NVD_API_KEY", args)
        self.assertNotIn(self.env["NVD_API_KEY"], args + result.stdout + result.stderr)
        raw = self.root / "target/security-reports/dependency-check/dependency-check-report.json"
        self.assertEqual(json.loads(raw.read_text()), self.dc)
        self.assertEqual(self.run_script("dependency-check.sh", DC_FAIL_ON_CVSS="11").returncode, 0)

    def test_missing_key_and_scanner_error_cannot_pass(self):
        self.assertEqual(self.run_script("dependency-check.sh", NVD_API_KEY="").returncode, 2)
        self.assertEqual(self.run_script("dependency-check.sh", MOCK_SCAN_ERROR="1").returncode, 7)

    def test_trivy_blocks_critical_and_checks_image_identity(self):
        result = self.run_script("trivy-scan.sh", "demo:1")
        self.assertEqual(result.returncode, 10, result.stdout + result.stderr)
        self.assertEqual(self.run_script("trivy-scan.sh", "demo:1", TRIVY_FAIL_SEVERITY="").returncode, 0)
        self.trivy["Metadata"]["ImageID"] = "sha256:other-image"
        (self.root / "trivy.json").write_text(json.dumps(self.trivy))
        self.assertEqual(self.run_script("trivy-scan.sh", "demo:1", TRIVY_FAIL_SEVERITY="").returncode, 2)

    def test_trivy_missing_image_and_scanner_error_cannot_pass(self):
        self.assertEqual(self.run_script("trivy-scan.sh").returncode, 2)
        self.assertEqual(self.run_script("trivy-scan.sh", "demo:1", MOCK_DOCKER_ERROR="1").returncode, 1)
        self.assertEqual(self.run_script("trivy-scan.sh", "demo:1", MOCK_SCAN_ERROR="1").returncode, 7)


if __name__ == "__main__":
    unittest.main()
