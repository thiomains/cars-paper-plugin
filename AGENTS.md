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
- **Der Lauf pinnt die Physik-Konfiguration** (`SelfTest.PINNED_CONFIG`, im Speicher, die Datei
  wird nie angefasst; `summary()` stellt sie zurueck). Die ausgelieferten Defaults sind eine
  Produkt-Entscheidung ueber das Fahrgefuehl und duerfen sich aendern, ohne dass ein halbes
  Dutzend Szenarien rot wird — geprueft wird die Physik, nicht der Geschmack. Ausserdem laeuft
  der Test damit unabhaengig von der `config.yml` des Servers, auf dem er zufaellig startet
  (eine dort veraltete Datei hat schon Testfehler gemeldet, die keine waren). Wer die
  Defaults aendert, muss `PINNED_CONFIG` NICHT anfassen; wer die Erwartungen kalibriert, schon.
- Jedes Szenario baut seine Strecke auf einer eigenen Bahn (60 Bloecke Abstand), raeumt auch
  **hinter** dem Start frei (hintere Kollisions-Samples liegen bei z−1,25) und endet in einer
  **Wand**: ohne die faehrt das Auto hinten heraus, faellt in die Flachwelt und fuehrt jede
  Distanzmessung ad absurdum. Zusaetzlich bricht die `minY`-Sicherung so einen Lauf ab.
