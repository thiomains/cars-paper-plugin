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
- Befehle dürfen NICHT in der YAML deklariert werden — das wirft beim Enable `UnsupportedOperationException`. Stattdessen zur Laufzeit: `registerCommand(String, String, BasicCommand)` aus `JavaPlugin` (siehe `CarCommand.java`).
- paper-api-Koordinaten: `io.papermc.paper:paper-api:[26.2.build,)` vom Repo `https://repo.papermc.io/repository/maven-public/` (nicht Maven Central).
- Download-API für Server-JARs ist `fill.papermc.io` (die alte api.papermc.io v2 ist sunset).
- Seit der Dimensions-Migration liegt die Overworld-Region unter `world/dimensions/minecraft/overworld/` (auch `entities/`), nicht in `world/region/`.
- Spawn-Chunks laden Entities nach Restart nicht ohne Spieler-Proximität; Auto-Re-Registrierung läuft daher über `EntitiesLoadEvent` + Sweeps in `AutoPlugin.onEnable` (Sofort + 100 Ticks verzögert).

## Live-Verifikation (headless, ohne Client)

```bash
cd /tmp/opencode/papertest   # enthält paper.jar (26.2 b119) + eula.txt
cp target/auto-*.jar plugins/
( sleep 50; echo "auto sim 1.5"; sleep 5; echo "stop"; ) \
  | "$JAVA_HOME/bin/java" -Xmx1536M -jar paper.jar nogui
```

- `/auto sim <speed> [drift] [gap] [ice] [stairs] [drive]` (negativer speed = Rückwärtsfahrt vom Startpunkt weg) baut eine kontrollierte Strecke (flach, y=60, drei Spalten breit x=199..201) bei x/z=200/200 mit Steinwand bei z+6 und fährt los — loggt `[Sim]`-Zeilen pro Tick (Speed, vf, slip, grounded, grip, blocked, pos). Erwartung Regression: Wand-Kontakt bei z≈204,6 (die Nase berührt die Wand — Footprint-Länge!), danach leichter Rückprall ~1–1,5 Blöcke (crash-restitution 25 %, Rebound-Cap 0,10 Bl/tick; `crash-restitution 0` = alter harter Stopp), kein Tunneling über z=206 hinaus. `gap` entfernt Boden bei z+2..+5 (4 Blöcke — kürzere Löcher ≤2 überbrückt der Footprint physikalisch korrekt, weil stets ein Rad gestützt bleibt; Flugphase grounded=false, grip=0,00). `ice` = Packeis (grip=0,15, langer Auslauf ohne Standfest-Raste), `stairs` lässt die Strecke ab z+3 stufenweise abfallen (bergab: durchgehend grounded, Energie-Gewinn + gefälle-skalierter Schub sichtbar), `drive` = Gas-Simulation (weiche Annäherung an max-speed).
- `/auto config get/set` wirkt LIVE (Reload ohne Restart), `/auto prefs` ist pro Spieler.

## Architektur-Kernstellen (nicht aus Dateinamen erkennbar)

