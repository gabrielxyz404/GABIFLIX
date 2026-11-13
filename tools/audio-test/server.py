#!/usr/bin/env python3
"""Servidor HTTP com Range para os vídeos de teste; conta os bytes enviados por arquivo e por "sessão" (escreve em /tmp/server-bytes.log)."""
import os, re, sys, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

ROOT = sys.argv[1]
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8000
LOG = '/tmp/server-bytes.log'
lock = threading.Lock()
total = {}

def note(s):
    with lock:
        with open(LOG, 'a') as f:
            f.write('%.3f %s\n' % (time.time(), s))

class H(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *a): pass
    def do_HEAD(self): self.serve(False)
    def do_GET(self):
        if self.path.startswith('/__bytes'):
            with lock: body = ('\n'.join('%s %d' % kv for kv in total.items()) + '\n').encode()
            self.send_response(200); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body); return
        self.serve(True)
    def serve(self, body):
        name = os.path.basename(self.path.split('?')[0])
        p = os.path.join(ROOT, name)
        if not os.path.isfile(p):
            self.send_response(404); self.send_header('Content-Length', '0'); self.end_headers(); return
        size = os.path.getsize(p)
        a, b = 0, size - 1
        rng = self.headers.get('Range')
        code = 200
        if rng:
            m = re.match(r'bytes=(\d*)-(\d*)', rng)
            if m:
                if m.group(1): a = int(m.group(1))
                if m.group(2): b = int(m.group(2))
                if not m.group(1) and m.group(2): a = max(0, size - int(m.group(2))); b = size - 1
                b = min(b, size - 1)
                code = 206
        n = b - a + 1
        self.send_response(code)
        self.send_header('Content-Type', 'video/x-matroska' if name.endswith('.mkv') else 'application/octet-stream')
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(n))
        if code == 206: self.send_header('Content-Range', 'bytes %d-%d/%d' % (a, b, size))
        self.end_headers()
        if not body: return
        sent = 0
        try:
            with open(p, 'rb') as f:
                f.seek(a)
                while sent < n:
                    chunk = f.read(min(262144, n - sent))
                    if not chunk: break
                    self.wfile.write(chunk); sent += len(chunk)
        except Exception:
            pass
        with lock: total[name] = total.get(name, 0) + sent
        note('%s range=%d-%d sent=%d' % (name, a, b, sent))

ThreadingHTTPServer(('0.0.0.0', PORT), H).serve_forever()
