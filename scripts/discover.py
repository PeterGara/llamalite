#!/usr/bin/env python3
# ============================================================
#  discover.py - najde vsetky .gguf modely na sdcard
#  - rozlisi hlavny model od mmproj (podla nazvu)
#  - ku kazdemu modelu automaticky priradi mmproj (rovnaka zlozka)
#  - zisti ci je subor platny GGUF (hlavicka) a jeho velkost
#
#  Ako kniznica:   discover.discover(cfg) -> [ {...}, ... ]
#  Z CLI:          python3 discover.py settings.json   -> JSON na stdout
# ============================================================
import os, json, sys

MMPROJ_HINTS = ("mmproj", "projector")


def header_ok(path):
    try:
        with open(path, "rb") as f:
            return f.read(4) == b"GGUF"
    except Exception:
        return False


def is_mmproj(name):
    n = name.lower()
    return any(h in n for h in MMPROJ_HINTS)


def tokens(name):
    base = os.path.splitext(name)[0].lower()
    out, cur = [], ""
    for ch in base:
        if ch.isalnum():
            cur += ch
        else:
            if cur:
                out.append(cur)
            cur = ""
    if cur:
        out.append(cur)
    return out


def match_score(model_name, mm_name):
    a, b = set(tokens(model_name)), set(tokens(mm_name))
    common = a & b
    return sum(len(t) for t in common)


def walk_gguf(dirs, maxdepth=3):
    found = []
    seen_files = set()
    seen_dirs = set()
    for d in dirs:
        d = os.path.normpath(d)
        if not os.path.isdir(d) or d in seen_dirs:
            continue
        seen_dirs.add(d)
        base_depth = d.count("/")
        for root, subdirs, files in os.walk(d):
            root = os.path.normpath(root)
            depth = root.count("/") - base_depth
            if depth >= maxdepth:
                subdirs[:] = []
            for fn in files:
                if fn.lower().endswith(".gguf"):
                    p = os.path.normpath(os.path.join(root, fn))
                    if p not in seen_files:
                        seen_files.add(p)
                        found.append(p)
    return found


def make_id(name, used):
    stem = os.path.splitext(name)[0]
    safe = "".join(c if (c.isalnum() or c in "._-") else "_" for c in stem).strip("_")
    if not safe:
        safe = "model"
    cid = safe
    i = 2
    while cid in used:
        cid = "%s_%d" % (safe, i)
        i += 1
    used.add(cid)
    return cid


def discover(cfg):
    dirs = cfg.get("scan_dirs") or ["/sdcard/Download"]
    depth = int(cfg.get("scan_depth", 3))
    files = walk_gguf(dirs, depth)
    mm = [f for f in files if is_mmproj(os.path.basename(f))]
    mains = [f for f in files if not is_mmproj(os.path.basename(f))]

    items, used = [], set()
    for m in sorted(mains):
        d = os.path.dirname(m)
        b = os.path.basename(m)
        cand = [x for x in mm if os.path.dirname(x) == d]
        best, bscore = "", -1
        for c in cand:
            sc = match_score(b, os.path.basename(c))
            if sc > bscore:
                bscore, best = sc, c
        if bscore <= 0 and len(cand) == 1:
            best = cand[0]
        try:
            sz = os.path.getsize(m)
        except OSError:
            sz = 0
        mmp_sz = os.path.getsize(best) if best and os.path.exists(best) else 0
        items.append({
            "id": make_id(b, used),
            "name": b,
            "path": m,
            "dir": d,
            "size_mb": round(sz / 1e6, 1),
            "broken": not header_ok(m),
            "mmproj": best,
            "mmproj_name": os.path.basename(best) if best else "",
            "mmproj_mb": round(mmp_sz / 1e6, 1),
            "vision": bool(best),
        })
    return items


if __name__ == "__main__":
    cfg = json.load(open(sys.argv[1], encoding="utf-8"))
    print(json.dumps(discover(cfg), ensure_ascii=False, indent=2))