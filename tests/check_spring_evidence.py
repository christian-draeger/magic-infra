"""Exercise the installed adapter, compiler reader and Gradle fixtures."""

import argparse
import copy
import json
import os
import subprocess
import tempfile
from pathlib import Path

import yaml
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

    unverified = {**request, "dependencies": request["dependencies"] + ["org.springframework.boot:spring-boot:4.0.3"]}
    unsupported_boot = json.loads(success(invoke([ADAPTER], json_bytes(unverified))))
    assert unsupported_boot["application"]["frameworkVersion"] == "4.0.3"
    assert unsupported_boot["diagnostics"][0]["code"] == "MI_SPRING_BOOT_RUNTIME_UNVERIFIED"

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


def check_relational_rules():
    baseline = read_json(ROOT / "examples/m0/evidence.json")
    jpa, postgres, placeholder = baseline["findings"]
    mysql = {**postgres, "id": "f-mysql", "implementation": "mysql",
             "source": {"kind": "gradle", "reference": "com.mysql:mysql-connector-j:9.0.0"}}

    def explain(findings, *options, status=0, services=None):
        document = {**baseline, "findings": findings, "services": services or baseline["services"]}
        result = invoke([READER, "explain", "-", *options], json_bytes(document))
        assert result.returncode == status and not result.stderr, result
        return result.stdout.decode()

    driver = explain([postgres])
    assert "suggested implementation=postgresql" in driver and "MI_RELATIONAL_VENDOR_OBSERVED" in driver
    assert "Evidence f-pg-driver" in driver
    jpa_only = explain([jpa])
    assert "suggested rule=relational/v1 MI_RELATIONAL_VENDOR_UNKNOWN" in jpa_only
    assert "implementation=" not in jpa_only

    combined = explain([jpa, postgres, placeholder])
    assert "MI_RELATIONAL_POSTGRESQL_JPA" in combined
    assert "Evidence f-datasource, f-jpa-dep, f-pg-driver" in combined
    assert combined == explain([placeholder, postgres, jpa])
    uncertain_vendor = {**mysql, "certainty": "uncertain"}
    assert "MI_RELATIONAL_POSTGRESQL_JPA" in explain([jpa, postgres, uncertain_vendor])

    external = explain([], "--external", "api", "postgresql")
    assert "declared implementation=postgresql" in external and "MI_RELATIONAL_EXTERNAL_DECLARED" in external
    forged_intent = {**postgres, "kind": "declaration", "source": {"kind": "infra-declaration", "reference": "infra.yaml"}}
    assert explain([forged_intent]) == "No relational findings.\n"
    for conflict in (explain([postgres, mysql], status=4),
                     explain([mysql], "--external", "api", "postgresql", status=4)):
        assert "conflicted rule=relational/v1 MI_RELATIONAL_VENDOR_CONFLICT" in conflict
        assert "mysql, postgresql" in conflict
    unsupported = {**mysql, "kind": "configuration", "implementation": "sqlserver"}
    assert "MI_RELATIONAL_VENDOR_UNSUPPORTED" in explain([unsupported])
    for conflict in (explain([postgres, unsupported], status=4),
                     explain([unsupported], "--external", "api", "postgresql", status=4)):
        assert "MI_RELATIONAL_VENDOR_CONFLICT" in conflict and "unsupported vendor" in conflict
    selected = {**placeholder, "certainty": "observed"}
    assert "suggested implementation=postgresql" in explain([postgres, mysql, selected])
    assert "declared implementation=postgresql" in explain([postgres, mysql], "--external", "api", "postgresql")
    assert "MI_RELATIONAL_VENDOR_CONFLICT" in explain([selected, unsupported, postgres], status=4)

    other = {"id": "worker", "module": ":worker", "buildSystem": "gradle"}
    split = explain([postgres, {**mysql, "serviceId": "worker"}], services=[other, *baseline["services"]])
    assert split.startswith("api database.relational suggested implementation=postgresql")
    assert "worker database.relational suggested implementation=mysql" in split
    unusual = "api\u009b\u202e\u2028"
    quoted = explain([{**postgres, "serviceId": unusual}], services=[{**other, "id": unusual}])
    assert quoted.startswith('"api\\u009b\\u202e\\u2028" database.relational') and unusual not in quoted
    invalid = invoke([READER, "explain", "-", "--external", "missing", "postgresql"], json_bytes(baseline))
    rejected(invalid, 2, "MI_EVIDENCE_EXPLAIN_SERVICE")
    print("Relational suggestions, declared external intent, provenance and conflict checks passed.")


