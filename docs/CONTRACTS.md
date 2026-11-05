# Draft compiler contracts

Evidence, Application Model and Deployment Plan use independently versioned
JSON documents. The schemas in [schemas](../schemas/) use Draft 2020-12.
Consumers select the schema from the complete `schemaVersion` discriminator
and reject unknown versions. These `v1alpha1` drafts can change. Record
breaking changes and provide updated fixtures and migration notes when a
version changes.

| Contract | Producer → consumer | What it owns |
| --- | --- | --- |
| Evidence | Framework adapter → resolver | Observation IDs, service, source, certainty and diagnostics. An observed driver suggests a capability. |
| Application Model | Resolver → target planner | Stable IDs for the app, services and resources, with bindings and provenance. Explicit declarations decide ownership and retention. |
| Deployment Plan | Target planner → renderer or user | A selected target's prerequisites, operations and artifact paths. `compile-preview` describes intended output before anything is applied. |

Schema validation checks structure, enums, versions and relative artifact paths.
Semantic checks cover unique logical IDs, references across documents, target
capabilities and ownership. A plan may create a database only when that resource
is explicitly managed. Treat secret references as identifiers. Resolve
credentials during deployment and keep their values out of the documents and
generated files. Adapters are responsible for redacting free-form evidence before
publication. A schema cannot identify every secret that might appear in prose.

The [M0 examples](../examples/m0/) illustrate one relational resource. Their
`infra.yaml` shape and names are scoped to the spike. The full declaration
language and runtime compatibility belong to later milestones. The fixtures
can be reviewed and validated without executing either prototype.
