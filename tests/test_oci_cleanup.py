import subprocess
import unittest
from unittest.mock import patch

from check_oci_image import local_docker, remove_fixture


class OciSmokeSafetyTest(unittest.TestCase):
    def test_rejects_remote_docker_context(self):
        with patch("check_oci_image.command", return_value="ssh://example.invalid") as command:
            with self.assertRaisesRegex(SystemExit, "local Docker context"):
                local_docker()
        command.assert_called_once_with("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}")

    def test_reports_cleanup_timeout_and_attempts_both_removals(self):
        with patch("check_oci_image.command", side_effect=[subprocess.TimeoutExpired("docker rm", 30), ""]) as command:
            with self.assertRaisesRegex(RuntimeError, "smoke test container"):
                remove_fixture(("docker",), "container-id", "test:image")
        self.assertEqual(command.call_count, 2)

    def test_preserves_original_failure_when_cleanup_also_fails(self):
        with patch("check_oci_image.command", side_effect=subprocess.CalledProcessError(1, "docker rm")):
            with self.assertRaisesRegex(ValueError, "health failed") as failure:
                try:
                    raise ValueError("health failed")
                finally:
                    remove_fixture(("docker",), "container-id", None)
        self.assertEqual(failure.exception.__notes__, ["Could not remove smoke test container."])
