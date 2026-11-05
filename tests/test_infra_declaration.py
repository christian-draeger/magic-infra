import unittest

import yaml
from jsonschema import ValidationError

from test_contracts import ROOT, validate_schema


EXAMPLES = ROOT / "examples/infra"


class InfraDeclarationSchemaTest(unittest.TestCase):
    def test_managed_and_external_declarations(self):
        for name in ("managed", "external"):
            with self.subTest(name=name):
                document = yaml.safe_load((EXAMPLES / f"{name}.yaml").read_text())
                validate_schema(document, "infra", "v1alpha2")
                self.assertEqual(document["resources"]["primary-db"]["management"], name)

    def test_management_is_required(self):
        document = yaml.safe_load((EXAMPLES / "invalid-missing-management.yaml").read_text())
        with self.assertRaises(ValidationError) as failure:
            validate_schema(document, "infra", "v1alpha2")
        self.assertEqual(failure.exception.validator, "required")
        self.assertEqual(list(failure.exception.path), ["resources", "primary-db"])
        self.assertIn("management", failure.exception.message)
