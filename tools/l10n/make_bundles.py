#!/usr/bin/env python3
"""One-off: patterns.tsv (from Extract scan) + reviewed English list -> keys.tsv, lang/ru.properties, lang/en.properties.

usage: make_bundles.py patterns.tsv english.txt... ; english lines are id|key|text (text uses \\n escapes)."""
import re, sys
from pathlib import Path
def unesc(s): return re.sub(r'\\(.)', lambda m: {'n': '\n', 't': '\t', 'r': '\r'}.get(m.group(1), m.group(1)), s)
def prop(s):
    s = s.replace('\\', '\\\\').replace('\n', '\\n').replace('\t', '\\t').replace('\r', '\\r')
    if s[:1] == ' ': s = '\\' + s
    if s[-1:] == ' ': s = s[:-1] + '\\u0020'
    return s
pat = {}
for line in Path(sys.argv[1]).read_text(encoding='utf-8').split('\n'):
    if line.strip('\n'):
        i, p, where = line.split('\t'); pat[int(i)] = unesc(p)
en = {}
for f in sys.argv[2:]:
    for line in Path(f).read_text(encoding='utf-8').split('\n'):
        if line.strip():
            i, key, text = line.split('|', 2); en[int(i)] = (key, unesc(text))
missing = sorted(set(pat) - set(en)); assert not missing, missing
keys = [k for k, _ in en.values()]; dup = {k for k in keys if keys.count(k) > 1}; assert not dup, dup
ph = lambda s: sorted(set(re.findall(r'\{\d+\}', s)))
for i in pat:
    assert ph(pat[i]) == ph(en[i][1]), (i, pat[i], en[i][1])
    for edge in (0, -1):
        a, b = pat[i][edge], en[i][1][edge]
        assert (a in ' \n') == (b in ' \n'), ('edge whitespace', i, pat[i], en[i][1])
esc = lambda s: s.replace('\\', '\\\\').replace('\n', '\\n').replace('\t', '\\t').replace('\r', '\\r')
Path(sys.argv[1]).with_name('keys.tsv').write_text(''.join(esc(pat[i]) + '\t' + en[i][0] + '\n' for i in sorted(pat)), encoding='utf-8')
def bundle(name, title, extra, text_of):
    out = ['# Sedona Studio interface texts: ' + title, '# UTF-8. {0}, {1}... are replaced with values; \\n is a line break; a leading "\\ " keeps a leading space.', '']
    out += [k + '=' + prop(v) for k, v in extra]
    area = None
    for i in sorted(pat, key=lambda i: en[i][0]):
        a = en[i][0].split('.')[0]
        if a != area: out.append(''); area = a
        out.append(en[i][0] + '=' + prop(text_of(i)))
    Path('lang/' + name + '.properties').write_text('\n'.join(out) + '\n', encoding='utf-8')
bundle('en', 'English (the reference; every key must be here)', [('language.name', 'English'), ('settings.language', 'Interface language'), ('settings.languageRestart', 'The language changes after the editor is restarted.')], lambda i: en[i][1])
bundle('ru', 'Русский', [('language.name', 'Русский'), ('settings.language', 'Язык интерфейса'), ('settings.languageRestart', 'Язык изменится после перезапуска редактора.')], lambda i: pat[i])
print(len(pat), 'keys')
