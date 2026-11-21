"""Opt-in Model Armor must validate ADC privately before touching Docker."""

import unittest

from scripts.tests.test_aws_ollama_scripts import AwsScriptsTestCase


class AwsModelArmorTests(AwsScriptsTestCase):
    def test_disabled_mode_accepts_existing_config_without_adc(self):
        result = self.run_script("compose.sh", "ps", "--all")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.compose_calls())

    def test_enabled_mode_requires_config_before_docker_or_mutation(self):
        result = self.run_script("compose.sh", "--model-armor", "up", "--detach", "dina-api")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MODEL_ARMOR_PROJECT_ID", result.stderr)
        self.assertEqual(self.calls(), [])

    def test_armor_and_ollama_flags_work_in_either_order_without_host_overrides(self):
        self.configure_model_armor()
        for flags in (("--model-armor",), ("--model-armor", "--ollama"),
                      ("--ollama", "--model-armor")):
            with self.subTest(flags=flags):
                self.log.unlink(missing_ok=True)
                result = self.run_script("compose.sh", *flags, "config", "--services")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(len(self.compose_calls()), 2)
                expected = [str(self.root / "compose.aws.yaml")]
                if "--ollama" in flags:
                    expected.append(str(self.root / "compose.aws.ollama.yaml"))
                expected.append(str(self.root / "compose.aws.model-armor.yaml"))
                for call in self.compose_calls():
                    self.assertEqual(call["inherited"], {})
                    files = [call["args"][index + 1] for index, arg in enumerate(call["args"]) if arg == "--file"]
                    self.assertEqual(files, expected)
                self.assertNotIn("offline-private-key-do-not-print", result.stdout + result.stderr + self.log.read_text())

    def test_missing_credentials_does_not_create_a_host_directory(self):
        missing = self.root / "missing-adc.json"
        self.configure_model_armor(MODEL_ARMOR_CREDENTIALS_FILE=str(missing))
        result = self.run_script("compose.sh", "--model-armor", "up", "--detach")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("archivo ADC existente", result.stderr)
        self.assertFalse(missing.exists())
        self.assertEqual(self.calls(), [])

    def test_world_readable_credentials_are_rejected_without_printing_content(self):
        credentials = self.configure_model_armor()
        credentials.chmod(0o644)
        result = self.run_script("compose.sh", "--model-armor", "ps")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("chmod 600", result.stderr)
        self.assertNotIn("offline-private-key-do-not-print", result.stdout + result.stderr)
        self.assertEqual(self.calls(), [])

    def test_invalid_adc_is_rejected_privately(self):
        credentials = self.configure_model_armor()
        for invalid in ('not-json-offline-secret', '[]', '{"type":[]}', '{"type":"service_account"}',
                        '{"type":"unknown","private_key":"offline-secret"}'):
            with self.subTest(invalid=invalid):
                credentials.write_text(invalid)
                result = self.run_script("compose.sh", "--model-armor", "ps")
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("offline-secret", result.stdout + result.stderr)
                self.assertEqual(self.calls(), [])

    def test_aws_workload_federation_is_supported(self):
        self.configure_model_armor({
            "type": "external_account", "audience": "//iam.googleapis.com/projects/123/locations/global/workloadIdentityPools/test/providers/aws",
            "subject_token_type": "urn:ietf:params:aws:token-type:aws4_request",
            "token_url": "https://sts.googleapis.com/v1/token", "credential_source": {
                "environment_id": "aws1", "region_url": "http://169.254.169.254/latest/meta-data/placement/availability-zone",
                "url": "http://169.254.169.254/latest/meta-data/iam/security-credentials",
                "regional_cred_verification_url": "https://sts.{region}.amazonaws.com?Action=GetCallerIdentity&Version=2011-06-15",
            },
        })
        result = self.run_script("compose.sh", "--model-armor", "config", "--quiet")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_file_sourced_federation_requires_additional_mounts(self):
        self.configure_model_armor({
            "type": "external_account", "audience": "test-audience", "subject_token_type": "test-token-type",
            "token_url": "https://sts.googleapis.com/v1/token", "credential_source": {"file": "/missing/token"},
        })
        result = self.run_script("compose.sh", "--model-armor", "up", "--detach")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("override adicional", result.stderr)
        self.assertEqual(self.calls(), [])

    def test_global_endpoint_is_rejected_before_deploy(self):
        self.configure_model_armor(MODEL_ARMOR_LOCATION="global")
        result = self.run_script("compose.sh", "--model-armor", "up", "--detach")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("distinta de global", result.stderr)
        self.assertEqual(self.calls(), [])


if __name__ == "__main__":
    unittest.main()