- **Sweeps** fassen viele kurze Faelle unter einem Namen zusammen und fahren sie zu je
  `BATCH` (12) Stueck **gleichzeitig** auf eigenen Baehnen — eine Matrix ueber alle Stufenhoehen,
  Belagswechsel und Hindernisse waere sonst nicht in vertretbarer Zeit zu fahren (303 Faelle
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
- **Derzeit ist kein `knownFail` gesetzt** — der Lauf ist vollstaendig gruen. Die frueher hier
  gelisteten offenen Bugs sind gefixt; drei davon teilten eine Wurzel und sind der Grund fuer
  den heutigen Zuschnitt der Kollision (nicht wieder zellenweise denken!):
  1. `columnObstacleTop` misst die **echte Hoehe** eines Hindernisses: es sieht jede Zelle von
     der Fuss-Zelle bis `floor(y + MAX_STEP)` an und vergleicht die Kollisions-Oberkante gegen
     `y + MAX_STEP`. Frueher galt **jede** Belegung der Kopf-Zelle `floor(y)+1` pauschal als
     unueberwindbar — stand das Auto auf einer gekappten Oberkante (Ackerland, Schlamm, Schnee,
     Stufen), ragte der Nachbarbelag genau dort hinein und sperrte Teilblock-Rampen komplett.
  2. `MAX_STEP` ist **1,125** und nicht 1,0: eine ganze 1-Block-Stufe misst von Schlamm/
     Seelensand (0,875) aus 1,125 und von Ackerland/Grasweg (0,9375) aus 1,0625. Was wie ein
     Block aussieht, muss auch von einem gekappten Belag aus befahrbar sein.
  3. `followGroundDown` UND `applyGravity` fuehren das Auto als **starren Koerper**: es sinkt nur
     so weit, wie die hoechste Stuetze unter der Aufstandsflaeche es zulaesst (`supportTopBelow`),
     und faellt nur, wenn dort gar nichts traegt. Zog die Schwerkraft nur an der Mitte, holte sie das Auto nach jedem
     Stufenaufstieg sofort wieder herunter — und die Stossstange rammte danach die uebernaechste
     Stufe. Zaehlte nur die Mitte, fiele es je
     Substep auf ihr Niveau zurueck und muesste die Steigung mit der Nase doppelt nehmen —
     ab 5/8 Block Anstieg je Block riss das `MAX_STEP` und die Rampe blockierte.
  4. `canStandAt` verbietet auf dem Zielniveau nur noch eine **Wand** (`> y + MAX_STEP`), nicht
     mehr jede Erhebung. Der alte Test (`top <= y`) hat die naechste Stufe verweigert, sobald
     die Nase schon ueber der uebernaechsten hing — bei Yaw greift die Ecke bis 1,54 Bloecke
     voraus, auf einer Treppe ist das immer der Fall. Das Auto rammte dann die Kante und bekam
     Drehimpuls statt Hoehe.
  5. Rasterpunkte **hinter der Fahrtrichtung** blockieren nicht (`footprintObstacle` /
     `followGroundDown` filtern ueber das Skalarprodukt mit dem Substep-Vektor). Aus einer
     Lage, in die das Auto hineingefahren ist, muss es immer wieder herausfahren koennen —
     sonst sperrt es ein Abstieg zwischen `MAX_STEP` und `MAX_STEP_DOWN` fuer immer ein: das
     Heck-Sample steht danach vor einer Kante ueber `MAX_STEP`.
- **Die 45-Grad-Treppe (ein Block je Block) ist befahrbar, auch aus dem Stand** — sie war
  vorher als "Folge der Fahrzeuglaenge" dokumentiert. Dafuer mussten drei Stellen dieselbe
  Starrkoerper-Regel befolgen: Fahrniveau (`followGroundDown`), Fall (`supportTopBelow`) und
  **Rad-Auflage** (`probeWheels`). Fehlt die letzte, traegt nur die Vorderachse, der Grip
  halbiert sich und die Leistung reicht nicht mehr. Die Szenarien `treppe-aus-dem-stand` und
  `gas-nach-stufe` pruefen das — und fahren als einzige mit den AUSGELIEFERTEN Defaults statt
  der gepinnten Testphysik, weil genau das die Produktfrage ist.
  Bergab geht 45 Grad ohnehin.
- Die flache 1/16-Kante (Farmland -> Vollblock auf gleicher Hoehe) ist dagegen nachweislich in
  Ordnung — die `edge-*`-Szenarien decken gerade/schraege/quere/drehende Anfahrt, Kriechtempo,
  Anfahren aus dem Stand, Fahrt laengs auf der Kante, ein Farmland-Feld mitten in Stein und
  negative Weltkoordinaten ab. Wer dort erneut ein Steckenbleiben meldet: erst pruefen, ob das
  Ziel nicht doch einen ganzen Block hoeher liegt (dann greift der Punkt darueber).
- **Steigungen kosten Energie, aber als Schuld:** die Hoehe kommt in Spruengen (die Nase nimmt
  eine ganze Stufe in einem Tick), der Vortrieb dagegen stetig. Die volle Lageenergie einer
  Stufe gegen die Momentangeschwindigkeit gerechnet ergibt eine Rechnung, die im Kriechtempo
  niemand bezahlen kann — das Auto blieb an einer Treppe fuer immer stehen, egal wie klein
  `slope-resistance` war (v²-Totalstopp ist ein absorbierender Zustand). `DriveTask` fuehrt die
  Hoehendifferenz deshalb als `Car.slopeDebt` und traegt sie ueber eine Fahrzeuglaenge ab
  (`SLOPE_SPREAD` = 2,5): je Tick faellt der ANTEIL an, der auf die gefahrene Strecke entfaellt.
  Zwei Fallen, beide schon dagewesen:
  * **Restschuld verfaellt** (`SLOPE_DEBT_FADE`), sobald es nicht mehr bergauf geht. Ohne das
    ueberlebt die Steigung sich selbst: abgetragen wird nur ueber Fahrstrecke, und der Abzug ist
    tempo-unabhaengig. Wer oben langsam ankommt, haengt in einer Rueckkopplung fest — kaum
    Beschleunigung, kaum Strecke, Schuld bleibt (live gesehen: 1 km/h auf der Ebene).
  * **Die Lageenergie mit cos² umrechnen.** `vx/vz` sind die WAAGERECHTE Komponente, die
    Lageenergie haengt an der Bahn. Ohne den Faktor kostet eine 45-Grad-Steigung doppelt so viel
    wie physikalisch richtig; flaches Gelaende merkt davon nichts.
  * **Nicht auf 45 Grad deckeln.** Mit hartem Deckel kostet die Restschuld `g × slope-resistance`
    je Tick — unabhaengig vom Tempo. Nach jeder einzelnen Stufe faehrt das Auto dann weiter wie
    an einer Dauersteigung und das Gaspedal fuehlt sich tot an. Anteilig konvergiert die Schuld
    am Berg von selbst auf die echte Steigung (was je Block dazukommt, wird je Block faellig).
  * **Die Schuld nur im echten Fall streichen**, nicht bei Stillstand. Der Standfest-Hartschnapp
    greift nur OHNE Pedal — ein kurzes Loslassen von W setzte `wantsMove` auf false und schenkte
    die ganze aufgelaufene Steigungsenergie. Das Szenario `gas-nach-stufe` haelt beides fest. Damit entscheidet die Leistungsbilanz statt
  eines Rundungszufalls: **befahrbare Steigung = `acceleration/400 × grip ≥ 0,08 × slope-resistance
  × Gefaelle`**. Mit den Defaults (5 m/s², 10 %, Grip 0,7) sind das rund 1,1 — 45 Grad gehen
  also gerade so, und zwar nur, weil auf der Treppe alle vier Raeder zaehlen (siehe unten).
  Wer die Defaults weiter drosselt, verliert die Treppe: der Selftest sagt es dann.
- **Sinken im Wasser:** `applyGravity` addiert im Wasser **keine** Erdbeschleunigung mehr,
  sondern laesst die Sinkgeschwindigkeit asymptotisch auf `max-sink-speed` zulaufen (Faktor
  0,85 je Substep, von oben wie von unten). Vorher wurde erst die volle Gravitation addiert und
  danach nur 15 % des Ueberschusses gedaempft — der Fixpunkt dieser Folge lag bei rund 30 statt
  9 km/h, der Key war eine Richtgroesse statt einer Grenze.
- **Umgebung und Grip** decken die Sweeps `environment` und `grip-crash` ab: Wasser (Sinken,
  Querbremsung), Lava als Wand, freier Fall gegen `max-fall-speed` mit exaktem Aufsetzen,
  harte Landung, Einrasten auf halber Blockhoehe, Steigungs-Totalstopp und Energiegewinn
  bergab; dazu die Grip-Tabelle als Funktionspruefung (inklusive: Betonpulver ist KEIN Beton —
  das haengt allein am Namenssuffix), das Abkippen mit nur zwei tragenden Raedern (ueber einer Kante wie ueber einem Bordstein),
  Schlupfaufbau auf Stein gegen Eis, Standfest-Hartschnapp gegen Ausrollen auf Eis, der
  Karosserie-Dreh aus einem aussermittigen Wandtreffer, das Herausfahren aus der Geometrie
  (`embedded`) und Wandkontakt bei vollen 162 km/h ohne Tunneling.
- **Konfiguration, Migration und Entities** deckt der Sweep `config-registry` ab (reine
  Funktionspruefungen, kostenlos im Lauf): jeder Key hat einen Default in der ausgelieferten
  `config.yml` und umgekehrt kennt jede Liste jeden Key; `config-version` in der Datei stimmt
  mit `AutoPlugin.CONFIG_VERSION` ueberein; jede Einheitenumrechnung (km/h, m/s², %, Luftwiderstand)
  wird aus dem Rohwert nachgerechnet; **jeder** Zahlen-Key hat eine Obergrenze (`clampHumanValue`;
  der `default`-Zweig bleibt absichtlich ohne Deckel, damit ein neuer Key im Fall
  `config-obergrenzen` auffaellt); `AutoPlugin.carryOver` uebernimmt bekannte Keys und
  verwirft unbekannte; `PlayerPrefs` migriert `reverse_invert_mouse` und liefert die Defaults;
  das Autocomplete zeigt nie `sim`/`selftest` und schlaegt bei `/car config <key> <wert>`
  **keinen** Wert vor (der aktuelle Wert wurde sonst beim Tippen mit uebernommen); ein Auto besteht aus drei markierten Entities,
  `ensureParts` baut ein entferntes Modell nach und `removeCar` laesst nichts zurueck.
  Fuer die letzten beiden Punkte nehmen `PlayerPrefs` und `SelfTest` ihre Datei bzw. ihre
  Abhaengigkeiten injiziert entgegen — die echte `prefs.yml` eines Servers wird nie angefasst.
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
  unsichtbar), Client-Autocomplete, Resourcepack, Spielmodus-Verhalten des Listeners
  (Kreativmodus droppt beim Abbauen kein Item, so wie er beim Platzieren keines abzieht) und
  die **Hupe** (der Klick braucht einen echten Spieler; automatisch geprueft sind nur die
  Config-Seite und die Aufloesung des Sound-Namens).

