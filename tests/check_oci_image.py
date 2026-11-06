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
from contextlib import contextmanager
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


@contextmanager
def compose_cleanup(compose, env=None):
    try:
        yield
    finally:
        original = sys.exception()
        try:
            command(*compose, "down", "--volumes", "--remove-orphans", timeout=60, env=env)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            if original is None:
                raise
            original.add_note("Could not remove the Compose smoke test stack.")


def start_compose(compose, service, env=None):
    command(*compose, "up", "--detach", "--wait", "--wait-timeout", "90", env=env)
    endpoint = command(*compose, "port", service["id"], str(service["runtime"]["port"]), env=env).strip()
    assert endpoint.startswith("127.0.0.1:"), endpoint
    address = f"http://{endpoint}"
    await_health(f"{address}{service['runtime']['healthPath']}")
    return address


def note_request(address, method, status, body=None):
    url = f"{address}/notes/fixture-note"
    data = body.encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "text/plain; charset=UTF-8"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=5) as response:
            assert response.status == status, response.status
            return response.read().decode()
    except urllib.error.HTTPError as error:
        if error.code != status:
            raise
        error.close()
        return None


def assert_password_absent(compose, password, env=None):
    logs = subprocess.run((*compose, "logs", "--no-color"), check=True, text=True, capture_output=True,
                          timeout=30, env=env)
    assert password not in logs.stdout + logs.stderr


def check_compose(docker, evidence, declaration, service, suffix):
    managed = yaml.safe_load(declaration)
    service_id = service["id"]
    with tempfile.TemporaryDirectory() as directory:
        folder = Path(directory)
        compose_file = folder / "compose.yaml"
        override = folder / "smoke.override.yaml"
        compose_file.write_text(command(str(READER), "generate", str(evidence), "-", input=declaration))
        port = service["runtime"]["port"]
        override.write_text(f"services:\n  {service_id}:\n    ports: !override ['127.0.0.1::{port}']\n")
        secret = folder / "secrets/fixture-db.env"
        secret.parent.mkdir(mode=0o700)
        password = uuid.uuid4().hex
        secret.write_text(f"POSTGRES_USER=fixture\nPOSTGRES_PASSWORD={password}\n"
                          f"SPRING_DATASOURCE_USERNAME=fixture\nSPRING_DATASOURCE_PASSWORD={password}\n")
        assert password not in compose_file.read_text()
        project = f"magic-infra-render-{suffix}"
        compose = (*docker, "compose", "-p", project, "-f", str(compose_file), "-f", str(override))
        command(*compose, "config", "--quiet")
        with compose_cleanup(compose):
            address = start_compose(compose, service)
            db_before = command(*compose, "ps", "--quiet", "primary-db").strip()
            assert command(*docker, "inspect", "--format", "{{.State.Health.Status}}", db_before).strip() == "healthy"
            note_request(address, "POST", 201, "first note")
            assert note_request(address, "GET", 200) == "first note"
            updated = "PostgreSQL kept this fixture's note"
            note_request(address, "PUT", 204, updated)
            assert note_request(address, "GET", 200) == updated
            assert_password_absent(compose, password)

            command(*compose, "down")
            address = start_compose(compose, service)
            db_after = command(*compose, "ps", "--quiet", "primary-db").strip()
            assert db_before and db_after and db_before != db_after
            assert note_request(address, "GET", 200) == updated

            resource = managed["resources"]["primary-db"]
            resource["management"] = "external"
            resource["endpointRef"] = "FIXTURE_DB_URL"
            del resource["storageSize"]
            external_file = folder / "external.yaml"
            external_file.write_text(command(str(READER), "generate", str(evidence), "-", input=yaml.safe_dump(managed)))
            external_doc = yaml.safe_load(external_file.read_text())
            assert set(external_doc["services"]) == {service_id} and "volumes" not in external_doc
            assert password not in external_file.read_text()
            external_network = folder / "external-network.override.yaml"
            external_network.write_text(f"networks:\n  default:\n    external: true\n    name: {project}_default\n")
            external = (*docker, "compose", "-p", f"{project}-external", "-f", str(external_file), "-f",
                        str(override), "-f", str(external_network))
            env = {**os.environ, "FIXTURE_DB_URL": "jdbc:postgresql://primary-db:5432/fixture"}
            command(*external, "config", "--quiet", env=env)
            with compose_cleanup(external, env=env):
                external_address = start_compose(external, service, env=env)
                assert note_request(external_address, "GET", 200) == updated
                assert_password_absent(external, password, env=env)

            note_request(address, "DELETE", 204)
            note_request(address, "GET", 404)
            assert_password_absent(compose, password)
    print("PostgreSQL CRUD, volume persistence after recreation, and an external binding to the existing database passed.")


def main(with_compose):
    docker = local_docker()
    gradle = ("gradle", "--no-daemon", "--console=plain", "-p", str(FIXTURE))
    analysis = command(*gradle, "springEvidence")
    for task in ("bootJar", "bootBuildImage", "bootRun", "compileKotlin"):
        assert f"> Task :{task}" not in analysis, analysis

    suffix = uuid.uuid4().hex[:12]
    image = f"magic-infra-compose-smoke:{suffix}"
    container = f"magic-infra-compose-smoke-{suffix}"
    declaration_path = FIXTURE / "infra.yaml"
    evidence = FIXTURE / "build/magic-infra/evidence.json"
    if with_compose:
        for action, name in (("plan", "plan.json"), ("generate", "compose.yaml")):
            artifact = command(str(READER), action, str(evidence), str(declaration_path))
            assert artifact == (FIXTURE / "expected" / name).read_text(), f"Unexpected {name} output"
        undeclared = yaml.safe_load(declaration_path.read_text())
        undeclared["resources"] = {}
        undeclared["bindings"] = []
        blocked = subprocess.run((str(READER), "generate", str(evidence), "-"), input=yaml.safe_dump(undeclared),
                                 text=True, capture_output=True, timeout=30)
        assert blocked.returncode == 4 and not blocked.stdout and "MI_APPLICATION_BINDING_REQUIRED" in blocked.stderr
    declaration = declaration_path.read_text().replace("magic-infra-compose-app:fixture", image)
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
                               f"127.0.0.1::{port}", "--env",
                               "SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                               service["build"]["imageRef"]).strip()
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
