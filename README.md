# Local API (Fabric client mod, Minecraft 26.3)

Macht den laufenden Client zu einer lokalen HTTP/JSON-API. AutoHotkey, Python, curl, alles auf diesem PC
kann den Spieler lesen und steuern. Kein zweiter Account, kein Chat-Befehl, kein Neuladen. Das Verhalten
ändert sich im externen Skript, die Mod bleibt die ganze Session geladen.

- Minecraft 26.3, Fabric Loader >= 0.19.5, Fabric API (getestet gegen 0.164.0+26.3), Java 25
- Nur Client. Bindet ausschließlich an `127.0.0.1`.

## Port und Passwort

`config/localapi.json` (wird beim ersten Start angelegt):

```json
{ "port": 25580, "password": "Test123", "timeoutMs": 5000, "frameBudgetMs": 4, "httpThreads": 4 }
```

Das Passwort ist ein schlichtes gemeinsames Passwort (zum Testen `Test123`). Es wird mitgeschickt als
`X-Password: Test123`, `Authorization: Bearer Test123` oder `?password=Test123`.
`GET /ping` braucht kein Passwort und liefert keine Spieldaten.

Schutz: Loopback-only, `Host` muss `127.0.0.1`/`localhost` sein, Requests mit `Origin`-Header
(Browser) werden abgelehnt.

## Antwortformat

```json
{ "ok": true,  "data": { ... } }
{ "ok": false, "error": "text" }
```

HTTP-Status: 200, 400 Parameter, 401 Passwort, 404 Route, 405 Methode, 409 Zustand (z. B. nicht in einer Welt),
413 JSON-Body größer als 1 MiB, 504 Spiel hat nicht in `timeoutMs` geantwortet.

## Nicht blockierend

HTTP läuft auf eigenen Threads. Der Spiel-Thread legt keine I/O-Wartezeit ein: er arbeitet die Warteschlange
einmal pro gerendertem Frame ab, höchstens `frameBudgetMs` (4 ms), Rest im nächsten Frame. Bei 30 FPS
(unfocused) heißt das: Antwort nach höchstens ca. 33 ms plus Bearbeitung. Skripte müssen nicht in
bestimmten Abständen pollen, aber öfter als jeden Frame bringt nichts. Sinnvoll sind 20 bis 50 ms zwischen
Zustandsabfragen. Mehrere Aktionen, die zusammen passieren sollen, gehören in `POST /batch` (ein Schritt).

Zeitüberschreitung/Abbruch: noch wartende Aktionen werden nicht mehr nachträglich ausgeführt.
Bereits gestartete Aktionen (auch asynchrone Plugin-Arbeit) lassen sich damit nicht zurückrollen.

## Fokus und Pause

- Alt-Tab öffnet kein Pause-Menü (vanilla `pauseIfInactive` ist abgeschaltet).
- Der Integrated Server pausiert nur, solange ein Pause-Screen offen ist. Ohne diesen Screen läuft die Welt
  auch im Singleplayer weiter, wenn das Fenster den Fokus verliert. Keine Einstellung nötig.
- Wer selbst Esc drückt, öffnet weiter das Pause-Menü (vanilla).
- dynamic-fps (30 FPS unfocused) stört nicht, siehe "Nicht blockierend".

## Tasten: halten, loslassen, tippen

| Route | Wirkung |
|---|---|
| `POST /key/down {"key":"forward"}` | gedrückt halten bis `up`. Wird jeden Tick neu gesetzt (vanilla löscht beim Öffnen/Schließen von Screens) |
| `POST /key/up {"key":"forward"}` | loslassen |
| `POST /key/tap {"key":"attack"}` | genau ein Tick drücken, ein Klick. `"ticks":5` oder `"ms":250` für länger |
| `POST /key/release_all` | alles loslassen |
| `GET /keys` | alle Keybinds inkl. Mod-Keybinds, gebunden an, gedrückt |

`key`: `attack use sneak jump sprint forward back left right drop swap_hands inventory pick_item chat command
playerlist hotbar1..hotbar9` oder ein beliebiger Name aus `GET /keys` (z. B. `key.inventory`).
Ein Tap auf eine gehaltene Taste ist ein zusätzlicher Klick. Toggle-Optionen (Sneak/Sprint umschalten) werden
berücksichtigt, einfacher ist es sie auszuschalten.

## Lesen

