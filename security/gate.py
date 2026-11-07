#!/usr/bin/env python3
"""Evaluate preserved scanner JSON. Exit 0=pass/report-only, 10=blocked, 2=invalid.

Only Dependency-Check findings can use the four reviewed exceptions. Trivy
findings require separate evidence before any exception is accepted there.
"""
import argparse
import datetime as dt
import fnmatch
import hashlib
import json
import math
import sys
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def flatten(dependencies):
    require(isinstance(dependencies, list), "dependencies must be a list")
    for dependency in dependencies:
        require(isinstance(dependency, dict), "invalid dependency entry")
        yield dependency
        yield from flatten(dependency.get("relatedDependencies") or [])


def load_policy(path):
    policy = json.loads(path.read_text(encoding="utf-8"))
    require(policy.get("schema_version") == 1, "unsupported exception policy schema")
    rules = policy.get("exceptions")
    require(isinstance(rules, list), "missing exception rules")
    ids = set()
    for rule in rules:
        for key in ("id", "cve", "package_url", "sha256", "classification", "reason", "evidence", "review_by"):
            require(isinstance(rule.get(key), str) and rule[key], f"invalid exception field: {key}")
        require(rule["id"] not in ids, "duplicate exception id")
        ids.add(rule["id"])
        require(len(rule["sha256"]) == 64, "invalid exception SHA-256")
        require(isinstance(rule.get("absent_artifacts"), list), "missing exception conditions")
        require(all(isinstance(pattern, str) and pattern for pattern in rule["absent_artifacts"]), "invalid artifact pattern")
        dt.date.fromisoformat(rule["review_by"])
    return policy


def cvss_score(vulnerability):
    scores = []
    for version, field in (("cvssv2", "score"), ("cvssv3", "baseScore"), ("cvssv4", "baseScore")):
        value = vulnerability.get(version) or {}
        if value.get(field) is not None:
            score = float(value[field])
            require(math.isfinite(score) and 0 <= score <= 10, "invalid CVSS score")
            scores.append(score)
    return max(scores) if scores else None


def dependency_findings(report, policy, threshold, today):
    require(report.get("scanInfo", {}).get("engineVersion"), "missing Dependency-Check scan metadata")
    require(report.get("projectInfo", {}).get("reportDate"), "missing Dependency-Check report date")
    dependencies = list(flatten(report.get("dependencies")))
    require(dependencies, "empty Dependency-Check dependency inventory")
    names = [item.get("fileName", "") for item in dependencies]
    findings = []
    for dependency in dependencies:
        package_urls = [item.get("id") for item in (dependency.get("packages") or [])]
        vulnerabilities = dependency.get("vulnerabilities") or []
        require(isinstance(vulnerabilities, list), "invalid vulnerability list")
        for vulnerability in vulnerabilities:
            cve = vulnerability.get("name")
            require(cve, "missing vulnerability identifier")
            exception = None
            for rule in policy["exceptions"]:
                if (rule["cve"] == cve
                        and rule["package_url"] in package_urls
                        and rule["sha256"] == dependency.get("sha256")
                        and today < dt.date.fromisoformat(rule["review_by"])
                        and not any(fnmatch.fnmatchcase(name, pattern)
                                    for name in names for pattern in rule["absent_artifacts"])):
                    exception = rule
                    break
            score = cvss_score(vulnerability)
            # Unscored findings need review when enforcing a numeric gate.
            blocked = threshold <= 10 and exception is None and (score is None or score >= threshold)
            findings.append({"cve": cve, "artifact": dependency.get("fileName"),
                             "cvss": score, "severity": vulnerability.get("severity"),
                             "decision": "exception" if exception else "block" if blocked else "report",
                             "exception": exception["id"] if exception else None})
    return findings


