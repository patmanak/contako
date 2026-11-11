"""Device-free regressions for the APK reproduction gate's rejection paths."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('reproduction', Path(__file__).with_name('verify-reproducible.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ReproductionGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.signed = Path(self.directory.name) / 'published.apk'
        self.unsigned = Path(self.directory.name) / 'rebuilt.apk'
        self.signed.write_bytes(b'signed fixture')
        self.unsigned.write_bytes(b'unsigned fixture')
        environment = patch.dict(os.environ, {}, clear=True)
        environment.start()
        self.addCleanup(environment.stop)

    def outcomes(self, digest=None, signed_rebuild=False):
        return [subprocess.CompletedProcess([], 0, 'Number of signers: 1\nSigner #1 certificate SHA-256 digest: '
                                            + (digest or gate.SIGNER_SHA256) + '\n'),
                subprocess.CompletedProcess([], 0 if signed_rebuild else 1)]

    def test_rejects_same_input_before_comparison(self):
        with self.assertRaisesRegex(ValueError, 'DISTINCT_ARTIFACTS_REQUIRED'):
            gate.compare(self.signed, self.signed)

    def test_rejects_environment_override(self):
        with patch.dict(os.environ, {'APKSIGCOPIER_IGNORE_DIFFERENCES': '1'}):
            with self.assertRaisesRegex(ValueError, 'COMPARISON_ENVIRONMENT_OVERRIDE'):
                gate.compare(self.signed, self.unsigned)

    def test_requires_android_verifier(self):
        with patch.object(gate.shutil, 'which', return_value=None):
            with self.assertRaisesRegex(ValueError, 'APKSIGNER_REQUIRED'):
                gate.compare(self.signed, self.unsigned)

    def test_rejects_wrong_signer(self):
        with patch.object(gate.shutil, 'which', return_value='/tools/apksigner'), \
                patch.object(gate.subprocess, 'run', side_effect=self.outcomes('0' * 64)):
            with self.assertRaisesRegex(ValueError, 'PUBLISHED_SIGNER_MISMATCH'):
                gate.compare(self.signed, self.unsigned)

    def test_rejects_failed_signature_verification(self):
        result = subprocess.CompletedProcess([], 1, 'Number of signers: 1\nSigner #1 certificate SHA-256 digest: '
                                             + gate.SIGNER_SHA256 + '\n')
        with patch.object(gate.shutil, 'which', return_value='/tools/apksigner'), \
                patch.object(gate.subprocess, 'run', return_value=result):
            with self.assertRaisesRegex(ValueError, 'PUBLISHED_SIGNER_MISMATCH'):
                gate.compare(self.signed, self.unsigned)

    def test_rejects_signed_rebuild(self):
        with patch.object(gate.shutil, 'which', return_value='/tools/apksigner'), \
                patch.object(gate.subprocess, 'run', side_effect=self.outcomes(signed_rebuild=True)):
            with self.assertRaisesRegex(ValueError, 'UNSIGNED_REBUILD_REQUIRED'):
                gate.compare(self.signed, self.unsigned)

    def test_delegates_to_upstream_without_weakening_options(self):
        comparator = Mock()
        with patch.object(gate.shutil, 'which', return_value='/tools/apksigner'), \
                patch.object(gate.subprocess, 'run', side_effect=self.outcomes()), \
                patch.dict('sys.modules', {'apksigcopier': types.SimpleNamespace(do_compare=comparator)}):
            gate.compare(self.signed, self.unsigned)
        comparator.assert_called_once_with(str(self.signed), str(self.unsigned), unsigned=True,
                                           verify_cmd=('/tools/apksigner', 'verify'))

    def test_accepts_build_tools_37_signer_output(self):
        outcomes = self.outcomes()
        outcomes[0].stdout = outcomes[0].stdout.replace('Signer #1 certificate', 'V3.0 Signer: certificate')
        comparator = Mock()
        with patch.object(gate.shutil, 'which', return_value='/tools/apksigner'), \
                patch.object(gate.subprocess, 'run', side_effect=outcomes), \
                patch.dict('sys.modules', {'apksigcopier': types.SimpleNamespace(do_compare=comparator)}):
            gate.compare(self.signed, self.unsigned)
        self.assertEqual(comparator.call_count, 1)

    def test_rejects_additional_signer(self):
        outcomes = self.outcomes()
        outcomes[0].stdout = outcomes[0].stdout.replace('Number of signers: 1', 'Number of signers: 2')
        with patch.object(gate.shutil, 'which', return_value='/tools/apksigner'), \
                patch.object(gate.subprocess, 'run', side_effect=outcomes):
            with self.assertRaisesRegex(ValueError, 'PUBLISHED_SIGNER_MISMATCH'):
                gate.compare(self.signed, self.unsigned)

    def test_upstream_mismatch_cannot_become_success(self):
        def mismatch(*args, **kwargs):
            raise RuntimeError('SIGNATURE_REPRODUCTION_MISMATCH')
        with patch.object(gate.shutil, 'which', return_value='/tools/apksigner'), \
                patch.object(gate.subprocess, 'run', side_effect=self.outcomes()), \
                patch.dict('sys.modules', {'apksigcopier': types.SimpleNamespace(do_compare=mismatch)}):
            with self.assertRaisesRegex(RuntimeError, 'SIGNATURE_REPRODUCTION_MISMATCH'):
                gate.compare(self.signed, self.unsigned)


if __name__ == '__main__':
    unittest.main()
