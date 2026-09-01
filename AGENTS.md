# AGENTS.md — Auto (Paper-Plugin, Minecraft 26.2)

Fahrbare Autos als Paper-Plugin. Java 25, Maven, kein Testframework, kein CI.

## Build (wichtig: System hat KEIN mvn und KEIN javac im PATH!)

Die System-Java ist eine reine JRE (openSUSE trennt JRE/Devel). Bauen geht nur über die mit IntelliJ gebündelte Maven + JetBrains-Runtime:

```bash
JAVA_HOME="$HOME/.local/share/JetBrains/Toolbox/apps/intellij-idea/jbr" \
  "$HOME/.local/share/JetBrains/Toolbox/apps/intellij-idea/plugins/maven-plugin/lib/maven3/bin/mvn" \
  -q -B clean package
```

Artefakt: `target/auto-<version>.jar`. Version im `pom.xml` pflegen (wird per Filtering in die `paper-plugin.yml` injiziert).

## Paper-Plugin-Fallen (alle live gesehen, nicht raten)

- Descriptor ist `paper-plugin.yml` (kein `plugin.yml`!). API-Version: `api-version: '26.2'`.
- Befehle dürfen NICHT in der YAML deklariert werden — das wirft beim Enable `UnsupportedOperationException`. Stattdessen zur Laufzeit: `registerCommand(String, String, Collection<String>, BasicCommand)` aus `JavaPlugin` (siehe `CarCommand.java`); der Befehl heißt `car`, `auto` ist Alias.
- paper-api-Koordinaten: `io.papermc.paper:paper-api:[26.2.build,)` vom Repo `https://repo.papermc.io/repository/maven-public/` (nicht Maven Central).
- Download-API für Server-JARs ist `fill.papermc.io` (die alte api.papermc.io v2 ist sunset).
- Seit der Dimensions-Migration liegt die Overworld-Region unter `world/dimensions/minecraft/overworld/` (auch `entities/`), nicht in `world/region/`.
- Spawn-Chunks laden Entities nach Restart nicht ohne Spieler-Proximität; Auto-Re-Registrierung läuft daher über `EntitiesLoadEvent` + Sweeps in `AutoPlugin.onEnable` (Sofort + 100 Ticks verzögert).

## Verifikation

**Automatisch (der Normalfall):**

```bash
scripts/selftest.sh                    # nur Ergebniszeilen und Fehlschlaege
scripts/selftest.sh --verbose          # zusaetzlich jeder Tick und das komplette Serverlog
scripts/selftest.sh --only step        # nur Szenarien, deren Name "step" enthaelt
scripts/selftest.sh --only kuchen      # bei Sweeps zieht auch das Fall-Label
```

Das Skript baut, laedt bei Bedarf ein Paper-JAR von `fill.papermc.io` nach `.testserver/`
(gitignored), startet einen Server mit **Flachwelt und festem Seed**, laesst dort
`/car selftest` laufen und wertet aus. Exit 0 = alles gruen, 1 = Testfehler, 2 = Harness-Fehler.
Maschinenspezifische Pfade gehoeren in `scripts/env.local` (gitignored, `MVN=`/`JAVA=`).

- **Die Erwartungen stehen in `SelfTest.java`**, nicht in dieser Datei — wer die Physik aendert,
  zieht dort die Konstanten nach (`WALL_CONTACT_MIN/MAX`, `ICE_GRIP`, …).
- Jedes Szenario baut seine Strecke auf einer eigenen Bahn (60 Bloecke Abstand), raeumt auch
  **hinter** dem Start frei (hintere Kollisions-Samples liegen bei z−1,25) und endet in einer
  **Wand**: ohne die faehrt das Auto hinten heraus, faellt in die Flachwelt und fuehrt jede
  Distanzmessung ad absurdum. Zusaetzlich bricht die `minY`-Sicherung so einen Lauf ab.