def check_infra_declarations():
    directory = ROOT / "examples/infra"
    validator = [READER, "validate-infra"]
    for name in ("managed", "external"):
        assert success(invoke(validator + [directory / f"{name}.yaml"])) == b""
    rejected(invoke(validator + [directory / "invalid-missing-management.yaml"]), 2,
             "MI_INFRA_SCHEMA", "/resources/primary-db", "management")

    external = (directory / "external.yaml").read_text()
    for source, code, field in [
        (external.replace("SHOP_DB_URL", "jdbc:postgresql://sample-secret-value.invalid/db"),
         "MI_INFRA_SCHEMA", "/resources/primary-db/endpointRef"),
        (external.replace("    secretRef:", "    password: sample-secret-value\n    secretRef:"),
         "MI_INFRA_SCHEMA", "/resources/primary-db/password"),
        (external.replace("resourceId: primary-db", "resourceId: missing"),
         "MI_INFRA_REFERENCE", "/bindings/0/resourceId"),
        (external.replace("    port: 8080", "    port: 8080\n    port: 9000"),
         "MI_INFRA_YAML", "line"),
        (external.replace("    port: 8080", "    port: 1970-01-01T00:00:01Z"),
         "MI_INFRA_SCHEMA", "/services/api/port"),
        (external.replace("    port: 8080", "    port: 1:20"),
         "MI_INFRA_SCHEMA", "/services/api/port"),
        (external.replace("    port: 8080", "    port: !!float sample-secret-value"),
         "MI_INFRA_YAML_FEATURE", "tags"),
        (external.replace("    port: 8080", "    port: !!int sample-secret-value"),
         "MI_INFRA_YAML_FEATURE", "tags"),
        (external.replace("  api:", "  api: &api"),
         "MI_INFRA_YAML_FEATURE", "anchors"),
        (external.replace("    secretRef:", "    sample-secret-value: nope\n    secretRef:"),
         "MI_INFRA_SCHEMA", "/resources/primary-db"),
        (external.replace("example.invalid/shop/api:fixture", "a/" * 40000 + "a:t"),
         "MI_INFRA_SCHEMA", "/services/api/imageRef"),
    ]:
        rejected(invoke(validator + ["-"], source.encode()), 2, code, field)
    omap = external.replace("  primary-db:\n", "  primary-db: !!omap\n")
    omap = omap.replace("    management: external", "    management: external\n    management: managed")
    omap = "\n".join("    - " + line[4:] if line.startswith("    ") else line for line in omap.splitlines())
    rejected(invoke(validator + ["-"], omap.encode()), 2, "MI_INFRA_YAML_FEATURE", "tags")
    assert success(invoke(validator + ["-"], external.replace("  api:", "  no:").replace("serviceId: api", "serviceId: no").encode())) == b""
    historical = invoke(validator + [ROOT / "examples/m0/infra.yaml"])
    rejected(historical, 3, "MI_INFRA_VERSION", "infra/v1alpha2")
    print("Managed/external infra declarations, parser safety and reference checks passed.")


