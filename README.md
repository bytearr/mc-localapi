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
| `GET /advancements` | clientbekannte Erfolge und übermittelter Fortschritt pro Kriterium |
| `GET /stats` | tatsächlich im Client vorhandene Statistikzähler |
| `GET /recipes` | clientbekannte Rezeptanzeigen, Ergebnisse und aktuelle Zutatenverfügbarkeit |
| `GET /events` | authentifizierter NDJSON-Livestream mit Änderungs-Hinweisen |
| `GET /journal?since=0&limit=200` | historische Ereignisse der aktuellen/zuletzt beendeten Session |
| `GET /block?x=&y=&z=` | Block + Blockstate |
| `GET /blocks?x1=&y1=&z1=&x2=&y2=&z2=&include_air=0` | Region (max 32768 Blöcke) als Palette + `[x,y,z,idx]` |
| `GET /chat/log?since=0` | empfangene Chat-/System-Nachrichten, `since` = zuletzt gesehene id |
| `GET /routes` | Liste aller Routen |

## Client-Darstellung und geladene Oberfläche

Alle folgenden Werte kommen aus dem aktuellen Client. Keine Übersetzungstabellen, externen Assets,
Icon-Dateien, Chunk-Caches oder gespeicherten Oberflächen werden angelegt.

- `/advancements` ergänzt `icon` (Item-ID) nur bei vorhandenem Display. `criteria` bleibt die bisherige
  Abbildung auf Booleans; `criterion_names` ergänzt ausschließlich bekannte, explizit namespaced IDs
  aus Biome-, Item-, Block- oder Entity-Type-Registries. Gewöhnliche Triggernamen bleiben ohne Label.
- Effekte unter `/player` (und in Item-Potion-Details) ergänzen `name`. `/environment` ergänzt
  `biome_name`; bei ungeladenem Chunk bleiben `biome` und `biome_name` null.
- `/stats` behält `type`, `id`, den rohen `value` und ergänzt `name` sowie `formatted_value` aus
  dem Vanilla-Statistikformatter. Spielzeit bleibt beispielsweise zusätzlich als Tickzahl verfügbar.
- `item_details=true` ergänzt bei nicht leeren, innerhalb des Detail-Limits ausgewerteten Stacks
  ein `tooltip`-Array. Es verwendet den Vanilla-Inventar-Tooltip für den aktuellen Spieler und die
  aktuelle Sprache, einschließlich der Einstellung für erweiterte Tooltips. Jede Zeile enthält `text`
  und `color` (RGB-Zahl); `runs` bewahrt zusätzlich die Textabschnitte mit unterschiedlichen Farben.
  Enchantment-Objekte ergänzen `name` einschließlich Stufe. Das bestehende Item-`name` bleibt unverändert.
  Ohne Details oder bei leeren Stacks entsteht kein Tooltip-Feld. Grafische Tooltip-Inhalte wie
  Bundle-Vorschauen sind keine Textzeilen und werden hier nicht als Bild nachgebaut.

`GET /blocks` behält `palette` als Array von Block-ID-Strings. Das zusätzliche `palette_entries`
enthält am selben Index `{id,map_color}`. Die RGB-Zahl kommt bei vorhandener Block-Tint aus
`Minecraft.getBlockColors().getTintSources(state)` und `colorInWorld` am gelesenen Ort: die erste
aktive Tint-Schicht in Registrierungsreihenfolge. `-1` bedeutet keine Tint. Falls keine Welt-Tint aktiv
ist, wird eine registrierte Terrain-Particle-Tint berücksichtigt (in 26.3 insbesondere Wasser).
Ohne aktive Tint bleibt `BlockState.getMapColor(...).col` die Farbe. Derselbe Block-ID-String kann mehrfach auftreten, wenn seine Zustände/Positionen unterschiedliche
Map-Farben liefern. Die Farbe ist eine Welt-Tint bzw. Materialfarbe, keine Texturprobe oder fertige, schattierte Karte.

`GET /surface?chunk_x1=-8&chunk_z1=-8&chunk_x2=7&chunk_z2=7` liest ein inklusives Rechteck in
**Chunk-Koordinaten**. Maximal 65536 angefragte Spalten (256 Chunks) pro Request; größere Bereiche
in mehrere Rechtecke aufteilen. Die Grenze gilt auch für fehlende Chunks. Die Antwort enthält:

