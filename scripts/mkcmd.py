#!/usr/bin/env python3
# ============================================================
#  mkcmd.py - zlozi argv pre llama-server z settings.json
#
#  settings.json:
#    {
#      "bin": "...",
#      "defaults":  { "<flag_key>": <value>, ... },
#      "overrides": { "<model_id>": { "<flag_key>": <value> } }
#    }
#
#  <flag_key> JE nazov prepinaca llama-servera BEZ uvodnych pomlciek,
#  napr. "ctx-size", "threads", "cache-type-k", "flash-attn", "repack".
#  Hodnota:
#    true  -> --flag            (zapnute)
#    false -> vynecha sa        (vypnute)
#    cislo -> --flag CISLO
#    text  -> --flag TEXT
#  Specialne (spracuje tento skript, nie llama-server):
#    "mmproj": "auto" | "off" | "<cesta>"   -> --mmproj ...
#    "extra_args": "volny shell text"
#
#  Pouzitie:  python3 mkcmd.py settings.json <id|/cesta.gguf> <state_dir>
#  Vystup:    MODEL, MMPROJ, ALIAS, BIN, HOST, PORT  (na eval)
#             <state_dir>/argv  (1 argument na riadok)
# ============================================================
import json, os, sys, shlex

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import discover  # noqa: E402

# kluce, ktore sa NEPOSIELAJU llama-serveru priamo
INTERNAL = {"extra_args", "mmproj", "alias", "bin", "server_url", "host", "port"}

# kluce, ktorych hodnota je "negativny" prepinac (true=zapnut, false=vypnut)
# format: key -> (flag_true, flag_false or None)
NEG_FLAGS = {
    "repack":          ("repack", "no-repack"),
    "warmup":          ("warmup", "no-warmup"),
    "jinja":           ("jinja", "no-jinja"),
    "kv-offload":      ("kv-offload", "no-kv-offload"),
    "context-shift":   ("context-shift", "no-context-shift"),
    "mmproj-auto":     ("mmproj-auto", "no-mmproj-auto"),
    "mmproj-offload":  ("mmproj-offload", "no-mmproj-offload"),
    "op-offload":      ("op-offload", "no-op-offload"),
    "cont-batching":   ("cont-batching", "no-cont-batching"),
    "metrics":         ("metrics", None),
    "swa-full":        ("swa-full", None),
}


def q(s):
    return shlex.quote(str(s))


def build_flags(settings):
    """Z settings dict -> zoznam argumentov pre llama-server (bez -m/--mmproj/host/port)."""
    args = []
    for key, val in settings.items():
        if key in INTERNAL:
            continue
        if val is None or val == "":
            continue
        if key in NEG_FLAGS:
            tflag, fflag = NEG_FLAGS[key]
            if val is True:
                args.append("--" + tflag)
            elif val is False and fflag:
                args.append("--" + fflag)
            continue
        if val is True:
            args.append("--" + key)
        elif val is False:
            # obecne: false = prepinac vynechat
            continue
        else:
            args.append("--" + key)
            args.append(str(val))
    return args


def main():
    cfgp, mid, state = sys.argv[1], sys.argv[2], sys.argv[3]
    cfg = json.load(open(cfgp, encoding="utf-8"))

    # 1) najdi model
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

    # 2) zluc defaults + override
    s = dict(cfg.get("defaults", {}))
    s.update(cfg.get("overrides", {}).get(mid, {}))

    # 3) mmproj
    mm = s.get("mmproj", "auto")
    mmproj_path = ""
    if mm and mm != "off":
        if mm == "auto":
            it = next((x for x in discover.discover(cfg) if x["path"] == mpath), None)
            mmproj_path = (it or {}).get("mmproj", "")
        elif os.path.isfile(mm):
            mmproj_path = mm
        else:
            mmproj_path = ""

    # 4) poskladaj argv
    a = ["-m", mpath]
    if mmproj_path:
        a += ["--mmproj", mmproj_path]
    a += build_flags(s)
    extra = (s.get("extra_args") or "").strip()
    if extra:
        a += shlex.split(extra)

    alias = s.get("alias") or (mid if not os.path.isfile(mid)
                               else os.path.splitext(os.path.basename(mpath))[0])
    if alias:
        a += ["--alias", str(alias)]
    host = str(s.get("host") or cfg.get("host", "127.0.0.1"))
    port = str(s.get("port") or cfg.get("port", 8080))
    a += ["--host", host, "--port", port]

    os.makedirs(state, exist_ok=True)
    with open(os.path.join(state, "argv"), "w", encoding="utf-8") as f:
        f.write("\n".join(a))

    print("MODEL=%s" % q(mpath))
    print("MMPROJ=%s" % q(mmproj_path))
    print("ALIAS=%s" % q(alias))
    print("BIN=%s" % q(cfg.get("bin", "/root/llama/llama-b11960")))
    print("HOST=%s" % q(host))
    print("PORT=%s" % q(port))


if __name__ == "__main__":
    main()