- Ein Auto = ArmorStand (Sitz, PDC-Marker `auto:car`) + ItemDisplay (Modell, Passagier) + Interaction (Klick-Hitbox, Passagier). Fahrer = Spieler-Passagier auf dem ArmorStand. Entity-Aufbau zentral in `CarManager` (spawnCar / reRegister / ensureParts). Sitzhöhe hängt am SCALE-Attribut des Stands (`SEAT_SCALE` ≈ +0,1 Blöcke über Standard); `MODEL_Y_OFFSET` hält das Modell dagegen fest.
- Input: `player.getCurrentInput()` (semantisch, respektiert Keybinds; funktioniert auch beim Reiten nicht steuerbarer Entities — Fahrer muss aber serverseitig wirklich Passagier sein).
- Physik (`DriveTask`): Zustand ist ein Geschwindigkeitsvektor (`Car.velX/velZ`) + `yaw` (geglättete Lenk-Drehrate `Car.yawVel`, Crash-Drehrate `Car.spinVel`), kein Skalar. Grip gibt es NUR bei Bodenkontakt: `grounded`/Grip kommen aus VIER Rad-Samples (±0,9 längs, ±0,7 quer, je 1 Block Federungstoleranz via `wheelSupport`) — halb über der Kante = halber Grip, in der Luft kein Antrieb/Bremse/Lenkung/ALIGN (ballistisch, nur Drag). Antrieb/Bremse wirken auf die Fahrtrichtungskomponente `vf`, weich Richtung `max-speed` begrenzt (Faktor `1 − vf/max` statt hartem Cut); Motorbremse × grip nur ohne Fahrpedal am Boden, Drag immer. Lenkung = min(Lenkrad-Anschlag `turn-curvature` °/m × |v|, Grip-Budget `maxLatGrip·grip/|v|`); der Vektor folgt mit `ALIGN_FRACTION=0.65` des Budgets der **Rollrichtung** (`travelYaw` = yaw bzw. yaw+180 bei Rückwärtsfahrt — niemals stur yaw!), Rotation nur oberhalb `turn-min-speed`; Schlupf wird durch laterale Reibung gefressen (`FRICTION_FRACTION`). Handbremse = Sprungtaste: `handbrake-deceleration` × Grip auf vf, Folge-Grip × `handbrake-grip` — die Lenkung behält vollen Grip (Vorderräder). Bei Wandkontakt darf im Stand rangiert werden (`CRAWL_TURN_DEG`). Standfest-Hartschnapp unter ~0,5 km/h nur bei grip ≥ 0,4 — auf Eis rollt das Auto aus.
- Kollision (`resolveStep`): achsenweise, substep-weise alle 0,4 Blöcke, mit yaw-ausgerichtetem 3×3-Footprint (Nase/Heck/Ecken, ~2,1×2,7 Blöcke) und anderen Autos als Hindernis. Stufen kommen aus der Kollisionsform (`supportTop`, max. 1 Block, Slabs/Treppen befahrbar); pro Sample folgt die Fahrzeughöhe dem Boden bis 1,2 Blöcke abwärts (`followGroundDown`, bergab kein Losfliegen) + `downhill-assist` gibt pro Abstieg Schub in Fahrtrichtung. Bei Blockade steht das Auto am letzten freien Sample — und reagiert physikalisch: gedeckelte Restitution (`crash-restitution`, positiver Abprall ≤0,10 Bl/tick via `CRASH_REBOUND_MAX`; Auto-Auto nur halb, ohne Spin, Hitch-Guard unter ~5 km/h = `CRASH_MIN_SPEED`) plus Drehimpuls aus dem Aufprall-Hebel (`crash-spin` × τ = Hebel × Impuls, landet in `Car.spinVel` und dreht NUR die Karosse; der Geschwindigkeitsvektor folgt grip-begrenzt per ALIGN hinterher → emergentes Schleudern; Decay am Boden × (1 − 0,25·grip), in der Luft nahezu erhalten). `embedded` prüft nur die Fahrzeugmitte (Fuß ragt über Niveau ODER Kopf massiv) — Nase/Heck NICHT (in der Luft neben Böschung sonst Fehlalarm → Tunnel-Bug!).
- Vertikal: Fall mit Substep-Abtastung bis `max-fall-speed` (konfigurierbar, Default 144 km/h), Landung snappt auf die echte Blockoberkante (Slab-Höhe!), harte Landung (≥~36 km/h vertikal) dämpft quer und ist hörbar. Wasser blockiert nicht, bremst stark und trägt nicht → Auto sinkt (Lava bleibt Wand); der Sink-Fall nähert sich dabei asymptotisch `max-sink-speed` (Default 9 km/h, Faktor 0,85/Tick) statt wie ein Stein durchzurauschen.
- **Vorsichts-Falle:** `StepResult.blocked` ist leicht invertierbar — `true` heißt „Route nicht frei"; x/z zeigen dann auf den letzten freien Sample.

## Config-Konventionen

- `config.yml` ist menschenlesbar (km/h, m/s², %, °/s); `CarConfig.reload()` konvertiert in Blöcke/Tick UND clampt jeden Wert über `clampHumanValue` auf dessen Sinn-Bereich — genau denselben wirksamen Wert zeigt `/auto config get` an (ein Display-RoHWert-Bug zeigte sonst abweichende Zahlen). Neue Keys müssen in `CarConfig.NUMBER_KEYS`/`BOOL_KEYS` (Komplettierung + Migration lesen daraus).
- `config-version` im YAML: bei Änderung hochzählen (`AutoPlugin.CONFIG_VERSION`, aktuell 8); die Migration sichert automatisch als `config.veraltet.yml` und übernimmt unveränderte Keys.
- Spieler-Prefs liegen in `prefs.yml` (`PlayerPrefs`); der frühere Key `reverse_invert_mouse` wurde zu `reverse_invert` umbenannt und wird beim Laden still migriert. Keys: `mouse_steer`, `reverse_invert`, `actionbar` (Hauptschalter), `actionbar_speed`, `actionbar_grip` (Grip-Budget-Balken; ≥100 % = Reifen am Limit); alles Default an.
- Untersteuer-Sound ist `ENTITY_HORSE_DEATH` mit Pitch 0 (gewollt so, Cooldown 18 Ticks über `tickCount` in `DriveTask` — tick-basiert statt Wall-Clock, lag-stabil).
- Physik-Konstanten gelten pro Tick (20-TPS-Annahme): bei anhaltendem Server-Lag driften reale Beschleunigungs-/Bremswerte. Bewusste, hier dokumentierte Limitation — keine dt-Kompensation implementiert.
- Modell-Optik ist separat vom Physik-Zustand: Pitch folgt der echten Tick-Steigung (EMA-geglättet, ±25°) und Roll der Querbeschleunigung (±12°); nur Anzeige, niemals Physik; die Achsen-Vorzeichen sind headless nicht prüfbar — bei falschem Drehsinn im Code-Kommentar markierten Flip vornehmen.

## Repo-Konventionen

- Deutsch für User-Meldungen/Kommentare, englische Bezeichner. Commits: semantisch + deutsch (`feat(physik): ...`), Hauptzweig `main`.
- Keine Tests; Verifikation = Build (Exit 0) + Headless-Server-smoke. Nutzer-Wert zuerst am Server prüfen, wenn sich Fahrphysik ändert.
- `.omo/` ist lokal (nicht committen). `/tmp/opencode/papertest` ist Wegwerf-Testserver.
