# ADR 0008 · Resolve declaration and Evidence into a portable model

## Status

Accepted

## Decision

Resolve validated Evidence and a declaration with matching application
and service identities into the versioned Application Model. The
declaration sets resource ownership, retention and service bindings.
The relational rules explain vendor conflicts. Require a binding for
observed relational usage and reject unused resources. Sort logical IDs
and provenance so the same inputs produce identical bytes.

## Evidence

The resolver reproduces the managed and external M0 Application Model
fixtures with the new declaration pointers. Tests cover incompatible
vendors, missing intent, orphan resources and a shared resource across two
services. Reordering input records leaves the output unchanged.

## Consequences

The planner receives one portable model with explicit ownership and
provenance. An external database carries connection references and no
managed storage settings. The resolver leaves planning and rendering to
the target backend.
