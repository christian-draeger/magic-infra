package dev.magicinfra.compiler.evidence

import com.fasterxml.jackson.databind.JsonNode
import java.security.MessageDigest
import java.util.HexFormat

internal class NixosManagedPostgresql(app: String, environment: String, id: String, resource: JsonNode) {
    private val database = resource.path("settings").path("database").asText()
    private val source = nixosCredentialsPath(app, environment, resource.path("connection").path("secretRef").asText())
    private val identity = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest("${app}_${environment}_$id".toByteArray()),
    ).take(32)
    private val marker = "magic-infra:$identity:$database"
    val role = "mi_$identity"
    val network = "magic-infra-$identity"
    private val bridge = "mi${identity.take(13)}"
    val provision = "$network-postgresql"
    val proxy = "$network-proxy"
    val credentials = "/run/$provision/database.env"
    val endpoint = "/run/$proxy/endpoint.env"

    private val allow = "host \"$database\" $role $proxySource/32 scram-sha-256"
    private val denyTcp = "host all $role all reject"
    private val denySocket = "local all $role reject"
    val assertion = """
        {
          assertion = authenticationFileMatches && proxyDenials != [ ]
            && lib.elem ''$allow'' managedAllows
            && lib.all (line: !lib.hasInfix " $role " line || line == ''$allow'') managedAllows
            && lib.elem "$denyTcp" managedDenials && lib.elem "$denySocket" managedDenials;
          message = "magic-infra requires its scoped PostgreSQL rules for $database before other active authentication rules.";
        }
    """.trimIndent().prependIndent("    ")

    private fun units(): String = """
        systemd.services."$provision" = {
          description = "Provision magic-infra database $database from runtime password file $source";
          requires = [ "postgresql.target" ];
          after = [ "postgresql.target" ];
          environment = {
            PGPORT = toString config.services.postgresql.settings.port;
            PGUSER = config.services.postgresql.superUser;
          };
          path = [ config.services.postgresql.package pkgs.gnused pkgs.coreutils ];
          serviceConfig = {
            Type = "oneshot";
            RemainAfterExit = true;
            User = "postgres";
            TimeoutStartSec = 60;
            RuntimeDirectory = "$provision";
            RuntimeDirectoryMode = "0700";
            LoadCredential = [ "database.env:$source" ];
          };
          script = ''
            set -euo pipefail
            ${'$'}{checkRuntimeInput}
            check_runtime_input "${'$'}CREDENTIALS_DIRECTORY/database.env" SPRING_DATASOURCE_PASSWORD
            password=$(sed -n 's/^SPRING_DATASOURCE_PASSWORD=//p' "${'$'}CREDENTIALS_DIRECTORY/database.env")
            printf '%s\n%s\n' "${'$'}password" "${'$'}password" | psql -Xq -w -v ON_ERROR_STOP=1 -d postgres -f "${'$'}{pkgs.writeText "$provision.sql" ''
${sql.prependIndent("              ")}
            ''}"
            install -m 0600 "${'$'}CREDENTIALS_DIRECTORY/database.env" "${'$'}RUNTIME_DIRECTORY/database.env"
          '';
        };

        systemd.services."$proxy" = {
          path = [ config.virtualisation.podman.package config.services.postgresql.package
                   pkgs.jq pkgs.socat pkgs.iproute2 pkgs.gnugrep pkgs.systemd ];
          environment.PGPORT = toString config.services.postgresql.settings.port;
          requires = [ "$provision.service" ];
          after = [ "$provision.service" ];
          serviceConfig = {
            Type = "notify";
            NotifyAccess = "all";
            TimeoutStartSec = 60;
            Restart = "on-failure";
            RuntimeDirectory = "$proxy";
            RuntimeDirectoryMode = "0700";
          };
          script = ''
            set -euo pipefail
            if ! pg_isready -q -t 3 -h 127.0.0.1 -d postgres; then
              echo "magic-infra: PostgreSQL must listen on 127.0.0.1:${'$'}PGPORT for $database." >&2
              exit 1
            fi
            podman network create --ignore --interface-name "$bridge" --label io.magic-infra.owner=$role "$network"
            if ! addresses=$(podman network inspect "$network" | jq -er '
              .[0] | select(.driver == "bridge" and .network_interface == "$bridge" and .labels["io.magic-infra.owner"] == "$role")
              | select((.subnets | length) == 1)
              | .subnets[0] | select((.gateway | contains(":") | not) and (.subnet | contains(":") | not))
              | [.gateway, .subnet] | @tsv'); then
              echo "magic-infra: network $network must be a magic-infra-owned Podman bridge with one IPv4 subnet." >&2
              exit 1
            fi
            read -r gateway subnet <<< "${'$'}addresses"
            socat "TCP4-LISTEN:$proxyPort,bind=${'$'}gateway,ip-freebind,reuseaddr,range=${'$'}subnet,fork" \
              "TCP4:127.0.0.1:${'$'}PGPORT,bind=$proxySource" &
            listener=$!
            until ss -H -ltnp "src ${'$'}gateway:$proxyPort" | grep -F "pid=${'$'}listener," >/dev/null; do
              kill -0 "${'$'}listener" || exit 1
              sleep 0.1
            done
            printf 'SPRING_DATASOURCE_URL=jdbc:postgresql://%s:$proxyPort/$database\n' "${'$'}gateway" > "${'$'}RUNTIME_DIRECTORY/endpoint.env"
            systemd-notify --ready
            wait "${'$'}listener"
          '';
        };
    """.trimIndent()

    private val sql: String
        get() = """
            SELECT pg_advisory_lock(hashtextextended('magic-infra:$database', 0));
            DO ${'$'}ownership${'$'}
            BEGIN
              IF EXISTS (
                SELECT FROM pg_database d JOIN pg_roles r ON r.oid = d.datdba
                WHERE d.datname = '$database' AND r.rolname <> '$role'
              ) THEN
                RAISE EXCEPTION 'magic-infra: database $database already has another owner. Choose a different name or use external mode.';
              END IF;

              IF EXISTS (SELECT FROM pg_roles WHERE rolname = '$role') THEN
                IF NOT EXISTS (
                  SELECT FROM pg_roles r
                  WHERE r.rolname = '$role'
                    AND shobj_description(r.oid, 'pg_authid') = '$marker'
                    AND r.rolcanlogin
                    AND NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls OR r.rolinherit)
                    AND NOT EXISTS (SELECT FROM pg_auth_members WHERE member = r.oid)
                ) THEN
                  RAISE EXCEPTION 'magic-infra: role $role already exists with unexpected ownership or privileges. Inspect the existing role.';
                END IF;
              ELSE
                CREATE ROLE "$role" LOGIN NOINHERIT;
                COMMENT ON ROLE "$role" IS '$marker';
              END IF;
            END;
            ${'$'}ownership${'$'};

            SELECT 'CREATE DATABASE "$database" OWNER "$role"'
            WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$database')
            \gexec

            SET ROLE "$role";
            DO ${'$'}ownership${'$'}
            BEGIN
              IF NOT EXISTS (
                SELECT FROM pg_database WHERE datname = '$database' AND datdba = current_user::regrole
              ) THEN
                RAISE EXCEPTION 'magic-infra: database $database already has another owner. Choose a different name or use external mode.';
              END IF;
              REVOKE CONNECT ON DATABASE "$database" FROM PUBLIC;
            END;
            ${'$'}ownership${'$'};
            RESET ROLE;
            SET password_encryption = 'scram-sha-256';
            \password $role
        """.trimIndent()

    companion object {
        private const val proxySource = "127.77.0.1"
        private const val proxyPort = 15432
        private const val denyProxy = "host all all $proxySource/32 reject"

        // PostgreSQL uses the first matching rule. Allow different generated modules to share
        // the leading rule groups while rejecting arbitrary rules before those groups.
        val authenticationCheck = """
            authentication = config.services.postgresql.authentication;
            authenticationFileMatches = (config.services.postgresql.settings.hba_file or "") ==
              toString (pkgs.writeText "pg_hba.conf" authentication);
            activeRules = lib.filter (line: line != "" && !lib.hasPrefix "#" line)
              (map lib.trim (lib.splitString "\n" authentication));
            takeWhile = predicate: lines:
              lib.take (lib.lists.findFirstIndex (line: !predicate line) (lib.length lines) lines) lines;
            managedAllows = takeWhile (line:
              builtins.match ''host "[a-z][a-z0-9_]{0,62}" mi_[0-9a-f]{32} 127[.]77[.]0[.]1/32 scram-sha-256'' line != null
            ) activeRules;
            afterAllows = lib.drop (lib.length managedAllows) activeRules;
            proxyDenials = takeWhile (line: line == "$denyProxy") afterAllows;
            managedDenials = takeWhile (line:
              builtins.match "(host all mi_[0-9a-f]{32} all|local all mi_[0-9a-f]{32}) reject" line != null
            ) (lib.drop (lib.length proxyDenials) afterAllows);
        """.trimIndent().prependIndent("  ")

        fun hostSettings(resources: Collection<NixosManagedPostgresql>): String = buildString {
            appendLine("  services.postgresql.enable = true;")
            appendLine("  services.postgresql.authentication = lib.mkMerge [")
            appendLine("    (lib.mkOrder 400 ''")
            resources.forEach { appendLine("      ${it.allow}") }
            appendLine("    '')")
            appendLine("    (lib.mkOrder 450 \"$denyProxy\")")
            appendLine("    (lib.mkOrder 460 ''")
            resources.forEach {
                appendLine("      ${it.denyTcp}")
                appendLine("      ${it.denySocket}")
            }
            appendLine("    '')")
            appendLine("  ];")
            resources.forEach {
                appendLine()
                appendLine("  networking.firewall.interfaces.\"${it.bridge}\".allowedTCPPorts = [ $proxyPort ];")
                appendLine(it.units().prependIndent("  "))
            }
        }.trimEnd()
    }
}
