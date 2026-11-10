#!/usr/bin/env python3
# ============================================================
#  backend.py - llamalite server
#  * servuje index.html
#  * API pre start/stop lokalneho llama-servera (cely argv)
#  * API na citanie/zapis nastaveni (settings.json)
#  * API na skenovanie modelov (discover.py)
#
#  Pouzitie:  python3 backend.py           (port 8181, PORT=... )
#  Endpointy (GET, *.json):
#     /                       UI
#     /api/health
#     /api/list               {profiles, running, current}
#     /api/start?id=ID        start modelu
#     /api/stop
#     /api/status
#     /api/settings           aktualne settings.json
#     /api/scan               nacitane modely (discover)
#  Endpoint (POST):
#     /api/settings           ulozi novy settings.json (cely JSON)
# ============================================================
import http.server, socketserver, json, os, subprocess, sys, urllib.parse

HERE  = os.path.dirname(os.path.abspath(__file__))
ROOT  = os.path.dirname(HERE)
STATE = os.path.join(ROOT, ".state")
CFG   = os.path.join(HERE, "settings.json")
LOG   = os.path.join(STATE, "server.log")
PIDF  = os.path.join(STATE, "server.pid")
PORT  = int(os.environ.get("PORT", "8181"))
os.makedirs(STATE, exist_ok=True)


# ---------- pomocne ----------
def read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_json_atomic(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def load_cfg():
    return read_json(CFG)


def hdr_ok(p):
    try:
        with open(p, "rb") as f:
            return f.read(4) == b"GGUF"
    except Exception:
        return False


def kill_all_llama():
    n = 0
    try:
        for p in os.listdir("/proc"):
            if not p.isdigit():
                continue
            try:
                with open("/proc/%s/cmdline" % p, "rb") as f:
                    c = f.read().replace(b"\0", b" ").decode("utf-8", "replace")
            except Exception:
                continue
            if "llama-server" in c:
                try:
                    os.kill(int(p), 9); n += 1
                except Exception:
                    pass
    except Exception:
        pass
    return n


def is_running():
    try:
        with open(PIDF) as f:
            pid = int(f.read().strip())
    except Exception:
        return 0
    try:
        # stav procesu (R/S/D..., 'Z' = zombie)
        stat = open("/proc/%d/stat" % pid).read()
        state = stat.split(") ", 1)[1].split()[0]
        if state == "Z":
            return 0
        cl = open("/proc/%d/cmdline" % pid, "rb").read().replace(b"\0", b" ").decode("utf-8", "replace")
        if "llama-server" not in cl:
            return 0
        os.kill(pid, 0)
        return pid
    except Exception:
        return 0


def start_model(mid):
    cfg = load_cfg()
    kill_all_llama()
    import time; time.sleep(2)

    try:
        r = subprocess.run([sys.executable, os.path.join(HERE, "mkcmd.py"), CFG, mid, STATE],
                           capture_output=True, text=True, timeout=60)
    except Exception as e:
        return False, "mkcmd: %s" % e
    if r.returncode != 0:
        return False, (r.stderr or r.stdout).strip()
    env = {}
    for ln in r.stdout.splitlines():
        if "=" in ln:
            k, v = ln.split("=", 1)
            env[k] = v.strip("'\"")

    argvf = os.path.join(STATE, "argv")
    with open(argvf) as f:
        args = [x for x in f.read().split("\n") if x != ""]

    binp = env.get("BIN", "/root/llama/llama-b11960")
    exe = os.path.join(binp, "llama-server")
    if not os.path.isfile(exe):
        return False, "binarka neexistuje: %s" % exe

    with open(LOG, "w") as lg:
        proc = subprocess.Popen([exe] + args, cwd=binp,
                                stdout=lg, stderr=subprocess.STDOUT,
                                stdin=subprocess.DEVNULL, start_new_session=True)
    with open(PIDF, "w") as f:
        f.write(str(proc.pid))

    # cakaj na "listening on"
    import time
    ok = False
    for _ in range(180):
        time.sleep(1)
        try:
            if "listening on" in open(LOG, encoding="utf-8", errors="replace").read():
                ok = True; break
        except Exception:
            pass
        if proc.poll() is not None:
            break
    tail = ""
    try:
        tail = "\n".join(open(LOG, encoding="utf-8", errors="replace").read().splitlines()[-15:])
    except Exception:
        pass
    if ok:
        return True, "OK %s -> http://%s:%s" % (mid, env.get("HOST", "127.0.0.1"), env.get("PORT", "8080"))
    return False, tail or "server sa nenastartoval"


def state_info():
    cfg = load_cfg()
    try:
        prof = subprocess.run([sys.executable, os.path.join(HERE, "discover.py"), CFG],
                              capture_output=True, text=True, timeout=120)
        profiles = json.loads(prof.stdout) if prof.returncode == 0 else []
    except Exception:
        profiles = []
    # aplikuj nastavenia -> active flag podla toho, co bezi
    pid = is_running()
    cur = 0
    if pid and os.path.exists(os.path.join(STATE, "argv")):
        try:
            a = open(os.path.join(STATE, "argv")).read()
            for p in profiles:
                if p["path"] in a:
                    cur = p["id"]; break
        except Exception:
            pass
    return {"profiles": profiles, "running": bool(pid) and bool(cur), "pid": pid, "current": cur}


class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _html(self, code, data):
        if isinstance(data, str):
            data = data.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        path, qs = u.path, urllib.parse.parse_qs(u.query)

        if path in ("/", "/index.html"):
            f = os.path.join(HERE, "index.html")
            return self._html(200, open(f, "rb").read()) if os.path.exists(f) else self._html(404, "index.html chyba")

        if path == "/api/health":
            return self._json(200, {"status": "ok"})

        if path == "/api/list":
            return self._json(200, state_info())

        if path == "/api/scan":
            return self._json(200, {"profiles": state_info()["profiles"]})

        if path == "/api/status":
            return self._json(200, {"running": bool(is_running())})

        if path == "/api/settings":
            try:
                return self._json(200, load_cfg())
            except Exception as e:
                return self._json(500, {"error": str(e)})

        if path == "/api/start":
            mid = (qs.get("id") or [""])[0]
            if not mid:
                return self._json(400, {"status": "error", "error": "chyba id"})
            ok, out = start_model(mid)
            return self._json(200 if ok else 500, {"status": "ok" if ok else "error", "output": out})

        if path == "/api/stop":
            n = kill_all_llama()
            try: os.remove(PIDF)
            except Exception: pass
            return self._json(200, {"status": "ok", "killed": n})

        if path == "/api/log":
            try:
                txt = "\n".join(open(LOG, encoding="utf-8", errors="replace").read().splitlines()[-80:])
            except Exception:
                txt = "(ziadny log)"
            return self._json(200, {"log": txt})

        return self._json(404, {"status": "error", "error": "not found"})

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        if u.path != "/api/settings":
            return self._json(404, {"status": "error", "error": "not found"})
        try:
            ln = int(self.headers.get("Content-Length", 0) or 0)
            obj = json.loads(self.rfile.read(ln).decode("utf-8"))
            if "defaults" not in obj:
                raise ValueError("chyba 'defaults'")
            write_json_atomic(CFG, obj)
            return self._json(200, {"status": "ok"})
        except Exception as e:
            return self._json(400, {"status": "error", "error": str(e)})


class S(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    print("llamalite backend na http://127.0.0.1:%d" % PORT, flush=True)
    S(("0.0.0.0", PORT), H).serve_forever()