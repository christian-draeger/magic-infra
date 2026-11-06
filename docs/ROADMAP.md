# Engineering roadmap

Milestones describe outcomes and have no fixed dates. Spring Boot and Gradle
are the first analysis targets. See the [tested adapter combinations](SPRING-EVIDENCE.md#tested-combinations).

| Milestone | What we need to prove |
| --- | --- |
| M0 · Foundations | Define versioned Evidence, Application Model and Deployment Plan contracts and check them with positive and negative fixtures. Decide who owns generated files and how ambiguous evidence is reported. Complete the [Kotlin vs. Go compiler spike](compiler-language-spike.md) and record the decision before production compiler work. Compare the first Application Model fixture with Score before locking the model. |
| M1 · Spring analysis | Java and Kotlin Spring Boot apps emit repeatable evidence with provenance without booting the app. A database driver on the classpath never authorizes provisioning. |
| M2 · First end-to-end slice | One Spring app with explicit PostgreSQL intent generates reviewable Docker Compose files. Verify CRUD and persistent storage, stable output on repeat generation, and references to credentials rather than credentials in Git. |
| M3a · NixOS on a VPS | Import the generated application module on an existing NixOS host. Verify Podman, managed and external PostgreSQL, and data retention in a VM. |
| M3b · Kubernetes portability | Render the same application model for K3s with CloudNativePG and verify it in a test cluster. Surface missing operator and storage prerequisites, and document differences from Compose and NixOS. |
| M4 · Safe operations | Make plan diffs, external bindings, secrets, retention and ownership transfer reviewable. Test that destructive changes are blocked by default and that handover keeps data intact. |
| M5 · More resources | Test RabbitMQ and Valkey on Compose and Kubernetes. Explain the durability and availability each target can deliver. |
| M6 · Extensibility and beta | Use an experimental Next.js adapter to prove the public contract works independently of the core and Spring adapter. Verify install, compatibility and operations before a beta. |

Framework adapters report evidence and uncertainty. An explicit declaration
determines ownership. The core resolves the application model, and each backend
checks its target's capabilities before producing a plan and artifacts. Image
building and deployment stay separate from that compilation path.

See the [product vision and integration priorities](PRODUCT.md#what-to-evaluate-first)
for the Score analysis and later compatibility experiments.

## NixOS on a VPS

The [app module](https://github.com/christian-draeger/magic-infra/issues/52)
uses the same Application Model to generate `magic-infra.nix` for a prebuilt
OCI app on an existing NixOS host. The [PostgreSQL spike](https://github.com/christian-draeger/magic-infra/issues/49)
adds native PostgreSQL for explicitly managed resources and runtime bindings
to external instances. It verifies CRUD and data retention after a
configuration switch and reboot in a NixOS VM. Credentials are supplied at
runtime.

The operator owns the host configuration, activation and database recovery.
A later container-free systemd mode would need a versioned package and an
explicit entry point. See the [product vision](PRODUCT.md#nixos-on-your-vps).
