#!/usr/bin/env python3
"""Create an untracked local configuration with independent random secrets."""
import argparse
from pathlib import Path
import re
import secrets


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=root / '.env')
    args = parser.parse_args()
    values = {
        'POSTGRES_PASSWORD': secrets.token_urlsafe(32),
        'JWT_SECRET': secrets.token_urlsafe(48),
        'MINIO_ROOT_PASSWORD': secrets.token_urlsafe(32),
        'VIDEO_CONFIG_ENCRYPTION_KEY': secrets.token_urlsafe(48),
    }
    values['MINIO_SECRET_KEY'] = values['MINIO_ROOT_PASSWORD']
    text = (root / '.env.example').read_text(encoding='utf-8')
    for key, value in values.items():
        text, count = re.subn(r'^' + re.escape(key) + r'=.*$', key + '=' + value,
                             text, flags=re.MULTILINE)
        if count != 1:
            raise SystemExit('Expected exactly one configuration field: ' + key)
    try:
        with args.output.open('x', encoding='utf-8', newline='\n') as target:
            target.write(text)
        args.output.chmod(0o600)
    except FileExistsError:
        raise SystemExit('Refusing to overwrite an existing environment file.')
    print('Created private local configuration. Secret values are not printed.')


if __name__ == '__main__':
    main()