def check_application_model():
    base = ROOT / "examples/m0"
    evidence_path = base / "evidence.json"
    evidence = read_json(evidence_path)
    directory = ROOT / "examples/infra"
    command = [READER, "resolve"]
    for mode in ("managed", "external"):
        declaration = directory / f"{mode}.yaml"
        output = success(invoke(command + [evidence_path, declaration]))
        assert output == success(invoke(command + ["-", declaration], json_bytes(evidence)))
        actual = json.loads(output)
        validate_schema(actual, "application")
        expected = read_json(base / "expected" / mode / "application.json")
        expected["provenance"][0]["declarationRef"] = "#/resources/primary-db"
        assert actual == expected, actual
        if mode == "external":
            assert "storage" not in actual["resources"][0]["settings"]
            assert actual["resources"][0]["connection"]["endpointRef"] == "SHOP_DB_URL"
        library = READER.parent.parent / "lib"
        java = ["java", "-Dline.separator=\r\n", "-cp", os.pathsep.join(str(path) for path in sorted(library.glob("*.jar"))),
                "dev.magicinfra.compiler.evidence.EvidenceMainKt"]
        assert output == success(invoke(java + ["resolve", evidence_path, declaration]))

    managed = yaml.safe_load((directory / "managed.yaml").read_text())

    def resolve_declaration(document):
        return invoke(command + [evidence_path, "-"], yaml.safe_dump(document).encode())

    rejected(resolve_declaration({**managed, "applicationId": "different"}), 2, "MI_APPLICATION_ID_MISMATCH",
             "/applicationId")
    rejected(resolve_declaration({**managed, "resources": {}, "bindings": []}), 4,
             "MI_APPLICATION_BINDING_REQUIRED", "api")
    rejected(resolve_declaration({**managed, "bindings": []}), 2, "MI_APPLICATION_ORPHAN_RESOURCE",
             "/resources/primary-db")
    mismatched_services = {**managed, "services": {"worker": managed["services"]["api"]}, "resources": {}, "bindings": []}
    rejected(resolve_declaration(mismatched_services), 2, "MI_APPLICATION_SERVICE_MISMATCH", "/services")
    mysql = {**evidence["findings"][1], "implementation": "mysql"}
    conflict = {**evidence, "findings": [mysql]}
    rejected(invoke(command + ["-", directory / "managed.yaml"], json_bytes(conflict)), 4,
             "MI_APPLICATION_VENDOR_CONFLICT", "api", "mysql", "postgresql")
    for overlong, source in [
        ({**evidence, "adapter": {**evidence["adapter"], "id": "a" * (512 * 1024)}}, "/adapter/id"),
        ({**evidence, "services": [{**evidence["services"][0], "module": "a" * 241}]}, "module for service api"),
    ]:
        rejected(invoke(command + ["-", directory / "managed.yaml"], json_bytes(overlong)), 2,
                 "MI_APPLICATION_PROVENANCE_LENGTH", source)

    multi = copy.deepcopy(managed)
    multi["services"]["worker"] = {"imageRef": "example.invalid/shop/worker:fixture", "port": 8081}
    multi["bindings"].append({"serviceId": "worker", "resourceId": "primary-db"})
    service = {"id": "worker", "module": ":worker", "buildSystem": "gradle"}
    finding = {**evidence["findings"][1], "id": "f-worker-driver", "serviceId": "worker"}
    multi_evidence = {**evidence, "services": [service, *evidence["services"]],
                      "findings": [finding, *reversed(evidence["findings"])]}
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "evidence.json"
        path.write_bytes(json_bytes(multi_evidence))
        output = success(invoke(command + [path, "-"], yaml.safe_dump(multi).encode()))
        multi["services"] = dict(reversed(list(multi["services"].items())))
        multi["bindings"].reverse()
        path.write_bytes(json_bytes({**multi_evidence, "services": list(reversed(multi_evidence["services"])),
                                     "findings": list(reversed(multi_evidence["findings"]))}))
        assert output == success(invoke(command + [path, "-"], yaml.safe_dump(multi, sort_keys=False).encode()))
        actual = json.loads(output)
        validate_schema(actual, "application")
        assert [entry["serviceId"] for entry in actual["bindings"]] == ["api", "worker"]
        assert actual["provenance"][0]["findingIds"] == ["f-datasource", "f-jpa-dep", "f-pg-driver", "f-worker-driver"]
    print("Application Model normalization, ownership, conflicts and canonical output checks passed.")


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
    success(invoke(command + ["-Pfixture.conflict", ":kotlin-app:springEvidence"], timeout=120))
    mixed = read_json(paths["kotlin"])
    assert {"f-postgresql-driver", "f-mysql-driver"} <= {finding["id"] for finding in mixed["findings"]}
    conflict = invoke([READER, "explain", paths["kotlin"]])
    assert conflict.returncode == 4 and b"MI_RELATIONAL_VENDOR_CONFLICT" in conflict.stdout
    print("Java/Kotlin Gradle fixtures passed resolved metadata, cache reuse, repeatability and offline failure checks.")