- `columns`: `[x,z,y,palette_index]` für den höchsten Nicht-Luft-Block jeder geladenen Spalte,
  einschließlich Flüssigkeiten, aus der aktuellen `WORLD_SURFACE`-Heightmap.
- `palette` und `palette_entries`: dasselbe Format wie `/blocks`.
- `unloaded_chunks`: `[chunk_x,chunk_z]` für ausgelassene Chunks. Sie werden weder geladen noch
  als Luft ausgegeben; auch ein vollständig ungeladenes Rechteck ist eine erfolgreiche, explizit leere Antwort.
- `empty_columns`: `[x,z]` für tatsächlich vollständig leere **geladene** Spalten.
- `requested_columns` und `max_columns`: angefragte Größe und Limit.

`GET /item/icon?id=minecraft:potion` liefert das Standard-Icon eines registrierten Items.
`GET /item/icon?slot=36` rendert den aktuell vorhandenen Stack im Slot von `player.inventoryMenu`,
genau wie `/inventory`: 36–44 Hotbar, 9–35 Inventar, 5–8 Rüstung und 45 Offhand (0–4 Crafting).
Das gilt auch bei geöffneter Truhe; deren `containerMenu` wird nicht gelesen. Außerhalb der Slots dieses
Spieler-Menüs folgt 400, bei einem leeren Slot 404. Genau einen Parameter verwenden. Der Erfolg ist direkt
`image/png`, 32×32 Pixel mit Transparenz, **kein JSON/Base64 und keine URL**. Die bestehende Loopback-,
Host- und `X-Password`-Prüfung gilt weiterhin. `Cache-Control: no-store` verhindert Wiederverwendung.

Der Endpunkt löst das GUI-Modell aus den geladenen Client-Ressourcen auf und rendert es über den
Vanilla-Item-Atlas in eine frische, nur für diesen Request verwendete Textur. Dadurch werden auch
Mod-Items und Stack-Tints wie Potion-Farbe und gefärbtes Leder berücksichtigt. Kein Count-/Haltbarkeits-
Overlay und kein Enchantment-Glint. Unbekannte IDs, leere Slots oder nicht geladene/nicht darstellbare
Item-Modelle liefern 404 als normale JSON-Fehlerantwort. Ungültige Parameter liefern 400; Slotabfragen
außerhalb einer Welt 409. Höchstens zwei GPU-Readbacks gleichzeitig, darüber 429. Die temporären GPU-
Ressourcen werden nach dem Readback freigegeben; es gibt keinen Icon-Cache. PNG-Abfragen sind GET-only
und nicht innerhalb von `/batch` verfügbar. `/inventory` enthält weiterhin keine eingebetteten Bilder.

## Passende Verzauberungen

Mit `item_details=true` erhalten nicht leere, nicht abgeschnittene Item-Antworten zusätzlich
`fits:[{id,name,max_level,level}]`. Die Liste verwendet die aktuellen Client-Registries:
Die Verzauberung muss auf den Stack passen, mit seinen vorhandenen Verzauberungen kompatibel sein
und unter ihrer Maximalstufe liegen. `level` ist die vorhandene Stufe (sonst 0), `name` der übersetzte
Name ohne Stufensuffix. Höherstufungen bleiben möglich; Schätze und Flüche werden nicht ausgefiltert.
Ohne verfügbare Welt-Registry ist `fits` null. Keine Empfehlungen, Kosten oder Amboss-Aktionen.

Bei `STORED_ENCHANTMENTS` kommt `book_targets:[{id,name,level,max_level,slots}]` hinzu.
`slots` enthält passende, nicht leere Slots des aktuellen `player.inventoryMenu`, mit den Indizes
von `/inventory`: 5–8 Rüstung, 9–35 Inventar, 36–44 Hotbar, 45 Offhand. Crafting-Slots fehlen.
Geprüft werden Anwendbarkeit und Kompatibilität, nicht die Möglichkeit einer weiteren Höherstufung.
Die Daten werden pro Anfrage gelesen und nicht gespeichert.

## Vanilla-Clientoptionen