**Manuell (einzelne Fahrt ansehen):** `/car sim <speed> [drift] [gap] [ice] [stairs] [drive]`
(nur Konsole, taucht im Autocomplete nicht auf; negativer speed = Rueckwaertsfahrt vom Startpunkt weg)
baut eine kontrollierte Strecke (flach, y=60, drei Spalten breit x=199..201) bei x/z=200/200 mit
Steinwand bei z+6 und loggt `[Sim]`-Zeilen pro Tick. Erwartung: Wand-Kontakt bei z≈204,6 (die Nase
beruehrt die Wand — Footprint 1,8 × 2,5 Bloecke, halbe Laenge 1,25!), danach Rueckprall ~1,4 Bloecke
(crash-restitution 25 %, Rebound-Cap 0,10 Bl/tick; `crash-restitution 0` = alter harter Stopp),
kein Tunneling ueber z=206 hinaus. `gap` entfernt Boden bei z+2..+5 (4 Bloecke — nur ein 1-Block-Loch
ueberbruecken die Achsen noch, der Radstand ist 1,4), `ice` = Packeis (grip=0,15),
`stairs` laesst die Strecke ab z+3 stufenweise abfallen, `drive` = Gas-Simulation.
Diese Strecke raeumt NICHT hinter dem Start frei: in generiertem Gelaende steht der hintere
Kollisions-Sample im Berg und das Auto meldet sofort `blocked=true` — dafuer eine Flachwelt nehmen.