| Route | Inhalt |
|---|---|
| `GET /state` | in_world, singleplayer, paused, window_focused, fps, offener Screen, gehaltene Tasten |
| `GET /world` | Dimension, Wetter, Höhenbereich, Entity-Zahl, Server |
| `GET /player` | Position, Blickrichtung, Geschwindigkeit, Leben, Hunger, XP, Zustände, Hände, Hotbar-Slot |
| `GET /target` | Block oder Entity im Fadenkreuz |
| `GET /hand` | Haupt-/Offhand, gewählter Slot |
| `GET /inventory` | Spielerinventar, immer verfügbar (Slots des InventoryMenu: 0 Ergebnis, 1-4 Crafting, 5-8 Rüstung, 9-35 Inventar, 36-44 Hotbar, 45 Offhand) |
| `GET /container` | das **wirklich offene** Menü (`player.containerMenu`): Truhe, Ofen, Dorfbewohner, Inventar, ... Alle Slots mit Index, Item, Bildschirmposition, `player_inventory`-Flag, `carried` |
| `GET /screen` | offener Screen: Klasse, Titel, Buttons/Widgets (Text, Position, aktiv), bei Container-Screens zusätzlich `container` |
| `GET /entities?radius=32&type=zombie&limit=200` | Entities nach Entfernung |
| `GET /players` | Tab-Liste |
| `GET /environment?x=&y=&z=` | Biom und Sky-/Block-Licht am geladenen Ort; ungeladen explizit `loaded:false` |
| `GET /scoreboard` | aktuell clientbekannte Objectives, Scores, Display-Slots und Teams |
| `GET /bossbars` | aktive Bossbars im Client |
| `GET /block?x=&y=&z=` | Block + Blockstate |
| `GET /blocks?x1=&y1=&z1=&x2=&y2=&z2=&include_air=0` | Region (max 32768 Blöcke) als Palette + `[x,y,z,idx]` |
| `GET /chat/log?since=0` | empfangene Chat-/System-Nachrichten, `since` = zuletzt gesehene id |
| `GET /routes` | Liste aller Routen |

## Live-Daten und optionale Details

Alle Zustandsabfragen lesen den **aktuellen Clientzustand auf dem Game-Thread**. Es gibt keine
persistierten Inventar-/Kisten-Snapshots und keine Rekonstruktion aus Events. Die Daten sind das,
was der Client gerade kennt, keine zusätzliche Abfrage verborgener Serverdaten. Bestehende JSON-Felder bleiben.

### Items

`item_details=true` bei `/player`, `/hand`, `/inventory`, `/container` oder `/screen` ergänzt die
jeweiligen Item-Objekte um `details`. Ohne Parameter bleibt die bisherige kompakte Item-Darstellung.
Auch Aktionsantworten, die diese Ansichten zurückgeben, akzeptieren den Parameter.

Details: `enchantments`, `stored_enchantments` (jeweils ID/Level), `lore`, `component_ids`, `potion`
(ID, Farbe, Basis-Effekte und `duration_scale`) sowie die aktuell im Item vorhandenen `container`-/`bundle`-Komponenten.
`component_ids` listet vorhandene Komponenten; es ist **kein beliebiger NBT-/Komponentenexport**.
Fehlende Potion-/Inhaltskomponenten sind `null`. Ein Inhalt ist eine Liste von `{slot,item}` mit
`size` und `truncated`; leere Slots behalten ihre Indizes. Das liest nur die aktuell übermittelten
Item-Komponenten, keine Kisten in der Welt.

Je Antwort werden höchstens 128 nicht-leere Items detailliert ausgegeben, mit zwei Ebenen verschachtelter
Inhalte. Am Limit bleiben die Basisfelder erhalten; `details.available:false` mit `reason:item_limit`
oder `truncated:true` bei Inhalten kennzeichnet die Auslassung ausdrücklich. Leere Stacks behalten
auch bei angefragten Details die bisherigen drei Felder.

### Spieler und Welt

`/player` ergänzt `effects` (ID, nullbasierter `amplifier`, `duration_ticks`, `infinite`,
`ambient`, `visible`, `show_icon`), `air`, `max_air`, `on_fire`, `fire_ticks`, `xp_progress` und `xp_total`.
Unendliche Effekte haben `duration_ticks:null`. `fire_ticks` ist `null`: der Client kennt den Brennstatus,
aber keinen verlässlichen synchronisierten Resttimer. Tick-Dauern sind Spielticks, keine garantierte Echtzeit.

`/world` ergänzt `game_time_ticks`, `clock`, `clock_time_ticks` und `time_of_day_ticks`.
Letzteres ist die Zeit der Dimensions-Standarduhr modulo 24000 (Vanilla-Tageszyklus).
Ohne Standarduhr sind diese drei Uhrfelder `null`; es wird keine Uhrzeit erfunden.

