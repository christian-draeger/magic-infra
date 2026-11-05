"""Exercise the installed adapter, compiler reader and Gradle fixtures."""

import argparse
import copy
import json
import subprocess

from jsonschema import ValidationError

from test_contracts import ROOT, read_json, validate_schema

FIXTURE = ROOT / "examples" / "spring-evidence"
ADAPTER = ROOT / "adapters/spring/build/install/spring-evidence/bin/spring-evidence"
READER = ROOT / "compiler/evidence/build/install/evidence-check/bin/evidence-check"


def invoke(command, document=None, timeout=30):
    return subprocess.run(command, input=document, capture_output=True, timeout=timeout, check=False)


def success(result):
    if result.returncode != 0:
        raise AssertionError(result.stdout.decode(errors="replace") + result.stderr.decode(errors="replace"))
    return result.stdout


def rejected(result, status, code, *messages):
    stderr = result.stderr.decode(errors="replace")
    if result.returncode != status or result.stdout or code not in stderr or "sample-secret-value" in stderr:
        raise AssertionError(f"Expected {status} {code}, got {result.returncode}, {stderr}")
    for message in messages:
        if message not in stderr:
            raise AssertionError(f"Expected diagnostic to mention {message}: {stderr}")


def json_bytes(value):
    return json.dumps(value).encode()


def check_protocol():
    request = read_json(FIXTURE / "request.json")
    validate_schema(request, "spring-adapter-request")
    output = success(invoke([ADAPTER], json_bytes(request)))
    assert output == success(invoke([ADAPTER], json_bytes(request)))
    doc = json.loads(output)
    validate_schema(doc, "evidence")
    assert doc["services"][0]["module"] == ":api"
    assert doc["findings"][0]["source"]["reference"] == "org.postgresql:postgresql:42.7.8"
    assert doc["diagnostics"][0]["code"] == "MI_SPRING_BOOT_RUNTIME_UNKNOWN"
    assert success(invoke([READER, "-"], output)) == b""

    unsupported_request = {**request, "schemaVersion": "spring-adapter-request/v2"}
    rejected(invoke([ADAPTER], json_bytes(unsupported_request)), 3, "MI_SPRING_REQUEST_VERSION", "v1alpha1")
    for change, message in [
        ({"sourceRoots": ["../outside"]}, "project-relative"),
        ({"module": ":a" * 5000}, "240 characters"),
        ({"password": "sample-secret-value"}, "Supply exactly"),
    ]:
        invalid = {**request, **change}
        try:
            validate_schema(invalid, "spring-adapter-request")
        except ValidationError:
            pass
        else:
            raise AssertionError(f"The request schema accepted {change.keys()}")
        rejected(invoke([ADAPTER], json_bytes(invalid)), 2, "MI_SPRING_INVALID_REQUEST", message)
    rejected(invoke([ADAPTER], b'{"bad": "sample-secret-value"'), 2, "MI_SPRING_INVALID_REQUEST")

    duplicate = output.replace(b'"application": {', b'"application": {}, "application": {', 1)
    rejected(invoke([READER, "-"], duplicate), 2, "MI_EVIDENCE_INVALID_JSON")
    repeated_request_field = b'{"applicationId":"sample-secret-value",' + json_bytes(request)[1:]
    rejected(invoke([ADAPTER], repeated_request_field), 2, "MI_SPRING_INVALID_REQUEST")

    unsupported = {**doc, "schemaVersion": "sample-secret-value"}
    rejected(invoke([READER, "-"], json_bytes(unsupported)), 3, "MI_EVIDENCE_VERSION", "v1alpha1")
    with_secret = copy.deepcopy(doc)
    with_secret["findings"][0]["source"]["password"] = "sample-secret-value"
    rejected(invoke([READER, "-"], json_bytes(with_secret)), 2, "MI_EVIDENCE_SCHEMA", "findings")
    rejected(invoke([READER, "-"], json_bytes({**doc, "deploymentPlan": {}})), 2, "MI_EVIDENCE_SCHEMA")
    dangling = copy.deepcopy(doc)
    dangling["findings"][0]["serviceId"] = "missing"
    rejected(invoke([READER, "-"], json_bytes(dangling)), 2, "MI_EVIDENCE_REFERENCE", "/findings/0/serviceId")
    rejected(invoke([READER, "-"], output + b"{}"), 2, "MI_EVIDENCE_INVALID_JSON")
    print("Adapter process and compiler Evidence boundary passed.")


def check_gradle(gradle):
    command = [gradle, "--no-daemon", "--console=plain", "-p", FIXTURE]
    args = ["--configuration-cache", "--rerun-tasks", "-PmagicInfra.profiles=test,dev",
            ":java-app:springEvidence", ":kotlin-app:springEvidence"]
    paths = {language: FIXTURE / f"{language}-app/build/magic-infra/evidence.json"
             for language in ("java", "kotlin")}
    first = success(invoke(command + args, timeout=300)).decode()
    outputs = {language: path.read_bytes() for language, path in paths.items()}
    second = success(invoke(command + args, timeout=300)).decode()
    assert "Reusing configuration cache." in second, second
    for language, path in paths.items():
        for task in ("compileJava", "compileKotlin", "bootRun", "bootJar"):
            assert f"> Task :{language}-app:{task}" not in first + second
        assert outputs[language] == path.read_bytes(), language
        doc = read_json(path)
        validate_schema(doc, "evidence")
        success(invoke([READER, path]))
        assert doc["application"] == {
            "id": f"{language}-app", "framework": "spring-boot",
            "language": language, "frameworkVersion": "3.5.16",
        }
        assert doc["services"] == [{"id": f"{language}-app", "module": f":{language}-app", "buildSystem": "gradle"}]
        assert doc["analysis"]["profiles"] == ["dev", "test"]
        assert not doc["diagnostics"]
        assert set(doc) == {"schemaVersion", "adapter", "application", "analysis", "services", "findings", "diagnostics"}
        findings = {item["id"]: item for item in doc["findings"]}
        expected = {"f-postgresql-driver"}
        if language == "kotlin":
            expected |= {"f-jpa-starter", "f-jdbc-starter"}
        assert findings.keys() == expected, findings
        assert findings["f-postgresql-driver"]["implementation"] == "postgresql"
        assert findings["f-postgresql-driver"]["source"]["reference"] == "org.postgresql:postgresql:42.7.11"
        assert all(item["certainty"] == "observed" and item["kind"] == "dependency" for item in findings.values())
    unresolved = invoke(command + ["--offline", "-Pfixture.unresolved", ":java-app:springEvidence"], timeout=120)
    assert unresolved.returncode != 0 and b"MI_SPRING_RUNTIME_UNRESOLVED" in unresolved.stdout + unresolved.stderr
    print("Java/Kotlin Gradle fixtures passed resolved metadata, cache reuse, repeatability and offline failure checks.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", default="gradle")
    parser.add_argument("--protocol-only", action="store_true")
    args = parser.parse_args()
    check_protocol()
    if not args.protocol_only:
        check_gradle(args.gradle)
