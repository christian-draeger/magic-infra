{ nixpkgs, modulePath }:
let
  name = "magic-infra-compose-app_dev_compose-app";
  evaluate = host:
    (import (nixpkgs + "/nixos/lib/eval-config.nix") {
      system = "x86_64-linux";
      modules = [
        (/. + modulePath)
        {
          system.stateVersion = "26.05";
          virtualisation.oci-containers.backend = "podman";
        }
        host
      ];
    }).config;
  rejects = config: message:
    builtins.any (entry: !entry.assertion && entry.message == message) config.assertions;
  authMessage = "magic-infra requires its scoped PostgreSQL rules for fixture before other active authentication rules.";
  compatible = evaluate ({ lib, ... }: {
    services.postgresql.authentication = lib.mkBefore "host all existing 127.0.0.1/32 scram-sha-256";
    services.postgresql.settings.port = 5433;
  });
  earlierRule = evaluate ({ lib, ... }: {
    services.postgresql.authentication = lib.mkOrder 300 "host all all 127.0.0.0/8 trust";
  });
  alternateFile = evaluate ({ lib, ... }: {
    services.postgresql.settings.hba_file = lib.mkForce "/etc/host-pg_hba.conf";
  });
  rootless = evaluate {
    users.users.operator = { isNormalUser = true; linger = false; };
    virtualisation.oci-containers.containers.${name}.podman.user = "operator";
  };
  portConflict = evaluate { services.postgresql.settings.port = 8080; };
in
assert compatible.services.postgresql.enable;
assert !rejects compatible authMessage;
assert rejects earlierRule authMessage;
assert rejects alternateFile authMessage;
assert rejects rootless "magic-infra managed PostgreSQL requires rootful Podman for ${name}.";
assert rejects portConflict "magic-infra app ports must differ from the native PostgreSQL port.";
true
