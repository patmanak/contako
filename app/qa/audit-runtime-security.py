"""Audit an explicitly hash-locked APK and runtime inventory, without uploading either.

Only public package names/versions are sent to api.osv.dev. Go metadata and scans
use official local tools. A stripped library MUST NOT be reported as symbol-qualified:
govulncheck can emit function-shaped findings from module metadata in this case.
This script does not alter dependencies, install APKs, or access accounts/devices.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from zipfile import ZipFile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def locked(path, expected):
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected) or digest(path) != expected.lower():
        raise ValueError("INPUT_HASH_MISMATCH")


def query_batch(queries):
    findings = [[] for _ in queries]
    for start in range(0, len(queries), 50):
        pending = [(i, dict(queries[i])) for i in range(start, min(start + 50, len(queries)))]
        for page in range(10):
            if not pending:
                break
            request = urllib.request.Request(
                "https://api.osv.dev/v1/querybatch",
                data=json.dumps({"queries": [q for _, q in pending]}).encode(),
                headers={"Content-Type": "application/json"},
            )
            for attempt in range(3):
                try:
                    with urllib.request.urlopen(request, timeout=25) as response:
                        raw = response.read(4_000_001)
                    if len(raw) > 4_000_000:
                        raise ValueError("OSV_RESPONSE_TOO_LARGE")
                    rows = json.loads(raw)["results"]
                    if len(rows) != len(pending):
                        raise ValueError("OSV_RESPONSE_COUNT_MISMATCH")
                    break
                except urllib.error.HTTPError as error:
                    if attempt == 2 or error.code not in (429, 500, 502, 503, 504):
                        raise
                    time.sleep(2 ** attempt)
            next_queries = []
            for (index, query), row in zip(pending, rows):
                if not isinstance(row, dict) or "error" in row:
                    raise ValueError("OSV_QUERY_FAILED")
                findings[index].extend(item["id"] for item in row.get("vulns", []))
                if row.get("next_page_token"):
                    next_queries.append((index, {**query, "page_token": row["next_page_token"]}))
            pending = next_queries
        if pending:
            raise ValueError("OSV_PAGINATION_LIMIT")
    return [{**q, "advisories": sorted(set(ids))} for q, ids in zip(queries, findings)]


def run(command, environment):
    return subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                          errors="strict", timeout=180, env=environment,
                          creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)


def json_stream(text):
    decoder = json.JSONDecoder()
    while text.strip():
        text = text.lstrip()
        value, end = decoder.raw_decode(text)
        yield value
        text = text[end:]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("apk", "manifest", "go", "govulncheck", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--expected-manifest-sha256", required=True)
    args = parser.parse_args()
    # Reject stale inputs before network calls or creating output files.
    locked(args.apk, args.expected_apk_sha256)
    locked(args.manifest, args.expected_manifest_sha256)
    manifest = json.loads(args.manifest.read_text(encoding="utf-8-sig"))
    coordinates = sorted({item["coordinates"] for item in manifest["artifacts"]})
    if not coordinates or len(manifest["artifacts"]) != manifest["artifactCount"]:
        raise ValueError("INVENTORY_COUNT_MISMATCH")
    if (args.output / "runtime-security.json").exists():
        raise ValueError("COMPLETED_OUTPUT_ALREADY_EXISTS; choose a new output directory")
    queries = []
    for coordinate in coordinates:
        group, name, version = coordinate.split(":")
        queries.append({"package": {"ecosystem": "Maven", "name": group + ":" + name},
                        "version": version})
    args.output.mkdir(parents=True, exist_ok=True)
    cache = args.output.resolve().parent / "security-tool-cache"
    environment = {**os.environ, "GOMAXPROCS": "2", "GOTOOLCHAIN": "local",
                   "GOPATH": str(cache / "gopath"), "GOCACHE": str(cache / "gocache")}
    report = {"schema": 1, "apk_sha256": digest(args.apk), "manifest_sha256": digest(args.manifest),
              "tools": {"go_sha256": digest(args.go), "govulncheck_sha256": digest(args.govulncheck)},
              "scan_started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "maven": query_batch(queries), "native": [],
              "ceiling": "Version matches are not exploitability proof; no-match is not a security guarantee."}
    with ZipFile(args.apk) as archive:
        for index, entry in enumerate(archive.infolist()):
            if not entry.filename.startswith("lib/") or not entry.filename.endswith(".so"):
                continue
            if entry.file_size > 100_000_000:
                raise ValueError("NATIVE_LIBRARY_TOO_LARGE")
            # Generated filename prevents ZIP paths from choosing a filesystem destination.
            library = args.output / ("native-" + str(index) + ".so")
            library.write_bytes(archive.read(entry))
            row = {"apk_entry": entry.filename, "sha256": digest(library)}
            metadata = run([str(args.go), "version", "-m", str(library)], environment)
            version = re.search(r": (go\d+\.\d+(?:\.\d+)?)\s*$", metadata.stdout.splitlines()[0]) if metadata.stdout else None
            if metadata.returncode != 0 or version is None:
                row["status"] = "NOT_GO_OR_METADATA_UNAVAILABLE; native origin review required"
                report["native"].append(row)
                continue
            row["go_version"] = version[1]
            native_queries = [{"package": {"ecosystem": "Go", "name": "stdlib"}, "version": version[1][2:]}]
            for line in metadata.stdout.splitlines()[1:]:
                fields = line.strip().split("\t")
                if fields[0] == "=>":
                    raise ValueError("GO_REPLACEMENT_REQUIRES_REVIEW")
                if fields[0] == "dep":
                    native_queries.append({"package": {"ecosystem": "Go", "name": fields[1]}, "version": fields[2]})
            row["modules"] = query_batch(native_queries)
            scanned = run([str(args.govulncheck), "-mode=binary", "-json", str(library)], environment)
            if scanned.returncode != 0:
                raise ValueError("GOVULNCHECK_EXECUTION_FAILED")
            records = list(json_stream(scanned.stdout))
            config = next((item["config"] for item in records if "config" in item), None)
            if not isinstance(config, dict):
                raise ValueError("GOVULNCHECK_CONFIG_MISSING")
            if not any("SBOM" in item for item in records):
                raise ValueError("GOVULNCHECK_SBOM_MISSING")
            row["scanner"] = config
            row["govulncheck_ids"] = sorted({item["finding"]["osv"] for item in records if "finding" in item})
            # Binary symbol availability is not inferred from function-shaped finding traces.
            symbols = run([str(args.go), "tool", "nm", str(library)], environment)
            row["symbol_table_available"] = symbols.returncode == 0 and bool(symbols.stdout.strip())
            row["status"] = "REVIEW_REQUIRED; binary scan has no application call graph"
            if not row["symbol_table_available"]:
                row["status"] = "MODULE_ONLY; stripped binary, no retained-symbol claim"
            (args.output / ("govulncheck-" + str(index) + ".json")).write_text(scanned.stdout, encoding="utf-8")
            report["native"].append(row)
    # Publish completion only after every query and scanner invocation succeeded.
    report["scan_completed_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    (args.output / "runtime-security.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "SCAN_COMPLETE_NOT_A_SECURITY_CLEARANCE", "maven_queries": len(queries),
                      "maven_matches": sum(bool(q["advisories"]) for q in report["maven"]),
                      "native_libraries": len(report["native"])}))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, IndexError, OSError, subprocess.SubprocessError):
        # No raw tool output, filesystem path or network response is emitted on failure.
        print("SECURITY_AUDIT_INCOMPLETE", file=sys.stderr)
        sys.exit(1)
