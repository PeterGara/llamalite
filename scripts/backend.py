#!/usr/bin/env python3
# ============================================================
#  backend.py - llamalite server (v2, multimodalny)
#  * servuje index.html
#  * API: start/stop modelu (cely argv), settings, scan, medzery
#  * API: /upload (obrazok/audio/video -> base64 pre chat)
#  * API: /tools/convert_video (ffmpeg -> snimky + audio wav)
#  * API: /proxy (obchadza CORS: preposle chat request na llama-server)
#
#  Spustenie:  python3 backend.py   (PORT=8181)
# ============================================================
import http.server, socketserver, json, os, subprocess, sys, urllib.parse, urllib.request, urllib.error, shutil

HERE  = os.path.dirname(os.path.abspath(__file__))
ROOT  = os.path.dirname(HERE)
STATE = os.path.join(ROOT, ".state")
UPLOADS = os.path.join(ROOT, ".state", "uploads")
CFG   = os.path.join(HERE, "settings.json")
LOG   = os.path.join(STATE, "server.log")
PIDF  = os.path.join(STATE, "server.pid")
PORT  = int(os.environ.get("PORT", "8181"))
os.makedirs(UPLOADS, exist_ok=True)

IMG_EXT = {".jpg",".jpeg",".png",".webp",".gif",".bmp"}
AUD_EXT = {".wav",".mp3",".ogg",".opus",".flac",".m4a",".aac"}
VID_EXT = {".mp4",".mkv",".mov",".webm",".avi",".m4v",".3gp"}


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


def meminfo():
    out = {}
    try:
        for ln in open("/proc/meminfo"):
            k, v = ln.split(":", 1)
            out[k] = int(v.strip().split()[0]) // 1024  # MB
    except Exception:
        pass
    return {"total_mb": out.get("MemTotal", 0), "free_mb": out.get("MemFree", 0),
            "avail_mb": out.get("MemAvailable", 0), "swap_total_mb": out.get("SwapTotal", 0),
            "swap_free_mb": out.get("SwapFree", 0)}


def start_model(mid):
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
    with open(os.path.join(STATE, "argv")) as f:
        args = [x for x in f.read().split("\n") if x != ""]
    binp = env.get("BIN", "/root/llama/llama-b11960")
    exe = os.path.join(binp, "llama-server")
    if not os.path.isfile(exe):
        return False, "binarka neexistuje: %s" % exe
    with open(LOG, "w") as lg:
        proc = subprocess.Popen([exe] + args, cwd=binp, stdout=lg, stderr=subprocess.STDOUT,
                                stdin=subprocess.DEVNULL, start_new_session=True)
    with open(PIDF, "w") as f:
        f.write(str(proc.pid))
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
    try:
        prof = subprocess.run([sys.executable, os.path.join(HERE, "discover.py"), CFG],
                              capture_output=True, text=True, timeout=120)
        profiles = json.loads(prof.stdout) if prof.returncode == 0 else []
    except Exception:
        profiles = []
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
    return {"profiles": profiles, "running": bool(pid) and bool(cur), "pid": pid,
            "current": cur, "mem": meminfo()}


def classify(name):
    ext = os.path.splitext(name)[1].lower()
    if ext in IMG_EXT:
        return "image"
    if ext in AUD_EXT:
        return "audio"
    if ext in VID_EXT:
        return "video"
    return "other"


def save_upload(name, data):
    safe = "".join(c if (c.isalnum() or c in "._-") else "_" for c in os.path.basename(name))
    p = os.path.join(UPLOADS, safe)
    i = 2
    while os.path.exists(p):
        stem, ext = os.path.splitext(safe)
        p = os.path.join(UPLOADS, "%s_%d%s" % (stem, i, ext)); i += 1
    with open(p, "wb") as f:
        f.write(data)
    return p


