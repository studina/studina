#!/usr/bin/env python3
"""Offline checks for credential generation and rejecting unsafe/malformed inputs."""

import base64
import importlib.util
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("aws_config", Path(__file__).with_name("config.py"))
config = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(config)


class ConfigTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.env = self.directory / ".env"
        self.template = self.directory / ".env.example"
        self.template.write_text("\n".join(f"{key}=" for key in config.REQUIRED) + "\n")
        with self.template.open("a") as handle:
            handle.write("AI_CHAT_MODEL=gemini-model\nGEMINI_EMBEDDING_MODEL=embedding-model\n")
        # Replace the empty model entries, rather than duplicating template keys.
        self.template.write_text(self.template.read_text().replace("AI_CHAT_MODEL=\n", "").replace("GEMINI_EMBEDDING_MODEL=\n", ""))
        self.patcher = patch.multiple(config, ENV=self.env, TEMPLATE=self.template)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)
        self.addCleanup(self.temp.cleanup)

    def setup_env(self, domain="studina.ourschool.edu.ar"):
        with patch.dict(os.environ, {"GEMINI_API_KEY": "fake-key-for-offline-tests"}):
            config.setup(domain, "admin@ourschool.edu.ar")

    def test_generated_credentials_are_valid_private_and_independent(self):
        self.setup_env()
        values = config.read_env(self.env)
        config.check(values)
        self.assertEqual(stat.S_IMODE(self.env.stat().st_mode), 0o600)
        self.assertEqual(len(base64.b64decode(values["AI_CREDENTIALS_ENCRYPTION_KEY"])), 32)
        self.assertNotEqual(values["POSTGRES_PASSWORD"], values["RAG_DB_PASSWORD"])
        self.assertNotEqual(values["DIRECTUS_SECRET"], values["DIRECTUS_ADMIN_PASSWORD"])

    def test_existing_credentials_are_never_overwritten(self):
        self.setup_env()
        original = self.env.read_bytes()
        with self.assertRaisesRegex(ValueError, "ya existe"):
            self.setup_env()
        self.assertEqual(original, self.env.read_bytes())

    def test_invalid_or_placeholder_domain_leaves_no_secret_file(self):
        for domain in ("https://studina.edu.ar", "127.0.0.1", "localhost", "demo.example.com"):
            with self.subTest(domain=domain), self.assertRaises(ValueError):
                self.setup_env(domain)
            self.assertFalse(self.env.exists())

    def test_wrong_encryption_key_length_is_rejected(self):
        self.setup_env()
        values = config.read_env(self.env)
        values["AI_CREDENTIALS_ENCRYPTION_KEY"] = base64.b64encode(b"too-short").decode()
        with self.assertRaisesRegex(ValueError, "32 bytes"):
            config.check(values)

    def test_public_ip_is_optional_and_accepts_public_ipv4(self):
        self.setup_env()
        values = config.read_env(self.env)
        config.check(values)
        for value in ("", "77.112.66.37"):
            with self.subTest(public_ip=value):
                config.check({**values, "STUDINA_PUBLIC_IP": value})

    def test_public_ip_rejects_private_or_malformed_addresses(self):
        self.setup_env()
        values = config.read_env(self.env)
        for value in ("172.31.38.149", "127.0.0.1", "0.0.0.0", "169.254.169.254",
                      "100.64.0.1", "224.0.0.1", "255.255.255.255", "2001:4860:4860::8888",
                      "https://77.112.66.37", "77.112.66.37:443", "77.112.66.999"):
            with self.subTest(public_ip=value), self.assertRaisesRegex(ValueError, "STUDINA_PUBLIC_IP"):
                config.check({**values, "STUDINA_PUBLIC_IP": value})

    def test_env_is_data_and_shell_payload_is_never_evaluated(self):
        marker = self.directory / "SHOULD_NOT_EXIST"
        self.env.write_text(f"GEMINI_API_KEY=$(touch {marker})\n")
        with self.assertRaises(ValueError):
            config.read_env(self.env)
        self.assertFalse(marker.exists())

    def test_duplicate_variables_are_rejected(self):
        self.env.write_text("GEMINI_API_KEY=first\nGEMINI_API_KEY=second\n")
        with self.assertRaisesRegex(ValueError, "repetida"):
            config.read_env(self.env)


if __name__ == "__main__":
    unittest.main()
