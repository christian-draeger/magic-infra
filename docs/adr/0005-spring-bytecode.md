# ADR 0005 · Read JVM metadata with ASM

## Status

Accepted

## Decision

Use ASM to visit selected annotations and method descriptors in the Gradle
main source set. Bytecode analysis is opt-in because it requires compilation.
The adapter skips method code, debug data and frames. It does not use the
application's classloader or execute application methods.

## Evidence

Java and Kotlin fixtures declare JPA entities, custom datasource beans and
conditions. The same adapter reports JVM-level provenance for both. Fixture
bean methods fail if called, and integration checks verify that the same
compiled inputs produce the same Evidence.

## Consequences

One metadata scanner covers Java and Kotlin without language-specific ASTs.
Directly observed annotations can contribute evidence, while the active
bean graph and the meaning of arbitrary conditions remain runtime questions.
The protocol and portable Evidence schema keep their existing versions.