- **Sweeps** fassen viele kurze Faelle unter einem Namen zusammen und fahren sie zu je
  `BATCH` (12) Stueck **gleichzeitig** auf eigenen Baehnen — eine Matrix ueber alle Stufenhoehen,
  Belagswechsel und Hindernisse waere sonst nicht in vertretbarer Zeit zu fahren (257 Faelle
  in rund vier Minuten). Die Baehnen werden per `addPluginChunkTicket` geladen gehalten:
  ohne Spieler in der Naehe entlaedt der Server sie sonst und `DriveTask` ueberspringt das Auto.
  Geladene Chunks **ticken** aber auch — Ackerland trocknet zu Dirt aus, Schnee schmilzt, Kaktus
  waechst — und das aendert die Strecke waehrend der Messung. `SelfTest.start()` setzt deshalb
  `RANDOM_TICK_SPEED=0` und `DO_FIRE_TICK=false`; ohne das ist der Lauf flaky.
  Ergebniszeile ist eine Bilanz plus eine Zeile je auffaelligem Fall.
- **Erwartungen kommen aus der echten Kollisionsform** (`supportTop`) und den Physik-Konstanten
  (`DriveTask.MAX_STEP`, `MAX_STEP_DOWN`), nicht aus einer gepflegten Hoehentabelle — die waere
  mit der naechsten Minecraft-Version falsch, ohne dass es auffaellt. Die Sweeps loggen die
  gemessene Oberkante jedes Blocks mit; `--verbose` ergibt damit eine lebende Referenztabelle.
- `knownFail` markiert Szenarien und einzelne Sweep-Faelle fuer bekannte, offene Bugs: sie duerfen
  fehlschlagen, ohne den Lauf rot zu faerben. Besteht so einer ploetzlich, meldet der Lauf
  `UNEXPECTED-PASS` und wird rot — dann ist der Bug gefixt und das Flag muss weg.
- **Aktuell offen (`knownFail`) — drei Auspraegungen derselben Wurzel:** die Kollision ist
  zellenweise gedacht. `columnObstacleTop` behandelt **jede** Belegung der Kopf-Zelle
  `floor(y)+1` als unueberwindbar, egal wie flach das Hindernis wirklich ist. Steht das Auto
  auf einer Oberkante zwischen zwei Zellgrenzen (Ackerland, Schlamm, Schnee, Stufen), ragt der
  Nachbarbelag genau dort hinein:
  1. `step-up-from`: **eine ganze 1-Block-Stufe ist von Ackerland/Grasweg (0,9375) und
     Schlamm/Seelensand (0,875) aus nicht befahrbar** (Stufe misst 1,0625 bzw. 1,125). Von
     Stein, Gras, Beton und Kies aus geht dieselbe Stufe.
  2. `step-down-heights`: **ein Abstieg zwischen `MAX_STEP` (1,0) und `MAX_STEP_DOWN` (1,2)
     ist eine Falle** — das Auto folgt dem Boden hinunter, steht dann mit dem Heck-Sample vor
     einer Kante ueber `MAX_STEP` und kommt fuer immer nicht mehr heraus (v=0, `blocked=true`).
     Tiefere Abstiege sind harmlos, weil sie als Fall behandelt werden. Praxisfall: von einer
     Steinstrasse einen Block hinunter auf ein Feld.
  3. `slope-subblock`: **jede Teilblock-Rampe (Schnee, Stufen) blockiert bergauf**; bergab
     bleiben 3/8, 5/8 und 7/8 je Block stecken, 1/8, 1/4, 1/2 und 3/4 kommen durch. Welche
     Neigung haengt, entscheidet die zufaellige Lage der Oberkanten zu den Zellgrenzen — die
     Liste im Code ist bewusst der IST-Stand.
