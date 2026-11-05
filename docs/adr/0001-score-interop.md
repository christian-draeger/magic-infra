# ADR 0001: Keep the Application Model and evaluate Score export later

**Status:** Accepted for M0

The [first user journey](../FIRST-USER.md) requires explicit ownership, traceable
findings and deterministic output with secret references. A Score workload
describes a useful subset. It delegates resource semantics to a provisioner.

| Need | Score mapping | What magic-infra keeps |
| --- | --- | --- |
| Image, service and port | Workload name, container image and service port | Stable app/service IDs and source |
| PostgreSQL dependency and binding | Resource type and ID, with references to provisioner outputs | Managed/external ownership, connection references and binding contract |
| Lifecycle and storage | Provisioner-specific parameters or templates | Retention and target capability decisions |
| Where a decision came from | No standard field with these semantics | Evidence, diagnostic source and provenance |
| What the target will produce | Generator output and implementation state | Deployment Plan with prerequisites and expected artifact paths |

## Experiment

Ran [the draft workload](../../examples/m0/score.yaml) with the unmodified
default provisioners from [score-compose 0.49.0](https://github.com/score-spec/score-compose/releases/tag/0.49.0)
and [score-k8s 0.22.0](https://github.com/score-spec/score-k8s/releases/tag/0.22.0).
In a separate scratch directory per tool, copy the fixture to `score.yaml`,
run `init --no-sample`, then `generate score.yaml` twice. Repeat from empty
state. The fixture uses a placeholder image and the test stops at generation.

- Compose emitted a PostgreSQL stack with a volume. The default provisioner
  generated credentials, logged a password and put a password in the app
  environment in the generated file.
- Kubernetes emitted a Secret with data and a PostgreSQL StatefulSet instead
  of the planned CloudNativePG resource.
- Both wrote local state. Both reproduced the output with that state in
  place. Both changed output when initialized from scratch.
- Setting `class: external` without adding an external provisioner still
  generated the database in both tools. External bindings need a provisioner
  that implements that contract. The class alone cannot enforce ownership.

Custom implementations can provide static outputs or use secret references.
They can also call external APIs or execute code as part of resource
provisioning. Generation therefore inherits the behavior of the configured
provisioner. [Score implementation requirements](https://docs.score.dev/docs/how-to/create-a-score-implementation/)

## Decision

Keep Evidence, the Application Model and the Deployment Plan as separate
contracts. Preserve the option to export the portable workload subset to
Score after the first backends are proven. Any future reuse of a renderer
or provisioner must demonstrate deterministic output without local resource
state, secret values or infrastructure writes. An export would be derived
from resolved intent and could not transfer ownership on its own.
