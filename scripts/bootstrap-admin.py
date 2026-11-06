#!/usr/bin/env python3
"""Create the first administrator in a migrated, local Docker Compose database.

Run interactively after the backend has completed its database migrations.
Passwords are read without echo and passed to a network-isolated official httpd
utility container over stdin. Only the resulting bcrypt hash is sent to Postgres.
No web registration endpoint or shared password is enabled.
"""
import getpass
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid


ROOT = Path(__file__).resolve().parent.parent
HASH_IMAGE = 'httpd:2.4-alpine'
ADMIN_FILTER = "deleted = 0 AND status = 'active' AND role IN ('admin', 'super_admin')"


def run(command, data=None, timeout=60):
    try:
        return subprocess.run(command, cwd=ROOT, input=data, text=True,
                              encoding='utf-8', capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise SystemExit('Docker command failed or timed out. Check Docker and service status.') from None


def database_command():
    return ['docker', 'compose', '--env-file', str(ROOT / '.env'),
            '-f', str(ROOT / 'docker-compose.yml'), 'exec', '-T', 'postgres',
            'sh', '-c', 'export PGPASSWORD="$POSTGRES_PASSWORD"; '
            'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']


def sql_literal(value):
    return "'" + value.replace("'", "''") + "'"


def main():
    if not sys.stdin.isatty() or not sys.stderr.isatty():
        raise SystemExit('Run this script in an interactive terminal; password piping is not supported.')
    if not shutil.which('docker'):
        raise SystemExit('Docker is not available on PATH.')
    if not (ROOT / '.env').is_file():
        raise SystemExit('Create the local .env with scripts/init-local-env.py first.')

    preflight = run(database_command(),
        "SELECT count(*) FROM flyway_schema_history WHERE version = '44' AND success;\n"
        "SELECT count(*) FROM users WHERE " + ADMIN_FILTER + ";\n")
    if preflight.returncode or preflight.stdout.strip().splitlines() != ['1', '0']:
        raise SystemExit('Database migrations are incomplete or an active administrator already exists. '
                         'This tool only initializes the first administrator; it never resets an account.')

    email = input('Administrator email: ').strip().lower()
    if len(email) > 255 or not re.fullmatch(r'[a-z0-9._%+-]+@(?:[a-z0-9-]+\.)+[a-z]{2,63}', email):
        raise SystemExit('Enter a valid email address.')
    if email == 'admin@openimage.local':
        raise SystemExit('Choose your own email; the legacy demo account is reserved and disabled.')
    password = getpass.getpass('New administrator password (12+ characters): ')
    if (len(password) < 12 or len(password.encode('utf-8')) > 72
            or any(ord(char) < 32 or ord(char) == 127 for char in password)):
        raise SystemExit('Use at least 12 characters, at most 72 UTF-8 bytes, and no control characters.')
    if password != getpass.getpass('Confirm password: '):
        raise SystemExit('Passwords do not match.')

    print('Creating a bcrypt hash with the official httpd utility image; the first run may download it.')
    hashed = run(['docker', 'run', '--rm', '-i', '--network', 'none', '--read-only',
                  '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                  '--entrypoint', 'htpasswd', HASH_IMAGE, '-niB', '-C', '12', 'bootstrap'],
                 password + '\n', timeout=300)
    password = None
    match = re.fullmatch(r'bootstrap:(\$2[aby]\$12\$[./A-Za-z0-9]{53})', hashed.stdout.strip())
    if hashed.returncode or match is None:
        raise SystemExit('Password hashing failed. Check access to the official httpd Docker image.')
    password_hash = match.group(1)
    sql = """BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;
DO $bootstrap$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '44' AND success) THEN
        RAISE EXCEPTION 'Required database migration has not completed';
    END IF;
    IF EXISTS (SELECT 1 FROM users WHERE %s) THEN
        RAISE EXCEPTION 'An active administrator already exists';
    END IF;
END;
$bootstrap$;
INSERT INTO users (id, email, password_hash, nickname, role, status, credits, auth_provider)
VALUES (%s, %s, %s, 'SuaCanvas administrator', 'super_admin', 'active', 0, 'password');
COMMIT;
""" % (ADMIN_FILTER, sql_literal('u_' + uuid.uuid4().hex), sql_literal(email), sql_literal(password_hash))
    result = run(database_command(), sql)
    if result.returncode:
        raise SystemExit('Administrator creation did not complete cleanly. Check whether this email '
                         'or another administrator already exists before retrying.')
    print('Administrator created. Sign in to the admin website with the email and password you entered.')
    print('No password or API credential was written to the repository. Initial credits are zero.')


if __name__ == '__main__':
    main()
