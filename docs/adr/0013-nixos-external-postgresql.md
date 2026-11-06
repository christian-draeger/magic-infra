# Bind external PostgreSQL through NixOS runtime files

Status: Accepted

## Decision

Map `secretRef` and `endpointRef` to separate runtime env files scoped by
application and environment. The generated Podman module receives only
absolute path strings. It validates that the operator supplied the required
Spring datasource keys at service startup.

## Evidence

The NixOS OCI module forwards `environmentFiles` to Podman as runtime inputs.
Separate files preserve the logical endpoint reference without embedding a
JDBC value or using shell interpolation. They also prevent collisions between
endpoint names and Spring credential keys. The CLI fixtures check both plan
and artifact bytes. The [verification guide](../NIXOS.md) documents the VM
test with the Spring and PostgreSQL fixture.

## Consequences

The host owner provides runtime files, controls rotation and keeps ownership
of the existing database. Managed PostgreSQL and system-configuration reboot
tests remain in [#49](https://github.com/christian-draeger/magic-infra/issues/49).
Container-free execution will need an explicit package and entry point and
a corresponding runtime credential handoff for systemd services.