`GET /options` funktioniert auch ohne Welt und liefert `{options:[...]}`. Jede Zeile hat
`id`, `name`, `group`, `kind`, `value` und `label`. IDs stammen aus den Vanilla-Übersetzungsschlüsseln,
Namen und Labels aus der aktuellen Sprache und dem eigenen Optionsformatter.
Die Gruppen umfassen Video (einschließlich FOV und Vollbildauflösung), Sound, Maus, Chat, Skin,
Barrierefreiheit, Online, Font, Sprache und alle registrierten Tastenbelegungen einschließlich Debug.
Skin-Teile werden wie ihre Vanilla-Schalter behandelt. Account-/Freundesverwaltung gehört nicht dazu.

- `kind`: `boolean`, `int`, `double`, `enum`, `language` oder `key`.
- `value` bleibt in Vanilla-Einheiten; beispielsweise GUI-Skalierung 0 bleibt 0.
- Ganzzahlbereiche liefern `min`/`max`, dynamische Grenzen werden live gelesen. Slider liefern
  zusätzlich ihre Vanilla-Position `slider`. Booleans haben keine numerischen Bereichsfelder.
- Auswahlen liefern `values:[{id,name}]` in Vanilla-Reihenfolge. Java-Enum-IDs sind deren `name()`;
  Sprache verwendet Sprachcodes, Tasten verwenden Save-Strings wie `key.keyboard.b`.

`POST /options` mit `{"id":"options.gamma","value":0.5}` ändert genau eine Option über
Vanillas Validator und Setter und ruft `options.save()` auf. Unbekannte IDs liefern 404;
abgelehnte Werte 400 ohne Änderung. Dynamische Clamp-Validatoren dürfen den Wert wie Vanilla begrenzen;
die Antwort `{id,value}` enthält den tatsächlich übernommenen Wert. Keine manuelle `options.txt`-Bearbeitung.
Tastenbelegungen werden gesetzt und die Zuordnung aktualisiert, die Taste wird nicht gedrückt.
Sprachwechsel verwenden den LanguageManager und den normalen Ressourcen-Reload; die Antwort ergänzt
`reload_requested`, wartet aber nicht auf dessen Abschluss. Vanilla-seitige Neustart-Anforderungen
(z. B. Grafik-Backend) bleiben bestehen. Optionsänderungen erzeugen keine pauschale Inventar-/Spielzustandsinvalidierung.

## Installierte Mods

`GET /mods` liefert `{mods:[{id,name,version,description,authors,environment,has_icon}]}`, nach ID sortiert,
aus allen Fabric-Modcontainern einschließlich eingebauter und verschachtelter Mods. `authors` enthält
nur Namen, `version` die Friendly-Version. Keine Pfade, Kontaktinformationen oder Custom-Metadaten.

`GET /mod/icon?id=localapi` liest ausschließlich den Iconpfad aus den Mod-Metadaten (128er-Auswahl).
Erfolg ist direkt `image/png`, ohne JSON/Base64 und mit `Cache-Control: no-store`.
Unbekannter Mod, fehlende Datei, falsche PNG-Signatur oder mehr als 1 MiB liefern 404 als JSON.
Datei-I/O läuft asynchron, ohne Cache oder angelegte Dateien. Die Route ist GET-only, geschützt wie
andere API-Routen und nicht in `/batch` verfügbar.

## Script-HUD

Timer, Zähler und Fortschrittsbalken sind flüchtige Anzeigen, deren Werte das Script liefert.
Keine Inventarableitungen, Journal-Einträge, Dateien oder Wegpunkt-Marker. Alle Routen verwenden die
vorhandene API-Authentifizierung; `owner` ist zusätzlich ein zufälliges Lease-Token. Token nicht zwischen
Scripts teilen: Es berechtigt zum Lesen und Ändern genau dieses Besitzers (keine eigene Benutzer-Authentifizierung).

| Route | Zweck |
|---|---|
| `POST /hud/owner/create` | Lease anlegen, Antwort `{owner,ttl_ms}` |
| `POST /hud/owner/renew` | bestehende Lease verlängern, `owner` erforderlich |
| `POST /hud/owner/release` | Lease und alle zugehörigen Widgets sofort entfernen |
| `GET /hud/widgets?owner=…` | nur die Widgets dieser Lease und Restlaufzeiten lesen |
| `POST /hud/widgets/put` | Widget über `owner` + `id` anlegen oder vollständig ersetzen |
| `POST /hud/widgets/remove` | genau dieses Widget entfernen, Antwort `{removed}` |

