# Spring Evidence adapter

The first Spring adapter reads a resolved Gradle runtime dependency graph.
It records observations about Spring JDBC and JPA starters and the PostgreSQL,
MySQL and H2 drivers. An observed component suggests a relational dependency.
Resource ownership and deployment choices live in a separate contract.

## Try the Gradle task

The current fixtures exercise Spring Boot 3.5.16, Gradle 8.5 and JDK 21,
with Kotlin 2.2.21 in the Kotlin project. Use this command at the repository
root:

```sh
gradle -p examples/spring-evidence --configuration-cache \
  :java-app:springEvidence :kotlin-app:springEvidence
```

Each opted-in Spring Boot module applies `dev.magicinfra.spring-evidence`.
The fixture's settings load the plugin from this repository as an included
build. The task writes `build/magic-infra/evidence.json` in each module.
`-PmagicInfra.applicationId=shop` overrides the default module name for a
single-module invocation. `-PmagicInfra.profiles=dev,test` records explicit
analysis scope. Profiles are metadata at this stage.

The task selects module coordinates from `runtimeClasspath`, including
transitive dependencies through project modules. Build dependencies,
compile-only dependencies, and platform constraints alone do not count as
observations. The task uses graph metadata, so resolving a cold graph may
fetch metadata. Gradle's `--offline` uses cached metadata. An unresolved
runtime dependency fails the task with `MI_SPRING_RUNTIME_UNRESOLVED`.
Only a successful task invocation identifies a valid current output.

The task leaves the application uncompiled and stopped. Running Gradle
still runs the project's normal build logic with the invoking user's
permissions. Spring ConfigData, placeholders, bytecode, and dynamic runtime
behavior are work for the later M1 tickets.

## Local process protocol

Build the JVM launchers with JDK 21 and Gradle 8.5:

```sh
gradle :spring-evidence:installDist :evidence-check:installDist
adapters/spring/build/install/spring-evidence/bin/spring-evidence \
  < examples/spring-evidence/request.json > /tmp/shop-evidence.json
compiler/evidence/build/install/evidence-check/bin/evidence-check /tmp/shop-evidence.json
```

The adapter takes exactly one UTF-8 JSON object on stdin. The same adapter
logic serves the Gradle task. See the
[example request](../examples/spring-evidence/request.json).

| Field | Meaning |
| --- | --- |
| `schemaVersion` | `spring-adapter-request/v1alpha1` |
| `applicationId`, `module`, `language` | Lowercase app/service ID, Gradle project path and `java` or `kotlin` |
| `profiles`, `sourceRoots` | Explicit profile names and project-relative roots. The dependency adapter validates the scope without reading those roots. |
| `dependencies` | Resolved `group:name:version` coordinates for the selected module. Supply dependency facts without credentials or source contents. |

Successful stdout is a single `evidence/v1alpha1` document with adapter and
rule versions. Findings identify a service, kind, certainty and Gradle
coordinate. Diagnostics may carry a source kind and reference, and a
project-relative path and line when available. A fixture without a known
Spring Boot runtime version emits a warning and leaves the version absent.

On failure, stdout is empty and the adapter writes a JSON diagnostic with
`code`, `severity` and `message` to stderr. The exit codes are:

| Code | Meaning |
| --- | --- |
| `0` | Evidence emitted, possibly with warnings |
| `1` | Input I/O failed |
| `2` | Request malformed or values invalid |
| `3` | Request version unsupported |

The compiler reader accepts a file or stdin (use `-`). It validates the
published schema, rejects duplicate logical IDs and dangling finding
references, and reports a diagnostic on stderr. It uses the same exit code
categories for I/O, invalid Evidence and an unsupported Evidence version.
A successful validation is silent. Request and Evidence versions evolve
independently. The current request and Evidence input limits are 1 MiB and
4 MiB respectively.

For the integration checks, install the
[test dependencies](../CONTRIBUTING.md#run-the-checks) and run
`python tests/check_spring_evidence.py` after building both launchers.
