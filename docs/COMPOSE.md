# Render a PostgreSQL stack for Docker Compose

The generator uses the same validated inputs as the Compose preview.
It writes one deterministic YAML document to stdout. Save it in the
directory where you want to review the Compose file.

```sh
evidence-check plan evidence.json infra.yaml dev
evidence-check generate evidence.json infra.yaml dev > compose.preview.yaml
docker compose -f compose.preview.yaml config --quiet
```

The renderer uses a prebuilt application image. Application ports are
published on the local loopback interface. A managed PostgreSQL resource
runs a digest-pinned PostgreSQL 16 image with a named volume. The Spring
service waits for the PostgreSQL healthcheck before it starts. An external
resource is supplied through an endpoint environment reference and adds
no database service or volume. Compose configuration validation needs the
referenced environment variables and credential files to be available.
Compose project names join the application and environment IDs with an
underscore, keeping different pairs distinct. The app's HTTP health path
remains runtime metadata for an external probe. The renderer adds the
database healthcheck to the Compose file.

## Supply local credentials

For each `secretRef`, provide `secrets/<secretRef>.env` beside
`compose.yaml`. Keep these files outside version control. The generated
file carries paths and references. Compose reads env files in raw format
so credential values are passed through as written. Use Compose 2.30 or
later for this format. See the
[Compose service reference](https://docs.docker.com/reference/compose-file/services/#env_file).

For Spring, provide `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD`. For a managed database, provide matching
`POSTGRES_USER` and `POSTGRES_PASSWORD` entries in the same local file.
The first slice uses the managed database's bootstrap login for the app.
That login has PostgreSQL superuser privileges.
The generated environment sets the database name and JDBC URL. For an
external database, provide the variable named by `endpointRef` to the
Compose CLI and use credentials for the existing database.

## Verify the fixture

```sh
gradle :evidence-check:installDist
python tests/check_oci_image.py --with-compose
```

The smoke test builds the real Spring image separately, validates
generated Compose, starts an isolated managed stack, and waits for
PostgreSQL and Spring health. It validates the external variant without
contacting its database.

The managed volume survives ordinary container replacement and
`docker compose down`. An explicit `docker compose down --volumes`
removes it. Compose does not enforce a storage quota. Passing
`--require-host-failover` to `plan` or `generate` stops compilation
because the Compose target uses a single host.
