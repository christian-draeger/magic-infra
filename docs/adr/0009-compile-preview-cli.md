# ADR 0009 · Separate compile preview from deployment

## Status

Accepted

## Decision

Keep the CLI noninteractive and put result documents on stdout and errors
on stderr. Use a compile-preview Deployment Plan derived from a validated
Application Model. Compose is the first target. Make the generator consume
that plan and place files in a selected local output directory.

## Evidence

The integration checks run managed and external previews, compare repeat
output, verify the JSON schema and check the working directory for writes.
An unresolved binding blocks the preview and produces an actionable
diagnostic. The Compose renderer will add artifact verification.

## Consequences

The same inputs can be reviewed in CI without a Docker context or registry
access. The preview records prerequisites and artifact paths without reading
deployed state. Local rendering and deployment are separate actions.
