# llamalite

Lokálny chat s **llama.cpp** modelmi na telefóne — **bez Operitu** a bez obrovského
systémového promptu. Naskenuje **všetky** `.gguf` na sdcard, ku každému modelu
automaticky priradí `mmproj` a cez jednoduchý web nastavíš **všetko** (kontext,
mmap/mlock, repack, flash-attn, KV cache, reasoning, sampling, sloty, batch…).

## Prečo
Operit posiela na začiatku každého requestu veľký systémový prompt + definície
nástrojov + pamäť (2000–4000 tokenov). Na CPU-only telefóne to zabije prefill
(desiatky sekúnd) a malý model sa „zasekne“. llamalite pošle len to, čo napíšeš.

## Dve možnosti
1. **Vstavané WebUI llama-servera** — engine b11960 ho má. Po štarte servera
   otvor v prehliadači `http://127.0.0.1:8080` (chat + nastavenia + streaming).
2. **llamalite** (`scripts/`) — minimalistický chat + celý panel nastavení,
   prepínanie modelov, štart/stop servera, log. Batériová verzia (bez repacku
   by bol prefill pomalý, ale to necháva na tebe).

## Rýchly štart
```bash
# web (backend + UI) na porte 8181
bash serve.sh

# v prehliadači otvor:  http://127.0.0.1:8181
#   → vyber model v selecte → ▶ Start → píš v chate

# zastaviť
bash stop-web.sh
```

Web ťa pustí aj k serveru — pozri taby **Chat / Nastavenia / Log**.

## Ako to funguje
```
serve.sh ─► scripts/backend.py  (HTTP server + API, port 8181)
                 │
                 ├─ /api/scan      discover.py  → nájde .gguf modely + mmproj
                 ├─ /api/start     mkcmd.py zloží argv → spustí llama-server
                 ├─ /api/settings  čítanie/zápis settings.json
                 └─ /            index.html (UI)

llama-server (port 8080)  ◄── /v1/chat/completions ◄── index.html (chat)
```

## Súbory
```
llamalite/
  serve.sh              spustí backend (port 8181)
  stop-web.sh           zastaví backend + llama-server
  scripts/
    index.html          UI (chat, model picker, celý panel nastavení, log)
    backend.py          HTTP server + API (scan/start/stop/settings/log)
    discover.py         sken sdcard → modely + auto-priradenie mmproj
    mkcmd.py            settings.json → argv pre llama-server
    settings.json       všetky nastavenia (defaults + overrides per model)
  android/              zdroják tenkej natívnej apky (Kotlin + WebView)
  .github/workflows/    build APK na GitHube (Actions)
  .state/               logy + pid (negitovať)
```

## Nastavenia (settings.json → tab „Nastavenia“)
Všetko sa ukladá do `scripts/settings.json`. Kľúčová časť `defaults`:

| skupina | kľúč → flag | popis |
|---|---|---|
| model | `mmproj` (`auto`/`off`/cesta), `image_min_tokens` | vision projektor |
| pamäť | `load_mode` (`auto`/`mmap`/`mlock`/`mmap+mlock`), `repack` (bool) | načítanie, ARM dotprod |
| výkon | `ctx`(`-c`), `threads`(`-t`), `threads_batch`(`-tb`), `batch`(`-b`), `ubatch`(`-ub`), `parallel`(`-np`) | kontext a dávky |
| KV cache | `ctk`, `ctv` | typ KV cache (f16/q8_0/q4_0/…) |
| flash | `flash_attn` (`on`/`off`/`auto`) | flash attention |
| reasoning | `reasoning` (`on`/`off`/`auto`), `reasoning_format`, `reasoning_budget` | myslenie |
| sampling | `temp`, `top_k`, `top_p`, `min_p`, `repeat_penalty`, `seed` | defaulty servera |
| server | `host`, `port`, `jinja`, `no_warmup` (bool), `chat_template_file`, `extra_args` | ostatné |

**`overrides`** = per-model nastavenia podľa `id` (id sa odvodí z názvu súboru
bez `.gguf`). Tam sa dajú prepísať ľubovoľné polia pre konkrétny model.
**`extra_args`** = voľný text, rozdelí sa ako shell → čokoľvek, čo llama-server
podporuje a nie je vo formulári (`--swa-full`, `--context-shift`, `--no-jinja`…).

## Modely
Sken prejde `scan_dirs` (default `/sdcard/Download` + `Operit/models…`, hĺbka 3)
a nájde každý `.gguf`. Súbory s `mmproj`/`projector` v názve berie ako projektory
a priradí ich k modelu v tej istej zložke (podľa spoločných tokenov v názve).
Každý model dostane `id` (názov bez prípony) — ten použi v `overrides`.

> ⚠ Ak hlavička súboru nie je `GGUF`, model je označený `[POSKODENY]` a `mkcmd.py`
> ho odmietne spustiť. Typicky nedokončený download.

## achillova pata telefónu: RAM
- Model + `-c 16384` na Qwen3.5 (hybrid) = iba ~544 MB KV (KV rastie s pár
  full-attention vrstvami). Bežné 4–9B modely majú KV drahšie → drž `ctx` nižšie.
- `mmap+mlock` drží model v RAM (rýchle, ale žerie). `mmap` šetrí RAM (stranky z disku).
- `repack on` zrýchli prefill ~2,7× (dlhší štart ~14 s, viac RAM).

## Android apka
`android/` = tenký **WebView** shell (Kotlin). Otvorí `http://127.0.0.1:8181`.
Adresu zmeníš long-pressom (uloží sa do prefs) — vieš tam dať aj
`http://127.0.0.1:8080` (vstavané WebUI). Build na GitHube cez `.github/workflows/build-apk.yml`.

## Licencia
MIT — viz `LICENSE`.