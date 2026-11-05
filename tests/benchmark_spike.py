"""Measure fresh processes from parsed input files through writing artifacts."""

import argparse
import json
import math
import os
import platform
import re
import statistics
import subprocess
import tempfile
import time
from pathlib import Path

from check_spike import large_fixture
from test_contracts import EXAMPLES, TARGETS


def timed(command, usage):
    flags = ["-f", "%M", "-o", str(usage)] if platform.system() == "Linux" else ["-l"]
    start = time.perf_counter_ns()
    result = subprocess.run(
        ["/usr/bin/time", *flags, *command],
        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, timeout=15, check=True,
    )
    elapsed_ms = (time.perf_counter_ns() - start) / 1e6
    if flags == ["-l"]:
        peak_kib = int(re.search(r"(\d+)\s+maximum resident set size", result.stderr)[1]) / 1024
    else:
        peak_kib = int(usage.read_text().strip())
    return elapsed_ms, peak_kib


def measure(compilers, count, adapter_classpath):
    results = {}
    with tempfile.TemporaryDirectory() as scratch:
        work = Path(scratch)
        small = (EXAMPLES / "evidence.json", EXAMPLES / "infra.yaml")
        large = large_fixture(work)
        usage = work / "time.txt"
        for target in TARGETS:
            for label, (evidence, declaration) in (("small", small), ("100-services", large)):
                samples = {name: [] for name in compilers}
                for trial in range(count):
                    order = list(compilers) if trial % 2 == 0 else list(reversed(compilers))
                    for name in order:
                        dest = work / f"{target}-{label}-{trial}-{name}"
                        samples[name].append(timed([compilers[name], target, evidence, declaration, dest], usage))
                for name, sample in samples.items():
                    wall = sorted(ms for ms, _ in sample)
                    row = {"p50_ms": round(statistics.median(wall), 2),
                           "p95_ms": round(wall[math.ceil(0.95 * count) - 1], 2),
                           "peak_mib": round(max(kib for _, kib in sample) / 1024, 2)}
                    results[f"{target}/{label}/{name}"] = row
        smoke = {}
        if adapter_classpath:
            java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java") if "JAVA_HOME" in os.environ else "java"
            for name, compiler in compilers.items():
                captured = work / f"{name}-adapter.json"
                start = time.perf_counter_ns()
                with captured.open("wb") as output:
                    subprocess.run([java, "-cp", adapter_classpath, "FixtureAdapter", small[0]],
                                   stdout=output, timeout=15, check=True)
                subprocess.run([compiler, "docker-compose", captured, small[1], work / f"smoke-{name}"],
                               timeout=15, check=True)
                smoke[name] = round((time.perf_counter_ns() - start) / 1e6, 2)
    return results, smoke


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("go", type=Path)
    parser.add_argument("kotlin", type=Path)
    parser.add_argument("--samples", type=int, default=30)
    parser.add_argument("--adapter-classpath", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.samples < 1:
        parser.error("--samples must be positive")
    rows, smoke = measure({"go": args.go.resolve(), "kotlin": args.kotlin.resolve()}, args.samples,
                          args.adapter_classpath)
    args.output.write_text(json.dumps({
        "platform": platform.platform(), "machine": platform.machine(), "logical_cpus": os.cpu_count(),
        "samples_per_candidate": args.samples, "results": rows, "adapter_pipeline_ms": smoke,
    }, indent=2) + "\n")
    print(args.output.read_text())