- **Aktuell offen (`knownFail`), unabhaengige Wurzel:** `environment/wasser-sinken` — das Auto
  sinkt mit rund **30 km/h statt der konfigurierten 9** (`max-sink-speed`). `applyGravity`
  addiert je Tick erst die volle Erdbeschleunigung und daempft danach nur 15 % des Ueberschusses
  je 0,25-Bloecke-Substep; der Fixpunkt dieser Folge liegt weit ueber dem Sollwert. Der Key
  wirkt als Richtgroesse, nicht als Grenze — entweder die Daempfung anziehen oder ihn umbenennen.
- **Kein Bug, sondern Folge der Fahrzeuglaenge:** eine 45-Grad-Treppe (ein Block je Block) ist
  bergauf nicht befahrbar. Auf Stufe N steht die 1,25 Bloecke lange Nase schon ueber Stufe N+1,
  `canStandAt` lehnt ab. Ab einem Block auf zwei geht es (`slope-up`), bergab geht auch 45 Grad.
- Die flache 1/16-Kante (Farmland -> Vollblock auf gleicher Hoehe) ist dagegen nachweislich in
  Ordnung — die `edge-*`-Szenarien decken gerade/schraege/quere/drehende Anfahrt, Kriechtempo,
  Anfahren aus dem Stand, Fahrt laengs auf der Kante, ein Farmland-Feld mitten in Stein und
  negative Weltkoordinaten ab. Wer dort erneut ein Steckenbleiben meldet: erst pruefen, ob das
  Ziel nicht doch einen ganzen Block hoeher liegt (dann greift der Punkt darueber).
- **Umgebung und Grip** decken die Sweeps `environment` und `grip-crash` ab: Wasser (Sinken,
  Querbremsung), Lava als Wand, freier Fall gegen `max-fall-speed` mit exaktem Aufsetzen,
  harte Landung, Einrasten auf halber Blockhoehe, Steigungs-Totalstopp und Energiegewinn
  bergab; dazu die Grip-Tabelle als Funktionspruefung (inklusive: Betonpulver ist KEIN Beton —
  das haengt allein am Namenssuffix), die Grip-Halbierung mit zwei Raedern ueber der Kante,
  Schlupfaufbau auf Stein gegen Eis, Standfest-Hartschnapp gegen Ausrollen auf Eis, der
  Karosserie-Dreh aus einem aussermittigen Wandtreffer, das Herausfahren aus der Geometrie
  (`embedded`) und Wandkontakt bei vollen 162 km/h ohne Tunneling.
- **Ein Szenario darf die Physik-Konfiguration umstellen** (`crash-restitution-null` tut das):
  `startNext()` ruft vor jedem Szenario `config.reload()`, damit sich das nicht fortpflanzt —
  auch dann nicht, wenn ein Lauf abbricht und die Pruefung nie laeuft. In einem Sweep geht das
  NICHT: dessen Faelle teilen sich als parallele Gruppe dieselbe Config.
- Flaechendeckend abgesichert sind ausserdem: **jede erreichbare Stufenhoehe** von 1/16 bis
  1 1/2 Bloecken hinauf (`step-up-heights`) und 1/16 bis 5 Bloecke hinunter
  (`step-down-heights`), **jeder Belagswechsel** als vollstaendige 10x10-Matrix
  (`surface-transition`, deckt "von Grasweg auf Vollblock" in beide Richtungen ab), **Rampen**
  aus ganzen Bloecken von 45 Grad bis 1 auf 8 in beide Richtungen (`slope-up`/`slope-down`)
  und **54 echte Bloecke einzeln ueberfahren** (`drive-over`: Kuchen, Bett, Truhe, Kessel,
  Amboss, Zaun, Schiene, Druckplatte, Weizen, Seerose, Pulverschnee, Spinnennetz, Kaktus …).
  Wer eine Stelle im Spiel meldet, an der das Auto haengt: erst `--only` auf den passenden
  Sweep, dann den Belag der Fundstelle in die Palette in `SelfTest.java` aufnehmen.
