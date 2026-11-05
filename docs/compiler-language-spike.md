# M0: Choose the compiler CLI language

**Status:** Proposed evaluation. Go is the baseline from the original design;
Kotlin/JVM is a candidate for the compiler, not just the Spring adapter. Decide
before M0 closes and before starting the production compiler.

The core's first job is to validate local inputs, resolve a graph and write
deterministic files. It does not sit on the application's request path.
A faster runtime only matters if the difference affects an actual CLI, CI or
release workflow. Familiarity with Kotlin matters here: it lets the project
owner review and change core code directly.

## Candidates

| Candidate | What it buys us | What to prove |
| --- | --- | --- |
| **Kotlin/JVM with a small CLI** | Kotlin reviewability and access to the [Java ecosystem](https://kotlinlang.org/docs/java-interop.html). The Spring/Gradle workflow already has a JVM. | Cold process startup, peak memory and a clear installation path for standalone users. Try a launcher on the existing JDK, then a bundle with a runtime if clean-host installs require one. |
| **Go CLI** | Straightforward cross builds for a pure Go binary using [Go target settings](https://go.dev/doc/install/source#environment), and access to [client-go](https://github.com/kubernetes/client-go) if we later need cluster API calls. | Whether distribution, resource use or required integrations are materially better for our use cases, and whether that pays for maintaining two language stacks. |
| **Kotlin/JVM + GraalVM Native Image** | A standalone CLI built from the same Kotlin/JVM core, without requiring a JVM on the host. | Test only if JVM startup or installation misses the bar. Verify native runtime behavior of the chosen dependencies, [reachability metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/), and release builds on each target. |
| **Kotlin/Native** | Standalone executables with a different runtime and [native library interop](https://kotlinlang.org/docs/native-overview.html). | Defer unless there is a concrete reason to try it. It cannot directly reuse JVM libraries, which is a key reason to consider Kotlin/JVM. |

The compiler remains independent of Spring in every option. The Spring adapter
continues to run on the JVM and exchanges versioned JSON through a local process
boundary. Rendering can stay offline. Before pulling in a cluster SDK, check
whether the planned preflight needs more than the user's `kubectl`. If it does,
compare the [official Go and Java Kubernetes clients](https://kubernetes.io/docs/reference/using-api/client-libraries/)
on the actual authentication and discovery cases, including CRDs.

## Run the spike

1. Timebox the first pass to three working days. Implement the same small
   command in Go and Kotlin/JVM: read a captured Spring Evidence fixture and an
   `infra.yaml`, resolve one PostgreSQL resource, emit the Application Model
   and target plan, then render Compose and a small Kubernetes/CloudNativePG
   fixture. Use the same schemas, graph sizes and golden outputs. No Gradle
   startup, network, image builds, or apply operations in the benchmark.
2. Check managed, external and ambiguous input; missing secret references;
   unsupported target capabilities; and stable artifact paths and bytes on
   repeated runs. Both must produce equivalent diagnostics and valid output
   for both targets. Confirm that an implicit database is never created and
   rendered files contain no secret values. Do a separate smoke test invoking
   the same JVM adapter from both launchers; report that total separately.
3. Measure end-to-end wall time from a fresh CLI process, including startup and
   writing files, and peak RSS. Run at least 30 alternating invocations per
   candidate on the same Linux CI runner, with the tiny fixture and a synthetic
   100-service fixture. Record p50 and p95, runner specs, runtime versions and
   commands. Capture compressed release size and cold CI build time separately.
4. Prove installation and the tiny fixture on clean Linux x64/arm64 and macOS
   arm64. Note the actual steps, prerequisites, release build matrix and any
   dependencies needing workarounds. Compare one real schema or renderer change
   in both implementations for review and maintenance effort.

## Decision rule

Before measuring, confirm the target environments and budgets. Proposed
starting budgets: on the chosen reference runner, p95 up to **500 ms** for
the tiny plan and **2 s** for the 100-service plan, with peak RSS up to
**256 MiB**. These are product budgets for the standalone CLI, not measured
results or user guarantees. Document package size and build time; judge their
impact on install and CI rather than treating either as an automatic veto.

Prefer Kotlin/JVM if it passes parity, budgets and install checks and can be
maintained without unsupported dependencies or fragile packaging. Give a
native JVM build a short follow-up if it fixes a demonstrated problem. Choose
Go if Kotlin's remaining cost would materially hurt supported installs, CI
or required integrations. Record the evidence and the choice in an ADR and
keep the public contracts independent of that decision.
