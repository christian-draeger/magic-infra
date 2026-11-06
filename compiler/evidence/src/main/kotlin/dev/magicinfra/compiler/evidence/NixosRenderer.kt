package dev.magicinfra.compiler.evidence

import com.fasterxml.jackson.databind.JsonNode

internal object NixosRenderer {
    fun render(model: JsonNode, plan: JsonNode): String = buildString {
        val app = model.path("application").path("id").asText()
        val environment = plan.path("target").path("environment").asText()
        appendLine("{ config, ... }:")
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
            appendLine("    };")
        }
        appendLine("  };")
        appendLine("}")
    }
}