Leases: `ttl_ms` standardmäßig 10000, erlaubt 1000–30000. Vor Ablauf explizit erneuern (z. B. alle
3 Sekunden bei 10 Sekunden TTL). Ein abgestürztes Script kann daher höchstens bis zum Lease-Ablauf
sichtbare Widgets hinterlassen. Bei sauberem Shutdown `/hud/owner/release` aufrufen. Abgelaufene Token
liefern 404 und lassen sich nicht wiederbeleben. Join, Disconnect, Respawn/Dimensionswechsel und
Client-Ende verwerfen alle Leases. Danach muss das Script eine neue Lease und neue Widgets anlegen.

Beispiel für ein Widget (`owner` durch das Token ersetzen):

```json
{"owner":"…","id":"building","type":"progress","label":"Platziert","value":23,"max":100,"x":8,"y":8,"width":180,"ttl_ms":10000}
```

`type` ist `counter`, `progress` oder `timer`; `id`, `label`, `value` sind Pflichtfelder. `put` ersetzt
immer den kompletten Eintrag: optionale Felder bekommen wieder ihre Standardwerte. Eine Änderung
verlängert nur die Widget-TTL, **nicht** die Lease; Lease-Erneuerung verlängert keine Widget-TTL.
Beide müssen gültig sein. IDs sind innerhalb einer Lease eindeutig; gleiche IDs anderer Scripts bleiben getrennt.

- `counter`: ganzzahliger Wert zwischen −1 Milliarde und +1 Milliarde.
- `progress`: `0 <= value <= max`, `max` standardmäßig 100 und höchstens 1 Milliarde.
- `timer`: verbleibende Sekunden, 0 bis 1 Milliarde. `running` standardmäßig `false`; bei `true`
  zählt die Anzeige anhand einer monotonen Echtzeituhr bis null herunter, auch während Spielpausen.
  Das Script kann jederzeit den Wert ersetzen. Null löst keine Aktion aus; das Widget bleibt bis zur TTL sichtbar.
- Widget-`ttl_ms`: 100–60000, Standard 10000. `x`/`y`: 0–10000 GUI-Pixel ab links oben, Standard 8.
  `width`: 40–400, Standard 180. GUI-Skalierung erfolgt durch Minecraft; bei Resize werden Position und
  Größe in den sichtbaren Bereich begrenzt. Lange Texte werden abgeschnitten. Bei F1 wird nichts gezeichnet.
- `id` maximal 64, `label` maximal 128 Zeichen, nicht leer, einzeilig und ohne Formatierungs-Steuercodes.

Maximal 16 Leases, 16 Widgets je Lease und insgesamt 64 Widgets; Überschreitung liefert 429.
Ungültige Werte liefern 400 und lassen den bisherigen Eintrag stehen. Updates vorhandener IDs sind
auch bei erreichtem Limit möglich. Überlappung und Anordnung legt das Script fest; spätere Einträge
werden über früheren gezeichnet. `GET` liefert die aktuelle Timer-Anzeige, Position/Größe wie vom Script
angegeben und `remaining_ms` begrenzt auf Widget- und Lease-Restlaufzeit. HUD-Daten werden nicht ins
Session-Journal oder den Event-Stream übernommen. HUD-POSTs lösen keine Spielzustands-Invalidierung aus,
auch nicht innerhalb von `/batch`; dort invalidieren nur die jeweiligen Spielaktionen. Die Routen zum Anlegen/Ändern/Lesen erfordern eine aktive Welt.

## Rezeptbuch

`GET /recipes?after=-1&limit=100` liest nur die tatsächlich im Client bekannten Rezeptanzeigen.
Die Antwort enthält `recipes`, `next_after`, `has_more`, `supported_menu`, `menu_kind`, `container_id`
und bei einem unterstützten offenen Screen dessen `revision`. `limit` ist 1–200; `after` ist eine
exklusive **Rezeptanzeige-ID**, kein Listenindex. Seiten werden jeweils live gelesen; nach einem
Rezeptbuch-Update die Aufzählung erneut beginnen. Es gibt keinen gespeicherten Inventar-/Rezeptstand.

