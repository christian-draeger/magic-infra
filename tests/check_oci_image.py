"""Build and start the explicitly packaged Spring OCI fixture."""

import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "examples/compose-app"
READER = ROOT / "compiler/evidence/build/install/evidence-check/bin/evidence-check"


def command(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=300, **kwargs).stdout


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
    try:
        command("docker", "build", "--tag", image, str(FIXTURE))
        command("docker", "run", "--detach", "--name", container, "--publish",
                f"127.0.0.1::{port}", service["build"]["imageRef"])
        endpoint = command("docker", "port", container, f"{port}/tcp").strip()
        assert endpoint.startswith("127.0.0.1:"), endpoint
        try:
            await_health(f"http://{endpoint}{health}")
        except AssertionError:
            subprocess.run(("docker", "logs", container), timeout=30)
            raise
    finally:
        subprocess.run(("docker", "rm", "--force", container), stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=30)
        subprocess.run(("docker", "image", "rm", image), stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=30)
    print("Spring analysis stayed separate from OCI build. The resolved image and health metadata worked in Docker.")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        raise SystemExit(failure.stdout + failure.stderr) from failure