def trivy_findings(report, severities):
    require(report.get("SchemaVersion") == 2, "unsupported Trivy report schema")
    require(report.get("ArtifactType") == "container_image", "Trivy report is not a container image scan")
    require(report.get("ArtifactName"), "missing Trivy image identity")
    results = report.get("Results")
    require(isinstance(results, list), "missing Trivy results")
    findings = []
    for result in results:
        vulnerabilities = result.get("Vulnerabilities") or []
        require(isinstance(vulnerabilities, list), "invalid Trivy vulnerability list")
        for vulnerability in vulnerabilities:
            cve, severity = vulnerability.get("VulnerabilityID"), vulnerability.get("Severity")
            require(cve and severity in {"UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"}, "invalid Trivy finding")
            # UNKNOWN is not implicitly treated as safe while enforcing a gate.
            blocked = severity in severities or (bool(severities) and severity == "UNKNOWN")
            findings.append({"cve": cve, "artifact": vulnerability.get("PkgName"),
                             "version": vulnerability.get("InstalledVersion"),
                             "severity": severity, "decision": "block" if blocked else "report"})
    return findings


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tool", choices=("dependency-check", "trivy"))
    parser.add_argument("report", type=Path)
    parser.add_argument("--cvss", type=float, default=9)
    parser.add_argument("--severity", default="CRITICAL", help="empty string = report only")
    parser.add_argument("--expected-image-id", help="require the Trivy report to match this local image ID")
    parser.add_argument("--exceptions", type=Path, default=Path(__file__).with_name("exceptions.json"))
    args = parser.parse_args(argv)
    if args.report.name.casefold() == "gate-summary.json":
        parser.error("the scanner report cannot be the gate output file")
    summary_path = args.report.with_name("gate-summary.json")
    summary = {"tool": args.tool, "status": "error"}
    try:
        require(math.isfinite(args.cvss) and (0 <= args.cvss <= 10 or args.cvss == 11), "invalid CVSS threshold")
        severities = set(args.severity.split(",")) if args.severity else set()
        require(severities <= {"UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"}, "invalid severity gate")
        raw = args.report.read_bytes()
        report = json.loads(raw)
        policy = load_policy(args.exceptions)
        if args.tool == "trivy" and args.expected_image_id:
            require(report.get("Metadata", {}).get("ImageID") == args.expected_image_id,
                    "Trivy report does not match the expected local image ID")
        today = dt.datetime.now(dt.timezone.utc).date()
        findings = (dependency_findings(report, policy, args.cvss, today)
                    if args.tool == "dependency-check" else trivy_findings(report, severities))
        blocked = sum(item["decision"] == "block" for item in findings)
        applied = sum(item["decision"] == "exception" for item in findings)
        summary.update({"policy": policy["policy"], "evaluated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                        "source_report_sha256": hashlib.sha256(raw).hexdigest(),
                        "exception_policy_sha256": hashlib.sha256(args.exceptions.read_bytes()).hexdigest(),
                        "threshold": args.cvss if args.tool == "dependency-check" else sorted(severities),
                        "report_only": args.cvss == 11 if args.tool == "dependency-check" else not severities,
                        "finding_count": len(findings), "blocked_count": blocked, "exception_count": applied,
                        "expired_exceptions": [rule["id"] for rule in policy["exceptions"]
                                               if today >= dt.date.fromisoformat(rule["review_by"])],
                        "status": "blocked" if blocked else "passed", "findings": findings})
        status = 10 if blocked else 0
        print(f"{args.tool}: {len(findings)} findings, {applied} exceptions, {blocked} blocked")
        if summary["expired_exceptions"]:
            print("Expired exceptions are inactive: " + ", ".join(summary["expired_exceptions"]), file=sys.stderr)
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        summary["error"] = str(error)
        print(f"ERROR: cannot evaluate security gate: {error}", file=sys.stderr)
        status = 2
    try:
        summary_path.parent.mkdir(parents=True, exist_ok=True)
        summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except OSError as error:
        print(f"ERROR: cannot save gate evidence: {error}", file=sys.stderr)
        return 2
    return status


if __name__ == "__main__":
    sys.exit(main())
