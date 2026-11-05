# Explain relational Evidence

The core evaluates each service's relational findings independently. Every
result carries the compiler rule version, a diagnostic code and the IDs of
the contributing findings. The original Evidence gives their sources and
certainty.

```sh
gradle :evidence-check:installDist
compiler/evidence/build/install/evidence-check/bin/evidence-check \
  explain examples/m0/evidence.json
```

| Evidence | Outcome |
| --- | --- |
| JPA only | Suggested relational binding with an unknown vendor |
| PostgreSQL driver only | Suggested PostgreSQL binding |
| JPA and a PostgreSQL driver or configuration observation | Suggested PostgreSQL binding with both sources |
| Explicit existing database | Declared external binding |
| Several drivers without a selected vendor, contradictory configuration, or a selected vendor with only incompatible drivers | Conflict that requires a binding decision |

Uncertain findings stay visible in the explanation. They do not choose a
vendor. A supported vendor reported by an observed dependency or effective
configuration can identify a candidate. An effective configuration or
explicit declaration selects among available drivers when a matching
driver is present. An observed unsupported vendor can still conflict with
other observations. Evidence alone never authorizes
resource creation. A declaration-shaped finding from an adapter cannot set
ownership.

For a local preview, pass an explicit external binding separately as
`--external api postgresql`. The command accepts one service and one vendor.
This preview has no endpoint or credentials and produces no plan. The
declaration file and plan command follow in the end-to-end slice.

A conflict produces an explanation and exits with status `4`. Input
validation and I/O retain the existing exit statuses. The plain
`evidence-check <file or ->` form still validates silently on success.