- `/car config <key> <wert>` wirkt LIVE (Reload ohne Restart), `/car prefs` ist pro Spieler.

## Architektur-Kernstellen (nicht aus Dateinamen erkennbar)

- Ein Auto = ArmorStand (Sitz, PDC-Marker `auto:car`) + ItemDisplay (Modell, Passagier) + Interaction (Klick-Hitbox, Passagier). Fahrer = Spieler-Passagier auf dem ArmorStand. Entity-Aufbau zentral in `CarManager` (spawnCar / reRegister / ensureParts). Sitzhöhe hängt am SCALE-Attribut des Stands (`SEAT_SCALE` ≈ +0,1 Blöcke über Standard); `MODEL_Y_OFFSET` hält das Modell dagegen fest.
- Input: `player.getCurrentInput()` (semantisch, respektiert Keybinds; funktioniert auch beim Reiten nicht steuerbarer Entities — Fahrer muss aber serverseitig wirklich Passagier sein). Die **Mauslenkung** (`mouseSteer`) misst den Blickwinkel gegen die FAHRZEUGACHSE: beide Achsenrichtungen sind geradeaus (nach vorn wie nach hinten schauen lenkt nicht), quer bei 90° liegt das Rad voll an, dazwischen linear mit 4° Totzone. Dieselbe Abbildung gilt damit vorwärts wie beim Blick über die Schulter; ohne sie stand beim Zurückschauen sofort Vollausschlag an (Rohwinkel ~180° direkt gedeckelt). Den Drehsinn beim Rückwärtsfahren entscheidet danach die Pref `reverse_invert`. Ohne Fahrer greift `car.getSimInput()` (`SimInput implements org.bukkit.Input`) — derselbe Pfad, nur ohne Spieler; Maus-Lenkung und Prefs sind dort naturgemäß aus.
- Physik (`DriveTask`): Zustand ist ein Geschwindigkeitsvektor (`Car.velX/velZ`) + `yaw` (geglättete Lenk-Drehrate `Car.yawVel`, Crash-Drehrate `Car.spinVel`), kein Skalar. Grip gibt es NUR bei Bodenkontakt: `grounded`/Grip kommen aus VIER Rad-Samples (`probeWheels`, ±0,7 längs und quer, also Radstand und Spurweite 1,4) — jedes nicht tragende Rad zählt mit 0 in die Division durch vier. Drei Regeln bestimmen, ob ein Rad trägt: **(1) Reichweite aus dem Gelände** — Bezug ist der Boden unter der Fahrzeugmitte (`contactGround`), von dort darf ein Rad noch `MAX_STEP_DOWN` tiefer stehen. Auf einer Treppe liegt die Hinterachse zwei Stufen unter dem Fahrniveau, aber nur eine unter dem Boden der Mitte — sie trägt also. Findet die Mitte nichts (Brücke über ein Loch, freier Fall), bleibt das Fahrniveau der Bezug, sonst griffe ein Rad im Fall nach Boden zwei Blöcke tiefer. **(2) Starre Achse** — je Achse hebt das Rad ab, das mehr als `AXLE_TRAVEL` (0,5) unter seinem Gegenstück hängt: ein Rad auf dem Bordstein und eines auf der Straße sind keine Achse, die sich um einen ganzen Block verwindet. **(3) Kippen statt balancieren** — unter drei tragenden Rädern gibt es keine Auflagefläche mehr, sondern nur noch eine Kippachse (`Wheels.stable()`); das Auto bekommt dann `tip-acceleration` Schub zur unbelasteten Seite und rutscht ab (0 = balancieren erlaubt). Der Schub muss über dem liegen, was `FRICTION_FRACTION` je Tick wegnimmt, sonst zappelt es nur auf der Kante. Ein 2-Block-Loch fällt damit genau zwischen die Achsen und wird NICHT mehr überbrückt, ein 1-Block-Loch schon. In der Luft gibt es kein Antrieb/Bremse/Lenkung/ALIGN (ballistisch, nur Drag). Antrieb/Bremse wirken auf die Fahrtrichtungskomponente `vf`, weich Richtung `max-speed` begrenzt (Faktor `1 − vf/max` statt hartem Cut); Motorbremse × grip nur ohne Fahrpedal am Boden, Drag immer. Lenkung = min(Lenkrad-Anschlag `turn-curvature` °/m × |v|, Grip-Budget `maxLatGrip·grip/|v|`); der Vektor folgt mit `ALIGN_FRACTION=0.65` des Budgets der **Rollrichtung** (`travelYaw` = yaw bzw. yaw+180 bei Rückwärtsfahrt — niemals stur yaw!), Rotation nur oberhalb `turn-min-speed`; Schlupf wird durch laterale Reibung gefressen (`FRICTION_FRACTION`). Handbremse = Sprungtaste: `handbrake-deceleration` × Grip auf vf, Folge-Grip × `handbrake-grip` — die Lenkung behält vollen Grip (Vorderräder). Bei Wandkontakt darf im Stand rangiert werden (`CRAWL_TURN_DEG`). Standfest-Hartschnapp unter ~0,5 km/h nur bei grip ≥ 0,4 — auf Eis rollt das Auto aus.
- Kollision (`resolveStep`): achsenweise, substep-weise alle 0,4 Blöcke, mit **zwei** yaw-ausgerichteten Rastern und anderen Autos als Hindernis. **Karosserie** (`GRID_*`, ±1,25 längs / ±0,9 quer = reale Maße 1,8×2,5; Interaction-Hitbox 2,5²×1,8) BLOCKIERT gegen Wände — die Stoßstange trifft die Wand vor den Rädern. **Aufstandsfläche** (`SUPPORT_*`, ±0,7 längs und quer plus Mitte = Achsen und Unterboden) TRÄGT — sie allein bestimmt Fahrniveau, Bodenkontakt und Schwerkraft. Reihenfolge im Substep: erst heben die Achsen, dann prüft die Karosserie. Wer beides vermischt, hebt das Auto schon an, wenn die Stoßstange über einer Stufe hängt: auf einer Treppe greift die Ecke bei Yaw bis 1,54 Blöcke voraus, das Auto springt zwei Stufen hoch und fällt eine zurück. Stufen kommen aus der Kollisionsform (`supportTop`, max. `MAX_STEP` = 1,125 Blöcke, Slabs/Treppen befahrbar; `columnObstacleTop` sieht dafür jede Zelle bis `floor(y + MAX_STEP)` an, statt die Kopf-Zelle pauschal zu sperren); pro Sample folgt die Fahrzeughöhe dem Boden bis 1,2 Blöcke abwärts (`followGroundDown`, bergab kein Losfliegen); und das Auto **fällt nur, wenn nichts unter der Aufstandsfläche trägt** (`supportTopBelow` in `applyGravity`) — auf einer Treppe steht es mit der Vorderachse auf der nächsten Stufe, während unter seiner Mitte die Luft vor der Stufenkante liegt. Beides zählt die **höchste Stütze**, sonst fällt das Auto an jeder Rampe auf das Niveau seiner Mitte zurück. Rasterpunkte **hinter** dem Substep-Vektor blockieren nie: aus einer Lage, in die es hineingefahren ist, muss es herausfahren können. Dazu gibt `downhill-assist` pro Abstieg Schub in Fahrtrichtung. Bei Blockade steht das Auto am letzten freien Sample — und reagiert physikalisch: gedeckelte Restitution (`crash-restitution`, positiver Abprall ≤0,10 Bl/tick via `CRASH_REBOUND_MAX`; Auto-Auto nur halb, ohne Spin, dafuer mit Impulsuebertrag auf das getroffene Auto (`pushCar`), Hitch-Guard unter ~5 km/h = `CRASH_MIN_SPEED`) plus Drehimpuls aus dem Aufprall-Hebel (`crash-spin` × τ = Hebel × Impuls, landet in `Car.spinVel` und dreht NUR die Karosse; der Geschwindigkeitsvektor folgt grip-begrenzt per ALIGN hinterher → emergentes Schleudern; Decay am Boden × (1 − 0,25·grip), in der Luft nahezu erhalten). `embedded` prüft nur die Fahrzeugmitte (Fuß ragt über Niveau ODER Kopf massiv) — Nase/Heck NICHT (in der Luft neben Böschung sonst Fehlalarm → Tunnel-Bug!).
- Auto gegen Auto (`DriveTask.pushCar`): das getroffene Auto bekommt `crash-transfer` der Aufprall-Geschwindigkeit als Impuls, gedeckelt auf `CAR_PUSH_MAX`. Richtung ist die **Verbindungsachse der beiden Mitten**, nicht die Fahrtrichtung — sonst schoebe ein seitlicher Streifer geradeaus. Dafuer fuehrt `StepResult` das getroffene `Car` mit (`hitCar`, nur bei Achse 3 gesetzt); ohne die Referenz bekaeme das getroffene Auto nichts vom Zusammenstoss mit. **`Car.lastPushTick` ist Pflicht:** beide Achsen eines Ticks melden denselben Zusammenstoss, ohne Riegel gaebe es den Stoss doppelt. Andrehen gibt es weiterhin nur an Waenden. Ein fuehrerloses Auto rollt danach weit aus (kein Gas, nur Motorbremse) — im Selftest rund 10 Bloecke aus einem Rempler bei 72 km/h; das ist die vorhandene Physik, kein Fehler.
- Anfahren (`DriveTask.hitEntities`): Suche ueber eine achsenparallele Box (die kennt der Server billig), danach Filter auf das yaw-gedrehte Karosserie-Rechteck — sonst trifft das Auto quer neben sich. Schaden geht ueber `damage(amount, damager)`: damit feuert ein `EntityDamageByEntityEvent` (PvP-Flags, Schutz-Plugins) und die **Vanilla-Unverwundbarkeit von 10 Ticks ist der Cooldown** — es braucht keine eigene Buchfuehrung, `getNoDamageTicks() > 0` filtert Schaden UND Wiederholungs-Stoss. Schaden skaliert linear mit dem Tempo (`impact-damage` gilt bei 100 km/h), der Stoss ist auf `IMPACT_KNOCKBACK_MAX` gedeckelt. **`IMPACT_LIFT` ist keine Deko:** am Boden frisst die Reibung die Querbewegung binnen weniger Ticks (0,6 je Tick), in der Luft nur 0,09 — ohne Auftrieb bleibt vom Stoss ein halber Block Geschubse (gemessen: 0,55 statt 3,1 Bloecke). Und Vorsicht beim Messen: laesst man das Szenario lange laufen, ist der grosse Teil der Strecke die Kuh, die WEGLAEUFT, nicht der Stoss — `impact-mob` ist deshalb bewusst nur 20 Ticks lang. Ausgenommen: Auto-Teile, Mitfahrer (`getVehicle() != null`) und Ruestungsstaender (Deko). **Das Auto wird nicht langsamer** — eine Impulsbilanz ueber Mobs waere ein eigenes Fass und macht das Fahrgefuehl schwammig. Im Selftest drosselt `impact-mob` den Schaden auf 2, sonst ueberlebt die Kuh den Treffer nicht und laesst sich nicht mehr wiegen.
- Feldschaden (`DriveTask.damageField`): dieselbe Aufteilung wie bei der Kollision — Pflanzen brechen unter der **Karosserie** (`GRID_*`, sie blockieren nicht, das Auto faehrt hindurch), Ackerland wird unter der **Aufstandsflaeche** (`SUPPORT_*`) zu Erde. Beides ueber `EntityChangeBlockEvent` (wie Vanilla fuer trampelnde Mobs und den Ravager), Verursacher ist der Fahrer bzw. das Auto selbst. Die Pflanzenliste `CROPS` ist bewusst fest statt "alles `Ageable`" — darunter fielen Zuckerrohr und Kaktus. **Die Falle:** Ackerland ist 0,9375 hoch, Erde 1,0 — der Boden unter dem Auto STEIGT beim Umpfluegen um 1/16, und dann meldet `embedded()` (prueft die Fuss-Zelle gegen `y + 0,05`) ein Steckenbleiben und setzt die Kollision fuer diesen Tick aus. Deshalb hebt `damageField` das Auto sofort auf die neue Oberkante; ohne das kann es an einem Zaun am Feldrand durch die Wand tunneln. Im Selftest steht der Schalter in `PINNED_CONFIG` auf **false**, sonst pfluegt die Physik-Suite ihre eigenen Ackerland-Bahnen um und misst ab dem zweiten Sample auf Erde (anderer Grip); die Szenarien `field-damage` und `field-damage-aus` schalten ihn fuer sich selbst.
- Hupe (`CarListener`): Rechtsklick, waehrend man faehrt. **Zwei** Events fuehren zu `honk()` — `PlayerInteractEvent` (Klick ins Leere oder auf einen Block) und `PlayerInteractEntityEvent` (Klick auf die eigene Klick-Hitbox, in der der Fahrer ja sitzt); ohne den zweiten Zweig bekaeme ausgerechnet der Fahrer die Meldung "bereits besetzt". Der Klick wird **komplett** geschluckt (`setCancelled`), aus dem Auto heraus wird nichts platziert oder benutzt. Dass `onHorn` auf `EventPriority.LOW` und `onSpawnCar` auf `NORMAL` mit `ignoreCancelled = true` laeuft, ist kein Zufall: nur so spawnt der Fahrer mit einem Auto-Item in der Hand kein zweites Auto aus dem Sitz heraus. Lautstaerke = `horn-range / 16` (Bukkits Hoerweite ist 16 Bloecke je Lautstaerke-Punkt), Cooldown 10 Ticks ueber `Bukkit.getCurrentTick()` gegen `Car.lastHornTick` — tick-basiert wie der Untersteuer-Sound.
- Reifenrauch (`DriveTask.probeWheels`, im selben Loop wie der Grip-Sample): ab `TIRE_SMOKE_MIN` (0.85) Grip-Verbrauch spawnt jedes tragende Rad `Particle.BLOCK` mit der `BlockData` des eigenen Untergrunds — Farbe/Textur kommen so automatisch vom Material, ohne eine Materialliste zu pflegen (Gras staeubt gruen-braun, Beton grau, etc.). Nur alle zwei Ticks, sonst wird Drift/Vollgasstart eine dichte Wand. `gripUsage` kommt nur beim ZWEITEN `probeWheels`-Aufruf mit (der Display-Aufruf nach der Bewegung); der erste (Physik, vor der Bewegung) bekommt `0.0` — zu dem Zeitpunkt ist `gripUsage` noch gar nicht berechnet. **Headless nur teilweise pruefbar:** `tire-smoke-particle` sichert ab, dass `Particle.BLOCK` `BlockData` als Payload akzeptiert (eine API-Aenderung waere sonst ein stiller `IllegalArgumentException`-Fehler im Log statt eines fehlenden Effekts im Spiel) — ob es am Auto gut aussieht, sieht man nur im Spiel.
- Vertikal: Fall mit Substep-Abtastung bis `max-fall-speed` (konfigurierbar, Default 144 km/h), Landung snappt auf die echte Blockoberkante (Slab-Höhe!), harte Landung (≥~36 km/h vertikal) dämpft quer und ist hörbar. Wasser blockiert nicht, bremst stark und trägt nicht → Auto sinkt (Lava bleibt Wand); im Wasser wirkt **keine** Gravitation mehr, die Sinkgeschwindigkeit läuft asymptotisch auf `max-sink-speed` zu (Default 9 km/h, Faktor 0,85 je Substep) — mit addierter Gravitation lag der Fixpunkt bei 30 statt 9 km/h.
- **Vorsichts-Falle:** `StepResult.blocked` ist leicht invertierbar — `true` heißt „Route nicht frei"; x/z zeigen dann auf den letzten freien Sample.

