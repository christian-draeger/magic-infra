# Compiler architecture

This is the planned design. Evidence, the Application Model and the Deployment
Plan are separate, versioned contracts. They distinguish what was observed,
what the user wants and what a specific target can produce.

```text
Framework adapter
  │  Evidence + diagnostics (versioned JSON)
  ▼
Input validation → resolver + policy  ←  infra.yaml
  │  Application Model (portable IR)
  ▼
Target planner  ←  backend capabilities + target settings
  │  Deployment Plan (target-specific preview)
  ▼
Backend renderer → Compose files or Kubernetes manifests
  │
  ▼
Your deployment workflow
```

## Adapter → Core: Evidence

A framework adapter reads the project and supported build/configuration
metadata in an explicit scope, such as the selected profiles. It reports
findings with stable IDs, service references, their source and uncertainty.
The handoff is a local subprocess or file protocol carrying versioned JSON,
so the compiler doesn't need the framework or the adapter's runtime.

For example, a PostgreSQL driver on the classpath is an observed fact. It
suggests a database binding; it doesn't authorize creating one. The compiler
validates the schema and references, and treats diagnostics and unresolved
questions as input to the next step. Evidence must not contain credentials.

## Core: Application Model (intermediate representation)

The resolver combines validated Evidence with explicit declarations in
`infra.yaml`. Policy checks catch conflicts, require an unambiguous binding and
prevent inferred requirements from turning into managed resources on their
own. A resource declared external is referenced without provisioning it.

The IR is a graph: services such as `api`, resources such as `primary-db`, and
bindings between them. Resources carry stable logical IDs, type, ownership,
and retention intent. A binding describes how a service uses a resource.
The model can refer to an image or a secret by reference and link decisions
back to findings and declarations. It carries no target-specific YAML or
secret values.

## Core → Backend: target planning and rendering

A selected backend works from the validated IR and target settings. Its
capabilities tell the planner which guarantees can be met. For example, a
single-host Compose target cannot promise host failover. Kubernetes planning
for managed PostgreSQL names the CloudNativePG operator and compatible storage
as prerequisites. An unsatisfied requirement blocks planning with a diagnostic.

The resulting Deployment Plan records expected resources, prerequisites,
artifact paths and explanations for one target. Early plans are compilation
previews; comparison with deployed infrastructure is a separate design step.
The renderer consumes the plan and emits ordinary files: a PostgreSQL service
and named volume for Compose, or a CloudNativePG Cluster manifest on Kubernetes,
where the operator manages the underlying storage. App bindings refer to the
chosen credentials without embedding their values.

Compilation can run without applying to a cluster or building an image.
Identical inputs, versions and target settings should yield the same files.
Image build, deployment and live operations are separate; the generated
workloads do not depend on a magic-infra runtime.

## Extension boundaries

An adapter should be testable against the Evidence contract without a backend.
A backend should be testable against IR and plan fixtures without a framework
adapter. Next.js is the planned test of a second adapter. Backend packaging and
the public plugin API remain open. The
[language decision](adr/0002-compiler-language.md) selects Kotlin/JVM for the
compiler. The [Score evaluation](adr/0001-score-interop.md) identifies the
workload subset that could be exported and the constraints on provisioner reuse.
