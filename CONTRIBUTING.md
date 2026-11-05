# Contributing

Start with the [engineering roadmap](https://github.com/christian-draeger/magic-infra/issues/37)
and pick an issue whose blockers are complete. Read the
[first user journey](docs/FIRST-USER.md) and [architecture](docs/ARCHITECTURE.md)
to identify which contract the change affects.

## Run the checks

From the repository root with Python 3.12 or later:

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r tests/requirements.txt
python -m unittest discover -s tests -v
```

The contract suite runs without a cluster, application image or credentials.
See [the compiler spike](spikes/compiler/README.md) for the experimental
toolchains. The Pages workflow handles the website independently.

## Review and versioning

Link the issue in your PR. Describe the expected behavior and include the
checks you ran. For contract changes, update the schema and positive and
negative examples together. Call out implications for credentials,
persistent data and existing consumers when they apply.

Use a short ADR in [docs/adr](docs/adr/) for decisions that affect a contract,
dependency, or target. Record the status, decision, evidence and consequences.
The version on each contract is independent. During alpha, record breaking
changes and migration instructions before changing the discriminator. Release
tags will use semantic versions, beginning with a prerelease when the project
has an installable artifact.

Contributions are covered by the [Apache-2.0 license](LICENSE).
