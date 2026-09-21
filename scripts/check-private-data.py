#!/usr/bin/env python3
"""Check commit-eligible text for deployment details and literal credentials.
This is a guardrail, not a substitute for reviewing changes or rotating exposed secrets.
Print paths and rule names only, never matched values.
"""
import re
import subprocess
import sys
from pathlib import Path

staged = '--staged' in sys.argv
names = subprocess.check_output(['git', 'diff', '--cached', '--name-only', '--diff-filter=ACMR', '-z'] if staged else ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z']).decode().split('\0')
rules = {
    'personal email': re.compile(r'[\w.+-]+@(?:gmail|hotmail|outlook|yahoo|icloud)\.com', re.I),
    'deployed Worker hostname': re.compile(r'(?<![\w.-])[\w-]+\.(?!example\.)[\w-]+\.workers\.dev', re.I),
    'deployed Pages hostname': re.compile(r'(?<![\w.-])(?!example\.)[\w-]+\.pages\.dev', re.I),
    'custom development URL': re.compile(r'https?://(?:[\w-]+\.)*(?!example\b)[\w-]+\.dev(?=[:/\s\"\'?#]|$)', re.I),
    'Access team hostname': re.compile(r'(?<![\w.-])(?!example[.\w-]*\.)[\w-]+\.cloudflareaccess\.com', re.I),
    'local home path': re.compile(r'/' + r'Users/(?!example\b|<)[^/\s]+/'),
    'literal credential': re.compile(r'''(?i)(?:client_secret|refresh_token|access_token|api_token|token_encryption_key|sinric_app_secret|control_token|device_credential|cf_api_key|cloudflare_api_token|cloudflare_api_key)["']?\s*[:=]\s*["']([A-Za-z0-9_+/=-]{24,})["']'''),
    'Cloudflare account or zone identifier': re.compile(r'''(?i)(?:account_id|zone_id)["']?\s*[:=]\s*["'][a-f0-9]{32}["']'''),
    'private key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----'),
    'pairing link with secret': re.compile(r'#pair=[A-Za-z0-9_-]{32,}'),
}
issues = []
for name in sorted(set(filter(None, names))):
    if not staged and not Path(name).is_file():
        continue
    if re.search(r'(^|/)(?:\.dev\.vars(?:\..+)?|\.env(?:\..+)?|wrangler\.local\.jsonc)$', name) and not name.endswith('.example'):
        issues.append((name, 'private configuration is tracked'))
    if name.startswith('private-notes/') or Path(name).name.startswith('Screenshot'):
        issues.append((name, 'private notes or screenshot is tracked'))
    if re.search(r'\.(?:pem|key|p12|pfx|jks|keystore|db|sqlite|sqlite3)(?:-wal|-shm)?$', name, re.I):
        issues.append((name, 'private key store or database file'))
    try:
        raw = subprocess.check_output(['git', 'show', ':'+name]) if staged else Path(name).read_bytes()
        data = raw.decode('utf8')
    except (OSError, UnicodeError, subprocess.CalledProcessError):
        continue
    for rule, pattern in rules.items():
        for match in pattern.finditer(data):
            issues.append((name, rule)); break
if issues:
    for name, rule in issues:
        print(f'{name}: {rule}')
    sys.exit(1)
print('No private deployment details or obvious literal credentials found in ' + ('staged changes.' if staged else 'tracked and commit-eligible untracked files.'))
