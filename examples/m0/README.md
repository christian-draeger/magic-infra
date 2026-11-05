# M0 contract fixtures

The captured Evidence represents a Gradle Spring Boot app with observed
PostgreSQL dependencies and an unresolved configuration placeholder. The
declarations show managed, external and ambiguous ownership. Positive golden
outputs cover both targets from the same target-neutral application model.
The `score.yaml` file is a [separate interoperability experiment](../../docs/adr/0001-score-interop.md).

Run the [contract checks](../../CONTRIBUTING.md#run-the-checks) after edits.
Review changes to IDs, provenance, ownership, plan prerequisites and secret
references. Update a golden file only when the intended behavior changes.
Both compiler prototypes must agree with the updated golden output. No
timestamps, host paths or actual credentials belong in these files.
