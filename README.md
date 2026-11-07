# magic-infra

Self-hosted, code-first infrastructure compiler.

magic-infra combines application evidence with explicit resource intent
to produce deployment files you can inspect, version, and run yourself.
The compiler analyzes Spring Boot and renders PostgreSQL stacks for Docker
Compose or [NixOS on a VPS](docs/NIXOS.md) from the same Application Model.
Kubernetes/K3s is the next target.

- [Explore the product preview](https://christian-draeger.github.io/magic-infra/)
- [Read the product vision and ecosystem comparison](docs/PRODUCT.md)
- [Read the engineering roadmap](docs/ROADMAP.md)
- [Try the Spring Evidence adapter](docs/SPRING-EVIDENCE.md)
- [Run the PostgreSQL Compose fixture](docs/COMPOSE.md#verify-the-fixture)
- [Run the contract checks and contribute](CONTRIBUTING.md)
- [Kotlin vs. Go compiler decision](docs/compiler-language-spike.md)

The website shows the broader intended workflow and illustrative outputs.

To preview the site locally, open `website/index.html` or run
`python3 -m http.server 8080 --directory website`. The
[Pages workflow](.github/workflows/pages.yml) publishes only `website/`.
