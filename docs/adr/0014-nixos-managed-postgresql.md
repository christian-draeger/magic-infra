# Provision managed PostgreSQL through the native NixOS service

Status: Accepted

## Decision

Extend the importable module from [ADR 0012](0012-render-nixos-app-module.md)
to enable the host's PostgreSQL service for explicit managed intent. A
systemd oneshot provisions a retained database and a role derived from the
application, environment and resource IDs. It checks ownership before
provisioning and loads the password at runtime through systemd credentials.
The app receives the resulting username, password and JDBC URL through
Podman. The external binding contract remains as recorded in
[ADR 0013](0013-nixos-external-postgresql.md).

Use a dedicated Podman bridge and a TCP proxy for each managed resource. The
proxy forwards to the native IPv4 loopback listener, with authentication
rules that pair the app role with its database. The host keeps its existing
PostgreSQL listen settings and can choose the PostgreSQL package and port.

## Evidence

The [pinned NixOS VM test](../NIXOS.md#verify-postgresql-in-vms) exercises the
generated module and Spring HTTP CRUD across a configuration activation and
reboot. It checks the missing and malformed secret paths, database ownership
conflicts and role scope.

## Consequences

The module shares one PostgreSQL instance with the host. Operators choose a
unique database name and own its backups, migrations and lifecycle. Removing
the module leaves PostgreSQL data on disk. NixOS configuration rollback
changes services and settings. Database recovery requires its own procedure.

A later container-free path needs a versioned Nix package or reproducible
artifact with an explicit entry point, runtime dependencies, service user
and writable directory contract. Keep those packaging inputs separate from
database intent. A native systemd workload could use a loopback database
binding with dedicated authentication rules.
