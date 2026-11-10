#!/usr/bin/env python3
# ============================================================
#  mkcmd.py - zlucni defaulty + override pre dany model a
#             zapise cely argv pre llama-server.
#
#  Pouzitie:  python3 mkcmd.py settings.json <id|/cesta/model.gguf> <state_dir>
#
#  Vystup na stdout (na eval v bash):  MODEL, MMPROJ, ALIAS, BIN, HOST, PORT
#  Subor <state_dir>/argv : jeden argument na riadok
# ============================================================
import json, os, sys, shlex

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import discover  # noqa: E402


def q(s):
    return shlex.quote(str(s))


def add(args, flag, val):
    if val is None or val == "":
        return
    args.append(flag)
    args.append(str(val))


def main():
    cfgp, mid, state = sys.argv[1], sys.argv[2], sys.argv[3]
    cfg = json.load(open(cfgp, encoding="utf-8"))

    # --- 1) najdi model ---
    if os.path.isfile(mid) and mid.lower().endswith(".gguf"):
        mpath = mid
    else:
        it = next((x for x in discover.discover(cfg) if x["id"] == mid), None)
        if not it:
            sys.stderr.write("CHYBA: neznamy model '%s'\n" % mid)
            sys.exit(2)
        mpath = it["path"]

    if not os.path.isfile(mpath):
        sys.stderr.write("CHYBA: model neexistuje: %s\n" % mpath)
        sys.exit(3)
    if not discover.header_ok(mpath):
        sys.stderr.write("CHYBA: '%s' nie je platny GGUF (poskodeny/nekompletny).\n" % mpath)
        sys.exit(4)

    # --- 2) zluc nastavenia ---
    s = dict(cfg.get("defaults", {}))
    s.update(cfg.get("overrides", {}).get(mid, {}))

    # --- 3) mmproj ---
    mm = s.get("mmproj", "auto")
    mmproj_path = ""
    if mm and mm != "off":
        if mm == "auto":
            it = next((x for x in discover.discover(cfg) if x["path"] == mpath), None)
            mmproj_path = (it or {}).get("mmproj", "")
        else:
            mmproj_path = mm if os.path.isfile(mm) else ""

    # --- 4) argv ---
    a = []
    a += ["-m", mpath]
    if mmproj_path:
        a += ["--mmproj", mmproj_path]
    add(a, "-c", s.get("ctx", 16384))
    add(a, "-t", s.get("threads", 4))
    add(a, "-tb", s.get("threads_batch", ""))
    add(a, "-b", s.get("batch", ""))
    add(a, "-ub", s.get("ubatch", ""))
    add(a, "-np", s.get("parallel", 1))
    add(a, "-ctk", s.get("ctk", "q8_0"))
    add(a, "-ctv", s.get("ctv", "q8_0"))
    add(a, "--flash-attn", s.get("flash_attn", "on"))
    a += ["--repack"] if s.get("repack", True) else ["--no-repack"]
    add(a, "-lm", s.get("load_mode", "mmap+mlock"))

    if mmproj_path:
        add(a, "--image-min-tokens", s.get("image_min_tokens", ""))

    # reasoning
    r = s.get("reasoning", "off")
    if r in ("on", "off", "auto"):
        add(a, "--reasoning", r)
    if r == "on":
        add(a, "--reasoning-format", s.get("reasoning_format", "deepseek"))
    add(a, "--reasoning-budget", s.get("reasoning_budget", ""))

    # sampling defaults servera
    add(a, "--temp", s.get("temp", ""))
    add(a, "--top-k", s.get("top_k", ""))
    add(a, "--top-p", s.get("top_p", ""))
    add(a, "--min-p", s.get("min_p", ""))
    add(a, "--repeat-penalty", s.get("repeat_penalty", ""))
    add(a, "-s", s.get("seed", ""))

    # ostatne
    a += ["--no-warmup"] if s.get("no_warmup", True) else ["--warmup"]
    a += ["--no-jinja"] if not s.get("jinja", True) else ["--jinja"]
    add(a, "--chat-template-file", s.get("chat_template_file", ""))

    alias = s.get("alias") or (mid if not os.path.isfile(mid) else os.path.splitext(os.path.basename(mpath))[0])
    add(a, "--alias", alias)
    a += ["--host", str(s.get("host", "127.0.0.1")), "--port", str(s.get("port", 8080))]

    # volny text - rozbijeme ako shell
    extra = (s.get("extra_args") or "").strip()
    if extra:
        a += shlex.split(extra)

    os.makedirs(state, exist_ok=True)
    with open(os.path.join(state, "argv"), "w", encoding="utf-8") as f:
        f.write("\n".join(a))

    print("MODEL=%s" % q(mpath))
    print("MMPROJ=%s" % q(mmproj_path))
    print("ALIAS=%s" % q(alias))
    print("BIN=%s" % q(cfg.get("bin", "/root/llama/llama-b11960")))
    print("HOST=%s" % q(s.get("host", "127.0.0.1")))
    print("PORT=%s" % q(s.get("port", 8080)))


if __name__ == "__main__":
    main()