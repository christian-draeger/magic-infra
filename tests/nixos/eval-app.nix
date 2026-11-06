{ nixpkgs, modulePath }:
let
  evaluate = backend:
    (import (nixpkgs + "/nixos/lib/eval-config.nix") {
      system = "x86_64-linux";
      modules = [
        (/. + modulePath)
        {
          system.stateVersion = "26.05";
          networking.hostName = "existing-host";
          virtualisation.oci-containers = {
            inherit backend;
            containers.existing.image = "docker.io/library/nginx:alpine";
          };
        }
      ];
    }).config;
  podman = evaluate "podman";
  docker = evaluate "docker";
  name = "magic-infra-static-web_dev_web";
  container = podman.virtualisation.oci-containers.containers.${name};
  rejectsBackend = config:
    builtins.any (entry:
      !entry.assertion && entry.message == "magic-infra requires Podman as the host OCI backend."
    ) config.assertions;
in
assert podman.networking.hostName == "existing-host";
assert builtins.attrNames podman.virtualisation.oci-containers.containers == [ "existing" name ];
assert container.ports == [ "127.0.0.1:80:80" ];
assert builtins.hasAttr "podman-${name}" podman.systemd.services;
assert !podman.services.postgresql.enable;
assert !rejectsBackend podman;
assert docker.virtualisation.oci-containers.backend == "docker";
assert rejectsBackend docker;
{
  inherit (container) image ports;
  service = container.serviceName;
}
