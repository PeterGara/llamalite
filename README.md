# llamalite

**Jedna Android apka** — lokálny chat s **llama.cpp** modelmi priamo na telefóne,
**bez Operitu** a bez obrovského systémového promptu. Naskenuje **všetky** `.gguf`
na sdcard, ku každému modelu automaticky priradí `mmproj`, a v appke máš **chat +
multimediálny upload (text / obrázok / audio / video) + všetky nastavenia
llama-servera + Start/Stop**.

## Prečo
Operit posiela na začiatku každého requestu veľký systémový prompt + definície
nástrojov + pamäť (2000–4000 tokenov). Na CPU-only telefóne to zabije prefill
(desiatky sekúnd) a malý model sa „zasekne“. llamalite pošle len to, čo napíšeš.

## Jedna apka = všetko
Apka `com.llamalite` obsahuje **celý web aj llama-server binárku**:

```
ikona apky
   │
   ├─ WebView (chat + nastavenia + upload)  ← servuje sa z vstavaného servera
   ├─ LocalServer (Kotlin HTTP server na 127.0.0.1:19778)
   │       ├─ /api/scan        ModelScan   → .gguf modely + auto mmproj
   │       ├─ /api/start       Cli.build   → argv → spustí llama-server
   │       ├─ /api/stop        zabije server (uvoľní RAM)
   │       ├─ /api/settings    config.json (všetky nastavenia)
   │       ├─ /upload          text/obrázok/audio/video (MediaCodec → wav/snímky)
   │       ├─ /api/file        náhľady príloh
   │       └─ /proxy           preposiela chat na llama-server (bez CORS)
   └─ llama-server (binárka v APK, port 19777)
```

**Žiadny Python, žiadny proot, žiadny terminál.** Klik na ikonu → celé UI.
Žiadne druhé riešenie — web verzia (`scripts/`) už neexistuje.

## Unikátne porty
Zámerne **mimo bežných** (8080/8081), aby nič nekolidovalo:
- **19777** — llama-server
- **19778** — vstavaný web (WebView)

## Multimodalita (bez ffmpeg — čisto Android)
V chate priložíš cez 📎 **akýkoľvek súbor**:
- **text** (.txt/.md/.csv/.json/.py…) → vloží sa do správy ako text (max 200 kB)
- **obrázok** → `image_url` (base64)
- **audio** (mp3/m4a/ogg/flac/…) → `MediaCodec` dekóduje → **16 kHz mono WAV** → `input_audio`
- **video** → `MediaMetadataRetriever` vytiahne **N snímok** + `MediaCodec` audio stopu

Funguje s multimodálnymi modelmi (mmproj) — overené na **Qwen2.5-Omni 3B**.
Vision/audio enkód na CPU je pomalý (obrázok ~1 min), prvý dotaz trvá.

## Všetky nastavenia
Záložka **Nastavenia** je generovaná z `assets/flags.json` a obsahuje **~92
reálnych prepínačov llama-servera** v kategóriách (CPU, kontext, pamäť, KV cache,
reasoning, sampling, gramatika, server, …). **Nevyplnené pole = prepínač sa
vynechá** (default llama-servera). Plus špeciálne: `mmproj`, `alias`,
`extra_args`, per-model `overrides` (v „Pokročilé → celý JSON“).

Kľúč nastavenia = názov flagu **bez pomlčiek** (`ctx-size`, `cache-type-k`,
`flash-attn`). `true` = zapnuté, `false` = vypnuté, číslo/text = hodnota.

## Build
GitHub Actions (`.github/workflows/build-apk.yml`) na `main`:
1. zkompiluje **statickú** arm64 `llama-server` cez Android NDK (bionic)
2. zabalí ju do `android/app/src/main/jniLibs/arm64-v8a/libllamaserver.so`
3. postaví + podpíše APK → artifact **llamalite-apk**

`push` do `main` (alebo ručne) = nový build (~12 min).

## Súbory
```
llamalite/
  android/
    app/src/main/
      assets/index.html            UI (chat, upload, celý panel nastavení, log)
      assets/flags.json            spec ~92 prepínačov (poháňa formulár)
      java/com/llamalite/
        MainActivity.kt            WebView + spustí LocalServer
        LocalServer.kt             vstavaný HTTP server + API + proxy
        Cli.kt                     config → argv pre llama-server (mkcmd)
        ModelScan.kt               sken sdcard → modely + auto mmproj (discover)
        MediaTools.kt              audio→wav, video→snímky+audio, klasifikácia
        LlamaServerManager.kt      Start/Stop/Log llama-server procesu
        Config.kt                  čítanie/zápis config.json (+ porty)
  .github/workflows/  build APK na GitHube (Actions)
```

## Modely
Sken prejde `/sdcard/Download` + `/sdcard/Download/Operit/models` (hĺbka 3)
a nájde každý `.gguf`. Súbory s `mmproj`/`projector` v názve berie ako projektory
a priradí ich k modelu v tej istej zložke. Ak hlavička nie je `GGUF`, model je
označený `⚠ POŠKODENÝ` a odmietne sa spustiť (typicky nedokončený download).

## achillova pata telefónu: RAM
- `mmap` šetrí RAM (stránky z disku), `mlock` drží v RAM (rýchle, ale žerie).
- `repack on` zrýchli prefill ~2,7× (dlhší štart, viac RAM).

## Licencia
MIT — viz `LICENSE`.
