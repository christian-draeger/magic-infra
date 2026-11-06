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
      local size
      size=$("${pkgs.coreutils}/bin/stat" -Lc %s -- "$file") || return 1
      if [ "$size" -gt 16386 ]; then
        echo "magic-infra: runtime env file $file exceeds 16386 bytes." >&2
        return 1
      fi
      if ! LC_ALL=C "${pkgs.gawk}/bin/awk" -v keys="$*" '
        BEGIN {
          split(keys, fields, " ")
          for (i in fields) expected[fields[i]] = 1
        }
        {
          splitAt = index($0, "=")
          if (splitAt == 0) exit 1
          key = substr($0, 1, splitAt - 1)
          if (!(key in expected) || seen[key]++ || splitAt == length($0) ||
              length($0) > 8192 || index($0, "\r") || index($0, sprintf("%c", 0))) exit 1
          if (key == "SPRING_DATASOURCE_URL" && substr($0, splitAt + 1) !~ /^jdbc:postgresql:.+/) exit 1
        }
        END { for (key in expected) if (seen[key] != 1) exit 1 }
      ' "$file"; then
        echo "magic-infra: provide one non-empty assignment for each of $* in $file, with LF line endings and at most 8192 bytes per line. Use a PostgreSQL JDBC URL for SPRING_DATASOURCE_URL." >&2
        return 1
      fi
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
  systemd.services.${config.virtualisation.oci-containers.containers."magic-infra-shop_dev_api".serviceName}.preStart = lib.mkBefore ''
    ${checkRuntimeInput}
    check_runtime_input "/run/secrets/magic-infra/shop_dev/shop-db-credentials.env" SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
    check_runtime_input "/run/secrets/magic-infra/shop_dev/endpoints/SHOP_DB_URL.env" SPRING_DATASOURCE_URL
  '';
}
