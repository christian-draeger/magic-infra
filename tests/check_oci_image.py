"""Build and start the explicitly packaged Spring OCI fixture."""

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "examples/compose-app"
READER = ROOT / "compiler/evidence/build/install/evidence-check/bin/evidence-check"


def command(*args, timeout=300, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=timeout, **kwargs).stdout


def local_docker():
    host = command("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}").strip()
    if not host.startswith("unix://"):
        raise SystemExit("Select a local Docker context backed by a Unix socket before running the OCI smoke test.")
    return ("docker", "--host", host)


def remove_fixture(docker, container_id, image):
    cleanup = []
    if container_id is not None:
        cleanup.append(("container", ("rm", "--force", container_id)))
    if image is not None:
        cleanup.append(("image", ("image", "rm", image)))
    failures = []
    for kind, arguments in cleanup:
        try:
            command(*docker, *arguments, timeout=30)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            failures.append(kind)
    if failures:
        message = "Could not remove smoke test " + " and ".join(failures) + "."
        original = sys.exception()
        if original is None:
            raise RuntimeError(message)
        original.add_note(message)


def await_health(url):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        try:
            with opener.open(url, timeout=2) as response:
                if json.load(response).get("status") == "UP":
                    return
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            pass
        time.sleep(1)
    raise AssertionError("The Spring OCI fixture did not become healthy.")


def check_compose(docker, evidence, declaration, service, suffix):
    managed = yaml.safe_load(declaration)
    service_id = service["id"]
    managed["resources"]["primary-db"] = {
        "kind": "postgresql", "management": "managed", "deletionPolicy": "retain",
        "database": "fixture", "secretRef": "fixture-db", "storageSize": "1Gi",
    }
    managed["bindings"] = [{"serviceId": service_id, "resourceId": "primary-db"}]
    with tempfile.TemporaryDirectory() as directory:
        folder = Path(directory)
        compose_file = folder / "compose.yaml"
        override = folder / "smoke.override.yaml"
        compose_file.write_text(command(str(READER), "generate", str(evidence), "-", input=yaml.safe_dump(managed)))
        port = service["runtime"]["port"]
        override.write_text(f"services:\n  {service_id}:\n    ports: !override ['127.0.0.1::{port}']\n")
        secret = folder / "secrets/fixture-db.env"
        secret.parent.mkdir(mode=0o700)
        password = uuid.uuid4().hex
        secret.write_text(f"POSTGRES_USER=fixture\nPOSTGRES_PASSWORD={password}\n"
                          f"SPRING_DATASOURCE_USERNAME=fixture\nSPRING_DATASOURCE_PASSWORD={password}\n")
        assert password not in compose_file.read_text()
        compose = (*docker, "compose", "-p", f"magic-infra-render-{suffix}", "-f", str(compose_file), "-f", str(override))
        command(*compose, "config", "--quiet")
        try:
            command(*compose, "up", "--detach", "--wait", "--wait-timeout", "90")
            endpoint = command(*compose, "port", service_id, str(port)).strip()
            assert endpoint.startswith("127.0.0.1:"), endpoint
            await_health(f"http://{endpoint}{service['runtime']['healthPath']}")
            db = command(*compose, "ps", "--quiet", "primary-db").strip()
            assert command(*docker, "inspect", "--format", "{{.State.Health.Status}}", db).strip() == "healthy"
        finally:
            original = sys.exception()
            try:
                command(*compose, "down", "--volumes", "--remove-orphans", timeout=60)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                if original is None:
                    raise
                original.add_note("Could not remove the Compose smoke test stack.")

        resource = managed["resources"]["primary-db"]
        resource["management"] = "external"
        resource["endpointRef"] = "FIXTURE_DB_URL"
        del resource["storageSize"]
        compose_file.write_text(command(str(READER), "generate", str(evidence), "-", input=yaml.safe_dump(managed)))
        command(*compose, "config", "--quiet", env={**os.environ, "FIXTURE_DB_URL": "jdbc:postgresql://example.invalid/fixture"})
    print("Generated Compose started PostgreSQL and the Spring fixture. External mode validated with an endpoint reference.")


def main(with_compose):
    docker = local_docker()
    gradle = ("gradle", "--no-daemon", "--console=plain", "-p", str(FIXTURE))
    analysis = command(*gradle, "springEvidence")
    for task in ("bootJar", "bootBuildImage", "bootRun", "compileKotlin"):
        assert f"> Task :{task}" not in analysis, analysis

    suffix = uuid.uuid4().hex[:12]
    image = f"magic-infra-compose-smoke:{suffix}"
    container = f"magic-infra-compose-smoke-{suffix}"
    declaration = (FIXTURE / "infra.yaml").read_text().replace("magic-infra-compose-app:fixture", image)
    evidence = FIXTURE / "build/magic-infra/evidence.json"
    model = json.loads(command(str(READER), "resolve", str(evidence), "-", input=declaration))
    service = model["services"][0]
    assert service["build"] == {"strategy": "prebuilt", "imageRef": image}
    port = service["runtime"]["port"]
    health = service["runtime"]["healthPath"]

    command(*gradle, "bootJar")
    built_image = container_id = None
    try:
        command(*docker, "build", "--builder", "default", "--tag", image, str(FIXTURE))
        built_image = image
        container_id = command(*docker, "create", "--name", container, "--publish",
                               f"127.0.0.1::{port}", service["build"]["imageRef"]).strip()
        command(*docker, "start", container_id)
        endpoint = command(*docker, "port", container_id, f"{port}/tcp").strip()
        assert endpoint.startswith("127.0.0.1:"), endpoint
        await_health(f"http://{endpoint}{health}")
        if with_compose:
            check_compose(docker, evidence, declaration, service, suffix)
    finally:
        remove_fixture(docker, container_id, built_image)
    print("Spring analysis stayed separate from OCI build. The resolved image and health metadata worked in Docker.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--with-compose", action="store_true")
    try:
        main(parser.parse_args().with_compose)
    except subprocess.CalledProcessError as failure:
        notes = "\n".join(getattr(failure, "__notes__", []))
        raise SystemExit(failure.stdout + failure.stderr + notes) from failure
