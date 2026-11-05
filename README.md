# magic-infra

Self-hosted, code-first infrastructure compiler for Docker and Kubernetes.

The goal: turn evidence from an existing application and explicit resource
intent into deployment files you can inspect, version, and run yourself.
Spring Boot, PostgreSQL, and Docker Compose are the first planned slice.
Kubernetes/K3s follows using the same application model.

- [Explore the product preview](https://christian-draeger.github.io/magic-infra/)
- [Read the engineering roadmap](docs/ROADMAP.md)
- [Kotlin vs. Go compiler decision](docs/compiler-language-spike.md)

The compiler is in planning. The website shows the intended workflow and
illustrative outputs.

To preview the site locally, open `website/index.html` or run
`python3 -m http.server 8080 --directory website`. The
[Pages workflow](.github/workflows/pages.yml) publishes only `website/`.
