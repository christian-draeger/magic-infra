# ADR 0011 · Render explicit PostgreSQL intent to Compose

## Status

Accepted

## Decision

Render the validated model and successful preview to a single Compose
document. Managed PostgreSQL gets a pinned image, named volume and TCP
readiness check. External PostgreSQL gets an endpoint reference. Bind
Spring through local credential files and keep image packaging separate.

## Evidence

Managed and external artifacts validate with Docker Compose. The smoke
test starts the generated managed stack with a real Spring image and
observes both PostgreSQL and HTTP health. Repeat runs are byte-identical.
Requests for host failover and unresolved intent stop before rendering.

## Consequences

The first renderer works on one host, publishes application ports on
loopback and delegates deployment to the operator. Persistent data lives
in a named volume. Credential files remain local, and the application
image does not depend on a magic-infra runtime.