`/environment` liefert die gespeicherte 4×4×4-Biomzelle (`biome_sampling:"quart"`) und Sky-/Block-Licht
(0–15, ohne Tageszeit-Abdunklung). So wird kein benachbarter ungeladener Chunk zur Biominterpolation benutzt.
Eine geladene Position außerhalb der Bauhöhe liefert 400. Ein ungeladener Chunk liefert 200 mit
`loaded:false`, `reason:"chunk_not_loaded"` und `null` für Biom und Licht.

Auch `/block` und das Blockziel in `/target` melden ungeladene Chunks ausdrücklich (`id:null`, `state:null`),
statt scheinbar gültige Luft zu liefern. `/blocks` prüft die gesamte Region vor dem Lesen und liefert
409 mit `loaded:false` und `unloaded_chunks:[[chunk_x,chunk_z],...]`, sobald ein benötigter Chunk fehlt.
Es wird kein Chunk geladen und keine unvollständige Region als vollständig ausgegeben.

### Menüs, Scoreboard, Bossbars

`menu_details=true` bei `/container` oder `/screen` ergänzt den Container um `details` mit `container_id`,
`kind` und `supported`. Unterstützt sind:

- Öfen, Schmelzöfen und Räucheröfen: `lit`, `cooking_progress`, `fuel_progress` (0–1).
- Händler: clientbekannte `offers` mit angepassten Kosten, Ergebnis, Nutzungen, Maximum, XP und Ausverkauft-Status;
  außerdem Händler-Level/XP und Restock-/Fortschrittsanzeige. Item-Details bleiben separat opt-in.
- Amboss: `level_cost`.
- Zaubertisch: drei Optionen mit Levelanforderung, Hinweis-Enchantment und Hinweis-Level. `available` bedeutet
  nur eine vorhandene Option, nicht dass der Spieler sie bezahlen kann. Nicht übermittelte Hinweise sind `null`.

Andere Menüs behalten ihre generischen Slots und melden `supported:false`, `kind:"unsupported"`.
Die Werte sind live synchronisierte Clientdaten; unmittelbar nach dem Öffnen kann noch ein Update ausstehen.

`/scoreboard` enthält `display_slots`, `objectives` (inklusive Scores) und `teams`.
Text steht als lesbarer String und als `*_component` mit Formatierung bereit; nicht kodierbare
Textkomponenten sind `null`, der lesbare Text bleibt erhalten. Score-Einträge enthalten Zahlenwert,
formatierten Wert und `hidden`. Es werden nur clientbekannte Einträge ausgegeben.

`/bossbars` enthält ID, Name/Textkomponente, Fortschritt, Farbe, Overlay und die Musik/Nebel/Dunkel-Flags.
Der Fortschritt ist der aktuell angezeigte, clientseitig interpolierte Wert. Ohne Welt liefern diese
neuen Welt-/Spielerabfragen wie die bestehenden Routen 409.

## Handeln

| Route | Body |
|---|---|
| `POST /hotbar` | `{"slot":0-8}` |
| `POST /player/look` | `{"yaw":90,"pitch":10,"relative":false}` |
| `POST /player/look_at` | `{"x":..,"y":..,"z":..}` |
| `POST /container/click` | `{"slot":13,"button":0,"type":"PICKUP","container_id":3}`. `type`: PICKUP QUICK_MOVE SWAP CLONE THROW QUICK_CRAFT PICKUP_ALL. `slot` -999 = außerhalb. `container_id` optional, schützt vor Klicks ins falsche Fenster (409) |
| `POST /screen/close` | schließt Container oder Screen |
| `POST /chat` | `{"text":"hallo"}` oder `{"text":"/time set day"}` |
| `POST /chat/echo` | `{"text":"nur lokal"}` |
| `POST /screenshot` | `{"base64":false}`, PNG nach `localapi-screenshots/` im Spielordner |
| `POST /batch` | `{"requests":[{"path":"/hotbar","body":{"slot":2}},{"path":"/key/tap","body":{"key":"use"}}]}` |

## Echte Requests

```powershell
$h = @{ "X-Password" = "Test123" }
irm http://127.0.0.1:25580/state -Headers $h
irm http://127.0.0.1:25580/container -Headers $h
irm http://127.0.0.1:25580/key/down -Method Post -Headers $h -Body '{"key":"forward"}'
irm http://127.0.0.1:25580/key/up   -Method Post -Headers $h -Body '{"key":"forward"}'
irm http://127.0.0.1:25580/key/tap  -Method Post -Headers $h -Body '{"key":"attack","ticks":3}'
irm http://127.0.0.1:25580/container/click -Method Post -Headers $h -Body '{"slot":0,"type":"QUICK_MOVE"}'
```