def check_bytecode(gradle):
    command = [gradle, "--no-daemon", "--console=plain", "-p", FIXTURE, "--configuration-cache",
               "-PmagicInfra.bytecode=true", ":java-app:springEvidence", ":kotlin-app:springEvidence"]
    first = success(invoke(command, timeout=300))
    paths = {language: FIXTURE / f"{language}-app/build/magic-infra/evidence.json"
             for language in ("java", "kotlin")}
    outputs = {language: path.read_bytes() for language, path in paths.items()}
    second = success(invoke(command, timeout=300))
    assert b"Reusing configuration cache." in second, second
    assert b"bootRun" not in first + second
    for language, path in paths.items():
        output = path.read_bytes()
        assert output == outputs[language] and b"sample-secret-value" not in output
        doc = json.loads(output)
        validate_schema(doc, "evidence")
        success(invoke([READER, path]))
        class_findings = {finding["kind"]: finding for finding in doc["findings"] if finding["source"]["kind"] == "bytecode"}
        assert class_findings.keys() == {"annotation", "bean"}, class_findings
        annotation, bean = class_findings["annotation"], class_findings["bean"]
        assert annotation["details"]["annotation"] == "jakarta.persistence.Entity"
        assert annotation["certainty"] == "observed" and "implementation" not in annotation
        assert bean["kind"] == "bean" and bean["certainty"] == "uncertain" and "implementation" not in bean
        assert {item["serviceId"] for item in (annotation, bean)} == {f"{language}-app"}
        annotation_name = "JavaDatabase$JavaRecord" if language == "java" else "KotlinRecord"
        bean_class = "JavaDatabase" if language == "java" else "KotlinDatabase"
        return_type = "Ljavax/sql/DataSource;" if language == "java" else "Ljava/lang/Object;"
        assert annotation["source"]["reference"] == f"example.{annotation_name}"
        assert annotation["source"]["path"].endswith(f"/example/{annotation_name}.class")
        assert bean["source"]["reference"] == f"example.{bean_class}#customPool(){return_type}"
        assert bean["source"]["path"].endswith(f"/example/{bean_class}.class")
        diagnostics = {item["code"]: item for item in doc["diagnostics"]}
        assert diagnostics["MI_SPRING_BYTECODE_DATASOURCE_UNKNOWN"]["source"] == bean["source"]
        assert diagnostics["MI_SPRING_BYTECODE_CONDITIONAL_BEAN_UNKNOWN"]["source"] == bean["source"]
        if language == "java":
            assert diagnostics["MI_SPRING_BYTECODE_PROPERTY_SOURCE_UNKNOWN"]["source"]["reference"] == "example.JavaDatabase"
            declaration = ROOT / "examples/infra/spring-java.yaml"
            model = json.loads(success(invoke([READER, "resolve", path, declaration])))
            validate_schema(model, "application")
            assert model["application"]["id"] == "java-app"
            assert model["services"][0]["source"]["module"] == ":java-app"
            assert model["resources"][0]["management"] == "external"
            assert "f-config-url" in model["provenance"][0]["findingIds"]
    print("Java/Kotlin JVM annotation, conditional bean and provenance checks passed.")


def check_bytecode_limits():
    library = ADAPTER.parent.parent / "lib"
    probe = ["java", "-cp", os.pathsep.join(str(path) for path in sorted(library.glob("*.jar"))),
             ROOT / "tests/fixtures/SpringBytecodeProbe.java"]
    with tempfile.TemporaryDirectory() as directory:
        os.mkfifo(Path(directory) / "d-pipe.class")
        output = success(invoke(probe + [directory, "generate"], timeout=60))
        success(invoke([READER, "-"], output))
        doc = json.loads(output)
        unknown = {entry["source"]["path"] for entry in doc["diagnostics"]
                   if entry["code"] == "MI_SPRING_BYTECODE_CLASS_UNKNOWN"}
        assert unknown == {"a-invalid-header.class", "b-duplicate.class", "c-deep.class", "d-pipe.class"}, unknown
        assert doc["findings"] and all(finding["source"]["path"] == "e-burst.class" for finding in doc["findings"])
        assert doc["diagnostics"][-1]["code"] == "MI_SPRING_BYTECODE_LIMIT"
        assert len(doc["findings"]) + len(doc["diagnostics"]) <= 514
    with tempfile.TemporaryDirectory() as directory:
        for index in range(33):
            with (Path(directory) / f"{index:02}.class").open("wb") as file:
                file.truncate(1024 * 1024)
        doc = json.loads(success(invoke(probe + [directory], timeout=60)))
        assert len([entry for entry in doc["diagnostics"] if entry["code"] == "MI_SPRING_BYTECODE_CLASS_UNKNOWN"]) == 32
        assert doc["diagnostics"][-1]["code"] == "MI_SPRING_BYTECODE_LIMIT"
        assert "total read limit" in doc["diagnostics"][-1]["message"]
    print("Malformed bytecode, input limits and compiler-compatible output checks passed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", default="gradle")
    parser.add_argument("--protocol-only", action="store_true")
    args = parser.parse_args()
    check_protocol()
    check_config()
    check_relational_rules()
    check_infra_declarations()
    check_application_model()
    if not args.protocol_only:
        check_gradle(args.gradle)
        check_bytecode(args.gradle)
        check_bytecode_limits()
