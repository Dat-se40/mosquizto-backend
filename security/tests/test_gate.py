"""Offline policy tests; no Maven, Docker, credentials or network required."""
import copy
import datetime as dt
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import gate


class GateTests(unittest.TestCase):
    def setUp(self):
        self.policy_path = Path(gate.__file__).with_name("exceptions.json")
        self.policy = gate.load_policy(self.policy_path)
        self.today = dt.date(2026, 11, 7)

    def dependency(self, rule):
        artifact = rule["package_url"].rsplit("/", 1)[1].replace("@", "-") + ".jar"
        return {"fileName": artifact, "sha256": rule["sha256"],
                "packages": [{"id": rule["package_url"]}],
                "vulnerabilities": [{"name": rule["cve"], "severity": "CRITICAL",
                                     "cvssv3": {"baseScore": 9.8}}]}

    def report(self, dependencies):
        return {"scanInfo": {"engineVersion": "13.0.0"},
                "projectInfo": {"reportDate": "2026-11-07T04:48:50Z"},
                "dependencies": dependencies}

    def trivy_report(self, severity="CRITICAL"):
        return {"SchemaVersion": 2, "ArtifactType": "container_image", "ArtifactName": "demo:1",
                "Metadata": {"ImageID": "sha256:expected"},
                "Results": [{"Vulnerabilities": [{"VulnerabilityID": "CVE-2024-7776",
                    "PkgName": "com.microsoft.onnxruntime:onnxruntime", "InstalledVersion": "1.11.0",
                    "Severity": severity}]}]}

    def test_only_four_exact_reviewed_exceptions_apply_and_raw_report_is_unchanged(self):
        report = self.report([self.dependency(rule) for rule in self.policy["exceptions"]])
        original = copy.deepcopy(report)
        findings = gate.dependency_findings(report, self.policy, 9, self.today)
        self.assertEqual([item["decision"] for item in findings], ["exception"] * 4)
        self.assertEqual(report, original)

    def test_wrong_hash_or_version_or_cve_does_not_get_an_exception(self):
        for field in ("hash", "version", "cve"):
            with self.subTest(field=field):
                dependency = self.dependency(self.policy["exceptions"][0])
                if field == "hash":
                    dependency["sha256"] = "0" * 64
                elif field == "version":
                    dependency["packages"][0]["id"] += "-other"
                else:
                    dependency["vulnerabilities"][0]["name"] = "CVE-2099-1234"
                self.assertEqual(gate.dependency_findings(self.report([dependency]), self.policy, 9, self.today)[0]["decision"], "block")

    def test_added_jms_or_webflux_in_nested_inventory_disables_spring_exception(self):
        for rule in self.policy["exceptions"][1:]:
            dependency = self.dependency(rule)
            dependency["relatedDependencies"] = [{"fileName": rule["absent_artifacts"][0].replace("*", "6.1.5")}]
            finding = gate.dependency_findings(self.report([dependency]), self.policy, 9, self.today)[0]
            self.assertEqual(finding["decision"], "block")

    def test_expired_exception_is_inactive_on_review_date(self):
        dependency = self.dependency(self.policy["exceptions"][0])
        findings = gate.dependency_findings(self.report([dependency]), self.policy, 9, dt.date(2026, 12, 7))
        self.assertEqual(findings[0]["decision"], "block")

    def test_numeric_boundary_unscored_and_report_only(self):
        dependency = {"fileName": "other-1.jar", "vulnerabilities": [
            {"name": "CVE-2099-0001", "cvssv3": {"baseScore": 8.9}},
            {"name": "CVE-2099-0002", "cvssv3": {"baseScore": 9.0}},
            {"name": "CVE-2099-0003"}]}
        report = self.report([dependency])
        self.assertEqual([f["decision"] for f in gate.dependency_findings(report, self.policy, 9, self.today)],
                         ["report", "block", "block"])
        self.assertTrue(all(f["decision"] == "report" for f in gate.dependency_findings(report, self.policy, 11, self.today)))

    def test_trivy_does_not_inherit_dependency_check_exceptions(self):
        self.assertEqual(gate.trivy_findings(self.trivy_report(), {"CRITICAL"})[0]["decision"], "block")
        self.assertEqual(gate.trivy_findings(self.trivy_report("HIGH"), {"CRITICAL"})[0]["decision"], "report")
        self.assertEqual(gate.trivy_findings(self.trivy_report("UNKNOWN"), {"CRITICAL"})[0]["decision"], "block")
        self.assertEqual(gate.trivy_findings(self.trivy_report(), set())[0]["decision"], "report")

    def test_invalid_or_empty_reports_fail_closed(self):
        with self.assertRaises(ValueError):
            gate.dependency_findings(self.report([]), self.policy, 9, self.today)
        with self.assertRaises(ValueError):
            gate.trivy_findings({"Results": []}, {"CRITICAL"})
        with self.assertRaises(ValueError):
            gate.dependency_findings({"dependencies": [{}]}, self.policy, 9, self.today)

    def test_cli_preserves_raw_evidence_and_reports_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trivy-report.json"
            raw = json.dumps(self.trivy_report()).encode()
            path.write_bytes(raw)
            self.assertEqual(gate.main(["trivy", str(path), "--expected-image-id", "sha256:expected"]), 10)
            summary = json.loads(path.with_name("gate-summary.json").read_text())
            self.assertEqual(summary["source_report_sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(summary["blocked_count"], 1)
            self.assertEqual(path.read_bytes(), raw)
            self.assertEqual(gate.main(["trivy", str(path), "--expected-image-id", "sha256:wrong"]), 2)
            path.write_text("broken json")
            self.assertEqual(gate.main(["trivy", str(path)]), 2)
            path.unlink()
            self.assertEqual(gate.main(["trivy", str(path)]), 2)

    def test_gate_output_cannot_overwrite_an_input_report(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gate-summary.json"
            path.write_text("preserve me")
            with self.assertRaises(SystemExit) as error:
                gate.main(["trivy", str(path)])
            self.assertEqual(error.exception.code, 2)
            self.assertEqual(path.read_text(), "preserve me")


if __name__ == "__main__":
    unittest.main()
