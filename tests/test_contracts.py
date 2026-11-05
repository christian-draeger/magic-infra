import copy
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, ValidationError

ROOT = Path(__file__).resolve().parents[1]
EXAMPLES = ROOT / "examples" / "m0"
TARGETS = {"docker-compose": "compose.yaml", "kubernetes": "kubernetes.yaml"}


def read_json(path):
    return json.loads(path.read_text())


def validate_schema(document, contract):
    schema = read_json(ROOT / "schemas" / f"{contract}.v1alpha1.schema.json")
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema).validate(document)


def unique(records, what):
    ids = {record["id"] for record in records}
    if len(ids) != len(records):
        raise ValueError(f"duplicate {what}")
    return ids


def validate_bundle(evidence, application, plan):
    for doc, contract in ((evidence, "evidence"), (application, "application"), (plan, "plan")):
        validate_schema(doc, contract)
    if len({evidence["application"]["id"], application["application"]["id"], plan["applicationId"]}) != 1:
        raise ValueError("application ID mismatch")
    evidence_services = unique(evidence["services"], "evidence service")
    findings = unique(evidence["findings"], "finding")
    services = unique(application["services"], "application service")
    resources = unique(application["resources"], "resource")
    unique(plan["operations"], "operation")
    if services != evidence_services or services & resources:
        raise ValueError("service identity mismatch or collision")
    if any(finding["serviceId"] not in evidence_services for finding in evidence["findings"]):
        raise ValueError("dangling finding")
    for binding in application["bindings"]:
        if binding["serviceId"] not in services or binding["resourceId"] not in resources:
            raise ValueError("dangling binding")
    for origin in application["provenance"]:
        if origin["resourceId"] not in resources or not set(origin["findingIds"]) <= findings:
            raise ValueError("dangling provenance")
    managed = {r["id"] for r in application["resources"] if r["management"] == "managed"}
    for operation in plan["operations"]:
        if operation["resourceId"] not in services | resources:
            raise ValueError("dangling plan operation")
        if operation["action"] == "create" and operation["resourceId"] in resources - managed:
            raise ValueError("create operation on a non-managed resource")


def verify_artifact(artifact, target, mode):
    managed = mode == "managed"
    if target == "docker-compose":
        services = artifact["services"]
        assert ("primary-db" in services) == managed
        assert ("primary-db" in artifact.get("volumes", {})) == managed
        if managed:
            assert "primary-db:/var/lib/postgresql/data" in services["primary-db"]["volumes"]
        app = services["api"]
        assert app["env_file"] == ["./secrets/shop-db-credentials.env"]
        assert set(app["environment"]) == {"SPRING_DATASOURCE_URL"}
    else:
        items = artifact["items"]
        assert any(item["kind"] == "Cluster" for item in items) == managed
        assert all(item["kind"] not in {"Secret", "PersistentVolumeClaim"} for item in items)
        if managed:
            cluster = next(item for item in items if item["kind"] == "Cluster")
            assert cluster["spec"]["storage"]["size"] == "1Gi"
            assert cluster["spec"]["bootstrap"]["initdb"]["secret"]["name"] == "shop-db-credentials"
        app = next(item for item in items if item["kind"] == "Deployment")
        env = {entry["name"]: entry for entry in app["spec"]["template"]["spec"]["containers"][0]["env"]}
        for suffix in ("USERNAME", "PASSWORD"):
            assert "secretKeyRef" in env[f"SPRING_DATASOURCE_{suffix}"]["valueFrom"]
        if not managed:
            assert "secretKeyRef" in env["SPRING_DATASOURCE_URL"]["valueFrom"]


class ContractsTest(unittest.TestCase):
    def setUp(self):
        self.evidence = read_json(EXAMPLES / "evidence.json")
        self.managed = EXAMPLES / "expected" / "managed"
        self.model = read_json(self.managed / "application.json")
        self.plan = read_json(self.managed / "plan.docker-compose.json")

    def test_positive_examples_and_ownership(self):
        for mode in ("managed", "external"):
            directory = EXAMPLES / "expected" / mode
            model = read_json(directory / "application.json")
            for target, artifact in TARGETS.items():
                with self.subTest(mode=mode, target=target):
                    plan = read_json(directory / f"plan.{target}.json")
                    validate_bundle(self.evidence, model, plan)
                    self.assertEqual(plan["artifacts"][0]["path"], artifact)
                    if mode == "managed" and target == "docker-compose":
                        self.assertIn("Compose does not enforce the declared storage size.", plan["limitations"])
                    verify_artifact(read_json(directory / artifact), target, mode)

    def test_schema_rejects_invalid_documents(self):
        for fixture in sorted((ROOT / "tests" / "fixtures" / "invalid").glob("*.json")):
            case = read_json(fixture)
            with self.subTest(fixture=fixture.name), self.assertRaises(ValidationError):
                validate_schema(case["document"], case["contract"])

    def test_dangling_binding_is_semantic_error(self):
        model = copy.deepcopy(self.model)
        model["bindings"][0]["resourceId"] = "missing"
        with self.assertRaisesRegex(ValueError, "dangling binding"):
            validate_bundle(self.evidence, model, self.plan)

    def test_duplicate_service_is_semantic_error(self):
        model = copy.deepcopy(self.model)
        model["services"].append(model["services"][0])
        with self.assertRaisesRegex(ValueError, "duplicate application service"):
            validate_bundle(self.evidence, model, self.plan)

    def test_external_resource_cannot_have_create_operation(self):
        model = read_json(EXAMPLES / "expected" / "external" / "application.json")
        with self.assertRaisesRegex(ValueError, "non-managed resource"):
            validate_bundle(self.evidence, model, self.plan)


if __name__ == "__main__":
    unittest.main()
