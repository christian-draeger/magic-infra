# Product vision and positioning

magic-infra is a self-hosted infrastructure compiler for existing applications.
The app code is the source of truth for what the app needs and how its services
connect. magic-infra reads those signals, asks for decisions the code cannot
make, and generates deployment files you can review, run and own. Each
framework adapter reports its findings through a shared Evidence contract,
so new adapters can use the same compiler and deployment backends. Spring Boot
is the first planned adapter; a Next.js pilot will test that boundary.

The compiler and integrations described here are design goals. The same
application model should cover three starting points:

- **Local Compose:** Give yourself or a teammate a repeatable dev stack with
  the app and its dependencies, without hand-writing the Compose file.
- **Compose on a VPS:** Ship that app to your own server with reviewable
  services, volumes and secret references. Keep your deployment workflow.
- **An existing Kubernetes cluster:** Add a service, bind infrastructure you
  already operate, and review changes to generated manifests as the app evolves.

The [first user journey](FIRST-USER.md) defines what the initial Compose slice
must demonstrate.

## What makes it magic

A PostgreSQL driver is evidence, not permission to create a database. The
adapter reports what it saw, where it came from, and what remains uncertain.
You can confirm managed PostgreSQL, bind a database you already run, or leave
the question unresolved. The compiler resolves that choice into the
**Application Model**, then turns it into a target-specific **Deployment Plan**
with prerequisites, diagnostics and changes you can review. Compose and
Kubernetes share the intent; their storage, availability and operations differ.

The files should outlive the compiler. Commit them, review diffs, and deploy
them with your own CI, GitOps or manual workflow. For persistent data, the
handover must say who owns the resource, where its credentials live, and how
to keep it when the old deployment stops managing it. Where safe automation
isn't possible, document a manual handover. You still run the backups, updates
and recovery process.

The trade-off: reading existing conventions avoids a framework migration, but
some Spring behavior only becomes clear at runtime. Favor traceable findings
and explicit questions over guesses. Start with a small, well-tested resource
catalog rather than building a general IaC engine.

For the handoff between adapters, the core and backends, see the
[compiler architecture](ARCHITECTURE.md).

## Where other tools fit

This is a comparison of starting points and responsibilities, not a feature
ranking.

| Tool | Choose it when… | Relationship to magic-infra |
| --- | --- | --- |
| [Nitric](https://nitric.io/docs/get-started/foundations/infrastructure) | You want an app SDK to express resource requirements and providers to implement them. | Close product comparison. SDK calls make intent explicit; magic-infra reads the conventions of existing frameworks and asks about ambiguity. Nitric also supports [custom providers](https://nitric.io/docs/providers/custom). |
| [Encore](https://encore.dev/docs/platform/introduction) | You want an integrated Go or TypeScript backend framework with local infrastructure and a managed or [self-hosted](https://encore.dev/docs/self-host/build) deployment path. | Close product comparison. Both derive infrastructure from applications. magic-infra focuses on adopting an existing framework and producing Compose or Kubernetes files, rather than using Encore primitives. |
| [SST](https://sst.dev/docs/providers/) | You want to define your infrastructure in TypeScript and use a broad provider catalog. | Adjacent alternative. SST gives you explicit control over topology and resource state; magic-infra trades some of that breadth for discovery and a smaller catalog. |
| [Score](https://docs.score.dev/docs/how-to/create-a-score-implementation/) | You have a workload spec and want to map it onto Compose or Kubernetes. | Closest architecture overlap. Evaluate exporting the workload portion of our model instead of inventing a second way to describe ports, containers and resource dependencies. |
| [Tilt](https://docs.tilt.dev/docker_compose.html) | You want fast local builds, live updates and feedback for Compose or Kubernetes. | Complement. A Tiltfile could consume generated artifacts; Tilt handles the development loop. |
| [Coolify](https://coolify.io/docs/applications/builds/docker-compose) | You want a dashboard to deploy Compose apps and manage them on your own servers. | Alternative way to run the deployment. Test whether it can consume the generated Compose files and clearly define which tool manages proxy settings, secrets and volumes. |
| [Dokploy](https://docs.dokploy.com/docs/core/docker-compose) | You want a deployment UI for Docker, with documented Compose and Swarm modes. | Similar overlap on self-hosted operations. Explore Compose compatibility; Swarm is a separate deployment target with different semantics. |
| [Pulumi](https://www.pulumi.com/docs/iac/concepts/stacks/) | You need to provision and track cloud networking, access policies or managed services. | Complement. A Pulumi stack could own PostgreSQL while magic-infra binds to it as external. Share an endpoint and secret reference; keep lifecycle ownership with Pulumi. |

## What to evaluate first

- **M0: Score fit.** The [Score evaluation](adr/0001-score-interop.md) keeps
  the Application Model and leaves room for exporting the portable workload
  subset after backend parity.
- **After the first Compose slice:** Try a small Tiltfile that reads generated
  files. Keep live updates in Tilt. After the Kubernetes slice, document how a
  GitOps workflow can deploy generated manifests with retention and pruning
  handled explicitly.
- **After backend parity:** Test the Coolify and Dokploy Compose workflows
  against real outputs before claiming compatibility. Start with external
  resource references for Pulumi-style workflows; consider a provider or
  export only if there is a concrete need. Assign one lifecycle owner to each
  resource.
