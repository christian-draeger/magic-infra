# Render an importable NixOS application module

Status: Accepted

## Decision

Select NixOS with `--target nixos` in `plan` or `generate`. Add `nixos` to
the alpha plan target enum. Compose remains the default. The NixOS renderer
uses the existing Application Model and writes `magic-infra.nix` to stdout.
The module declares OCI containers and asserts that the host uses Podman.
The host owner controls global configuration, image access and activation.

## Evidence

The Compose slice already establishes the prebuilt image and portable model
contract. A NixOS evaluation fixture imports the module alongside an existing
container and checks the generated Podman service and Docker-host assertion.
See the [verification instructions](../NIXOS.md#verify-the-module-import).

## Consequences

This slice supports apps without resource bindings. It rejects database
bindings and host failover requirements before rendering. PostgreSQL
provisioning, runtime credentials and VM testing follow in
[#49](https://github.com/christian-draeger/magic-infra/issues/49). Container-free
operation would need an explicit package and entry point contract.
