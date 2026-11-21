"""Check operational commands with a fake Docker CLI; never start containers."""

import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class AwsScriptsTestCase(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        scripts = self.root / "scripts/aws"
        scripts.mkdir(parents=True)
        for name in ("common.sh", "compose.sh", "config.py", "ollama.sh"):
            shutil.copyfile(ROOT / "scripts/aws" / name, scripts / name)
        for name in ("compose.aws.yaml", "compose.aws.ollama.yaml", "compose.aws.model-armor.yaml"):
            shutil.copyfile(ROOT / name, self.root / name)
        deploy = self.root / "deploy/aws"
        deploy.mkdir(parents=True)
        values = {
            "STUDINA_DOMAIN": "studina.school.edu.ar", "ACME_EMAIL": "test@school.edu.ar",
            "GEMINI_API_KEY": "offline-key", "AI_CHAT_MODEL": "gemini-3.5-flash-lite",
            "GEMINI_EMBEDDING_MODEL": "gemini-embedding-2",
            "AI_CREDENTIALS_ENCRYPTION_KEY": base64.b64encode(bytes(32)).decode(),
            "DIRECTUS_SECRET": "s" * 32, "DIRECTUS_ADMIN_EMAIL": "test@school.edu.ar",
            "DIRECTUS_ADMIN_PASSWORD": "a" * 32, "POSTGRES_PASSWORD": "b" * 32,
            "RAG_DB_PASSWORD": "c" * 32,
        }
        env_file = deploy / ".env"
        env_file.write_text("".join(f"{key}={value}\n" for key, value in values.items()))
        env_file.chmod(0o600)
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        self.log = self.root / "docker-calls.jsonl"
        docker = bin_dir / "docker"
        docker.write_text("""#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
observed = {key: os.environ[key] for key in (
    'COMPOSE_FILE', 'COMPOSE_PROFILES', 'COMPOSE_PROJECT_NAME', 'AI_GENERATION_TIMEOUT_MS',
    'OLLAMA_BASE_URL', 'OLLAMA_READ_TIMEOUT_SECONDS', 'OLLAMA_QUEUE_TIMEOUT_SECONDS',
    'OLLAMA_MAX_QUEUED_REQUESTS', 'OLLAMA_NUM_THREADS', 'STUDINA_API_TIMEOUT',
    'MODEL_ARMOR_PROJECT_ID', 'MODEL_ARMOR_LOCATION', 'MODEL_ARMOR_TEMPLATE_ID',
    'MODEL_ARMOR_CREDENTIALS_FILE', 'MODEL_ARMOR_ENABLED', 'GOOGLE_APPLICATION_CREDENTIALS') if key in os.environ}
with open(os.environ['AWS_TEST_DOCKER_LOG'], 'a') as handle:
    handle.write(json.dumps({'args': args, 'inherited': observed}) + '\\n')
if args[-3:] == ['ollama', 'pull', os.environ.get('AWS_TEST_FAIL_MODEL')]:
    sys.exit(17)
if args == ['info', '--format', '{{.DockerRootDir}}']:
    print('/offline-docker-data')
elif '--quiet' in args and 'ps' in args:
    print(os.environ.get('AWS_TEST_CONTAINER_ID', ''))
elif args and args[0] == 'inspect':
    print('Ollama: status=exited OOMKilled=true exitCode=137 health=unhealthy')
""")
        docker.chmod(0o755)
        df = bin_dir / "df"
        df.write_text("""#!/usr/bin/env python3
import os
print('Filesystem 1024-blocks Used Available Capacity Mounted on')
print('/offline 30000000 10000000 ' + os.environ.get('AWS_TEST_FREE_KIB', '15000000') + ' 33% /offline-docker-data')
""")
        df.chmod(0o755)
        self.env = {**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}",
                    "AWS_TEST_DOCKER_LOG": str(self.log),
                    "COMPOSE_FILE": "compose.yaml", "COMPOSE_PROFILES": "ollama",
                    "COMPOSE_PROJECT_NAME": "development",
                    "AI_GENERATION_TIMEOUT_MS": "5", "OLLAMA_BASE_URL": "http://host.invalid",
                    "OLLAMA_READ_TIMEOUT_SECONDS": "2", "OLLAMA_QUEUE_TIMEOUT_SECONDS": "3",
                    "OLLAMA_MAX_QUEUED_REQUESTS": "999", "OLLAMA_NUM_THREADS": "4",
                    "STUDINA_API_TIMEOUT": "1s", "MODEL_ARMOR_PROJECT_ID": "untrusted-project",
                    "MODEL_ARMOR_LOCATION": "untrusted-region", "MODEL_ARMOR_TEMPLATE_ID": "untrusted-template",
                    "MODEL_ARMOR_CREDENTIALS_FILE": "/untrusted/adc.json", "MODEL_ARMOR_ENABLED": "true",
                    "GOOGLE_APPLICATION_CREDENTIALS": "/untrusted/adc.json"}

    def configure_model_armor(self, credential_data=None, **overrides):
        credentials = self.root / "private-model-armor-adc.json"
        credentials.write_text(json.dumps(credential_data if credential_data is not None else {
            "type": "service_account", "client_email": "model-armor@studina-test.iam.gserviceaccount.com",
            "private_key": "offline-private-key-do-not-print", "token_uri": "https://oauth2.googleapis.com/token",
        }))
        credentials.chmod(0o600)
        values = {"MODEL_ARMOR_PROJECT_ID": "studina-test", "MODEL_ARMOR_LOCATION": "us-central1",
                  "MODEL_ARMOR_TEMPLATE_ID": "studina-template", "MODEL_ARMOR_CREDENTIALS_FILE": str(credentials),
                  **overrides}
        with (self.root / "deploy/aws/.env").open("a") as handle:
            handle.write("".join(f"{key}={value}\n" for key, value in values.items()))
        return credentials

    def run_script(self, script, *arguments, **env):
        return subprocess.run(["bash", str(self.root / "scripts/aws" / script), *arguments],
                              env={**self.env, **env}, text=True, capture_output=True)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def compose_calls(self):
        return [call for call in self.calls() if call["args"][0] == "compose"
                and call["args"] != ["compose", "version"]]


