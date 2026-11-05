"""Exercise the installed adapter, compiler reader and Gradle fixtures."""

import argparse
import copy
import json
import os
import subprocess
import tempfile
from pathlib import Path

from jsonschema import ValidationError

from test_contracts import ROOT, read_json, validate_schema

FIXTURE = ROOT / "examples" / "spring-evidence"
ADAPTER = ROOT / "adapters/spring/build/install/spring-evidence/bin/spring-evidence"
READER = ROOT / "compiler/evidence/build/install/evidence-check/bin/evidence-check"


def invoke(command, document=None, timeout=30, **kwargs):
    return subprocess.run(command, input=document, capture_output=True, timeout=timeout, check=False, **kwargs)


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
        ({"module": ":api\n"}, "240 characters"),
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


def check_config():
    request = read_json(FIXTURE / "request.json")

    def analyze(project, profiles):
        snapshot = json_bytes({**request, "profiles": profiles})
        result = invoke([ADAPTER], snapshot, cwd=project, env={**os.environ, "MI_MISSING_DB": "sample-secret-value"})
        output = success(result)
        assert b"sample-secret-value" not in output + result.stderr
        assert b"jdbc:" not in output + result.stderr
        doc = json.loads(output)
        validate_schema(doc, "evidence")
        success(invoke([READER, "-"], output))
        return doc

    for profiles, implementation, source in [
        ([], "postgresql", "db/default.properties"),
        (["test", "dev"], "mysql", "application-dev.yaml"),
        (["dev", "test"], "h2", "application-test.yaml"),
    ]:
        doc = analyze(FIXTURE / "java-app", profiles)
        assert doc["analysis"]["profiles"] == (profiles or ["default"])
        assert doc["analysis"]["environmentScope"] == "local-supported", doc["diagnostics"]
        finding = next(finding for finding in doc["findings"] if finding["kind"] == "configuration")
        assert finding["implementation"] == implementation
        assert finding["source"] == {
            "kind": "spring-property", "reference": "spring.datasource.url",
            "path": f"src/main/resources/{source}", "line": 2 if source.endswith(".properties") else 3,
        }, finding

    with tempfile.TemporaryDirectory() as directory:
        project = Path(directory)
        config = project / "src/main/resources/application.properties"
        config.parent.mkdir(parents=True)
        config.write_text("spring.profiles.default=staging\n")
        staging = config.with_name("application-staging.properties")
        staging.write_text("demo.itemPrice=jdbc:postgresql://sample-secret-value.invalid/app\n"
                           "spring.datasource.url=${demo.item-price}\n")
        doc = analyze(project, [])
        assert doc["analysis"]["profiles"] == ["staging"]
        assert doc["findings"][-1]["implementation"] == "postgresql", doc["findings"]
        staging.unlink()

        config.write_text("spring.datasource.url=${MI_MISSING_DB}\n")
        doc = analyze(project, [])
        assert doc["findings"][-1]["certainty"] == "uncertain"
        assert "implementation" not in doc["findings"][-1]
        assert doc["diagnostics"][-1]["code"] == "MI_SPRING_CONFIG_PLACEHOLDER_UNKNOWN"

        for links, repeated in ((5000, False), (40, True)):
            chain = ["spring.datasource.url=${k0}"]
            for i in range(links):
                next_link = "${k%d}" % (i + 1)
                chain.append(f"k{i}={next_link * (2 if repeated else 1)}")
            chain.append(f"k{links}=sample-secret-value")
            config.write_text("\n".join(chain))
            doc = analyze(project, [])
            assert doc["findings"][-1]["certainty"] == "uncertain"
            assert doc["diagnostics"][-1]["code"] == "MI_SPRING_CONFIG_PLACEHOLDER_UNKNOWN"

        secret_file = config.with_name("sample-secret-value.properties")
        secret_file.write_text("spring.datasource.url=jdbc:postgresql://example.invalid/test\n")
        for control in ("spring.profiles.active", "spring.profiles.default", "spring.profiles.include[0]",
                        "spring.profiles.group.dev[0]", "spring.config.activate.on-profile", "spring.config.import[0]"):
            config.write_text(f"{control}=${{spring.datasource.password}}\n"
                              "spring.datasource.password=sample-secret-value.properties\n")
            doc = analyze(project, [])
            assert doc["analysis"]["environmentScope"] == "explicit-snapshot"
            expected = "MI_SPRING_CONFIG_IMPORT_UNKNOWN" if control.startswith("spring.config.import") else (
                "MI_SPRING_CONFIG_CONTROL_UNKNOWN")
            assert doc["diagnostics"][-1]["code"] == expected, doc["diagnostics"]
        secret_file.unlink()

        config.write_text("spring.datasource.jndi-name=sample-secret-value\n")
        doc = analyze(project, [])
        assert doc["findings"][-1]["certainty"] == "uncertain"
        assert doc["diagnostics"][-1]["code"] == "MI_SPRING_CONFIG_CUSTOM_DATASOURCE_UNKNOWN"

        for location in ("configserver:https://example.invalid/sample-secret-value",
                         "https://example.invalid/sample-secret-value.properties",
                         "file:/sample-secret-value.properties", "configtree:/",
                         "env:MI_MISSING_DB", "../outside.properties", "${MI_MISSING_DB}"):
            config.write_text(f"spring.config.import=optional:{location}\n"
                              "spring.datasource.url=jdbc:postgresql://example.invalid/test\n")
            doc = analyze(project, [])
            assert doc["analysis"]["environmentScope"] == "explicit-snapshot"
            assert all(finding["kind"] == "dependency" for finding in doc["findings"])
            diagnostic = doc["diagnostics"][-1]
            assert diagnostic["code"] == "MI_SPRING_CONFIG_IMPORT_UNKNOWN", diagnostic
            assert diagnostic["source"]["path"] == "src/main/resources/application.properties", diagnostic

        config.unlink()
        yaml = config.with_suffix(".yaml")
        yaml.write_text("base: &cycle [*cycle]\n")
        doc = analyze(project, [])
        assert doc["diagnostics"][-1]["code"] == "MI_SPRING_CONFIG_YAML_ALIAS", doc["diagnostics"]

        yaml.unlink()
        outside = project / "private.properties"
        outside.write_text("spring.datasource.url=jdbc:postgresql://sample-secret-value.invalid/db\n")
        config.symlink_to(outside)
        doc = analyze(project, [])
        assert doc["diagnostics"][-1]["code"] == "MI_SPRING_CONFIG_LINK", doc["diagnostics"]
    print("Spring configuration profile, origin, import isolation and redaction checks passed.")


