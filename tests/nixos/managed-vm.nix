{ nixpkgs, modulePath, imagePath }:
let
  pkgs = import nixpkgs { };
  containerName = "magic-infra-compose-app_dev_compose-app";
  credentials = "/run/secrets/magic-infra/compose-app_dev/fixture-db.env";
  identity = builtins.substring 0 32 (builtins.hashString "sha256" "compose-app_dev_primary-db");
  role = "mi_${identity}";
  provision = "magic-infra-${identity}-postgresql.service";
  proxy = "magic-infra-${identity}-proxy";
in
pkgs.testers.runNixOSTest {
  name = "magic-infra-managed-postgresql";
  requiredFeatures.kvm = false;

  nodes.app = { lib, ... }: {
    imports = [ (/. + modulePath) ];
    system.stateVersion = "26.05";
    system.switch.enable = true;
    virtualisation.memorySize = 1280;
    environment.systemPackages = [ pkgs.curl ];
    environment.etc."magic-infra-generation".text = "initial";
    services.postgresql.settings = {
      port = 5433;
      log_checkpoints = true;
    };
    specialisation.switched.configuration = {
      environment.etc."magic-infra-generation".text = lib.mkForce "switched";
      services.postgresql.settings.log_checkpoints = lib.mkForce false;
    };
    virtualisation.oci-containers = {
      backend = "podman";
      containers.${containerName} = {
        autoStart = false;
        imageFile = builtins.path { path = /. + imagePath; name = "fixture-image.tar"; };
      };
    };
    systemd.services."podman-${containerName}".serviceConfig.Restart = lib.mkForce "no";
  };

  testScript = ''
    from pathlib import Path
    from tempfile import TemporaryDirectory
    from uuid import uuid4

    service = "podman-${containerName}.service"
    address = "http://127.0.0.1:8080"
    note = f"{address}/notes/fixture-note"
    psql = "runuser -u postgres -- psql -p 5433 -XqAt -v ON_ERROR_STOP=1 -d postgres"

    def upload_credentials(path):
        app.succeed("install -d -m 0700 /run/secrets/magic-infra/compose-app_dev")
        app.copy_from_host(str(path), "${credentials}")
        app.succeed("chmod 0600 ${credentials}")

    def start_workload():
        app.succeed(f"systemctl start {service}")
        app.wait_until_succeeds(f"curl -fsS {address}/actuator/health | grep -F '\"status\":\"UP\"'")

    def check_logs(password):
        status, logs = app.execute(
            f"journalctl --no-pager -u {service} -u postgresql.service -u ${provision} -u ${proxy}.service"
        )
        assert status == 0, "Could not read service logs"
        assert password not in logs, "Fixture password appeared in the journal"

    app.start()
    app.wait_for_unit("postgresql.target")
    app.wait_for_unit("multi-user.target")
    app.fail(f"systemctl start {service}")
    app.succeed("journalctl -u ${provision} --no-pager | grep -F '${credentials}'")

    with TemporaryDirectory() as directory:
        path = Path(directory) / "database.env"
        password = f"{uuid4().hex}= $!\"'\\ end"
        path.touch(mode=0o600)
        path.write_text(f"SPRING_DATASOURCE_PASSWORD={password}\nSPRING_DATASOURCE_PASSWORD=duplicate\n")
        upload_credentials(path)
        app.fail(f"systemctl start {service}")
        app.succeed("journalctl -u ${provision} --no-pager | grep -F 'provide one non-empty assignment for each of SPRING_DATASOURCE_PASSWORD'")

        path.write_text(f"SPRING_DATASOURCE_PASSWORD={password}\n")
        upload_credentials(path)
        app.succeed(f'{psql} -c "CREATE DATABASE fixture"')
        app.fail(f"systemctl start {service}")
        app.succeed("journalctl -u ${provision} --no-pager | grep -F 'database fixture already has another owner'")
        assert app.succeed(f"{psql} -c \"SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'fixture'\"").strip() == "postgres"
        app.succeed(f'{psql} -c "DROP DATABASE fixture"')
        app.succeed(f"systemctl reset-failed {service} ${provision}")

        start_workload()
        assert app.succeed(f"{psql} -c \"SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'fixture'\"").strip() == "${role}"
        assert app.succeed(
            f"{psql} -c \"SELECT rolcanlogin AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit) FROM pg_roles WHERE rolname = '${role}'\""
        ).strip() == "t"
        gateway = app.succeed("sed -n 's|.*://\\([^:]*\\):.*|\\1|p' /run/${proxy}/endpoint.env").strip()
        client = "PGPASSWORD=$(cut -d= -f2- ${credentials}) psql -Xqw -U ${role}"
        app.succeed(f'{client} -h {gateway} -p 15432 -d fixture -c "SELECT 1"')
        app.succeed(f'{client} -h {gateway} -p 15432 -d postgres -c "SELECT 1" 2>&1 | grep -F "pg_hba.conf rejects connection"')
        app.succeed(f'{client} -h 127.0.0.1 -p 5433 -d fixture -c "SELECT 1" 2>&1 | grep -F "pg_hba.conf rejects connection"')
        app.succeed("ss -H -ltn 'sport = :5433' | grep -F '127.0.0.1:5433'")

        app.succeed(f"curl -fsS -X POST -H 'Content-Type: text/plain' --data-binary 'first note' {note}")
        assert app.succeed(f"curl -fsS {note}") == "first note"
        app.succeed(f"curl -fsS -X PUT -H 'Content-Type: text/plain' --data-binary 'retained note' {note}")
        boot_id = app.succeed("cat /proc/sys/kernel/random/boot_id")
        assert app.succeed("cat /etc/magic-infra-generation") == "initial"
        assert app.succeed(f"{psql} -c 'SHOW log_checkpoints'").strip() == "on"
        app.succeed("/run/current-system/specialisation/switched/bin/switch-to-configuration test")
        assert app.succeed("cat /etc/magic-infra-generation") == "switched"
        app.wait_until_succeeds(f"{psql} -c 'SHOW log_checkpoints' | grep -Fx off")
        start_workload()
        assert app.succeed(f"curl -fsS {note}") == "retained note"
        check_logs(password)

        app.shutdown()
        app.start()
        app.wait_for_unit("postgresql.target")
        app.wait_for_unit("multi-user.target")
        assert app.succeed("cat /proc/sys/kernel/random/boot_id") != boot_id
        app.fail("test -e ${credentials}")
        upload_credentials(path)
        start_workload()
        assert app.succeed(f"curl -fsS {note}") == "retained note"
        app.succeed(f"curl -fsS -X DELETE {note}")
        assert app.succeed(f"curl -sS -o /dev/null -w '%{{http_code}}' {note}") == "404"
        check_logs(password)
  '';
}
