"""Generate additive test-account SQL and k6 credentials. Does NOT connect to any database."""
import argparse
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--count', type=int, default=100)
args = parser.parse_args()
if not 1 <= args.count <= 10000:
    parser.error('count must be 1..10000')
root = Path(__file__).resolve().parents[2]
out = root / 'deploy/generated/load'
out.mkdir(parents=True, exist_ok=True)
password = 'load-test-only-123'
digest = hashlib.md5(('goat' + password).encode('utf-8')).hexdigest()
accounts = [dict(email=f'load-{i:05d}@example.invalid', password=password) for i in range(args.count)]
sql = '-- Dedicated load-test database ONLY. No DROP, DELETE, balance updates or existing-row resets.\n'
sql += 'INSERT INTO `user`(user_id,email,password,nickname,state) VALUES\n'
sql += ',\n'.join(f"({5000000+i},'{a['email']}','{digest}','load-{i:05d}',0)" for i, a in enumerate(accounts))
sql += '\nON DUPLICATE KEY UPDATE user_id=user_id;\n'
(out / 'accounts.sql').write_text(sql, encoding='utf-8')
(out / 'accounts.json').write_text(json.dumps(accounts, indent=2) + '\n', encoding='utf-8')
print(f'Generated {args.count} accounts under deploy/generated/load; no SQL executed.')
