# First user journey

A developer already has a Spring Boot service built with Gradle and wants to
run it with PostgreSQL locally or on a single VPS. Both Java and Kotlin are in
scope. The first slice starts with a prebuilt application image. The developer
reviews the generated files and runs the Compose stack themselves.

1. Analyze supported project inputs and show which findings point to a
   relational database. A finding carries its source and uncertainty.
2. Ask the developer how the database should be managed. Record their
   choice in `infra.yaml` and retain it as deployment intent.
3. Produce a preview with prerequisites, planned files and the source of
   resource decisions. The developer can review the Compose files before
   deploying them.
4. Verify the application can create and read data and the data survives
   restarting the database container with its volume intact.

| Case | Expected outcome |
| --- | --- |
| Explicitly managed PostgreSQL | Plan an app binding, database service and named volume. Bind credentials by reference. Repeating generation with the same inputs yields identical files. |
| Explicitly external PostgreSQL | Plan an app binding to the supplied endpoint and secret references. The files contain no database service or volume for that resource. |
| Ownership is unclear | Explain the finding and the missing decision. Deployment generation stops with a diagnostic and produces no managed database or deployment files. |

The core model must support the same intent on Kubernetes. M3 demonstrates
that with K3s and CloudNativePG. The first slice leaves Kubernetes integration,
live drift detection, and automated ownership transfers to their corresponding
milestones. The [roadmap](ROADMAP.md) tracks those milestones. Apache-2.0
governs contributions under the [repository license](../LICENSE).
