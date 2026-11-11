"""Linux preflight rejection tests; no Go compilation or network access."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(sys.platform.startswith('linux'), 'Linux builder preflight')
class BuildPreflightTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.go = self.root / 'go'
        self.java = self.root / 'java'
        self.ndk = self.root / 'ndk'
        self.sdk = self.root / 'sdk'
        for folder in (self.go / 'bin', self.java / 'bin', self.ndk, self.sdk / 'platforms'):
            folder.mkdir(parents=True)
        self.executable(self.go / 'bin/go', 'go1.27.1')
        self.executable(self.java / 'bin/javac', 'javac 21.0.13')
        (self.ndk / 'source.properties').write_text('Pkg.Revision = 27.2.12479018\n')

    def executable(self, path, output):
        path.write_text('#!/bin/sh\nprintf "%s\\n" "' + output + '"\n')
        path.chmod(0o755)

    def reject(self, expected, extra=()):
        command = ['bash', str(Path(__file__).with_name('build.sh')),
                   '--go-home', str(self.go), '--java-home', str(self.java),
                   '--android-sdk', str(self.sdk), '--android-ndk', str(self.ndk),
                   '--work', str(self.root / 'output'), '--repository', str(self.root / 'maven'), *extra]
        result = subprocess.run(command, capture_output=True, text=True,
                                env={'PATH': os.environ['PATH']}, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(expected, result.stderr)
        self.assertFalse((self.root / 'output').exists())
        self.assertFalse((self.root / 'maven').exists())

    def test_missing_argument(self):
        self.reject('MISSING_ARGUMENT', ['--work'])

    def test_unknown_argument(self):
        self.reject('UNKNOWN_ARGUMENT', ['--unknown'])

    def test_missing_go(self):
        self.reject('GO_HOME_INVALID', ['--go-home', str(self.root / 'missing')])

    def test_wrong_go(self):
        self.executable(self.go / 'bin/go', 'go1.26.8')
        self.reject('GO_1_27_1_REQUIRED')

    def test_wrong_java(self):
        self.executable(self.java / 'bin/javac', 'javac 17.0.1')
        self.reject('JAVA_21_REQUIRED')

    def test_wrong_ndk(self):
        (self.ndk / 'source.properties').write_text('Pkg.Revision = 27.2.124790180\n')
        self.reject('NDK_R27C_REQUIRED')

    def test_missing_sdk(self):
        self.reject('ANDROID_SDK_INVALID', ['--android-sdk', str(self.root / 'missing')])


if __name__ == '__main__':
    unittest.main()
