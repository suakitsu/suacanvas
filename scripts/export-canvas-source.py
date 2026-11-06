#!/usr/bin/env python3
"""Export committed OpenImage and verified pinned upstream sources, without Git history."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import subprocess
import tarfile
import urllib.request

UPSTREAMS = [
    ('nova-image-studio', 'tianjiangqiji/nova-image-studio',
     '7768f3f8d7f47e04c6d18572837a086c7a533161',
     '50351fe5ed4b41765c56809fa7618390024a15f7e3d4afc1dedf800a5487052a'),
    ('infinite-canvas', 'basketikun/infinite-canvas',
     '705fdd9b2f7bfe6fd58156fd5c1cdb1ad251c767',
     '0ca3503e2dc8c32a5c6e841bd2721a4f0f5ad18a7e09d751af9081b54cff2256'),
]


def add_bytes(archive, name, data, mode=0o644):
    info = tarfile.TarInfo(name)
    info.size, info.mode = len(data), mode
    archive.addfile(info, io.BytesIO(data))


def copy_archive(target, data, prefix, strip_root=False):
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:*') as source:
        for member in source:
            parts = PurePosixPath(member.name).parts
            if '..' in parts or member.name.startswith('/'):
                raise RuntimeError('Unsafe archive path')
            if strip_root:
                parts = parts[1:]
            if not parts or not member.isfile():
                continue
            add_bytes(target, prefix + '/'.join(parts), source.extractfile(member).read(), member.mode)


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=root / 'canvas-source')
    args = parser.parse_args()
    git = lambda *a: subprocess.check_output(['git', *a], cwd=root)
    if git('status', '--porcelain', '--untracked-files=no').strip():
        raise SystemExit('Commit tracked changes before exporting the exact release source.')
    revision = git('rev-parse', 'HEAD').decode().strip()
    source = git('archive', '--format=tar', 'HEAD')
    downloads = []
    for name, repository, commit, expected in UPSTREAMS:
        with urllib.request.urlopen('https://codeload.github.com/' + repository + '/tar.gz/' + commit, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise SystemExit('Upstream archive checksum mismatch: ' + name)
        downloads.append((name, commit, data))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / 'openimage-canvas-source.tar.gz'
    with output.open('xb') as stream:
        with tarfile.open(fileobj=stream, mode='w:gz') as archive:
            copy_archive(archive, source, 'openimage/')
            for name, commit, data in downloads:
                copy_archive(archive, data, 'openimage/third_party/canvas-lab/' + name + '/', True)
            add_bytes(archive, 'SOURCE-REVISION.json', json.dumps({
                'openimage': revision,
                'upstreams': {name: commit for name, commit, _ in downloads},
            }, indent=2).encode())
    license_text = git('show', 'HEAD:third_party/nova-image-studio/LICENSE')
    (args.output_dir / 'AGPL-3.0.txt').write_bytes(license_text)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    (args.output_dir / 'SHA256SUMS').write_text(digest + '  ' + output.name + '\n', encoding='utf-8')
    print('Exported committed source for ' + revision + '; no history or untracked files included.')


if __name__ == '__main__':
    main()
