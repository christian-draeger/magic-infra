package dev.magicinfra.compiler.evidence

import com.fasterxml.jackson.databind.JsonNode

internal fun nixosCredentialsPath(app: String, environment: String, secretRef: String): String =
    "/run/secrets/magic-infra/${app}_$environment/$secretRef.env"

internal fun nixosEndpointPath(app: String, environment: String, endpointRef: String): String =
    "/run/secrets/magic-infra/${app}_$environment/endpoints/$endpointRef.env"

internal object NixosRenderer {
    fun render(model: JsonNode, plan: JsonNode): String = buildString {
        val app = model.path("application").path("id").asText()
        val environment = plan.path("target").path("environment").asText()
        val resources = model.path("resources").associateBy { it.path("id").asText() }
        val files = model.path("bindings").associate { binding ->
            val id = binding.path("serviceId").asText()
            val connection = resources.getValue(binding.path("resourceId").asText()).path("connection")
            id to ExternalFiles(
                nixosCredentialsPath(app, environment, connection.path("secretRef").asText()),
                nixosEndpointPath(app, environment, connection.path("endpointRef").asText()),
            )
        }
        appendLine(if (files.isEmpty()) "{ config, ... }:" else "{ config, lib, pkgs, ... }:")
        if (files.isNotEmpty()) appendLine(runtimeFileCheck)
        appendLine("{")
        appendLine("  assertions = [")
        appendLine("    {")
        appendLine("      assertion = config.virtualisation.oci-containers.backend == \"podman\";")
        appendLine("      message = \"magic-infra requires Podman as the host OCI backend.\";")
        appendLine("    }")
        appendLine("  ];")
        appendLine("  virtualisation.oci-containers.containers = {")
        // The declaration and environment validators restrict values to Nix-safe ASCII.
        for (service in model.path("services")) {
            val id = service.path("id").asText()
            val image = service.path("build").path("imageRef").asText()
            val port = service.path("runtime").path("port").asInt()
            appendLine("    \"magic-infra-${app}_${environment}_$id\" = {")
            appendLine("      image = \"$image\";")
            appendLine("      ports = [ \"127.0.0.1:$port:$port\" ];")
            files[id]?.let { input ->
                appendLine("      environmentFiles = [ \"${input.credentials}\" \"${input.endpoint}\" ];")
            }
            appendLine("    };")
        }
        appendLine("  };")
        for ((id, input) in files) {
            val serviceName = "\${config.virtualisation.oci-containers.containers.\"magic-infra-${app}_${environment}_$id\".serviceName}"
            appendLine("  systemd.services.$serviceName.preStart = lib.mkBefore ''")
            appendLine("    \${checkRuntimeInput}")
            appendLine("    check_runtime_input \"${input.credentials}\" SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD")
            appendLine("    check_runtime_input \"${input.endpoint}\" SPRING_DATASOURCE_URL")
            appendLine("  '';")
        }
        appendLine("}")
    }

    private data class ExternalFiles(val credentials: String, val endpoint: String)

    private val runtimeFileCheck = """
        let
          checkRuntimeInput = ''
            check_runtime_input() {
              local file="${'$'}1"
              shift
              if [ ! -f "${'$'}file" ] || [ ! -r "${'$'}file" ]; then
                echo "magic-infra: provide a readable runtime env file at ${'$'}file." >&2
                return 1
              fi
              local size
              size=${'$'}("${'$'}{pkgs.coreutils}/bin/stat" -Lc %s -- "${'$'}file") || return 1
              if [ "${'$'}size" -gt 16386 ]; then
                echo "magic-infra: runtime env file ${'$'}file exceeds 16386 bytes." >&2
                return 1
              fi
              if ! LC_ALL=C "${'$'}{pkgs.gawk}/bin/awk" -v keys="${'$'}*" '
                BEGIN {
                  split(keys, fields, " ")
                  for (i in fields) expected[fields[i]] = 1
                }
                {
                  splitAt = index(${'$'}0, "=")
                  if (splitAt == 0) exit 1
                  key = substr(${'$'}0, 1, splitAt - 1)
                  if (!(key in expected) || seen[key]++ || splitAt == length(${'$'}0) ||
                      length(${'$'}0) > 8192 || index(${'$'}0, "\r") || index(${'$'}0, sprintf("%c", 0))) exit 1
                  if (key == "SPRING_DATASOURCE_URL" && substr(${'$'}0, splitAt + 1) !~ /^jdbc:postgresql:.+/) exit 1
                }
                END { for (key in expected) if (seen[key] != 1) exit 1 }
              ' "${'$'}file"; then
                echo "magic-infra: provide one non-empty assignment for each of ${'$'}* in ${'$'}file, with LF line endings and at most 8192 bytes per line. Use a PostgreSQL JDBC URL for SPRING_DATASOURCE_URL." >&2
                return 1
              fi
            }
          '';
        in
    """.trimIndent()
}
