{ config, ... }:
{
  assertions = [
    {
      assertion = config.virtualisation.oci-containers.backend == "podman";
      message = "magic-infra requires Podman as the host OCI backend.";
    }
  ];
  virtualisation.oci-containers.containers = {
    "magic-infra-static-web_dev_web" = {
      image = "docker.io/library/nginx@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236";
      ports = [ "127.0.0.1:80:80" ];
    };
  };
}
