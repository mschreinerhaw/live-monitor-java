"""Filesystem ownership guards must reject an unknown existing installation."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('deployment', Path(__file__).with_name('deploy-server-validation.py'))
deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deployment)


class SafetyTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def test_uploaded_release_is_allowed_without_touching_it(self):
        (self.root / 'releases').mkdir()
        deployment.validate_workspace(self.root, False, 'source')
        self.assertTrue((self.root / 'releases').is_dir())

    def test_existing_data_is_preserved_and_rejected(self):
        (self.root / 'data').mkdir()
        sentinel = self.root / 'data/database'
        sentinel.write_bytes(b'existing-database')
        with self.assertRaises(AssertionError):
            deployment.validate_workspace(self.root, False, 'source')
        self.assertEqual(sentinel.read_bytes(), b'existing-database')

    def test_resume_requires_owned_marker(self):
        with self.assertRaises(AssertionError):
            deployment.validate_workspace(self.root, True, 'source')

    def test_resume_is_bound_to_original_source_and_path(self):
        marker = self.root / 'pending-installation.json'
        marker.write_text(json.dumps({'root': str(self.root), 'source': 'original'}))
        deployment.validate_workspace(self.root, True, 'original')
        with self.assertRaises(AssertionError):
            deployment.validate_workspace(self.root, True, 'different')
        marker.write_text(json.dumps({'root': '/unrelated', 'source': 'original'}))
        with self.assertRaises(AssertionError):
            deployment.validate_workspace(self.root, True, 'original')


if __name__ == '__main__':
    unittest.main()
