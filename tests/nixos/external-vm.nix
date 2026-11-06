{ nixpkgs, modulePath, imagePath }:
let
  pkgs = import nixpkgs { };
  containerName = "magic-infra-compose-app_dev_compose-app";
  credentials = "/run/secrets/magic-infra/compose-app_dev/fixture-db.env";
  endpoint = "/run/secrets/magic-infra/compose-app_dev/endpoints/FIXTURE_DB_URL.env";
in
pkgs.testers.runNixOSTest {
  name = "magic-infra-external-postgresql";
  requiredFeatures.kvm = false;

  nodes.app = { lib, ... }: {
    imports = [ (/. + modulePath) ];
    system.stateVersion = "26.05";
    virtualisation.memorySize = 1280;
    environment.systemPackages = [ pkgs.curl ];
    virtualisation.oci-containers = {
      backend = "podman";
      containers.${containerName} = {
        autoStart = false;
        imageFile = builtins.path { path = /. + imagePath; name = "fixture-image.tar"; };
      };
    };
    systemd.services."podman-${containerName}".serviceConfig.Restart = lib.mkForce "no";
  };

  nodes.database = { lib, nodes, ... }: {
    system.stateVersion = "26.05";
    services.postgresql = {
      enable = true;
      enableTCPIP = true;
      ensureDatabases = [ "fixture" ];
      ensureUsers = [{ name = "fixture"; ensureDBOwnership = true; }];
      authentication = lib.mkBefore ''
        host fixture fixture ${nodes.app.networking.primaryIPAddress}/32 scram-sha-256
      '';
    };
    networking.firewall.allowedTCPPorts = [ 5432 ];
  };

  testScript = { nodes, ... }: ''
    from pathlib import Path
    from tempfile import TemporaryDirectory
    from uuid import uuid4

    def write_private(path, contents):
        path.write_text(contents)
        path.chmod(0o600)

    service = "podman-${containerName}.service"
    address = "http://127.0.0.1:8080"
    start_all()
    database.wait_for_unit("postgresql.target")
    app.wait_for_unit("multi-user.target")
    app.fail("systemctl cat postgresql.service")
    app.fail(f"systemctl start {service}")
    app.succeed(f"journalctl -u {service} --no-pager | grep -F 'provide a readable runtime env file at ${credentials}'")

    with TemporaryDirectory() as directory:
        files = Path(directory)
        password = uuid4().hex
        write_private(files / "psql-input", f"{password}\n{password}\n")
        database.copy_from_host(str(files / "psql-input"), "/tmp/psql-input")
        database.succeed(r"setsid --wait timeout 30s runuser -u postgres -- psql -Xq -v ON_ERROR_STOP=1 -c '\password fixture' < /tmp/psql-input")
        database.succeed("rm /tmp/psql-input")

        credentials_content = f"SPRING_DATASOURCE_USERNAME=fixture\nSPRING_DATASOURCE_PASSWORD={password}\n"
        write_private(files / "credentials.env", credentials_content)
        write_private(files / "duplicate.env", credentials_content + f"SPRING_DATASOURCE_PASSWORD={password}\n")
        write_private(files / "wrong-endpoint.env", "SPRING_DATASOURCE_URL=jdbc:mysql://invalid/fixture\n")
        write_private(files / "endpoint.env", "SPRING_DATASOURCE_URL=jdbc:postgresql://${nodes.database.networking.primaryIPAddress}:5432/fixture\n")
        app.succeed("install -d -m 0700 /run/secrets/magic-infra/compose-app_dev/endpoints")
        app.copy_from_host(str(files / "duplicate.env"), "${credentials}")
        app.fail(f"systemctl start {service}")
        app.succeed(f"journalctl -u {service} --no-pager | grep -F 'provide one non-empty assignment for each of SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD'")
        app.copy_from_host(str(files / "credentials.env"), "${credentials}")
        app.copy_from_host(str(files / "wrong-endpoint.env"), "${endpoint}")
        app.fail(f"systemctl start {service}")
        app.succeed(f"journalctl -u {service} --no-pager | grep -F 'provide one non-empty assignment for each of SPRING_DATASOURCE_URL'")
        app.copy_from_host(str(files / "endpoint.env"), "${endpoint}")
        app.succeed("chmod 0600 ${credentials} ${endpoint}")
        app.succeed(f"systemctl reset-failed {service}")

    app.succeed(f"systemctl start {service}")
    app.wait_until_succeeds(f"curl -fsS {address}/actuator/health | grep -F '\"status\":\"UP\"'")
    note = f"{address}/notes/fixture-note"
    app.succeed(f"curl -fsS -X POST -H 'Content-Type: text/plain' --data-binary 'first note' {note}")
    assert app.succeed(f"curl -fsS {note}") == "first note"
    app.succeed(f"curl -fsS -X PUT -H 'Content-Type: text/plain' --data-binary 'retained note' {note}")
    app.succeed(f"systemctl restart {service}")
    app.wait_until_succeeds(f"curl -fsS {address}/actuator/health | grep -F '\"status\":\"UP\"'")
    assert app.succeed(f"curl -fsS {note}") == "retained note"
    app.succeed(f"curl -fsS -X DELETE {note}")
    assert app.succeed(f"curl -sS -o /dev/null -w '%{{http_code}}' {note}") == "404"
    for machine, unit in [(app, service), (database, "postgresql.service")]:
        result, logs = machine.execute(f"journalctl --no-pager -u {unit}")
        assert result == 0, f"Could not read {unit} logs"
        assert password not in logs, f"Fixture password appeared in {unit} logs"
  '';
}