## Config-Konventionen

- `config.yml` ist menschenlesbar (km/h, m/s², %, °/s); `CarConfig.reload()` konvertiert in Blöcke/Tick UND clampt jeden Wert über `clampHumanValue` auf dessen Sinn-Bereich — genau denselben wirksamen Wert zeigt `/car config` an (ein Display-RoHWert-Bug zeigte sonst abweichende Zahlen). Neue Keys müssen in `CarConfig.NUMBER_KEYS`/`BOOL_KEYS`/`STRING_KEYS` (Komplettierung + Migration lesen daraus).
- **Drei Key-Kategorien:** Zahlen, Schalter und seit 1.5.0 freier Text (`STRING_KEYS`, bisher nur `horn-sound`). Fünf Stellen lesen die Listen und müssen bei einer neuen Kategorie mitgezogen werden: `CarConfig.reload`, `CarCommand` (Anzeige/Setzen/Autocomplete), `CarPermissions.configKeys`, `AutoPlugin.carryOver` und der Selftest. Der Sound-Name wird über `CarConfig.lookupSound` (Registry `SOUNDS`) aufgelöst — ein unbekannter Name **darf `reload()` nicht sprengen**, er ergibt eine Log-Warnung und den Default `CarConfig.DEFAULT_HORN_SOUND`. Autocomplete schlägt für String-Keys nichts vor (die Registry hat vierstellig viele Einträge).
- **`/car config reset` und `/car config <key> reset`** lesen `CarConfig.shippedDefaults(plugin)` — die `config.yml` direkt aus dem Jar-Ressourcenordner, unabhaengig von einer schon auf der Platte stehenden Datei (derselbe Helfer, den auch der Selftest fuer `shippedConfig()`/`applyShippedPhysics()` nutzt — nicht zweimal implementieren, das laeuft sonst auseinander). Voller Reset braucht `car.config.*` (eigenes Gate in `CarCommand.decide()`, `args.length == 2 && "reset"` — die normale `car.config`-Node deckt nur Lesen ab), Einzel-Reset die normale `car.config.<key>`-Node wie jedes Setzen.
- **Umbenannte Keys brauchen einen Eintrag in `AutoPlugin.RENAMED_KEYS`** (alter → neuer Name), sonst fällt der Wert bei der Migration weg und der Nutzer steht wieder auf dem Default. So gewandert: `understeer-sound` → `understeer-sound-enabled` (1.5.0).
- `config-version` im YAML: bei Änderung hochzählen (`AutoPlugin.CONFIG_VERSION`, aktuell 14); die Migration sichert automatisch als `config.veraltet.yml` und übernimmt unveränderte Keys. **Geänderte Default-WERTE erreichen einen bestehenden Server nicht** — `carryOver` übernimmt jeden gemeinsamen Key aus der alten Datei, auch bei Versionssprung. Wer die neuen Defaults will, löscht seine `config.yml` (das gilt auch für `.testserver/plugins/Auto/config.yml`).
- Permissions: `car.use`/`car.prefs` (Default true), `car.give`/`car.config` (Default op) stehen in der `paper-plugin.yml`; die Pro-Key-Nodes `car.config.<key>` und der Sammelknoten `car.config.*` entstehen zur Laufzeit aus `CarConfig.NUMBER_KEYS`/`BOOL_KEYS`/`STRING_KEYS` (`CarPermissions.register`) — ein neuer Config-Key bekommt seine Node damit automatisch. Lesen deckt `car.config` ab, Setzen braucht die Key-Node. Geprüft wird nur über `hasPermission`, nie `isOp`, damit Permissions-Plugins überschreiben können. `car.give` hieß früher `auto.give`.
- Spieler-Prefs liegen in `prefs.yml` (`PlayerPrefs`); der frühere Key `reverse_invert_mouse` wurde zu `reverse_invert` umbenannt und wird beim Laden still migriert. Keys: `mouse_steer`, `reverse_invert`, `actionbar` (Hauptschalter), `actionbar_speed`, `actionbar_grip` (Grip-Budget-Balken; ≥100 % = Reifen am Limit; zählt Quer- UND Pedalkraft als Traktionskreis); alles Default an.
- Untersteuer-Sound ist `ENTITY_HORSE_DEATH` mit Pitch 0 (gewollt so, Cooldown 18 Ticks über `tickCount` in `DriveTask` — tick-basiert statt Wall-Clock, lag-stabil).
- Physik-Konstanten gelten pro Tick (20-TPS-Annahme): bei anhaltendem Server-Lag driften reale Beschleunigungs-/Bremswerte. Bewusste, hier dokumentierte Limitation — keine dt-Kompensation implementiert.
- Modell-Optik ist separat vom Physik-Zustand: Pitch folgt der **echten Achslage** (Aufstandshöhe der Vorderachse gegen die der Hinterachse über den Achsabstand, EMA-geglättet, ±25°), Roll der Achsverschränkung plus dem Kurven-Anteil, und das Modell **sitzt zwischen den Achsen** statt auf dem Fahrniveau (`MODEL_MAX_SINK`, EMA): beim Herunterfahren einer Stufe hält die Mitte das Fahrniveau oben, während die Vorderachse schon unten steht — ohne den Versatz schwebt das Modell sichtbar. Kollisionshöhe, Sitz und Klick-Hitbox bleiben, wo die Physik sie hat — vorher kam er aus der Tick-Steigung, damit stand ein parkendes Auto am Hang gerade und rückwärts brauchte es eine Vorzeichen-Korrektur; Roll folgt der Querbeschleunigung (±12°); nur Anzeige, niemals Physik; die Achsen-Vorzeichen sind headless nicht prüfbar — bei falschem Drehsinn im Code-Kommentar markierten Flip vornehmen.
- **Debug-Anzeige der Aufstandspunkte:** `/car config debug-wheels true` wirkt live und zeichnet alle zwei Ticks Partikel — grün = Rad trägt (Punkt sitzt auf seiner Aufstandshöhe), rot = Rad hängt, blau = die acht Karosserie-Punkte auf Fahrniveau. Damit sieht man den Unterschied der beiden Raster im Spiel; für „warum hängt das Auto hier?" der erste Griff.

## Repo-Konventionen

- Deutsch für User-Meldungen/Kommentare, englische Bezeichner. Commits: semantisch + deutsch (`feat(physik): ...`), Hauptzweig `main`.
- Kein JUnit/Mock-Framework (die Physik liest echte Bloecke, Mocks wuerden nur meine Annahmen bestaetigen); Verifikation = `scripts/selftest.sh` (Exit 0). Nutzer-Wert zuerst am Server prüfen, wenn sich Fahrphysik ändert.
- `.omo/` ist lokal (nicht committen). `.testserver/` ist der Wegwerf-Testserver des Selftests (gitignored, wird bei Bedarf neu erzeugt).
