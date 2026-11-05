"""Compare both packaged prototypes against the same fixtures."""

import argparse
import copy
import json
import subprocess
import tempfile
from pathlib import Path

import yaml

from test_contracts import EXAMPLES, TARGETS, read_json, validate_bundle, verify_artifact


def run(compiler, target, evidence, declaration, output, error=None):
    result = subprocess.run(
        [compiler, target, evidence, declaration, output],
        capture_output=True, text=True, timeout=15, check=False,
    )
    if error:
        if (result.returncode, result.stderr.strip(), output.exists()) != (1, error, False):
            raise AssertionError(f"{compiler.name}: expected {error}, got {result.returncode}: {result.stderr}")
    elif result.returncode:
        raise RuntimeError(f"{compiler.name}: {result.stderr}")


def large_fixture(directory):
    observed = read_json(EXAMPLES / "evidence.json")
    declared = yaml.safe_load((EXAMPLES / "infra.yaml").read_text())
    observed["services"] = []
    observed["findings"] = []
    observed["diagnostics"] = []
    declared["services"] = {}
    for index in range(100):
        service = f"api-{index:03}"
        observed["services"].append({"id": service, "module": f":{service}", "buildSystem": "gradle"})
        observed["findings"].append({
            "id": f"f-{service}", "serviceId": service, "kind": "dependency",
            "capability": "database.relational", "implementation": "postgresql",
            "source": {"kind": "gradle", "reference": "org.postgresql:postgresql"},
            "certainty": "observed",
        })
        declared["services"][service] = {"imageRef": "example.invalid/shop/api:fixture", "port": 8080}
    evidence = directory / "large-evidence.json"
    declaration = directory / "large-infra.yaml"
    evidence.write_text(json.dumps(observed))
    declaration.write_text(yaml.safe_dump(declared))
    return evidence, declaration


def check(compilers):
    with tempfile.TemporaryDirectory() as scratch:
        work = Path(scratch)
        evidence = EXAMPLES / "evidence.json"
        observed = read_json(evidence)
        normal = EXAMPLES / "infra.yaml"
        external = EXAMPLES / "infra.external.yaml"
        no_secret = work / "no-secret.yaml"
        no_secret.write_text("\n".join(line for line in normal.read_text().splitlines() if "secretRef:" not in line))
        unsupported = work / "unsupported.yaml"
        unsupported.write_text(normal.read_text() + "requireHostFailover: true\n")
        conflicting = copy.deepcopy(observed)
        conflicting["findings"][0]["implementation"] = "mysql"
        conflict = work / "conflict.json"
        conflict.write_text(json.dumps(conflicting))
        no_endpoint = work / "no-endpoint.yaml"
        no_endpoint.write_text("\n".join(line for line in external.read_text().splitlines() if "endpointRef:" not in line))
        cases = [
            (evidence, EXAMPLES / "infra.ambiguous.yaml", "E_OWNERSHIP"),
            (evidence, no_secret, "E_SECRET_REF"),
            (evidence, no_endpoint, "E_ENDPOINT_REF"),
            (evidence, unsupported, "E_CAPABILITY"),
            (conflict, normal, "E_CONFLICT"),
        ]
        large_evidence, large_declaration = large_fixture(work)
        for target, artifact in TARGETS.items():
            large_outputs = []
            for index, compiler in enumerate(compilers):
                for mode, declaration in (("managed", normal), ("external", external)):
                    output = work / f"{index}-{target}-{mode}"
                    repeated = work / f"{index}-{target}-{mode}-repeat"
                    run(compiler, target, evidence, declaration, output)
                    run(compiler, target, evidence, declaration, repeated)
                    expected = EXAMPLES / "expected" / mode
                    plan = read_json(output / "plan.json")
                    validate_bundle(observed, read_json(output / "application.json"), plan)
                    verify_artifact(read_json(output / artifact), target, mode)
                    files = {"application.json": "application.json", "plan.json": f"plan.{target}.json", artifact: artifact}
                    if {path.name for path in output.iterdir()} != files.keys():
                        raise AssertionError("unexpected artifact paths")
                    for actual, golden in files.items():
                        if read_json(output / actual) != read_json(expected / golden):
                            raise AssertionError(f"{compiler.name}: {target}/{mode}/{actual} differs from the golden fixture")
                        if (output / actual).read_bytes() != (repeated / actual).read_bytes():
                            raise AssertionError(f"{compiler.name}: {actual} is not byte-stable")
                for case, (input_evidence, declaration, error) in enumerate(cases):
                    run(compiler, target, input_evidence, declaration, work / f"rejected-{index}-{target}-{case}", error)
                output = work / f"large-{index}-{target}"
                run(compiler, target, large_evidence, large_declaration, output)
                documents = [read_json(output / filename) for filename in ("application.json", "plan.json", artifact)]
                validate_bundle(read_json(large_evidence), *documents[:2])
                if len(documents[0]["services"]) != 100 or len(documents[0]["bindings"]) != 100:
                    raise AssertionError("missing services or bindings in 100-service fixture")
                large_outputs.append(documents)
            if large_outputs[0] != large_outputs[1]:
                raise AssertionError(f"{target}: large fixture differs across prototypes")
    print("Both prototypes passed both targets, golden files, byte stability, five diagnostics and 100-service parity.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("go", type=Path)
    parser.add_argument("kotlin", type=Path)
    args = parser.parse_args()
    check([args.go.resolve(), args.kotlin.resolve()])
