"""Build and start the explicitly packaged Spring OCI fixture."""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


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


def main():
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
    finally:
        remove_fixture(docker, container_id, built_image)
    print("Spring analysis stayed separate from OCI build. The resolved image and health metadata worked in Docker.")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        notes = "\n".join(getattr(failure, "__notes__", []))
        raise SystemExit(failure.stdout + failure.stderr + notes) from failure
