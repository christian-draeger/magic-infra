# Declare infrastructure in infra.yaml

The declaration describes the infrastructure you intend to own or connect
to. Framework adapters report observations in Evidence. Add a resource
and a service binding when you are ready to make that decision.

The [managed PostgreSQL example](../examples/infra/managed.yaml) declares a
database and storage. The [external PostgreSQL example](../examples/infra/external.yaml)
binds the same service to an existing database. Both use the
[infra/v1alpha2 schema](../schemas/infra.v1alpha2.schema.json).

| Field | Meaning |
| --- | --- |
| `applicationId` | Stable identifier matching the analyzed application. |
| `services` | Service IDs mapped to a prebuilt `imageRef` and container `port`. Use a tagged image or a SHA-256 digest. |
| `resources` | Resource IDs mapped to kind, explicit management and deletion policy. The first slice supports PostgreSQL and `retain`. |
| `bindings` | Pairs of existing `serviceId` and `resourceId`. A service has at most one database binding in this version. |

A managed database needs `storageSize` in whole GiB. An external database
needs an `endpointRef` and has no storage declaration. Both need the
logical database name and a `secretRef`. The endpoint is the name of an
environment variable available at deployment. The secret is a logical
reference. Keep the endpoint value, username and password outside this file.
Target-specific configuration belongs to the backend.

## Validate a declaration

```sh
gradle :evidence-check:installDist
compiler/evidence/build/install/evidence-check/bin/evidence-check \
  validate-infra examples/infra/managed.yaml
```

The validator accepts one UTF-8 YAML mapping, or stdin with `-`. It checks
the version, property rules and binding references. Mapping keys must be
unique strings. Use explicit values in place of YAML anchors and merge keys.
Success is silent. Invalid input exits with status `2`, an unknown
version with status `3`, and input I/O failure with status `1`.

## Resolve Evidence and intent

The declaration supplies ownership and retention. Evidence can suggest a
binding and explain incompatibilities. The resolver will stop when an
effective configuration or an incompatible driver observation conflicts with
the declared resource. A matching driver may coexist with unused drivers.
An empty resource and binding list keeps ownership undecided for an observed
database. A database-dependent plan needs an explicit binding.

## Migrate the M0 spike fixture

The M0 prototypes use the historical `infra/v1alpha1` shape. Keep it for
running the prototypes. For the compiler declaration, move the old
`postgres` block into `resources` under its former `id`, add
`kind: postgresql` and `deletionPolicy: retain`, and create a binding from
each consuming service. Keep the old management choice, database and
references. Use `infra/v1alpha2` after changing the shape. The core rejects
other versions and performs no implicit migration.
