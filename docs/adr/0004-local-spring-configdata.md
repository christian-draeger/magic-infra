# ADR 0004 · Apply Spring ConfigData to a local snapshot

## Status

Accepted

## Decision

Use Spring Boot 3.5.16's ConfigData processor for document parsing, profile
selection, and import precedence. Supply a fresh Environment with explicit
inputs and a resource loader over bounded project-local bytes. Restrict
factories to the standard properties and YAML loaders and a local import
guard. Use SnakeYAML's parser events to reject alias expansion before
Spring flattens loaded documents.

## Evidence

Fixtures cover profile ordering, optional relative imports, tracked source
locations, and datasource placeholders. Tests inject a host environment
variable, block external and dynamic imports, and verify that raw values do
not appear in the output. Gradle fingerprints the supported configuration
inputs and uses the same analysis as the standalone adapter.

## Consequences

Evidence can identify a local datasource vendor without starting Spring or
making network lookups. Custom runtime sources, environment overrides and
unsupported documents remain unknown. The adapter emits normalized facts
and provenance rather than connection data or resource decisions. The
compiler stays independent of Spring and the request and Evidence formats
keep their existing versions.