```
curl -H "X-Password: Test123" "http://127.0.0.1:25580/entities?radius=20&type=zombie"
curl -H "X-Password: Test123" -d "{\"slot\":4}" http://127.0.0.1:25580/hotbar
```

AutoHotkey v2: siehe `examples/mc.ahk`.

## Bauen

`.\build.ps1` (JDK 25, kompiliert direkt gegen `client-26.3.jar` und Fabric API). Ergebnis:
`build\localapi-1.0.0.jar`, in den `mods`-Ordner kopieren.

## Tests

Nach `.\build.ps1` führt `.\test\run-test.ps1` die Request-Regressionstests und den vorhandenen
Plugin-Harness sowie `LiveDataHarness` gegen echte Minecraft-26.3-Datenobjekte aus
(ohne gestartetes Minecraft, mit denselben lokalen Build-Abhängigkeiten).
Der HTTP-Test verwendet einen freien lokalen Port; der Plugin-Harness verwendet Port 25581.

Die Tests prüfen Zahlenbereiche, Batch-Validierung, Blockregionsgrößen, Abbruch wartender Aktionen,
das HTTP-Body-Limit und den Server-Shutdown. Gültige Core-Integerwerte werden weiterhin abgerundet.
Ungültige Werte (NaN/Infinity, Überläufe, Objekte/Arrays statt Skalaren) liefern 400.
Batch-Fehler bleiben pro Eintrag sichtbar; spätere gültige Einträge werden weiterhin ausgeführt.

Vor dem Merge im Client prüfen: `/state`, Tasten halten/loslassen, Container-Klicks, normale und
höhenmäßig leere `/blocks`-Regionen, `/batch`, Screenshot, Plugin-Streams und Portänderung vor/nach Neustart.

## Grenzen

- Nicht-Container-Screens (Optionen, Titelbildschirm) werden gelesen und geschlossen, Buttons darin
  werden nicht per Route geklickt. Dafür Tasten (`inventory`, `chat`, ...) oder Container-Klicks nutzen.
- Screenshot: PNG wird neben dem Spiel-Thread geschrieben, die Antwort kommt nach dem GPU-Readback.
- Das Passwort ist absichtlich schlicht. Jedes Programm auf diesem PC, das es kennt, steuert den Client.

## Oberfläche im Spiel

Mod Menu → Local API → Configure. Zwei Tabs, jeder mit eigener Suche (Teilstrings, Reihenfolge-Treffer, Tippfehler).

- **API**: alle Routen durchsuchbar, Klick füllt die Konsole vor. Konsole: Pfad (mit `?query`), GET/POST, JSON-Body, läuft durch denselben Dispatcher wie HTTP und blockiert den Render-Thread nicht. Tab "Access": Passwort ändern (sofort aktiv, in `config/localapi.json`), Port ändern (greift erst nach Neustart des Clients).
- **Plugins**: Jars aus `config/localapi/plugins/` mit Status und Fehlern, "Rescan folder", "Approve and load". Funktionsliste (Doppelklick oder Button öffnet die Seite): An/Aus, Pfad, Methode, Parameter, "Run test" (zeigt das JSON) bzw. "Start stream test" (zeigt Verbindung und Frame-Zähler, keine Bytes).

## Plugins

Ein Plugin ist ein Jar in `config/localapi/plugins/` mit `localapi.plugin.json` im Wurzelverzeichnis:

```json
{ "id": "voice", "name": "Voice", "version": "1.0.0", "apiVersion": 1,
  "entrypoint": "refvoice.VoiceRefPlugin",
  "voicechatPlugin": "refvoice.VoiceHooks",
  "requires": ["voicechat"] }
```

`entrypoint` implementiert `localapi.api.LocalApiPlugin` und deklariert Funktionen (`ctx.function("id")...once(...)` oder `.stream(...)`, plus `suggestedPath`, `method`, Parameter mit Typ). Die Pfade vergibst du in der UI; Einstellungen liegen in `config/localapi/plugins/<id>.json`. Funktionen sind standardmäßig **aus**. Kollidiert ein Pfad mit einer Kernroute (auch `/ping`) oder einem anderen Plugin, wird er abgelehnt. Fehler: 400 (Parameter), 404 (unbekannter Pfad, mit Pfadliste), 405, 409 (Funktion aus, Abhängigkeit fehlt), 500 (Plugin-Fehler mit Meldung), 504 (Game-Thread-Timeout), 429 (zu viele Streams, `maxStreams`).

