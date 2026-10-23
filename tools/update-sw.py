"""Recalcula as revisões do cache offline (service-worker.js) depois de mudar arquivos."""
import re, hashlib, os, sys
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
p = os.path.join(root, 'service-worker.js')
s = open(p, encoding='utf-8').read()
changed = 0
def fix(m):
    global changed
    url, rev = m.group(1), m.group(2)
    f = os.path.join(root, url)
    if not os.path.isfile(f):
        return m.group(0)
    new = hashlib.md5(open(f, 'rb').read()).hexdigest()
    if new != rev:
        changed += 1
    return '{url:"%s",revision:"%s"}' % (url, new)
s = re.sub(r'\{url:"([^"]*)",revision:"([^"]*)"\}', fix, s)
open(p, 'w', encoding='utf-8').write(s)
print('revisões atualizadas:', changed)