def convert_audio(path):
    """Prevedie lubovolny audio na 16k mono wav (llama-server prijima toto)."""
    if not shutil.which("ffmpeg"):
        return ""
    out = os.path.splitext(path)[0] + "_16k.wav"
    try:
        subprocess.run(["ffmpeg", "-y", "-i", path, "-vn", "-ac", "1", "-ar", "16000", out],
                       capture_output=True, text=True, timeout=180)
        if os.path.exists(out) and os.path.getsize(out) > 44:
            return out
    except Exception:
        pass
    return ""


def convert_video(path, max_frames=6, audio=True):
    """ffmpeg: vytiahne rovnomerne snimky (jpg) + audio (16k mono wav)."""
    out = {"frames": [], "audio": "", "error": ""}
    if not shutil.which("ffmpeg"):
        out["error"] = "ffmpeg nie je nainstalovany"
        return out
    base = os.path.splitext(path)[0]
    try:
        # trvanie
        pr = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                             "-of", "default=nk=1:nw=1", path], capture_output=True, text=True, timeout=30)
        dur = float((pr.stdout or "0").strip() or 0)
    except Exception:
        dur = 0
    # snimky
    try:
        if dur > 0 and max_frames > 1:
            step = max(dur / max_frames, 0.1)
            pattern = "%s_f%%02d.jpg" % base
            subprocess.run(["ffmpeg", "-y", "-i", path, "-vf", "fps=1/%f,scale=768:-1" % step,
                            "-frames:v", str(max_frames), pattern],
                           capture_output=True, text=True, timeout=120)
        else:
            pattern = "%s_f%%02d.jpg" % base
            subprocess.run(["ffmpeg", "-y", "-i", path, "-vf", "scale=768:-1",
                            "-frames:v", str(max_frames), pattern],
                           capture_output=True, text=True, timeout=120)
        d = os.path.dirname(path)
        stem = os.path.basename(base) + "_f"
        for fn in sorted(os.listdir(d)):
            if fn.startswith(stem) and fn.endswith(".jpg"):
                out["frames"].append(os.path.join(d, fn))
    except Exception as e:
        out["error"] += "snimky: %s " % e
    # audio
    if audio:
        try:
            ap = base + "_audio.wav"
            subprocess.run(["ffmpeg", "-y", "-i", path, "-vn", "-ac", "1", "-ar", "16000", ap],
                           capture_output=True, text=True, timeout=180)
            if os.path.exists(ap) and os.path.getsize(ap) > 44:
                out["audio"] = ap
        except Exception as e:
            out["error"] += "audio: %s " % e
    return out


