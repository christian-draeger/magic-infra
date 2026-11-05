# ADR 0003 · Keep Spring analysis behind the Evidence process boundary

## Status

Accepted

## Decision

The Spring adapter accepts one versioned JSON request on stdin and emits one
Evidence document on stdout. Gradle calls the same adapter logic in-process.
The request describes an explicit analysis scope and resolved components.
The core accepts Evidence independently of the request and adapter runtime.

Gson reads the narrow adapter request and writes Evidence. The core uses
Jackson for strict JSON reading and networknt to enforce the shared Draft
2020-12 schema. The core owns reference checks that span Evidence objects.

## Evidence

Java and Kotlin fixtures collect the same kind of resolved metadata through
Gradle's configuration cache. The installed adapter also runs as a standalone
process. Integration checks exercise malformed JSON, incompatible versions and
forbidden fields at the two boundaries.

## Consequences

Adapters in other languages can target Evidence without depending on Spring
or Gradle. The Spring request and Evidence version independently. The
adapter owns input limits and cannot supply deployment artifacts through
Evidence. Validation diagnostics should name the failing field or rule
without copying rejected values.
