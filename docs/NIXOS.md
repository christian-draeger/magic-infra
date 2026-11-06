# Generate an app module for NixOS

The first NixOS slice takes a prebuilt OCI image and generates an importable
module. Build the compiler with `gradle :evidence-check:installDist` and run
the launcher from `compiler/evidence/build/install/evidence-check/bin/`.

```sh
evidence-check plan examples/nixos-app/evidence.json examples/nixos-app/infra.yaml --target nixos
evidence-check generate examples/nixos-app/evidence.json examples/nixos-app/infra.yaml --target nixos > magic-infra.nix
```

Review the plan and module. Import the module alongside the existing host
configuration on a NixOS host whose `virtualisation.oci-containers.backend`
is `podman`. The module asserts that choice and leaves it to the host owner.

```nix
{
  imports = [ ./magic-infra.nix ];
}
```

The module publishes each declared port on loopback and qualifies container
names by application, environment and service. Check for port conflicts with
the host before activation. The host needs access to the prebuilt image.
Use `nixos-rebuild build` to validate the resulting host configuration and
activate it through your usual workflow. Probe application health separately.

The NixOS target rejects resource bindings and host failover requests. The
[PostgreSQL follow-up](https://github.com/christian-draeger/magic-infra/issues/49)
covers managed and external bindings, runtime credentials, and VM tests.

## Verify the module import

After generating `magic-infra.nix` from the fixture above, use Nix to evaluate
it against a pinned NixOS 26.05 snapshot. The fixture checks that the module
adds the expected Podman service while keeping an existing host workload.
It also checks the assertion on a host configured for Docker.

```sh
nix-instantiate --eval --strict --json tests/nixos/eval-app.nix \
  --arg nixpkgs 'builtins.fetchTarball {
    url = "https://codeload.github.com/NixOS/nixpkgs/tar.gz/c56a87d8e41ce69bd2af57eb35dcc117a9dfca33";
    sha256 = "06g8y8y9bysnpxrl6bfqmn63l8w2lx2yhj0618r34j5jfqy5vjpx";
  }' \
  --argstr modulePath "$PWD/magic-infra.nix"
```