Ein Rezept enthält `id` (die vom Server vergebene Display-ID, kein erfundener Recipe-Resource-Name),
`display_type`, `category`, `highlighted`, `compatible`, `craftable`, `reason`, `results` und `requirements`.
Shaped-Rezepte ergänzen `width`/`height`. Ergebnisse sind **Anzeigevorschauen**, keine erzeugten Items.
`results.items` verwendet die bisherigen kompakten Item-Felder; erst `item_details=true` ergänzt Details.
Es werden höchstens 32 Ergebnisvarianten pro Rezept ausgegeben; `results.truncated` meldet weitere.

Bekannte `requirements.ingredients` enthalten pro benötigter Zutat die möglichen Item-IDs in
`alternatives`; wiederholte Zutaten bleiben wiederholt. Das ist keine Zuordnung zu Crafting-Slots
und kein berechneter Restbestand. Die Ausgabe begrenzt Zutaten auf 64 und Alternativen auf 128,
jeweils mit `truncated`. Nicht übermittelte Anforderungen sind `null`, nicht eine leere Zutatenliste.

`craftable` wird frisch wie im Vanilla-Rezeptbuch aus dem Spielerinventar plus den Crafting-Eingabefeldern
des aktuellen Menüs berechnet. Ohne unterstützten offenen Screen oder ohne übermittelte Anforderungen
ist es `null`. `reason` unterscheidet `no_supported_recipe_screen`, `incompatible_menu`,
`disabled_features`, `requirements_unavailable` und `missing_ingredients`. `craftable:true` sagt nichts
über freien Inventarplatz, Serverzustand, Brennstoff oder ein bereits fertiges Ergebnis aus.

Unterstützt sind die Vanilla-Rezeptbuch-Screens mit Inventar-Crafting (2×2), Werkbank (3×3), Ofen,
Schmelzofen oder Räucherofen. Kategorie und Rastergröße müssen passen. Andere Menüs, unter anderem
Steinsäge/Schmiedetisch und eigene Menüklassen, lösen keine Rezeptplatzierung aus.

`POST /recipes/select {"revision":"…","container_id":7,"id":42,"all":false}` verwendet die Revision
und IDs aus der Live-Antwort. `all:true` entspricht der Vanilla-Auswahl für die maximal platzierbare
Menge; es gibt keine eigene Crafting-Schleife. Die Aktion prüft Screenrevision, Screen-/Menü-/Verbindungs-
Identität, Container-ID, bekanntes Rezept und aktuelle Zutaten direkt vor der normalen Rezeptbuch-
Platzierung erneut. Fehlende Rezepte liefern 404, inkompatible/veraltete Zustände oder fehlende Zutaten
409, ungültige Parameter 400. Rezeptbuch-Änderungen invalidieren alte Revisionen, damit wiederverwendete
Display-IDs nicht ein anderes Rezept auswählen. Screen-/Overlay-/Resize-Prüfungen werden mit den
Widget-Aktionen geteilt; eine ausgeführte Auswahl verbraucht die Revision ebenfalls.

Erfolg bedeutet nur `{requested:true,...,confirmation:"read_live_container_after_server_update"}`.
Die normale Rezeptbuchfunktion räumt die Ghost-Vorschau auf und sendet die Anfrage an den Server.
Sie entnimmt **kein** Ergebnis und garantiert weder Platzierung noch Crafting. Nach den Serverupdates
`/container` bzw. `/inventory` frisch lesen; diese Änderungen invalidieren über `/events` auch `/recipes`.
Es werden keine Zutatenlisten oder Inventarantworten im Journal gespeichert.

## Widget-Aktionen

`GET /screen` behält seine bisherigen Felder und ergänzt bei offenem Screen `revision` sowie pro
Widget `ref`, `supported` und `action` (`activate`, `set_text` oder `null`). Beide Referenzen sind opaque:
immer die Strings aus der Antwort verwenden, keine Indizes oder Bildschirmkoordinaten ableiten.
Aktuell unterstützt: normale `Button.Plain`-Buttons aus dem Vanilla-Button-Builder und die exakte
Vanilla-Klasse `EditBox`. Eigene Unterklassen, Icon-/Cycle-Buttons, Slider, Listen und verschachtelte
Controls werden nicht automatisch bedient. Nicht unterstützte direkt gelistete Controls melden
`supported:false` und `unsupported_reason:"unsupported_widget_type"`.