def check_gradle(gradle):
    command = [gradle, "--no-daemon", "--console=plain", "-p", FIXTURE]
    args = ["--configuration-cache", "-PmagicInfra.profiles=test,dev",
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
        assert doc["analysis"]["profiles"] == ["test", "dev"]
        assert set(doc) == {"schemaVersion", "adapter", "application", "analysis", "services", "findings", "diagnostics"}
        findings = {item["id"]: item for item in doc["findings"]}
        expected = {"f-postgresql-driver"}
        if language == "kotlin":
            expected |= {"f-jpa-starter", "f-jdbc-starter"}
            assert not doc["diagnostics"]
        else:
            expected.add("f-config-url")
            assert findings["f-config-url"]["implementation"] == "mysql"
            assert findings["f-config-url"]["source"]["path"].endswith("/application-dev.yaml")
        assert findings.keys() == expected, findings
        assert findings["f-postgresql-driver"]["implementation"] == "postgresql"
        assert findings["f-postgresql-driver"]["source"]["reference"] == "org.postgresql:postgresql:42.7.11"
        assert all(item["certainty"] == "observed" for item in findings.values())
    symlink = FIXTURE / "java-app/src/main/resources/temporary-cache-probe.txt"
    symlink.symlink_to("application.properties")
    try:
        third = success(invoke(command + args, timeout=300)).decode()
        assert "Reusing configuration cache." in third, third
        assert read_json(paths["java"])["diagnostics"][-1]["code"] == "MI_SPRING_CONFIG_LINK"
    finally:
        symlink.unlink()
    unresolved = invoke(command + ["--offline", "-Pfixture.unresolved", ":java-app:springEvidence"], timeout=120)
    assert unresolved.returncode != 0 and b"MI_SPRING_RUNTIME_UNRESOLVED" in unresolved.stdout + unresolved.stderr
    print("Java/Kotlin Gradle fixtures passed resolved metadata, cache reuse, repeatability and offline failure checks.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", default="gradle")
    parser.add_argument("--protocol-only", action="store_true")
    args = parser.parse_args()
    check_protocol()
    check_config()
    if not args.protocol_only:
        check_gradle(args.gradle)
