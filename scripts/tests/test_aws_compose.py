"""Verify that the VM entrypoint cannot accidentally publish development services.

Run with: python3 -m unittest discover -s scripts/tests -p 'test_aws_*.py'
Only Docker Compose's config renderer is used; no containers or API calls start.
"""

import base64
import json
import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]


class AwsComposeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        env = {
            **os.environ,
            "COMPOSE_PROFILES": "ollama",  # Even an inherited dev profile adds no services.
            "STUDINA_DOMAIN": "studina.test",
            "ACME_EMAIL": "test@studina.test",
            "GEMINI_API_KEY": "test-not-a-real-api-key",
            "AI_CREDENTIALS_ENCRYPTION_KEY": base64.b64encode(bytes(32)).decode(),
            "DIRECTUS_SECRET": "test-secret-" + "a" * 32,
            "DIRECTUS_ADMIN_EMAIL": "test@studina.test",
            "DIRECTUS_ADMIN_PASSWORD": "test-password-" + "b" * 32,
            "POSTGRES_PASSWORD": "test-password-" + "c" * 32,
            "RAG_DB_PASSWORD": "test-password-" + "d" * 32,
        }
        result = subprocess.run(
            ["docker", "compose", "--env-file", "/dev/null", "--project-name",
             "studina-aws-config-check", "-f", str(ROOT / "compose.aws.yaml"),
             "config", "--format", "json"],
            env=env, check=True, capture_output=True, text=True,
        )
        cls.config = json.loads(result.stdout)
        cls.services = cls.config["services"]

    def test_only_the_web_entrypoint_publishes_ports(self):
        published = {
            name: [str(port["published"]) for port in service.get("ports", [])]
            for name, service in self.services.items() if service.get("ports")
        }
        self.assertEqual(published, {"frontend": ["80", "443"]})

    def test_local_inference_cannot_be_started_by_inherited_profiles(self):
        self.assertEqual(set(self.services), {
            "postgres", "pgvector", "directus", "directus-schema", "dina-api", "frontend",
        })
        self.assertFalse(any("ollama" in name for name in self.config.get("volumes", {})))

    def test_api_waits_for_database_and_schema_with_shared_credentials(self):
        api = self.services["dina-api"]
        self.assertEqual(api["depends_on"]["pgvector"]["condition"], "service_healthy")
        self.assertEqual(api["depends_on"]["directus-schema"]["condition"], "service_completed_successfully")
        self.assertEqual(api["environment"]["RAG_DB_PASSWORD"],
                         self.services["pgvector"]["environment"]["POSTGRES_PASSWORD"])
        self.assertEqual(self.services["directus"]["environment"]["DB_PASSWORD"],
                         self.services["postgres"]["environment"]["POSTGRES_PASSWORD"])

    def test_browser_session_uses_https_and_the_same_origin(self):
        env = self.services["dina-api"]["environment"]
        self.assertEqual(env["AUTH_COOKIE_SECURE"], "true")
        self.assertEqual(env["AUTH_COOKIE_PATH"], "/api/auth")
        self.assertEqual(env["SERVER_FORWARD_HEADERS_STRATEGY"], "framework")
        self.assertEqual(env["AI_CHAT_PROVIDER"], "google-genai")
        self.assertEqual(env["AI_ALLOWED_PROVIDERS"], "google-genai,openai")
        self.assertEqual(env["APP_CORS_ALLOWED_ORIGINS"], "https://studina.test")
        self.assertNotIn("OPENAI_API_KEY", env)


if __name__ == "__main__":
    unittest.main()
