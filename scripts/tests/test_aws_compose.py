"""Verify that the VM entrypoint cannot accidentally publish development services.

Run with: python3 -m unittest discover -s scripts/tests -p 'test_aws_*.py'
Compose's config renderer and an offline Caddy config adapter are used.
No application services or API calls start.
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
        cls.env = {
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
        # Check file defaults independently of any local development shell settings.
        for key in ("STUDINA_PUBLIC_IP", "AI_CHAT_MODEL", "AI_GENERATION_MODEL",
                    "AI_CLASSIFICATION_MODEL", "AI_SUMMARY_MODEL"):
            cls.env.pop(key, None)
        cls.config = cls.render()
        cls.services = cls.config["services"]

    @classmethod
    def render(cls, **overrides):
        result = subprocess.run(
            ["docker", "compose", "--env-file", "/dev/null", "--project-name",
             "studina-aws-config-check", "-f", str(ROOT / "compose.aws.yaml"),
             "config", "--format", "json"],
            env={**cls.env, **overrides}, check=True, capture_output=True, text=True,
        )
        return json.loads(result.stdout)

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

    def test_public_ip_adds_an_explicit_https_origin_and_sni(self):
        services = self.render(STUDINA_PUBLIC_IP="77.112.66.37")["services"]
        self.assertEqual(services["dina-api"]["environment"]["APP_CORS_ALLOWED_ORIGINS"],
                         "https://studina.test,https://77.112.66.37")
        self.assertEqual(services["frontend"]["environment"]["STUDINA_PUBLIC_IP"], "77.112.66.37")
        self.assertEqual(services["frontend"]["environment"]["STUDINA_DEFAULT_SNI"], "77.112.66.37")
        self.assertEqual(self.services["frontend"]["environment"]["STUDINA_PUBLIC_IP"], "")
        self.assertEqual(self.services["frontend"]["environment"]["STUDINA_DEFAULT_SNI"], "studina.test")

    def test_default_gemini_model_is_consistent_for_all_features(self):
        env = self.services["dina-api"]["environment"]
        for key in ("AI_CHAT_MODEL", "GEMINI_MODEL", "AI_GENERATION_MODEL",
                    "AI_CLASSIFICATION_MODEL", "AI_SUMMARY_MODEL"):
            self.assertEqual(env[key], "gemini-3.5-flash-lite")

    def test_caddy_adapts_both_with_and_without_public_ip(self):
        caddyfile = (ROOT / "deploy/aws/Caddyfile").read_text()
        for public_ip in ("", "77.112.66.37"):
            with self.subTest(public_ip=public_ip):
                env = self.render(STUDINA_PUBLIC_IP=public_ip)["services"]["frontend"]["environment"]
                command = ["docker", "run", "--rm", "--network", "none", "-i"]
                for key, value in env.items():
                    command.extend(["-e", f"{key}={value}"])
                command.extend(["caddy:2.11.7-alpine", "caddy", "adapt", "--config", "/dev/stdin",
                                "--adapter", "caddyfile"])
                result = subprocess.run(command, input=caddyfile, check=True,
                                        capture_output=True, text=True)
                adapted = json.loads(result.stdout)["apps"]
                server = next(server for server in adapted["http"]["servers"].values()
                              if ":443" in server["listen"])
                expected_hosts = {"studina.test", public_ip} if public_ip else {"studina.test"}
                hosts = {host for route in server["routes"] for matcher in route.get("match", [])
                         for host in matcher.get("host", [])}
                self.assertEqual(hosts, expected_hosts)
                self.assertTrue(all(policy["default_sni"] == (public_ip or "studina.test")
                                    for policy in server["tls_connection_policies"]))
                policies = adapted["tls"]["automation"]["policies"]
                self.assertEqual({subject for policy in policies for subject in policy["subjects"]},
                                 expected_hosts)
                for policy in policies:
                    self.assertEqual(len(policy["issuers"]), 1)
                    self.assertEqual(policy["issuers"][0]["ca"],
                                     "https://acme-v02.api.letsencrypt.org/directory")
                    self.assertEqual(policy["issuers"][0]["profile"], "shortlived")


if __name__ == "__main__":
    unittest.main()
