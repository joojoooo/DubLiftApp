"""Exercise release ranges with real Git history without changing the app repo."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("release_notes", Path(__file__).with_name("generate-release-notes.py"))
notes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notes)


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.original_root = notes.ROOT
        notes.ROOT = self.root
        (self.root / ".github").mkdir()
        (self.root / ".github/release-notes.md").write_text("## Download\n\nInstall an APK.\n")
        self.git("init", "--quiet")
        self.git("config", "user.name", "Release notes test")
        self.git("config", "user.email", "test@example.invalid")
        for tag, title in (("v0.0.1", "Initial app"), ("v0.0.2", "Fix playback"),
                           ("v0.0.3", "Add updates"), ("v0.0.4", "Future changes")):
            self.git("commit", "--quiet", "--allow-empty", "-m", title)
            self.git("tag", tag)

    def tearDown(self):
        notes.ROOT = self.original_root
        self.directory.cleanup()

    def git(self, *arguments):
        return subprocess.check_output(["git", *arguments], cwd=self.root, text=True)

    def test_changes_since_last_published_version_and_reruns(self):
        first = notes.generate("v0.0.3", ["v0.0.1", "v0.0.2"])
        rerun = notes.generate("v0.0.3", ["v0.0.4", "v0.0.3", "v0.0.2", "v0.0.1", "v0.0.3-beta"])
        self.assertEqual(first, rerun)
        self.assertIn("## Changelog\n\n- Add updates\n", first)
        self.assertIn("/compare/v0.0.2...v0.0.3", first)
        self.assertNotIn("Fix playback", first)
        self.assertNotIn("Future changes", first)

    def test_unreleased_tags_do_not_exclude_commits(self):
        generated = notes.generate("v0.0.3", ["v0.0.1"])
        self.assertIn("- Fix playback", generated)
        self.assertIn("- Add updates", generated)
        self.assertNotIn("- Initial app", generated)

    def test_first_release_includes_history_up_to_the_tag(self):
        generated = notes.generate("v0.0.3", [])
        self.assertIn("## Download", generated)
        for title in ("Initial app", "Fix playback", "Add updates"):
            self.assertIn(f"- {title}", generated)
        self.assertNotIn("Future changes", generated)
        self.assertNotIn("## Full changelog", generated)

    def test_rejects_invalid_tags(self):
        with self.assertRaises(ValueError):
            notes.generate("v1.2.3-beta", [])


if __name__ == "__main__":
    unittest.main()