- **Fahrer-Eingaben ohne Fahrer:** `SimInput` implementiert `org.bukkit.Input` (eine reine
  Schnittstelle aus sieben Boolean-Gettern). `DriveTask` liest ohne Fahrer
  `car.getSimInput()`, damit Gas, Bremse, Handbremse, Rueckwaertsgang und A/D-Lenkung durch
  **dieselbe** Stelle laufen wie beim Spieler (`applyInput`, Handbrems-Zweig, Lenk-Deckel).
  Vorher hatte die Simulation die Gas-Formel nachgebaut — der Test prueft seitdem die
  Produktivstelle statt einer Kopie. Der Sweep `driver-input` deckt Fussbremse, Handbremse,
  W+S, Ausrollen, Rueckwaerts-Anfahren, `max-speed`-Limiter, Lenk-Deckel, `turn-min-speed`
  und den Handbrems-Grip ab. `car.setSimDrive(true)` bleibt als Kurzform fuer Vollgas.
  `car.setSimDrift(true)` dreht die Karosse stur weiter und provoziert damit Schlupf.
- Nicht automatisierbar und weiterhin manuell: **Maus**lenkung und Spieler-Prefs (beides braucht
  einen echten Spieler), Actionbar, Modell-Optik (Pitch/Roll-Vorzeichen sind headless
  unsichtbar), Client-Autocomplete, Resourcepack.

**Manuell (einzelne Fahrt ansehen):** `/car sim <speed> [drift] [gap] [ice] [stairs] [drive]`
(nur Konsole, taucht im Autocomplete nicht auf; negativer speed = Rueckwaertsfahrt vom Startpunkt weg)
baut eine kontrollierte Strecke (flach, y=60, drei Spalten breit x=199..201) bei x/z=200/200 mit
Steinwand bei z+6 und loggt `[Sim]`-Zeilen pro Tick. Erwartung: Wand-Kontakt bei z≈204,6 (die Nase
beruehrt die Wand — Footprint 1,8 × 2,5 Bloecke, halbe Laenge 1,25!), danach Rueckprall ~1,4 Bloecke
(crash-restitution 25 %, Rebound-Cap 0,10 Bl/tick; `crash-restitution 0` = alter harter Stopp),
kein Tunneling ueber z=206 hinaus. `gap` entfernt Boden bei z+2..+5 (4 Bloecke — kuerzere Loecher ≤2
ueberbrueckt der Footprint, weil stets ein Rad gestuetzt bleibt), `ice` = Packeis (grip=0,15),
`stairs` laesst die Strecke ab z+3 stufenweise abfallen, `drive` = Gas-Simulation.
Diese Strecke raeumt NICHT hinter dem Start frei: in generiertem Gelaende steht der hintere
Kollisions-Sample im Berg und das Auto meldet sofort `blocked=true` — dafuer eine Flachwelt nehmen.

- `/car config <key> <wert>` wirkt LIVE (Reload ohne Restart), `/car prefs` ist pro Spieler.

## Architektur-Kernstellen (nicht aus Dateinamen erkennbar)

