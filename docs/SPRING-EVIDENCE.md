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
analysis scope and passes profiles to Spring in that order.
Use the override for Gradle module names that are not lowercase service IDs.

The task selects module coordinates from `runtimeClasspath`, including
transitive dependencies through project modules. Build dependencies,
compile-only dependencies, and platform constraints alone do not count as
observations. The task uses graph metadata, so resolving a cold graph may
fetch metadata. Gradle's `--offline` uses cached metadata. An unresolved
runtime dependency fails the task with `MI_SPRING_RUNTIME_UNRESOLVED`.
Only a successful task invocation identifies a valid current output.
Gradle can reuse its configuration cache. The task rechecks its bounded
local snapshot on every execution so file changes and newly introduced
symbolic links cannot leave stale Evidence.

The task leaves the application uncompiled and stopped. Running Gradle
still runs the project's normal build logic with the invoking user's
permissions. Bytecode and runtime bean metadata are separate analysis work.

## Local Spring ConfigData

The adapter uses Spring Boot 3.5.16's ConfigData processor with a local
snapshot. Gradle uses `src/main/resources` in the selected module. The
standalone process resolves the supplied `sourceRoots` against its working
directory. The snapshot considers `application.properties`, YAML files
named `application.yml` or `application.yaml`, and their profile variants.
Spring determines the winning property, including profile-specific overrides
and document activation with `spring.config.activate.on-profile`. A supplied
profile list takes precedence over a file's active-profile setting. Later
profiles win when two profiles define the same setting. The reported scope
uses Spring's default profiles when no active profiles were selected.

A local document can import a relative `.properties`, `.yml`, or `.yaml`
file from its own directory or a subdirectory. `optional:` allows the file
to be absent. Absolute paths, traversal, symbolic links, wildcard locations,
YAML aliases and other import schemes are outside this scope. A rejected
import makes the effective configuration unknown. The adapter reads up to
128 candidate documents and 2 MiB of source contents. The Spring loader sees
only this in-memory snapshot. It has no application-defined loaders, host
environment variables, JVM system properties or live secret sources.

Imports, profile settings and activation controls require literal values.
Local placeholders and defaults can resolve in datasource properties within
bounded lookup and expansion limits. Unresolved placeholders produce an
uncertain finding. The adapter only evaluates
`spring.datasource.url`, `spring.datasource.driver-class-name`, and
`spring.datasource.jndi-name` for database evidence. It records the
recognized database family, key, file and line when available. The Evidence
contains no raw URL, host, username, password, or JNDI name. A datasource URL
is an observation for later binding decisions. Managed resources require
an explicit declaration. See the [ConfigData ADR](adr/0004-local-spring-configdata.md).

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
[request schema](../schemas/spring-adapter-request.v1alpha1.schema.json),
[example request](../examples/spring-evidence/request.json) and
[process ADR](adr/0003-spring-evidence-process.md).

| Field | Meaning |
| --- | --- |
| `schemaVersion` | `spring-adapter-request/v1alpha1` |
| `applicationId`, `module`, `language` | Lowercase app/service ID, Gradle project path up to 240 characters and `java` or `kotlin` |
| `profiles`, `sourceRoots` | Ordered profile names and project-relative roots for local ConfigData analysis. Use an empty source-root list for dependency-only analysis. |
| `dependencies` | Resolved `group:name:version` coordinates for the selected module. Supply dependency facts without credentials or source contents. |

Successful stdout is a single `evidence/v1alpha1` document with adapter and
rule versions. Findings identify a service, kind, certainty and Gradle
coordinate. Diagnostics may carry a source kind and reference, and a
project-relative path and line when available. A fixture without a known
Spring Boot runtime version emits a warning and leaves the version absent.
The [Evidence schema](../schemas/evidence.v1alpha1.schema.json) defines the
diagnostic source shape and the `info`, `warning`, and `error` severities.

| Adapter code | Severity | Meaning |
| --- | --- | --- |
| `MI_SPRING_BOOT_RUNTIME_UNKNOWN` | warning | The selected runtime graph does not identify a unique Boot version. |
| `MI_SPRING_AMBIGUOUS_COMPONENT` | error | Multiple versions of a recognized component were supplied. |
| `MI_SPRING_APPLICATION_ID` | error | The service ID is not a lowercase identifier starting with a letter. |
| `MI_SPRING_INVALID_REQUEST` | error | The request is malformed or violates a field constraint. |
| `MI_SPRING_REQUEST_VERSION` | error | The adapter does not support the request version. |
| `MI_SPRING_USAGE` | error | The standalone adapter takes input on stdin. |
| `MI_SPRING_IO` | error | The standalone adapter could not read stdin. |
| `MI_SPRING_RUNTIME_UNRESOLVED` | error | Gradle could not resolve a selected runtime dependency. |
| `MI_SPRING_CONFIG_IMPORT_UNKNOWN` | warning | An import is outside the supported local scope. |
| `MI_SPRING_CONFIG_CONTROL_UNKNOWN` | warning | A ConfigData control used a placeholder. |
| `MI_SPRING_CONFIG_PLACEHOLDER_UNKNOWN` | warning | A datasource placeholder did not resolve within the local scope and limits. |
| `MI_SPRING_CONFIG_CUSTOM_DATASOURCE_UNKNOWN` | warning | A JNDI datasource needs runtime configuration. |
| `MI_SPRING_CONFIG_DATASOURCE_UNKNOWN` | warning | The datasource implementation could not be identified. |
| `MI_SPRING_CONFIG_INVALID`, `MI_SPRING_CONFIG_READ` | warning | Local configuration could not be processed or read. |
| `MI_SPRING_CONFIG_LINK`, `MI_SPRING_CONFIG_LIMIT`, `MI_SPRING_CONFIG_YAML_ALIAS`, `MI_SPRING_CONFIG_PROFILES_UNKNOWN` | warning | The snapshot is outside the supported path, size, document or profile limits. |
| `MI_SPRING_CONFIG_CUSTOM_SOURCES_UNKNOWN` | info | Application-defined property sources and environment overrides were outside the snapshot. |
| `MI_SPRING_CONFIG_DATASOURCE_URL` | info | A recognized local datasource URL was observed. |

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
