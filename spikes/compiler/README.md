# Compiler language spike

The two prototypes consume the same captured Evidence and a small subset of
`infra.yaml`. They write Application Model JSON, a deployment preview and
one target artifact. The inputs and expected results live in
[examples/m0](../../examples/m0/). The prototypes share a single database
binding per service. Image names are placeholders.

From the repository root, after installing the
[test dependencies](../../CONTRIBUTING.md#run-the-checks), use Go 1.26,
Gradle 8.5 and JDK 21:

```sh
python spikes/compiler/package.py /tmp/magic-infra-distributions
mkdir /tmp/magic-infra-installed
tar -xzf /tmp/magic-infra-distributions/go.tar.gz -C /tmp/magic-infra-installed
tar -xzf /tmp/magic-infra-distributions/kotlin.tar.gz -C /tmp/magic-infra-installed
python tests/check_spike.py \
  /tmp/magic-infra-installed/m0-compiler-go \
  /tmp/magic-infra-installed/m0-compiler-kotlin/bin/m0-compiler-kotlin
python tests/benchmark_spike.py \
  /tmp/magic-infra-installed/m0-compiler-go \
  /tmp/magic-infra-installed/m0-compiler-kotlin/bin/m0-compiler-kotlin \
  --adapter-classpath /tmp/magic-infra-distributions/adapter \
  --output /tmp/magic-infra-benchmark.json
```

The compiler entry point takes four arguments: target, Evidence file,
declaration file and a new output directory. For instance:

```sh
/tmp/magic-infra-installed/m0-compiler-go docker-compose \
  examples/m0/evidence.json examples/m0/infra.yaml /tmp/shop-compose
```

Both artifacts use JSON syntax within YAML files. Compose expects a local
env file outside version control containing the matching Spring database
variables and, for managed mode, the PostgreSQL initialization variables.
For external mode it also expects the referenced JDBC URL at interpolation
time. Kubernetes references an existing Secret with `username` and
`password` keys. For managed mode, the bootstrap owner is `app`. For
external mode, the endpoint reference selects the JDBC URL key in the
Secret. These artifacts demonstrate planning and references. The actual app,
image availability, readiness, and persistence are covered by M2 and M3.

The benchmark runs each CLI as a fresh process, alternating candidate order
for 30 rounds per scenario by default. It uses the same files and records
elapsed time through output writes. Maximum RSS comes from the platform's
`/usr/bin/time`. The separate adapter measurement starts a JVM stub that
emits captured Evidence before invoking the compiler. It excludes Gradle and
Spring analysis.

CI packages and checks both distributions on Linux x64, Linux arm64 and
macOS arm64, then benchmarks on Linux x64. The runtime for the Kotlin
archive is Java 21. Go ships as a binary for each target OS and architecture.
