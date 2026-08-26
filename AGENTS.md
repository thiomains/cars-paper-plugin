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

- `/auto sim <speed> [drift]` baut eine kontrollierte Strecke (flach, y=60) bei x/z=200/200 mit Steinwand bei z+6 und fährt los — loggt `[Sim]`-Zeilen pro Tick (Speed, vf, slip, blocked, pos). Erwartung Regression: Wand-Stopp bei z≈205, kein Tunneling.
- `/auto config get/set` wirkt LIVE (Reload ohne Restart), `/auto prefs` ist pro Spieler.

## Architektur-Kernstellen (nicht aus Dateinamen erkennbar)

- Ein Auto = ArmorStand (Sitz, PDC-Marker `auto:car`) + ItemDisplay (Modell, Passagier) + Interaction (Klick-Hitbox, Passagier). Fahrer = Spieler-Passagier auf dem ArmorStand. Entity-Aufbau zentral in `CarManager` (spawnCar / reRegister / ensureParts).
- Input: `player.getCurrentInput()` (semantisch, respektiert Keybinds; funktioniert auch beim Reiten nicht steuerbarer Entities — Fahrer muss aber serverseitig wirklich Passagier sein).
- Physik (`DriveTask`): Zustand ist ein Geschwindigkeitsvektor (`Car.velX/velZ`) + `yaw`, kein Skalar. Antrieb/Bremse wirken auf die Fahrtrichtungskomponente `vf`; Motorbremse + Drag wirken immer. Lenkung = min(Lenkrad-Deckel `turn-rate-max`, Grip-Budget `maxLatGrip·grip/|v|`); der Vektor folgt dem Yaw nur mit `ALIGN_FRACTION=0.65` des Budgets → Schlupf = quer rutschen. A/D hat Vorrang vor Maus; Rückwärtsinvertierung über `PlayerPrefs.reverseInvert`.
- **Vorsichts-Falle:** `StepResult.blocked` ist leicht invertierbar — `true` heißt „nicht bewegen". 1-Block-Stufen sind gewollt; Kollision tastet die Route substep-weise ab (0,4 Blöcke), sonst Tunnel bei hohen km/h.

## Config-Konventionen

- `config.yml` ist menschenlesbar (km/h, m/s², %, °/s); `CarConfig.reload()` konvertiert in Blöcke/Tick. Neue Keys müssen in `CarConfig.NUMBER_KEYS`/`BOOL_KEYS` (Komplettierung + Migration lesen daraus).
- `config-version` im YAML: bei Änderung hochzählen (`AutoPlugin.CONFIG_VERSION`); die Migration sichert automatisch als `config.veraltet.yml` und übernimmt unveränderte Keys.
- Spieler-Prefs liegen in `prefs.yml` (`PlayerPrefs`); der frühere Key `reverse_invert_mouse` wurde zu `reverse_invert` umbenannt und wird beim Laden still migriert.

## Repo-Konventionen

- Deutsch für User-Meldungen/Kommentare, englische Bezeichner. Commits: semantisch + deutsch (`feat(physik): ...`), Hauptzweig `main`.
- Keine Tests; Verifikation = Build (Exit 0) + Headless-Server-smoke. Nutzer-Wert zuerst am Server prüfen, wenn sich Fahrphysik ändert.
- `.omo/` ist lokal (nicht committen). `/tmp/opencode/papertest` ist Wegwerf-Testserver.
