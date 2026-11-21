"""Backups must restart the same containers without applying deployment config."""

import json
from pathlib import Path
import shutil
import unittest

from scripts.tests.test_aws_ollama_scripts import AwsScriptsTestCase


ROOT = Path(__file__).resolve().parents[2]


class AwsBackupTests(AwsScriptsTestCase):
    def setUp(self):
        super().setUp()
        shutil.copyfile(ROOT / "scripts/aws/backup.sh", self.root / "scripts/aws/backup.sh")
        shutil.copyfile(ROOT / "deploy/aws/Caddyfile", self.root / "deploy/aws/Caddyfile")
        self.state_file = self.root / "containers.json"
        self.env["AWS_TEST_CONTAINERS"] = str(self.state_file)
        git = self.root / "bin/git"
        git.write_text("#!/usr/bin/env python3\nprint('0000000000000000000000000000000000000001')\n")
        git.chmod(0o755)
        docker = self.root / "bin/docker"
        docker.write_text("""#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
state_file = Path(os.environ['AWS_TEST_CONTAINERS'])
state = json.loads(state_file.read_text())
with open(os.environ['AWS_TEST_DOCKER_LOG'], 'a') as handle:
    handle.write(json.dumps({'args': args, 'mode': state['mode']}) + '\\n')
if args and args[0] == 'inspect':
    label = '{{ index .Config.Labels "com.docker.compose.project.config_files" }}'
    health = '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}'
    if args[1:3] == ['--format', label]:
        root = state_file.parent
        files = [str(root / 'compose.aws.yaml')]
        if 'ollama' in state['mode']:
            files.append(str(root / 'compose.aws.ollama.yaml'))
        if 'model-armor' in state['mode']:
            files.append(str(root / 'compose.aws.model-armor.yaml'))
        print(','.join(files))
    elif args[1:3] == ['--format', health]:
        service = args[-1].removeprefix('offline-')
        failed = service == os.environ.get('AWS_TEST_UNHEALTHY_SERVICE')
        print('running unhealthy' if failed else 'running healthy')
    else:
        sys.exit('Only the explicit Compose label and safe health state may be inspected')
elif args and args[0] == 'start':
    service = args[1].removeprefix('offline-')
    state['containers'][service]['running'] = True
    state_file.write_text(json.dumps(state))
elif args and args[0] == 'run':
    mount = args[args.index('--mount') + 1]
    directory = Path(next(part[4:] for part in mount.split(',') if part.startswith('src=')))
    archive = next(part for part in args if part.startswith('/backup/'))
    (directory / archive.removeprefix('/backup/')).write_bytes(b'offline archive')
elif args and args[0] == 'compose' and args != ['compose', 'version']:
    command = next((part for part in args if part in ('config', 'ps', 'stop', 'start', 'up', 'exec')), None)
    if command == 'ps' and '--quiet' in args:
        service = args[-1]
        if service != os.environ.get('AWS_TEST_MISSING_SERVICE'):
            print('offline-' + service)
    elif command in ('stop', 'start', 'up'):
        if command == 'up':
            state['mode'] = 'ollama' if any(part.endswith('compose.aws.ollama.yaml') for part in args) else 'base'
        services = args[args.index(command) + 1:]
        for service in state['containers']:
            if service in services:
                state['containers'][service]['running'] = command != 'stop'
        state_file.write_text(json.dumps(state))
    elif command == 'exec':
        if os.environ.get('AWS_TEST_DUMP_FAIL') == '1':
            sys.exit(17)
        print('offline-db-dump')
""")
        docker.chmod(0o755)

    def set_mode(self, mode):
        state = {"mode": mode, "containers": {
            service: {"running": True, "image": "existing-" + service,
                      "config": "existing-" + mode + "-" + service}
            for service in ("directus", "dina-api", "frontend")
        }}
        self.state_file.write_text(json.dumps(state))
        self.log.unlink(missing_ok=True)
        return state

    def test_backup_preserves_existing_configuration_in_both_modes(self):
        for mode in ("base", "ollama", "model-armor", "ollama+model-armor"):
            with self.subTest(mode=mode):
                original = self.set_mode(mode)
                destination = self.root / ("backups-" + mode)
                result = self.run_script("backup.sh", str(destination))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(self.state_file.read_text()), original)
                commands = [call["args"] for call in self.compose_calls()]
                self.assertFalse(any("up" in command for command in commands))
                self.assertFalse(any("start" in command for command in commands))
                starts = [call["args"] for call in self.calls() if call["args"][0] == "start"]
                self.assertEqual(starts, [["start", "offline-directus"], ["start", "offline-dina-api"],
                                          ["start", "offline-frontend"]])
                self.assertFalse(any("directus-schema" in argument for call in self.calls()
                                     for argument in call["args"]))
                self.assertFalse(any("ollama" in command for command in commands))
                complete = next(path for path in destination.iterdir() if ".partial." not in path.name)
                label = (complete / "compose-files.txt").read_text()
                self.assertEqual("compose.aws.ollama.yaml" in label, "ollama" in mode)
                self.assertEqual("compose.aws.model-armor.yaml" in label, "model-armor" in mode)
                self.assertEqual((complete / "compose.aws.ollama.yaml").read_bytes(),
                                 (ROOT / "compose.aws.ollama.yaml").read_bytes())
                manifest = (complete / "SHA256SUMS").read_text()
                self.assertIn("compose.aws.ollama.yaml", manifest)
                self.assertIn("compose-files.txt", manifest)
                self.assertIn("compose.aws.model-armor.yaml", manifest)
                self.assertEqual((complete / "compose.aws.model-armor.yaml").read_bytes(),
                                 (ROOT / "compose.aws.model-armor.yaml").read_bytes())

    def test_backup_does_not_copy_external_adc_file(self):
        credentials = self.configure_model_armor()
        self.set_mode("model-armor")
        destination = self.root / "backups"
        result = self.run_script("backup.sh", str(destination))
        self.assertEqual(result.returncode, 0, result.stderr)
        complete = next(path for path in destination.iterdir() if ".partial." not in path.name)
        self.assertFalse(any(path.name == credentials.name for path in complete.rglob("*")))
        self.assertNotIn("offline-private-key-do-not-print", b"".join(
            path.read_bytes() for path in complete.iterdir() if path.is_file()).decode(errors="replace"))

    def test_failed_dump_still_resumes_the_existing_optional_configuration(self):
        original = self.set_mode("ollama")
        result = self.run_script("backup.sh", str(self.root / "backups"), AWS_TEST_DUMP_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(json.loads(self.state_file.read_text()), original)
        commands = [call["args"] for call in self.compose_calls()]
        self.assertFalse(any("up" in command for command in commands))
        self.assertEqual([call["args"][-1] for call in self.calls() if call["args"][0] == "start"],
                         ["offline-directus", "offline-dina-api", "offline-frontend"])

    def test_missing_api_aborts_before_stopping_writers_or_creating_a_container(self):
        original = self.set_mode("ollama")
        result = self.run_script("backup.sh", str(self.root / "backups"),
                                 AWS_TEST_MISSING_SERVICE="dina-api")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(json.loads(self.state_file.read_text()), original)
        commands = [call["args"] for call in self.compose_calls()]
        self.assertFalse(any("stop" in command or "start" in command or "up" in command
                             for command in commands))

    def test_unhealthy_restart_is_reported_without_recreating_or_downgrading_configuration(self):
        original = self.set_mode("ollama")
        result = self.run_script("backup.sh", str(self.root / "backups"),
                                 AWS_TEST_UNHEALTHY_SERVICE="directus")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no está saludable", result.stderr)
        state = json.loads(self.state_file.read_text())
        self.assertEqual(state["mode"], original["mode"])
        self.assertEqual(state["containers"]["dina-api"]["config"],
                         original["containers"]["dina-api"]["config"])
        self.assertFalse(any("up" in call["args"] for call in self.compose_calls()))
        self.assertFalse(any("directus-schema" in argument for call in self.calls()
                             for argument in call["args"]))


if __name__ == "__main__":
    unittest.main()