class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _raw(self, code, b, ctype):
        if isinstance(b, str):
            b = b.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _json(self, code, obj):
        self._raw(code, json.dumps(obj, ensure_ascii=False), "application/json; charset=utf-8")

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        path, qs = u.path, urllib.parse.parse_qs(u.query)

        if path in ("/", "/index.html"):
            f = os.path.join(HERE, "index.html")
            return self._raw(200, open(f, "rb").read(), "text/html; charset=utf-8") \
                if os.path.exists(f) else self._raw(404, "index.html chyba", "text/plain")

        if path == "/flags.json":
            f = os.path.join(HERE, "flags.json")
            return self._raw(200, open(f, "rb").read(), "application/json; charset=utf-8") \
                if os.path.exists(f) else self._json(404, {"error": "flags.json chyba"})

        if path == "/api/health":
            return self._json(200, {"status": "ok", "mem": meminfo()})

        if path == "/api/list":
            return self._json(200, state_info())

        if path == "/api/scan":
            return self._json(200, {"profiles": state_info()["profiles"]})

        if path == "/api/status":
            return self._json(200, {"running": bool(is_running()), "mem": meminfo()})

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
            return self._json(200, {"status": "ok", "killed": n, "mem": meminfo()})

        if path == "/api/log":
            try:
                txt = "\n".join(open(LOG, encoding="utf-8", errors="replace").read().splitlines()[-80:])
            except Exception:
                txt = "(ziadny log)"
            return self._json(200, {"log": txt})

        if path == "/api/file":
            # servuje uploadnuty subor (na nahlad)
            p = (qs.get("path") or [""])[0]
            p = os.path.normpath(p)
            if p.startswith(UPLOADS) and os.path.isfile(p):
                ext = os.path.splitext(p)[1].lower()
                ct = "application/octet-stream"
                if ext in (".jpg", ".jpeg"): ct = "image/jpeg"
                elif ext == ".png": ct = "image/png"
                elif ext == ".webp": ct = "image/webp"
                elif ext in (".wav",): ct = "audio/wav"
                elif ext in (".mp3",): ct = "audio/mpeg"
                elif ext in (".mp4",): ct = "video/mp4"
                return self._raw(200, open(p, "rb").read(), ct)
            return self._raw(404, "neexistuje", "text/plain")

        return self._json(404, {"status": "error", "error": "not found"})

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        path = u.path
        ln = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(ln) if ln else b""

        if path == "/api/settings":
            try:
                obj = json.loads(raw.decode("utf-8"))
                if "defaults" not in obj:
                    raise ValueError("chyba 'defaults'")
                write_json_atomic(CFG, obj)
                return self._json(200, {"status": "ok"})
            except Exception as e:
                return self._json(400, {"status": "error", "error": str(e)})

        if path == "/upload":
            # hlavicka: X-Filename (URL-encoded)
            name = urllib.parse.unquote(self.headers.get("X-Filename", "upload.bin"))
            if not raw:
                return self._json(400, {"status": "error", "error": "prazdny subor"})
            p = save_upload(name, raw)
            kind = classify(name)
            res = {"status": "ok", "path": p, "name": os.path.basename(p),
                   "kind": kind, "size": len(raw)}
            if kind == "audio" and os.path.splitext(name)[1].lower() != ".wav":
                conv = convert_audio(p)
                if conv:
                    res["converted"] = conv
            if kind == "video":
                conv = convert_video(p, max_frames=6, audio=True)
                res["frames"] = [{"path": f, "name": os.path.basename(f)} for f in conv["frames"]]
                res["audio"] = conv["audio"]
                if conv["error"]:
                    res["convert_error"] = conv["error"]
            return self._json(200, res)

        if path == "/api/convert_video":
            try:
                obj = json.loads(raw.decode("utf-8"))
                p = obj.get("path", "")
                if not (os.path.normpath(p).startswith(UPLOADS) and os.path.isfile(p)):
                    return self._json(400, {"status": "error", "error": "neplatna cesta"})
                conv = convert_video(p, int(obj.get("max_frames", 6)), bool(obj.get("audio", True)))
                return self._json(200, {"status": "ok", "frames": conv["frames"],
                                        "audio": conv["audio"], "error": conv["error"]})
            except Exception as e:
                return self._json(400, {"status": "error", "error": str(e)})

        if path == "/proxy":
            # telo = JSON pre llama-server; hlavicka X-Target = cielova URL
            target = self.headers.get("X-Target", "")
            if not target.startswith("http://127.0.0.1"):
                return self._json(400, {"status": "error", "error": "neplatny target (len 127.0.0.1)"})
            req = urllib.request.Request(target, data=raw, method="POST",
                                         headers={"Content-Type": "application/json"})
            try:
                resp = urllib.request.urlopen(req, timeout=600)
                # streamni odpoved
                self.send_response(resp.status)
                self.send_header("Content-Type", resp.headers.get("Content-Type", "application/json"))
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                while True:
                    chunk = resp.read(2048)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            except urllib.error.HTTPError as e:
                body = e.read()
                self._raw(e.code, body, "application/json")
            except Exception as e:
                self._json(502, {"status": "error", "error": str(e)})
            return

        return self._json(404, {"status": "error", "error": "not found"})


class S(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    print("llamalite backend (v2) na http://127.0.0.1:%d" % PORT, flush=True)
    S(("0.0.0.0", PORT), H).serve_forever()