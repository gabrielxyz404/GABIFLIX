#!/usr/bin/env python3
"""Servidor de prova de uma rede LENTA e realista (só no CI): imita um link de debrid (redirecionamento 302 + latência por pedido) ou o servidor de
streaming do Stremio a buscar peças de um torrent.
  - GET /film.mkv  -> 302 para /cdn/<token>/film.mkv (como um link direto de debrid)
  - cada pedido (HEAD/GET, com ou sem Range) espera LAT_MIN..LAT_MAX ms antes de responder (sorteio com semente fixa)
  - a velocidade de TODAS as ligações juntas é limitada a RATE_KBPS (kB/s): o player e a captura de áudio disputam a mesma rede
  - outros arquivos (a legenda) saem sem espera
  - REPORT_PORT: um segundo servidor que só guarda o que recebe por POST (o relatório ANALISE que o app manda ao ntfy) em REPORTS/NNN.txt
Registro de cada pedido em LOG: "epoch inicio=.. fim=.. range=a-b enviado=n latencia_ms=..".
Uso: lento.py PASTA PORTA   (RATE_KBPS=1500 LAT_MIN_MS=1000 LAT_MAX_MS=3000 REDIRECT=1 REPORT_PORT=11481 LOG=/tmp/lento.log REPORTS=/tmp/reports)"""
import os, random, re, sys, threading, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

ROOT = sys.argv[1]
PORT = int(sys.argv[2])
RATE = float(os.environ.get('RATE_KBPS', '1500')) * 1024
LAT_MIN = float(os.environ.get('LAT_MIN_MS', '1000')) / 1000.0
LAT_MAX = float(os.environ.get('LAT_MAX_MS', '3000')) / 1000.0
REDIRECT = os.environ.get('REDIRECT', '1') == '1'
LOG = os.environ.get('LOG', '/tmp/lento.log')
REPORT_PORT = int(os.environ.get('REPORT_PORT', '0'))
REPORTS = os.environ.get('REPORTS', '/tmp/reports')
SLOW_NAMES = set(os.environ.get('SLOW_FILES', 'film.mkv').split(','))   # só estes arquivos são lentos

rng = random.Random(42)
rng_lock = threading.Lock()
rate_lock = threading.Lock()
rate_next = [0.0]
log_lock = threading.Lock()
total = {'bytes': 0, 'reqs': 0}


def throttle(n):
    with rate_lock:
        now = time.time()
        t = max(rate_next[0], now)
        rate_next[0] = t + n / RATE
    if t > now:
        time.sleep(t - now)


def note(s):
    with log_lock:
        with open(LOG, 'a') as f:
            f.write('%.3f %s\n' % (time.time(), s))


class H(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def do_HEAD(self):
        self.serve(False)

    def do_GET(self):
        if self.path.startswith('/__stats'):
            body = ('bytes=%d pedidos=%d\n' % (total['bytes'], total['reqs'])).encode()
            self.send_response(200); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
            return
        self.serve(True)

    def serve(self, body):
        t0 = time.time()
        path = self.path.split('?')[0]
        name = os.path.basename(path)
        p = os.path.join(ROOT, name)
        if not os.path.isfile(p):
            self.send_response(404); self.send_header('Content-Length', '0'); self.end_headers(); return
        slow = name in SLOW_NAMES
        lat = 0.0
        if slow:
            with rng_lock:
                lat = rng.uniform(LAT_MIN, LAT_MAX)
            time.sleep(lat)
        if slow and REDIRECT and not path.startswith('/cdn/'):
            self.send_response(302)
            self.send_header('Location', 'http://127.0.0.1:%d/cdn/t0k3n-%d/%s' % (PORT, int(t0), name))
            self.send_header('Content-Length', '0')
            self.end_headers()
            note('redireciona %s latencia_ms=%d' % (name, lat * 1000))
            return
        size = os.path.getsize(p)
        a, b = 0, size - 1
        rng_h = self.headers.get('Range')
        code = 200
        if rng_h:
            m = re.match(r'bytes=(\d*)-(\d*)', rng_h)
            if m:
                if m.group(1): a = int(m.group(1))
                if m.group(2): b = int(m.group(2))
                if not m.group(1) and m.group(2): a = max(0, size - int(m.group(2))); b = size - 1
                b = min(b, size - 1)
                code = 206
        n = b - a + 1
        self.send_response(code)
        self.send_header('Content-Type', 'video/x-matroska' if name.endswith('.mkv') else 'text/plain; charset=utf-8')
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(n))
        if code == 206: self.send_header('Content-Range', 'bytes %d-%d/%d' % (a, b, size))
        self.end_headers()
        if not body:
            return
        sent = 0
        try:
            with open(p, 'rb') as f:
                f.seek(a)
                while sent < n:
                    chunk = f.read(min(32768 if slow else 262144, n - sent))
                    if not chunk: break
                    if slow: throttle(len(chunk))
                    self.wfile.write(chunk); sent += len(chunk)
        except Exception:
            pass
        with log_lock:
            total['bytes'] += sent if slow else 0
            total['reqs'] += 1 if slow else 0
        if slow:
            note('%s range=%d-%d enviado=%d inicio=%.3f latencia_ms=%d duracao_s=%.2f' % (name, a, b, sent, t0, lat * 1000, time.time() - t0))


class R(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    n = [0]

    def log_message(self, *a):
        pass

    def do_POST(self):
        ln = int(self.headers.get('Content-Length', '0') or 0)
        data = self.rfile.read(ln) if ln else b''
        os.makedirs(REPORTS, exist_ok=True)
        with log_lock:
            R.n[0] += 1
            k = R.n[0]
        with open(os.path.join(REPORTS, '%03d.txt' % k), 'wb') as f:
            f.write(('# epoch=%.3f titulo=%s\n' % (time.time(), self.headers.get('Title', ''))).encode() + data)
        self.send_response(200); self.send_header('Content-Length', '2'); self.end_headers(); self.wfile.write(b'{}')


if REPORT_PORT:
    threading.Thread(target=lambda: ThreadingHTTPServer(('127.0.0.1', REPORT_PORT), R).serve_forever(), daemon=True).start()
ThreadingHTTPServer(('127.0.0.1', PORT), H).serve_forever()