**Sicherheit:** Ein Plugin ist beliebiger Code im Client. Ein Jar läuft erst, wenn du es freigibst (SHA-256 in `config/localapi/plugins.json`; wird die Datei geändert, ist die Freigabe weg). Jars mit Mixins werden abgelehnt (dafür gehören sie nach `mods/`).

**Grenzen:** Neu gefundene Jars laden per Hot-Load, entladen geht nicht. Ein ersetztes Jar braucht einen Neustart. Voice-Chat-Hooks eines Plugins werden nur beim Start von Voice Chat registriert: das Jar muss beim Clientstart schon im Ordner liegen.

### Streams

`Content-Type: application/x-ndjson`, eine JSON-Zeile pro Nachricht, chunked. Erste Zeile `{"type":"hello",...}`, alle 5 s `{"type":"heartbeat","frames":N,"dropped":M}`. Ist der Leser zu langsam, werden die ältesten Frames verworfen (Queue 512), nie blockiert.

```python
import requests, json, base64
r = requests.get("http://127.0.0.1:25580/voice/stream", headers={"X-Password": "Test123"}, stream=True)
for line in r.iter_lines():
    m = json.loads(line)
    if m["type"] == "frame":
        pcm = base64.b64decode(m["pcm_b64"])  # s16le, m["rate"] Hz, m["channels"] Kanäle
```

### Voice Chat

Die Kernklasse `localapi.voice.VoiceBridge` implementiert `VoicechatPlugin` ohne Audiocode und reicht dieselben `EventRegistration` an die `voicechatPlugin`-Klassen der Plugins weiter. Voice Chat selbst wird nicht verändert; die API-Klassen liegen nur compile-only in `libs/voicechat-api/` (nicht im Jar, nicht im Git). Referenzplugin: `plugins/voice-ref` (`/voice/speakers`, `/voice/stream`).

### Einstellungen und Status (Plugin-API)

`ctx.options(List<OptionSpec>, onChange)` deklariert Einstellungen (CHOICE, INTEGER, NUMBER, TEXT mit Bereichen). Werte stehen in `config/localapi/plugins/<id>.json` unter `options`, werden vom Kern geprüft und sofort gespeichert; `onChange(id)` kommt nach jeder gültigen Änderung. Die Seite des Plugins zeigt sie als Block unter der Funktionsliste (vanilla Widgets). Ein Plugin ohne Funktionen bekommt trotzdem eine Seite. `ctx.status(...)` liefert Statuszeilen für denselben Bereich, `ctx.pluginLoaded(id)` fragt, ob ein anderes Plugin geladen ist.

### AudioBus

`localapi.api.AudioBus` liegt im Kern, damit Plugins (eigene Classloader) Frames austauschen können, ohne einander zu importieren. Das Voice-Plugin veröffentlicht jede kopierte Frame (PCM s16le, 48 kHz, mono, 960 Samples, Art entity/locational/static/microphone, Sprecher-UUID). Der NDJSON-Stream bleibt für externe Programme.

### Plugin captions

Live-Untertitel aus Simple Voice Chat, HUD als Kopie von voicetranscript 1.5.0 (RIGHT_WINDOW, TOP_LEFT_ICONS, OFF). Einstellungen nur auf der Plugin-Seite: `hudMode`, `windowWidth`, `maxEntries`, `fadeTimeSeconds`, `modelPath`, `language` (auto/de/en), `silenceMs`, `rmsGate`, `cpuThreads`. Ein Worker schneidet pro Sprecher Äußerungen an Stille (silenceMs), Whisper JNI 1.7.1 (MIT, in das Jar kopiert zusammen mit `HallucinationFilter.class` aus dem alten Mod) läuft nur auf diesem Worker. Ohne Modelldatei: Hinweis auf der Seite, kein Download. Abweichungen: Sprache "auto" läuft als `language="auto"` (`detectLanguage=true` liefert in whisper.cpp 0 Segmente); Eingaben unter 1 s werden mit Stille aufgefüllt (Whisper lehnt sie sonst ab); harte Grenze 25 s je Äußerung. Bauen: `plugins\captions\build.ps1`; Test: `plugins\captions\test\run-test.ps1`.
## Installieren

`.\build.ps1`, `plugins\voice-ref\build.ps1`, dann bei geschlossenem Minecraft `.\install.ps1` (kopiert beide Jars und gibt das Referenzplugin per Hash frei).

install.ps1 installiert Mod, Voice-Plugin und captions und trägt beide Plugin-SHA-256 in plugins.json ein. Minecraft vorher schließen. Den alten Mod voicetranscript deaktivieren (.jar → .jar.disabled), sonst laufen zwei Whisper-Instanzen und zwei HUDs.

