# Build an OCI image separately from infrastructure compilation

The first slice consumes a prebuilt image. A declaration provides a tagged
or digest-pinned `imageRef`, the container port, and an optional HTTP
`healthPath`. The Application Model carries these as runtime facts.
Backends consume the facts without depending on Spring or a build provider.

| Image source | Fit for the first slice |
| --- | --- |
| Existing image | Use its tag for local iteration or its digest for a reproducible deployment reference. The registry must retain that digest and the target must be able to pull it. |
| Dockerfile | The fixture builds a Spring Boot executable JAR separately and copies it into a digest-pinned JRE image. The app team owns the base updates and build recipe. |
| Cloud Native Buildpacks | Spring Boot's explicit `bootBuildImage` task delegates packaging to a builder. Pin the builder and run image when repeatability matters, and pass the resulting image reference through the same declaration. |

The analysis task can read dependencies and configuration without building
an image. Image build modes in `infra.yaml` are rejected. The build,
registry credentials, and image publication belong to the caller's
workflow. The [Spring Boot Dockerfile guide](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html)
describes executable and layered JAR packaging.

## Run the fixture

Use JDK 21, Gradle 8.5 and a local Docker engine. The fixture has Spring Boot
3.5.16 and a health endpoint on port 8080.

```sh
gradle -p examples/compose-app bootJar
docker build -t magic-infra-compose-app:fixture examples/compose-app
docker run --rm -p 127.0.0.1:8080:8080 magic-infra-compose-app:fixture
```

Check `http://127.0.0.1:8080/actuator/health`. The
[fixture declaration](../examples/compose-app/infra.yaml) records the same
image and runtime settings. `python tests/check_oci_image.py` runs a smoke
test with an isolated tag, container name and loopback port on a local
Unix-socket Docker context. Build the
compiler launcher first with `gradle :evidence-check:installDist`.

A local Compose host can use an image in its Docker engine. A remote VPS or
K3s node needs access to the same reference through a registry or a
node-local import. For a multi-node target, make it available on every
eligible node and provide images for their architectures. The fixture
builds for the local engine's platform. Its Temurin base digest points to
an OCI index with Linux AMD64 and ARM64 manifests.
The base digest and deterministic JAR settings reduce build drift. Use
the resulting image digest to identify the exact deployed image.
