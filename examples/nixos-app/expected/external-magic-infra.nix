{ config, lib, pkgs, ... }:
let
  checkRuntimeInput = ''
    check_runtime_input() {
      local file="$1"
      shift
      if [ ! -f "$file" ] || [ ! -r "$file" ]; then
        echo "magic-infra: provide a readable runtime env file at $file." >&2
        return 1
      fi
      local grep="${pkgs.gnugrep}/bin/grep"
      local allowed="$(IFS='|'; echo "$*")"
      if "$grep" -Eqv "^($allowed)=.+" "$file" || "$grep" -q $'\r' "$file"; then
        echo "magic-infra: use only LF-terminated, non-empty $* assignments in $file." >&2
        return 1
      fi
      local key
      for key in "$@"; do
        if [ "$("$grep" -c "^$key=" "$file")" != 1 ]; then
          echo "magic-infra: provide exactly one $key assignment in $file." >&2
          return 1
        fi
      done
    }
  '';
in
{
  assertions = [
    {
      assertion = config.virtualisation.oci-containers.backend == "podman";
      message = "magic-infra requires Podman as the host OCI backend.";
    }
  ];
  virtualisation.oci-containers.containers = {
    "magic-infra-shop_dev_api" = {
      image = "example.invalid/shop/api:fixture";
      ports = [ "127.0.0.1:8080:8080" ];
      environmentFiles = [ "/run/secrets/magic-infra/shop_dev/shop-db-credentials.env" "/run/secrets/magic-infra/shop_dev/endpoints/SHOP_DB_URL.env" ];
    };
  };
  systemd.services."podman-magic-infra-shop_dev_api".preStart = lib.mkBefore ''
    ${checkRuntimeInput}
    check_runtime_input "/run/secrets/magic-infra/shop_dev/shop-db-credentials.env" SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
    check_runtime_input "/run/secrets/magic-infra/shop_dev/endpoints/SHOP_DB_URL.env" SPRING_DATASOURCE_URL
  '';
}
