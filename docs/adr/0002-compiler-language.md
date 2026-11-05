# ADR 0002: Use Kotlin/JVM for the compiler

**Status:** Accepted for the next implementation slice

Both prototypes satisfy the [spike criteria](../compiler-language-spike.md).
Choose Kotlin/JVM with Java 21 for the initial compiler. The latency and memory
are within the proposed budgets. The team can review the implementation in its
preferred language, and Spring and the compiler can share a language toolchain
while retaining separate processes and versioned contracts.

## Measurements

[GitHub Actions run 42109674469](https://github.com/christian-draeger/magic-infra/actions/runs/42109674469) used commit `04c755609d73e50f08bbca7a85cd4dbede42d5f5`.
The reference runner was Ubuntu 24.04 x64 with 4 logical CPUs,
using go1.26.10, Kotlin 2.2.21, Temurin 21.0.13 and Gradle 8.5.
Each row uses 30 fresh processes per candidate in
alternating order. Time includes reading the same inputs and writing the
target artifacts. RSS is the maximum recorded across the row.

| Target | Fixture | Go p50 ms | Go p95 ms | Go peak MiB | Kotlin p50 ms | Kotlin p95 ms | Kotlin peak MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Compose | small | 4.22 | 4.59 | 3.73 | 219.95 | 234.61 | 56.64 |
| Compose | 100-services | 6.93 | 7.51 | 4.73 | 274.09 | 297.36 | 63.72 |
| Kubernetes | small | 4.15 | 4.78 | 3.73 | 219.18 | 238.66 | 56.24 |
| Kubernetes | 100-services | 9.80 | 10.28 | 6.36 | 284.93 | 309.20 | 67.33 |

All rows fit the proposed limits of 500 ms p95 for the small fixture, 2 s
p95 for 100 services and 256 MiB peak RSS. The separate one-run JVM fixture
adapter smoke test measured 67.75 ms through Go and 329.15 ms through Kotlin.
It measures the subprocess handoff with captured Evidence.

## Packaging and maintenance

| Platform | Go cold build / archive | Kotlin cold build / archive |
| --- | ---: | ---: |
| Linux x64 | 5.19 s / 1.11 MiB | 41.05 s / 2.16 MiB |
| Linux arm64 | 4.77 s / 1.00 MiB | 35.05 s / 2.16 MiB |
| macOS arm64 | 6.41 s / 1.03 MiB | 43.44 s / 2.16 MiB |

CI installed both archives into fresh directories on separate GitHub-hosted
runners for all three platforms. The Kotlin archive includes its libraries
and launcher. A Java 21 runtime is a separate installation. The Go binary
runs without a language runtime. Build times start with empty dependency and
build caches and include downloads. The archive sizes exclude the JVM.

Both packages passed the shared managed, external and ambiguous cases,
diagnostics, golden outputs, repeatability and 100-service comparisons on
both targets. The measured Go prototype uses the standard JSON package and
the original YAML v3 module path. The follow-up switches to the maintained YAML v3 module.
Kotlin uses Gson and SnakeYAML. Both render the required artifacts
from local inputs. A live Kubernetes API client is outside this path.

As a maintenance exercise, both versions were changed to report that Compose
does not enforce the requested storage size. Both changes fit in one target
condition and a shared golden plan update. Both updated archives passed the
same suite locally.
A repeat on macOS arm64 put Kotlin at 124.80 ms p95 for the small Compose
fixture and 145.57 ms p95 for the 100-service Kubernetes fixture. Kotlin's
slower cold build and startup are measurable.
For the first audience, the Java 21 installation and those costs fit the
tested workflow.

Keep the measured spike as a reference. Use the same conformance fixtures
during production implementation and revisit packaging if Java 21 becomes
a barrier for a supported audience or the compiler exceeds its budgets.
