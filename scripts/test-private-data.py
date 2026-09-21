"""Exercise the privacy guard against real temporary Git indexes."""
import subprocess
import tempfile
import unittest
from pathlib import Path

CHECKER = Path(__file__).with_name('check-private-data.py').resolve()


class PrivacyCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')

    def git(self, *args):
        subprocess.run(['git', *args], cwd=self.root, check=True, capture_output=True)

    def check(self, *args):
        return subprocess.run(['python3', str(CHECKER), *args], cwd=self.root,
                              capture_output=True, text=True)

    def test_untracked_private_hosts_and_credentials_are_redacted(self):
        values = [
            'https://' + 'private-test.account.workers' + '.dev',
            'https://' + 'private-test.pages' + '.dev',
            'https://' + 'private-test' + '.dev',
            'https://' + 'private-test.cloudflareaccess' + '.com',
            'api_token = "' + 'x' * 40 + '"',
            'account_id = "' + 'a' * 32 + '"',
            'https://example.invalid/#pair=' + 'a' * 64,
            '-----BEGIN ' + 'PRIVATE KEY-----',
        ]
        for value in values:
            with self.subTest(value_type=values.index(value)):
                (self.root / 'config.txt').write_text(value)
                result = self.check()
                self.assertEqual(result.returncode, 1)
                self.assertIn('config.txt:', result.stdout)
                self.assertNotIn(value, result.stdout)

    def test_staged_contents_are_checked_even_if_worktree_is_cleaned(self):
        path = self.root / 'config.txt'
        path.write_text('https://' + 'private-test.pages' + '.dev')
        self.git('add', 'config.txt')
        path.write_text('https://example.invalid')
        self.assertEqual(self.check('--staged').returncode, 1)
        self.assertEqual(self.check().returncode, 0)

    def test_ignored_local_config_is_excluded_but_force_added_config_fails(self):
        (self.root / '.gitignore').write_text('.env\n')
        (self.root / '.env').write_text('LOCAL_ONLY=1\n')
        self.assertEqual(self.check().returncode, 0)
        self.git('add', '-f', '.env')
        self.assertEqual(self.check('--staged').returncode, 1)

    def test_safe_examples_and_app_identifier_pass(self):
        (self.root / 'example.txt').write_text(
            'https://example.invalid com.spop.poverlay.dev ${CLOUDFLARE_API_TOKEN}')
        self.assertEqual(self.check().returncode, 0)


if __name__ == '__main__':
    unittest.main()
