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

The NixOS target rejects host failover requests. The
[PostgreSQL follow-up](https://github.com/christian-draeger/magic-infra/issues/49)
tracks native managed PostgreSQL and data retention across a configuration
switch and reboot.

## Bind existing PostgreSQL

Declare an `external` PostgreSQL resource and a Spring datasource binding.
The NixOS plan names two runtime env files on the host. For application
`shop`, environment `dev`, `secretRef: shop-db-credentials` and
`endpointRef: SHOP_DB_URL`, supply these files:

| Runtime file | Required assignments |
| --- | --- |
| `/run/secrets/magic-infra/shop_dev/shop-db-credentials.env` | `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` |
| `/run/secrets/magic-infra/shop_dev/endpoints/SHOP_DB_URL.env` | `SPRING_DATASOURCE_URL` |

Use one non-empty `KEY=value` assignment per line, with LF line endings and
at most 8192 bytes per line. Each file accepts only its listed keys. Supply
a `jdbc:postgresql:` URL and keep credentials separate from it. Have your
secret provider create the files on the host before
starting the workload, with access limited to the Podman service user.
The service checks for missing or malformed files before starting the app.
Its diagnostics name the file and expected keys.

The generated module references those files as strings. Podman reads them
at runtime. The module does not copy their contents into the Nix store or
manage the external database. After changing the files, restart the app's
systemd unit to load the new values, for example
`podman-magic-infra-shop_dev_api.service`.

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

## Verify external PostgreSQL in VMs

Run on Linux with Nix, JDK 21, Gradle 8.5 and a local Docker engine for
building the fixture image. Build for the Linux test runner's architecture.
The test driver can use KVM or QEMU software emulation.

```sh
gradle :evidence-check:installDist
gradle -p examples/compose-app springEvidence bootJar
work=$(mktemp -d)
image="localhost/magic-infra-nixos-fixture:$(basename "$work")"
docker build -t "$image" examples/compose-app
docker save -o "$work/image.tar" "$image"
docker image rm "$image"
sed -e "s|magic-infra-compose-app:fixture|$image|" \
  -e 's/management: managed/management: external/' \
  -e 's/storageSize: 1Gi/endpointRef: FIXTURE_DB_URL/' examples/compose-app/infra.yaml | \
  compiler/evidence/build/install/evidence-check/bin/evidence-check generate \
    examples/compose-app/build/magic-infra/evidence.json - --target nixos > "$work/magic-infra.nix"

nix-build --no-out-link tests/nixos/external-vm.nix \
  --arg nixpkgs 'builtins.fetchTarball {
    url = "https://codeload.github.com/NixOS/nixpkgs/tar.gz/c56a87d8e41ce69bd2af57eb35dcc117a9dfca33";
    sha256 = "06g8y8y9bysnpxrl6bfqmn63l8w2lx2yhj0618r34j5jfqy5vjpx";
  }' \
  --argstr modulePath "$work/magic-infra.nix" --argstr imagePath "$work/image.tar"
```

The driver boots separate app and database VMs. It checks the missing-file
diagnostic, supplies credentials at runtime, verifies HTTP CRUD, and checks
that a note survives an app service restart. The app VM runs no PostgreSQL
service.
