import http.server, os, re, socketserver, sys
os.chdir(sys.argv[1])
class H(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self): self.serve(True)
    def do_HEAD(self): self.serve(False)
    def serve(self, body):
        path = self.translate_path(self.path)
        if not os.path.isfile(path): self.send_error(404); return
        size = os.path.getsize(path); a, b, code = 0, size - 1, 200
        m = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
        if m:
            if m.group(1) == "": a = max(0, size - int(m.group(2)))
            else:
                a = int(m.group(1))
                if m.group(2): b = min(size - 1, int(m.group(2)))
            code = 206
        self.send_response(code)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(b - a + 1))
        if code == 206: self.send_header("Content-Range", "bytes %d-%d/%d" % (a, b, size))
        self.end_headers()
        if body:
            with open(path, "rb") as f:
                f.seek(a); left = b - a + 1
                try:
                    while left > 0:
                        d = f.read(min(65536, left))
                        if not d: break
                        self.wfile.write(d); left -= len(d)
                except Exception: pass
class S(socketserver.ThreadingMixIn, http.server.HTTPServer): daemon_threads = True
S(("127.0.0.1", 8765), H).serve_forever()