- Ein Auto = ArmorStand (Sitz, PDC-Marker `auto:car`) + ItemDisplay (Modell, Passagier) + Interaction (Klick-Hitbox, Passagier). Fahrer = Spieler-Passagier auf dem ArmorStand. Entity-Aufbau zentral in `CarManager` (spawnCar / reRegister / ensureParts). Sitzhöhe hängt am SCALE-Attribut des Stands (`SEAT_SCALE` ≈ +0,1 Blöcke über Standard); `MODEL_Y_OFFSET` hält das Modell dagegen fest.
- Input: `player.getCurrentInput()` (semantisch, respektiert Keybinds; funktioniert auch beim Reiten nicht steuerbarer Entities — Fahrer muss aber serverseitig wirklich Passagier sein). Ohne Fahrer greift `car.getSimInput()` (`SimInput implements org.bukkit.Input`) — derselbe Pfad, nur ohne Spieler; Maus-Lenkung und Prefs sind dort naturgemäß aus.
- Physik (`DriveTask`): Zustand ist ein Geschwindigkeitsvektor (`Car.velX/velZ`) + `yaw` (geglättete Lenk-Drehrate `Car.yawVel`, Crash-Drehrate `Car.spinVel`), kein Skalar. Grip gibt es NUR bei Bodenkontakt: `grounded`/Grip kommen aus VIER Rad-Samples (±0,9 längs, ±0,7 quer, je 1 Block Federungstoleranz via `wheelSupport`) — halb über der Kante = halber Grip, in der Luft kein Antrieb/Bremse/Lenkung/ALIGN (ballistisch, nur Drag). Antrieb/Bremse wirken auf die Fahrtrichtungskomponente `vf`, weich Richtung `max-speed` begrenzt (Faktor `1 − vf/max` statt hartem Cut); Motorbremse × grip nur ohne Fahrpedal am Boden, Drag immer. Lenkung = min(Lenkrad-Anschlag `turn-curvature` °/m × |v|, Grip-Budget `maxLatGrip·grip/|v|`); der Vektor folgt mit `ALIGN_FRACTION=0.65` des Budgets der **Rollrichtung** (`travelYaw` = yaw bzw. yaw+180 bei Rückwärtsfahrt — niemals stur yaw!), Rotation nur oberhalb `turn-min-speed`; Schlupf wird durch laterale Reibung gefressen (`FRICTION_FRACTION`). Handbremse = Sprungtaste: `handbrake-deceleration` × Grip auf vf, Folge-Grip × `handbrake-grip` — die Lenkung behält vollen Grip (Vorderräder). Bei Wandkontakt darf im Stand rangiert werden (`CRAWL_TURN_DEG`). Standfest-Hartschnapp unter ~0,5 km/h nur bei grip ≥ 0,4 — auf Eis rollt das Auto aus.
- Kollision (`resolveStep`): achsenweise, substep-weise alle 0,4 Blöcke, mit yaw-ausgerichtetem 3×3-Footprint (Nase/Heck/Ecken, 1,8×2,5 Blöcke = reale Maße; Interaction-Hitbox 2,5²×1,8) und anderen Autos als Hindernis. Stufen kommen aus der Kollisionsform (`supportTop`, max. 1 Block, Slabs/Treppen befahrbar); pro Sample folgt die Fahrzeughöhe dem Boden bis 1,2 Blöcke abwärts (`followGroundDown`, bergab kein Losfliegen) + `downhill-assist` gibt pro Abstieg Schub in Fahrtrichtung. Bei Blockade steht das Auto am letzten freien Sample — und reagiert physikalisch: gedeckelte Restitution (`crash-restitution`, positiver Abprall ≤0,10 Bl/tick via `CRASH_REBOUND_MAX`; Auto-Auto nur halb, ohne Spin, Hitch-Guard unter ~5 km/h = `CRASH_MIN_SPEED`) plus Drehimpuls aus dem Aufprall-Hebel (`crash-spin` × τ = Hebel × Impuls, landet in `Car.spinVel` und dreht NUR die Karosse; der Geschwindigkeitsvektor folgt grip-begrenzt per ALIGN hinterher → emergentes Schleudern; Decay am Boden × (1 − 0,25·grip), in der Luft nahezu erhalten). `embedded` prüft nur die Fahrzeugmitte (Fuß ragt über Niveau ODER Kopf massiv) — Nase/Heck NICHT (in der Luft neben Böschung sonst Fehlalarm → Tunnel-Bug!).
- Vertikal: Fall mit Substep-Abtastung bis `max-fall-speed` (konfigurierbar, Default 144 km/h), Landung snappt auf die echte Blockoberkante (Slab-Höhe!), harte Landung (≥~36 km/h vertikal) dämpft quer und ist hörbar. Wasser blockiert nicht, bremst stark und trägt nicht → Auto sinkt (Lava bleibt Wand); der Sink-Fall nähert sich dabei asymptotisch `max-sink-speed` (Default 9 km/h, Faktor 0,85/Tick) statt wie ein Stein durchzurauschen.
- **Vorsichts-Falle:** `StepResult.blocked` ist leicht invertierbar — `true` heißt „Route nicht frei"; x/z zeigen dann auf den letzten freien Sample.