class AwsOllamaScriptsTests(AwsScriptsTestCase):
    def test_ordinary_wrapper_ignores_dev_profiles_and_optional_settings(self):
        result = self.run_script("compose.sh", "config", "--services")
        self.assertEqual(result.returncode, 0, result.stderr)
        for call in self.compose_calls():
            self.assertEqual(call["inherited"], {})
            files = [call["args"][i + 1] for i, arg in enumerate(call["args"]) if arg == "--file"]
            self.assertEqual(files, [str(self.root / "compose.aws.yaml")])

    def test_explicit_override_is_used_for_validation_and_actual_command(self):
        result = self.run_script("compose.sh", "--ollama", "config", "--services")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.compose_calls()), 2)
        for call in self.compose_calls():
            self.assertEqual(call["inherited"], {})
            files = [call["args"][i + 1] for i, arg in enumerate(call["args"]) if arg == "--file"]
            self.assertEqual(files, [str(self.root / "compose.aws.yaml"),
                                     str(self.root / "compose.aws.ollama.yaml")])
        self.assertEqual(self.compose_calls()[-1]["args"][-2:], ["config", "--services"])

    def test_prepare_only_starts_ollama_and_installs_llama_and_gemma(self):
        result = self.run_script("ollama.sh", "prepare")
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = [call["args"] for call in self.compose_calls()]
        start = next(command for command in commands if "up" in command)
        self.assertEqual(start[-1], "ollama")
        self.assertIn("--no-deps", start)
        downloads = [command[-1] for command in commands if command[-3:-1] == ["ollama", "pull"]]
        self.assertEqual(downloads, ["llama3.2:3b", "gemma3:1b"])
        verified = [command[-1] for command in commands if command[-3:-1] == ["ollama", "show"]]
        self.assertEqual(verified, downloads)
        self.assertIn("Modelos llama3.2:3b y gemma3:1b preparados", result.stdout)
        self.assertFalse(any("dina-api" in command or "frontend" in command for command in commands))
        self.assertFalse(any("down" in command or "--volumes" in command for command in commands))

    def test_insufficient_docker_disk_space_prevents_download_or_start(self):
        result = self.run_script("ollama.sh", "prepare", AWS_TEST_FREE_KIB="14680063")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("14 GiB", result.stderr)
        self.assertFalse(any("up" in call["args"] or "pull" in call["args"] for call in self.calls()))

    def test_failed_model_download_does_not_report_success_or_activate_the_api(self):
        result = self.run_script("ollama.sh", "prepare", AWS_TEST_FAIL_MODEL="gemma3:1b")
        self.assertNotEqual(result.returncode, 0)
        commands = [call["args"] for call in self.compose_calls()]
        self.assertTrue(any(command[-3:] == ["ollama", "pull", "gemma3:1b"] for command in commands))
        self.assertFalse(any(command[-3:] == ["ollama", "show", "gemma3:1b"] for command in commands))
        self.assertFalse(any("dina-api" in command or "frontend" in command for command in commands))
        self.assertNotIn("preparados en el volumen persistente", result.stdout)

    def test_status_is_read_only_and_inspect_selects_only_safe_state(self):
        result = self.run_script("ollama.sh", "status", AWS_TEST_CONTAINER_ID="offline-container")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("OOMKilled=true", result.stdout)
        inspect = next(call["args"] for call in self.calls() if call["args"][0] == "inspect")
        self.assertEqual(inspect[1], "--format")
        self.assertNotIn("Env", inspect[2])
        self.assertNotIn("Config", inspect[2])
        self.assertFalse(any("up" in call["args"] or "pull" in call["args"] for call in self.calls()))

    def test_unknown_command_cannot_trigger_preparation(self):
        result = self.run_script("ollama.sh", "enable")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.calls(), [])


if __name__ == "__main__":
    unittest.main()
