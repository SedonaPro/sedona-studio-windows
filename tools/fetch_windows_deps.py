# SPDX-License-Identifier: Apache-2.0
"""Fetch pinned JavaFX dependencies for a Windows source build."""
from pathlib import Path
import hashlib,json,urllib.request
root=Path(__file__).resolve().parents[1]
for item in json.loads((root/'tools/windows-javafx-dependencies.json').read_text()):
    target=root/'lib/javafx'/item['file']
    if target.exists(): data=target.read_bytes()
    else:
        with urllib.request.urlopen(item['url'],timeout=60) as response: data=response.read()
    if hashlib.sha256(data).hexdigest()!=item['sha256']: raise SystemExit('Checksum mismatch: '+item['file'])
    target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
    print('OK',item['file'])