- `POST /screen/activate {"revision":"…","ref":"…"}` fokussiert den unterstützten Button und ruft
  seine normale Aktivierungsfunktion mit Enter ohne Modifikatortasten auf. Es werden keine
  Bildschirmkoordinaten geklickt und keine zusätzlichen Enter-/Chat-Sendeaktionen ausgeführt.
- `POST /screen/text {"revision":"…","ref":"…","text":"neuer Inhalt"}` fokussiert das Feld und ersetzt
  seinen gesamten Text über `EditBox.setValue`, einschließlich des normalen Responders. Ein leerer String
  leert das Feld. Es gibt kein automatisches Bestätigen/Absenden. Bei EditBoxen zeigt `/screen` außerdem
  den aktuellen `value`, `max_length`, `editable` und `focused`.

Alle Pflichtparameter müssen Strings sein. Zeichen, die Vanilla beim Einfügen entfernen würde,
Zeilenumbrüche, kaputte UTF-16-Sequenzen und zu lange Eingaben werden mit 400 abgewiesen; es wird
nicht still gekürzt. `max_length` zählt wie Vanilla UTF-16-Codeeinheiten. Die Validierung des Screens
im Responder bleibt wirksam: die Antwort enthält den resultierenden `value` und `accepted`, ob er dem
angefragten Text entspricht. Das ist keine Zusage, dass der Screen den Inhalt fachlich akzeptiert oder
bereits an den Server übermittelt hat.

Screenwechsel, Overlaywechsel, Neuinitialisierung, Resize, Widget-Neuaufbau oder eine geänderte
Child-Liste machen alte Referenzen ungültig. Unverändertes wiederholtes Lesen behält die Revision.
Vor jeder Aktion wird Screenidentität, Revision, Widgetzugehörigkeit und active/visible geprüft — und
nach dem Fokussieren nochmals. Ungültige/veraltete Referenzen, verdeckter Screen oder gesperrte Felder
liefern 409. Eine nicht unterstützte Aktion liefert 400. Kein Klick auf einen inzwischen anderen Screen.

Die Antwort enthält `performed:true` und `screen_changed` (Screenidentität gewechselt oder nun durch
Overlay verdeckt). **Eine ausgeführte Aktion verbraucht alle Referenzen dieser Revision**, auch wenn
nur ihr Callback scheitert. Vor der nächsten Aktion wieder `/screen` lesen. So kann ein Retry mit
alten Referenzen keinen zweiten Klick auslösen. Alte `/screen/close`- und Container-Endpunkte bleiben
unverändert; diese neue Referenzprüfung gilt für die beiden neuen Widget-Aktionen.

## Live-Stream und Session-Journal

`GET /events` ist ein authentifizierter NDJSON-Stream (eine JSON-Zeile pro Frame), kein gewöhnlicher
JSON-Response und nicht für `/batch`. Beispiel: `curl -N -H "X-Password: ..." http://127.0.0.1:25580/events`.
Er verwendet dieselben Authentifizierungsregeln und das gemeinsame `maxStreams`-Limit wie Plugin-Streams.

- `hello` enthält `resync:true`. Nach **jedem** Verbindungsaufbau aktuelle Werte von den benötigten
  Live-Endpunkten holen. Der folgende `resync`-Frame nennt `session`, `active`, `seq` und `journal_last_id`.
- `state_changed` enthält `session`, `seq`, `resync:true` und `paths`, z. B.
  `["/inventory","/container"]`. Das sind zusammengefasste Invalidierungen: dort können sich Werte
  geändert haben. Es werden **keine** Inventar-, Kisten- oder anderen Zustandssnapshots übertragen/gespeichert.
  Parameter für positionsbezogene Endpunkte wählt weiterhin das Script.
- `historical` enthält `session`, `seq` und `event`, denselben historischen Datensatz wie das Journal.
- `heartbeat` enthält die bisherigen Felder `t`, `frames` und `dropped`; Heartbeats haben keine `seq`.

