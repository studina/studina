"""Deployment must migrate once and start application layers only after success."""

from pathlib import Path
import shutil
import unittest

from scripts.tests.test_aws_ollama_scripts import AwsScriptsTestCase


ROOT = Path(__file__).resolve().parents[2]


class AwsDeployTests(AwsScriptsTestCase):
    def setUp(self):
        super().setUp()
        shutil.copyfile(ROOT / "scripts/aws/deploy.sh", self.root / "scripts/aws/deploy.sh")
        docker = self.root / "bin/docker"
        docker.write_text(docker.read_text() + """
if os.environ.get('AWS_TEST_MIGRATION_FAIL') == '1' and args[0] == 'compose' \\
        and 'up' in args and args[-1] == 'directus-schema':
    sys.exit(17)
""")

    def commands(self):
        verbs = {"config", "pull", "build", "up", "stop", "ps"}
        return [args[next(index for index, value in enumerate(args) if value in verbs):]
                for call in self.compose_calls() for args in [call["args"]]]

    def test_success_migrates_once_before_starting_api_then_frontend(self):
        result = self.run_script("deploy.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = self.commands()
        builds = [call["args"] for call in self.compose_calls() if "build" in call["args"]]
        self.assertEqual(len(builds), 1)
        self.assertEqual(builds[0][-3:], ["--parallel", "1", "build"])
        migrations = [command for command in commands
                      if command[0] == "up" and "directus-schema" in command]
        self.assertEqual(len(migrations), 1)
        migration = migrations[0]
        self.assertIn("--no-deps", migration)
        self.assertIn("--force-recreate", migration)
        self.assertEqual(migration[-3:],
                         ["--exit-code-from", "directus-schema", "directus-schema"])
        app_starts = [command for command in commands
                      if command[0] == "up" and command[-1] in ("dina-api", "frontend")]
        self.assertEqual([command[-1] for command in app_starts], ["dina-api", "frontend"])
        for command in app_starts:
            self.assertIn("--no-deps", command)
            self.assertIn("--wait", command)
        self.assertLess(commands.index(["build"]), commands.index(["stop", "dina-api"]))
        self.assertLess(commands.index(["stop", "dina-api"]), commands.index(migration))
        self.assertLess(commands.index(migration), commands.index(app_starts[0]))
        self.assertIn("Studina: https://studina.school.edu.ar", result.stdout)

    def test_failed_migration_stops_deployment_before_application_start(self):
        result = self.run_script("deploy.sh", AWS_TEST_MIGRATION_FAIL="1")
        self.assertEqual(result.returncode, 17, result.stderr)
        commands = self.commands()
        self.assertIn(["stop", "dina-api"], commands)
        self.assertEqual(sum(command[0] == "up" and command[-1] == "directus-schema"
                             for command in commands), 1)
        self.assertFalse(any(command[0] == "up" and command[-1] in ("dina-api", "frontend")
                             for command in commands))
        self.assertNotIn("Studina: https://", result.stdout)


if __name__ == "__main__":
    unittest.main()
