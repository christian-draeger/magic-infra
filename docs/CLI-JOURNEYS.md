# Preview infrastructure from the command line

Build the local compiler launcher with `gradle :evidence-check:installDist`.
In the examples below, `evidence-check` refers to the launcher under
`compiler/evidence/build/install/evidence-check/bin/`.

| Journey | Command and result |
| --- | --- |
| Start a declaration | `evidence-check init shop example.invalid/shop:fixture 8080` prints starter YAML with an empty resource map and no bindings. Save it as `infra.yaml` after reviewing it. |
| Analyze | Run the Spring module's `springEvidence` task. It writes Evidence under `build/magic-infra/`. Run `evidence-check analyze evidence.json` to validate and inspect the handoff. |
| Explain | `evidence-check explain evidence.json` shows inferred bindings and conflicts with rule and finding IDs. |
| Plan | `evidence-check plan evidence.json infra.yaml dev` emits a versioned Compose preview. The environment defaults to `dev`. |
| Generate | The Compose renderer will consume the resolved model and successful plan and write `compose.yaml` to the selected local output directory. The renderer is tracked in [#17](https://github.com/christian-draeger/magic-infra/issues/17). |

`plan` reads only the two inputs. It outputs anticipated actions,
prerequisites and relative artifact paths, including `compose.yaml`. It
does not inspect deployed state. An external database yields a reference
action and a managed database yields a create action in the preview.
Actual deployment runs separately after the files have been reviewed.

Review the plan before generation. The renderer will use deterministic
file contents, refuse unresolved ownership, and preserve files outside its
chosen output directory. The model and plan contain secret references.
Credential material comes from the deployment environment.

## CI and diagnostics

These commands are noninteractive. Use `-` for at most one input to a
command. Structured result documents go to stdout. Prefix commands with
`--diagnostics=json` to get a JSON error on stderr with `code`,
`severity` and `message`. Plan diagnostics also carry warnings from
Evidence. A blocked plan emits no partial JSON document.

| Exit status | Meaning |
| --- | --- |
| `0` | Successful validation, analysis or preview |
| `1` | Input I/O failure or invalid internally generated output |
| `2` | Invalid arguments or input |
| `3` | Unsupported contract version |
| `4` | Unresolved binding or conflicting Evidence |

The first plan targets one Compose host. Kubernetes planning and
state-aware comparisons have separate roadmap milestones.
