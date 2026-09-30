#!/usr/bin/env python3
"""Local deployment regression: no root, database, Java process or production paths touched."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class BackendEntrypoints(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='logmonitor-entrypoints-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.bundle = self.base / 'bundle'
        self.bundle.mkdir()
        for name in ['start.sh', 'shutdown.sh', 'logmonitor-backend.sh.template', 'backend.env.example']:
            shutil.copy2(ROOT / 'backend/deploy' / name, self.bundle / name)
        for name in ['application.example.yml', 'application-prod.example.yml']:
            shutil.copy2(ROOT / 'backend/config' / name, self.bundle / name)
        (self.bundle / 'VERSION').write_text('test-version\n')
        (self.bundle / 'logmonitor-backend.jar').write_bytes(b'fixture only')
        self.env = os.environ.copy()
        self.env.update(DESTDIR=str(self.base / 'stage'), APP_USER='logmonitor', APP_GROUP='logmonitor')
        # Avoid inheriting deployment overrides from the caller.
        for key in ['APP_DIR', 'CONFIG_DIR', 'DATA_DIR', 'LEGACY_SERVICE_DIR', 'JAVA_BIN', 'START_PROCESS', 'ENABLE_SERVICE']:
            self.env.pop(key, None)

    def invoke(self, shell, entry, env=None):
        return subprocess.run([shell, str(self.bundle / entry)], env=env or self.env,
                              capture_output=True, text=True, timeout=20)

    def test_sh_and_dash_prepare_and_preserve_existing_configuration_and_data(self):
        shells = ['sh']
        if shutil.which('dash'):
            shells.append('dash')
        for shell in shells:
            result = self.invoke(shell, 'start.sh')
            self.assertEqual(result.returncode, 0, result.stderr)
            stage = self.base / 'stage'
            env_file = stage / 'etc/logmonitor/backend.env'
            config_file = stage / 'etc/logmonitor/application.yml'
            env_file.write_text('FIXTURE=preserved\n')
            config_file.write_text('fixture: preserved\n')
            state = stage / 'var/lib/logmonitor/backend/state'
            state.write_text('preserved')
            result = self.invoke(shell, 'start.sh')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(env_file.read_text(), 'FIXTURE=preserved\n')
            self.assertEqual(config_file.read_text(), 'fixture: preserved\n')
            self.assertEqual(state.read_text(), 'preserved')
            control = stage / 'opt/logmonitor/backend/logmonitor-backend.sh'
            self.assertTrue(os.access(control, os.X_OK))
            self.assertNotIn('__APP_USER__', control.read_text())

    def test_shutdown_passes_stop_and_propagates_control_failure(self):
        app = self.base / 'app'
        app.mkdir()
        marker = self.base / 'called'
        (app / 'logmonitor-backend.sh').write_text('#!/bin/sh\nprintf "%s" "$1" > "$TEST_MARKER"\nexit 7\n')
        env = self.env.copy()
        env.update(APP_DIR=str(app), TEST_MARKER=str(marker))
        result = self.invoke('dash' if shutil.which('dash') else 'sh', 'shutdown.sh', env)
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertEqual(marker.read_text(), 'stop')

    def test_shutdown_missing_installation_and_invalid_path_fail(self):
        env = self.env.copy()
        env['APP_DIR'] = str(self.base / 'missing')
        self.assertEqual(self.invoke('sh', 'shutdown.sh', env).returncode, 1)
        env['APP_DIR'] = '/unsafe path'
        self.assertEqual(self.invoke('sh', 'shutdown.sh', env).returncode, 2)


if __name__ == '__main__':
    unittest.main()
