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
        (files / "psql-input").write_text(f"{password}\n{password}\n")
        database.copy_from_host(str(files / "psql-input"), "/tmp/psql-input")
        database.succeed(r"runuser -u postgres -- psql -Xq -v ON_ERROR_STOP=1 -c '\password fixture' < /tmp/psql-input")
        database.succeed("rm /tmp/psql-input")

        (files / "credentials.env").write_text(f"SPRING_DATASOURCE_USERNAME=fixture\nSPRING_DATASOURCE_PASSWORD={password}\n")
        (files / "endpoint.env").write_text("SPRING_DATASOURCE_URL=jdbc:postgresql://${nodes.database.networking.primaryIPAddress}:5432/fixture\n")
        app.succeed("install -d -m 0700 /run/secrets/magic-infra/compose-app_dev/endpoints")
        app.copy_from_host(str(files / "credentials.env"), "${credentials}")
        app.copy_from_host(str(files / "endpoint.env"), "${endpoint}")
        app.succeed("chmod 0600 ${credentials} ${endpoint}")

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
        logs = machine.succeed(f"journalctl --no-pager -u {unit}")
        assert password not in logs, f"Fixture password appeared in {unit} logs"
  '';
}
