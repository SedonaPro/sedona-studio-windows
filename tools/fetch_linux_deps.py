#!/usr/bin/env python3
"""Download what a Linux build needs and git does not keep: the JavaFX jars, the headless test
toolkit and a portable Python for the SDK's build tool. Checksums are pinned."""
from pathlib import Path
import hashlib, shutil, tarfile, urllib.request
ROOT = Path(__file__).resolve().parents[1]
FILES = [
    ('lib/javafx-linux/javafx-base-17.0.12-linux.jar', 'https://repo.maven.apache.org/maven2/org/openjfx/javafx-base/17.0.12/javafx-base-17.0.12-linux.jar',
     '35d5b6d865c280a3cdff862f523d2ed91c534bfcbd70c89bce5e019eb9ccf351'),
    ('lib/javafx-linux/javafx-controls-17.0.12-linux.jar', 'https://repo.maven.apache.org/maven2/org/openjfx/javafx-controls/17.0.12/javafx-controls-17.0.12-linux.jar',
     'ed0b726aeca1dad383d3e6c857b6f39d46057c9eb0380eac0662c20d79a5a5f0'),
    ('lib/javafx-linux/javafx-graphics-17.0.12-linux.jar', 'https://repo.maven.apache.org/maven2/org/openjfx/javafx-graphics/17.0.12/javafx-graphics-17.0.12-linux.jar',
     '8d9fc71b12d5cc8591697a42a3c50948b365648833b4b57acfa70f9e24e64782'),
    ('lib/javafx-linux/javafx-swing-17.0.12-linux.jar', 'https://repo.maven.apache.org/maven2/org/openjfx/javafx-swing/17.0.12/javafx-swing-17.0.12-linux.jar',
     '0a7db061df9e9f6e840c5b665b3bd3c9af7011705af3fd635194a3dc5ff78fa2'),
    ('lib/test/openjfx-monocle-17.0.10.jar', 'https://repo.maven.apache.org/maven2/org/testfx/openjfx-monocle/17.0.10/openjfx-monocle-17.0.10.jar',
     'd924b0549ca3d8205580123fa99769815bf15211832aed91a85413979c9c0635'),
]
for rel, url, sha in FILES:
    target = ROOT / rel
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != sha:
            raise SystemExit('Checksum mismatch: ' + url)
        target.write_bytes(data)
    elif hashlib.sha256(target.read_bytes()).hexdigest() != sha:
        raise SystemExit('Checksum mismatch: ' + rel)
    print('OK', rel)

# Portable Python (python-build-standalone): the kit workshop runs the SDK's "spro" tool with it,
# so the editor does not depend on a Python installed on the computer.
PYTHON = ('lib/python-linux/cpython-3.12.16-20261110-x86_64-linux.tar.gz',
          'https://github.com/astral-sh/python-build-standalone/releases/download/20261110/'
          'cpython-3.12.16%2B20261110-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz',
          '6a9e62dad033790aef722dbd370e0ce76ce17b73627ba3ae679f537049350b39')
archive = ROOT / PYTHON[0]
if not archive.exists():
    archive.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(PYTHON[1], timeout=300) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != PYTHON[2]:
        raise SystemExit('Checksum mismatch: ' + PYTHON[1])
    archive.write_bytes(data)
elif hashlib.sha256(archive.read_bytes()).hexdigest() != PYTHON[2]:
    raise SystemExit('Checksum mismatch: ' + PYTHON[0])
home = archive.parent / 'python'
marker = home / '.source'
if not marker.exists() or marker.read_text() != PYTHON[2]:
    shutil.rmtree(home, ignore_errors=True)
    with tarfile.open(archive) as tar:
        tar.extractall(archive.parent, filter='data')   # the archive has one top folder: python/
    marker.write_text(PYTHON[2])
print('OK', home.relative_to(ROOT), '(portable Python)')