Pro Verbindung bleiben höchstens 512 ausstehende Frames. Bei Überlauf werden die ältesten verworfen,
ohne auf den Leser zu warten. Eine Lücke in `seq` oder ein Anstieg von `heartbeat.dropped` bedeutet:
benötigten aktuellen Zustand erneut live lesen. Das gilt auch, wenn der Stream währenddessen weiterlief.
Die Sequenz zählt publizierte Frames innerhalb dieses Mod-Laufs; ein Wechsel der `session` verlangt
ebenfalls einen frischen Zustand. `/events?since=...` wird abgelehnt: **kein Replay von Zustandsänderungen**.
Heartbeats werden auch bei kontinuierlichem Traffic gesendet. Server-Stopp beendet seine Streams.

Invalidierungen kommen nach verarbeiteten Vanilla-Paketen für Inventar/Menüs, Spielerwerte,
Scoreboards, Bossbars, Erfolge, Statistiken, Chunks/Licht und Spielerliste; außerdem nach lokalen
Container-/Creative-/Drop-Aktionen und API-Aktionen. Spielerbewegung und Screenwechsel werden pro
Clienttick erkannt. Weltzeit, interpolierte Entities und Ziel werden konservativ jeden aktiven
Clienttick invalidiert, aktive Effekte ebenfalls. Ein Hinweis garantiert keinen bestimmten neuen Wert,
und mod-interne Änderungen außerhalb dieser Hooks sind keine vollständig abgedeckte Eventquelle.
Live-Abfragen bleiben maßgeblich; der Stream ist kein lückenloses Änderungsprotokoll.

### Historische Fakten

`GET /journal` liefert `session` (UUID oder `null`), `active`, `last_id`, `next_since`, `has_more`
und `events`. Ein Ereignis hat `id`, `session`, `time` (lokale Beobachtungszeit in Unix-Millisekunden),
`kind` und `data`. Es bleiben ausschließlich diese vergangenen Fakten erhalten:

- `session_started`, `session_ended`: Grenzen einer Play-Verbindung.
- `chat_received`: tatsächlich empfangener Chat oder Systemtext mit `kind`, bekanntem `sender` und
  `sender_uuid`, `text` sowie `signature` (Base64, sonst `null`). Actionbar/HUD-Updates gehören nicht hinein.
- `chat_deletion_requested`: eine verarbeitete Server-Löschanforderung mit der aufgelösten Signatur.
  Damit lässt sich signierter Chat zuordnen. Die ursprüngliche Empfangstatsache wird nicht umgeschrieben;
  die Löschanforderung behauptet nicht, dass die Nachricht noch angezeigt wurde oder bereits entfernt ist.
  Für unsignierte Nachrichten wird keine Zuordnung erfunden.
- `death`: das Server-Todessignal für den lokalen Spieler, einmal pro Leben, mit `cause`
  (empfangene Todesnachricht, keine erfundene Damage-Type-ID), `cause_source`, `dimension` und
  `position:[x,y,z]` bei Beobachtung. Unbekannte Ursache ist `null`.
- `dimension_changed`: ein beim Respawn-Paket tatsächlich gewechselter Dimensionsschlüssel mit `from`
  und `to`. Respawn in derselben Dimension erzeugt keinen Wechsel; Neuverbinden ebenfalls nicht.

Zum Blättern zunächst `/journal?limit=200`, dann `since=<next_since>&session=<session>` verwenden.
`limit` ist 1–1000; `since` ist eine exklusive Ereignis-ID. Bei `since>0` ist die Session erforderlich.
Eine veraltete Session oder ein Cursor jenseits des Journals ergibt 409. `last_id` ist die neueste ID,
**nicht** der Fortsetzungs-Cursor einer noch unvollständigen Seite.

Die gesamte Historie bleibt im Arbeitsspeicher für diese Session, unabhängig von verlorenen Streamframes.
Nach Disconnect ist sie bis zur nächsten Play-Verbindung noch lesbar; diese leert das Journal und
vergibt eine neue Session-ID. Ein Mod-/Client-Neustart leert es ebenfalls. Keine Dateien, Marker,
Screenshots, Inventar-/Kistenstände, Loadouts oder Materiallisten. Der Speicherbedarf wächst mit der
Session-Historie. Das bestehende `/chat/log` mit seinen bisherigen JSON-Feldern bleibt unverändert.
Historische Fakten sind **keine Quelle für aktuellen Zustand**.

