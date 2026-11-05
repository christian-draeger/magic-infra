"""Build the two spike distributions and record build time and compressed size."""

import gzip
import json
import shutil
import os
import subprocess
import sys
import tarfile
import time
from pathlib import Path

root = Path(__file__).resolve().parent
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)


def run(command, cwd=root, env=None):
    start = time.perf_counter()
    subprocess.run(command, cwd=cwd, env=env, check=True)
    return round(time.perf_counter() - start, 2)


def version(command):
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout.strip()


go_archive = out / "go.tar.gz"
go_build = root / "go" / "build"
go_build.mkdir(exist_ok=True)
go_binary = go_build / "m0-compiler-go"
go_seconds = run(["go", "build", "-trimpath", "-ldflags=-s -w", "-o", str(go_binary), "."],
                 cwd=root / "go", env={**os.environ, "CGO_ENABLED": "0"})
with tarfile.open(go_archive, "w:gz") as archive:
    archive.add(go_binary, arcname=go_binary.name)
run(["go", "vet", "./..."], cwd=root / "go")

kotlin_archive = out / "kotlin.tar.gz"
kotlin_seconds = run(["gradle", "--no-daemon", "--console=plain", "distTar"], cwd=root / "kotlin")
with (root / "kotlin" / "build" / "distributions" / "m0-compiler-kotlin.tar").open("rb") as source:
    with gzip.open(kotlin_archive, "wb") as archive:
        shutil.copyfileobj(source, archive)
adapter = out / "adapter"
adapter.mkdir(exist_ok=True)
run(["javac", "-d", str(adapter), "FixtureAdapter.java"])

report = {
    "go": {"runtime": version(["go", "version"]), "seconds": go_seconds, "archive_bytes": go_archive.stat().st_size},
    "kotlin": {"runtime": version(["java", "--version"]),
               "gradle": next(line.strip() for line in version(["gradle", "--version"]).splitlines() if line.startswith("Gradle ")),
               "seconds": kotlin_seconds, "archive_bytes": kotlin_archive.stat().st_size},
}
(out / "build.json").write_text(json.dumps(report, indent=2) + "\n")
print((out / "build.json").read_text())
