# ADR 0006 · Separate relational suggestions from ownership

## Status

Accepted

## Decision

Apply a small versioned rule set to validated relational findings grouped
by service. Correlate independent JPA and PostgreSQL observations. Surface
multiple observed vendors as a conflict. Pass any explicit external intent
through a separate input owned by the caller. Evidence from an adapter
cannot serve as that input.

## Evidence

The fixtures exercise driver-only, JPA-only, combined, ambiguous,
multi-service and declared external cases. A Gradle fixture resolves
both MySQL and PostgreSQL drivers and the compiler reports a conflict.

## Consequences

A suggestion remains advisory. The compiler can explain it by rule version
and original finding IDs. An external declaration supports an existing
binding and a conflict blocks an unsupported resolution. Planning,
provisioning and declaration-file parsing remain separate steps.