## Erfolge und Statistiken

`GET /advancements` liefert `{advancements:[...]}` aus dem aktuellen Advancement-Baum der Verbindung.
Jeder Eintrag enthält `id`, gegebenenfalls `parent`, `title` und `description`, sowie `requirements`
und `progress_known`. Nicht vom Server bekanntgemachte Erfolge fehlen vollständig. Ohne empfangenen
Fortschritt ist `progress_known:false`; `done`, `progress` und `criteria` fehlen dann, statt einen
unerreichten Erfolg vorzutäuschen.

Bei bekanntem Fortschritt enthält der Eintrag `done`, `progress` (0–1) und `criteria:{name:boolean}`.
Ein Kriterium ist erfüllt oder offen; Vanilla liefert keinen Zwischenzähler innerhalb eines Kriteriums.
Die äußere `requirements`-Liste ist UND-verknüpft, jede innere Liste ODER-verknüpft. Der Gesamtfortschritt
zählt erfüllte Anforderungsgruppen, nicht einfach erfüllte Kriterien. Kriteriennamen stammen aus dem
Clientfortschritt; serverseitige Triggerbedingungen werden nicht rekonstruiert. Auch Widerrufe und
entfernte Erfolge werden beim nächsten Lesen berücksichtigt.

`GET /stats` liefert `{stats:[{type,id,value},...],received:boolean}`. Es werden nur Einträge aus dem tatsächlichen
Vanilla-Statistikbestand ausgegeben. Ein fehlender Zähler ist **nicht** null; ein ausdrücklich vorhandener
Nullwert wird ausgegeben. Typen umfassen `minecraft:mined`, `minecraft:used`, `minecraft:crafted`,
`minecraft:killed` und `minecraft:custom` sowie weitere vorhandene Typen wie aufgehoben/weggeworfen.
`used` entspricht Vanillas Benutzungszähler, nicht einem separat berechneten Materialverbrauch.
Allgemeine IDs sind beispielsweise `minecraft:play_time`, `minecraft:deaths` und `minecraft:walk_one_cm`.
`value` bleibt der rohe Vanilla-Ganzzahlwert: Spielzeit in Spielticks (normal 20/s), Strecken in Zentimetern;
andere Zähler behalten ihre jeweilige Vanilla-Einheit.

Beide Endpunkte lesen auf dem Game-Thread live den Clientbestand, ohne eigene Speicherung, Ereignislog,
Hintergrundabfrage oder automatische Serveranfrage. Statistiken können bis zur Vanilla-Synchronisierung
(z. B. beim Öffnen des Statistikbildschirms) fehlen oder den zuletzt empfangenen Wert enthalten.
Die Antwort behauptet keinen frisch abgefragten Serverstand. Ohne aktive Welt liefern beide Routen 409.
Diese Live-Ansichten bleiben unabhängig vom NDJSON-Stream und Session-Journal.

`POST /stats/sync` fordert mit Vanillas `REQUEST_STATS` einmal ein Statistikpaket beim Server an.
Die Antwort `{requested:true}` bestätigt nur das Absenden, ohne auf die Antwort zu warten.
Ohne Verbindung folgt 409. `GET /stats` ergänzt `received`: erst nach einem Statistikpaket der aktuellen
Verbindung (auch einem leeren) true, nach Disconnect oder neuer Verbindung wieder false.
Kein automatisches Polling, keine erfundenen Nullzähler, kein zusätzlicher Statistik-Snapshot.

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
Plugin-Harness sowie `LiveDataHarness`, `ProgressDataHarness`, `SessionEventsHarness`, `ScreenWidgetsHarness`, `RecipeDataHarness`, `HudWidgetsHarness`, `HudDispatchHarness`, `PresentationHarness` und `ClientMetadataHarness` gegen echte Minecraft-26.3-Datenobjekte aus
(ohne gestartetes Minecraft, mit denselben lokalen Build-Abhängigkeiten).
Die HTTP-Tests verwenden freie lokale Ports; der Plugin-Harness verwendet Port 25581.

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