## Config-Konventionen

- `config.yml` ist menschenlesbar (km/h, m/s², %, °/s); `CarConfig.reload()` konvertiert in Blöcke/Tick UND clampt jeden Wert über `clampHumanValue` auf dessen Sinn-Bereich — genau denselben wirksamen Wert zeigt `/car config` an (ein Display-RoHWert-Bug zeigte sonst abweichende Zahlen). Neue Keys müssen in `CarConfig.NUMBER_KEYS`/`BOOL_KEYS` (Komplettierung + Migration lesen daraus).
- `config-version` im YAML: bei Änderung hochzählen (`AutoPlugin.CONFIG_VERSION`, aktuell 8); die Migration sichert automatisch als `config.veraltet.yml` und übernimmt unveränderte Keys.
- Permissions: `car.use`/`car.prefs` (Default true), `car.give`/`car.config` (Default op) stehen in der `paper-plugin.yml`; die Pro-Key-Nodes `car.config.<key>` und der Sammelknoten `car.config.*` entstehen zur Laufzeit aus `CarConfig.NUMBER_KEYS`/`BOOL_KEYS` (`CarPermissions.register`) — ein neuer Config-Key bekommt seine Node damit automatisch. Lesen deckt `car.config` ab, Setzen braucht die Key-Node. Geprüft wird nur über `hasPermission`, nie `isOp`, damit Permissions-Plugins überschreiben können. `car.give` hieß früher `auto.give`.
- Spieler-Prefs liegen in `prefs.yml` (`PlayerPrefs`); der frühere Key `reverse_invert_mouse` wurde zu `reverse_invert` umbenannt und wird beim Laden still migriert. Keys: `mouse_steer`, `reverse_invert`, `actionbar` (Hauptschalter), `actionbar_speed`, `actionbar_grip` (Grip-Budget-Balken; ≥100 % = Reifen am Limit; zählt Quer- UND Pedalkraft als Traktionskreis); alles Default an.
- Untersteuer-Sound ist `ENTITY_HORSE_DEATH` mit Pitch 0 (gewollt so, Cooldown 18 Ticks über `tickCount` in `DriveTask` — tick-basiert statt Wall-Clock, lag-stabil).
- Physik-Konstanten gelten pro Tick (20-TPS-Annahme): bei anhaltendem Server-Lag driften reale Beschleunigungs-/Bremswerte. Bewusste, hier dokumentierte Limitation — keine dt-Kompensation implementiert.
- Modell-Optik ist separat vom Physik-Zustand: Pitch folgt der echten Tick-Steigung (EMA-geglättet, ±25°) und Roll der Querbeschleunigung (±12°); nur Anzeige, niemals Physik; die Achsen-Vorzeichen sind headless nicht prüfbar — bei falschem Drehsinn im Code-Kommentar markierten Flip vornehmen.

## Repo-Konventionen

- Deutsch für User-Meldungen/Kommentare, englische Bezeichner. Commits: semantisch + deutsch (`feat(physik): ...`), Hauptzweig `main`.
- Kein JUnit/Mock-Framework (die Physik liest echte Bloecke, Mocks wuerden nur meine Annahmen bestaetigen); Verifikation = `scripts/selftest.sh` (Exit 0). Nutzer-Wert zuerst am Server prüfen, wenn sich Fahrphysik ändert.
- `.omo/` ist lokal (nicht committen). `.testserver/` ist der Wegwerf-Testserver des Selftests (gitignored, wird bei Bedarf neu erzeugt).
