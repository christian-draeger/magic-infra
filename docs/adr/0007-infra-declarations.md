# ADR 0007 · Declare resource ownership explicitly

## Status

Accepted

## Decision

Use `infra/v1alpha2` for the first validated declaration contract. Give
services and resources stable IDs and connect them with bindings. Require
explicit management and retain policy. Keep endpoint and credential values
outside the document. Parse a bounded, safe YAML subset with SnakeYAML,
then validate the normalized data against the Draft 2020-12 schema.

## Evidence

The managed and external PostgreSQL examples validate in the core.
Regression tests exercise missing management, unknown properties, secret
references, duplicate YAML keys, dangling bindings and version rejection.

## Consequences

The declaration is service-neutral and backend-independent. A later
resolver combines it with Evidence and derives framework-specific binding
contracts. M0 prototypes retain their historical format and have a
documented migration path. The Evidence and Application Model versions
keep their existing discriminators.
