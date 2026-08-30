# Auto

Fahrbare Autos für Minecraft als Paper-Plugin — mit einer Fahrphysik, die auf einem
echten Geschwindigkeitsvektor beruht: Grip pro Rad, Traktionskreis, Schlupf, Driften,
Stufen, Steigungen und Crashs mit Drehimpuls.

- **Plugin:** `Auto` (`de.thiomains:auto`), aktuell **1.2.0**
- **Server:** Paper, `api-version: '26.2'` (Minecraft 26.2)
- **Java:** 25
- **Build:** Maven, kein Testframework, kein CI

## Überblick

Ein Auto ist ein Item, das man auf den Boden setzt, per Rechtsklick besteigt und mit
W/S + Maus (oder A/D) fährt. Technisch besteht es aus drei Entities: einem ArmorStand
als Sitz, einem ItemDisplay als Fahrzeugmodell und einer Interaction als Klick-Hitbox.
Alle Fahrwerte liegen menschenlesbar in der `config.yml` (km/h, m/s², %, °/m) und lassen
sich per Befehl **live** ändern — ohne Restart.

## Installation

1. Plugin bauen (siehe unten) oder fertige `auto-<version>.jar` nehmen.
2. JAR nach `plugins/` kopieren, Server starten.
3. Resourcepack mit dem Item-Modell `thiomains:auto` ausliefern — ohne Pack zeigen Item
   und Fahrzeug den schwarz-pinken Missing-Texture-Würfel; fahren lässt es sich trotzdem.
4. `/car give` (Permission `car.give`, Default: OP) und losfahren.

## Bedienung

| Eingabe | Wirkung |
| --- | --- |
| Rechtsklick mit dem Auto-Item auf einen Block | Auto platzieren |
| Rechtsklick aufs Auto | Einsteigen (nur wenn frei) |
| **W / S** | Gas vorwärts / rückwärts; die Gegentaste bremst mit voller Bremskraft |
| **Maus** oder **A / D** | Lenken (A/D hat Vorrang; Mauslenkung abschaltbar) |
| **Springen** | Handbremse — blockiert die Räder, Grip bricht auf `handbrake-grip` ein |
| **Sneak** | Aussteigen |
| Schlag aufs Auto | Abbauen (nur wenn niemand fährt), Item droppt |

In der Actionbar laufen ein Strich-Tacho (km/h, `R` bei Rückwärtsfahrt) und optional ein
Grip-Budget-Balken: ≥ 100 % heißt, die Reifen sind am Limit (Quer- **und** Pedalkraft
zusammen, Traktionskreis).

## Befehle

Hauptbefehl ist `/car`, `/auto` bleibt als Alias erhalten. Tab-Completion zeigt nur, was der
Sender auch ausführen darf.

```
/car                            Übersicht (identisch zu /car help)
/car help                       Plugin, Version, Autor und alle erlaubten Unterbefehle
/car prefs [<key> [on|off]]     Eigene Fahreinstellungen anzeigen/ändern
/car give                       Auto-Item ins Inventar
/car config [<key> [wert]]      Fahrwerte anzeigen/ändern — Änderungen greifen live
```

`/car prefs`-Keys: `mouse_steer`, `reverse_invert` (Lenkung rückwärts spiegeln),
`actionbar` (Hauptschalter), `actionbar_speed`, `actionbar_grip`. Gespeichert in
`prefs.yml`, alles außer `actionbar_grip` standardmäßig an; als Wert gehen
`true/false/on/off/an/aus`.

Dazu gibt es `/car sim <speed> [drift] [gap] [ice] [stairs] [drive]` — ein internes
Testwerkzeug, das nur auf der Server-Konsole läuft und im Autocomplete nicht auftaucht.
Es baut eine kontrollierte Strecke, fährt sie ab und loggt pro Tick den Fahrzustand.

### Permissions

| Node | Default | Erlaubt |
| --- | --- | --- |
| `car.use` | alle | `/car`, `/car help` |
| `car.prefs` | alle | `/car prefs …` |
| `car.give` | OP | `/car give` |
| `car.config` | OP | Fahrwerte **lesen** |
| `car.config.<key>` | OP | genau diesen Key **setzen**, z. B. `car.config.acceleration` |
| `car.config.*` | OP | alle Fahrwerte setzen |

Ohne OP kann man also fahren und die eigenen Einstellungen pflegen; alles Weitere ist
OP-Sache. Geprüft wird ausschließlich über Bukkit-Permissions, nie über den OP-Status —
jedes Permissions-Plugin (LuckPerms & Co.) kann die Defaults beliebig überschreiben, auch
in beide Richtungen. Die Pro-Key-Nodes entstehen automatisch aus der Config-Key-Liste, ein
neuer Fahrwert bringt seine Node also mit.

Beim Update von 1.1.0: `auto.give` heißt jetzt `car.give` — bestehende Zuweisungen einmal
umstellen.

## Konfiguration

`config.yml` (`config-version: 8`). Die Werte sind menschenlesbar; `CarConfig.reload()`
rechnet in Blöcke/Tick um und clampt jeden Wert auf seinen Sinn-Bereich — genau diesen
wirksamen Wert zeigt `/car config`. Bei einem Versionssprung wird die alte Datei als
`config.veraltet.yml` gesichert und unveränderte Keys werden übernommen.

| Bereich | Keys |
| --- | --- |
| Tempo | `max-speed`, `max-reverse-speed`, `max-fall-speed`, `max-sink-speed` |
| Antrieb & Bremsen | `acceleration`, `reverse-acceleration`, `brake-deceleration`, `handbrake-deceleration`, `engine-braking`, `drag` |
| Lenkung & Grip | `max-lateral-grip`, `turn-curvature`, `turn-min-speed`, `handbrake-grip` |
| Untergrund | `grip-concrete`, `grip-grass`, `grip-ice`, `grip-default` |
| Gelände | `downhill-assist`, `slope-resistance` |
| Crash | `crash-restitution`, `crash-spin` |
| Sonstiges | `understeer-sound`, `debug` |

Der Grip kommt aus dem Material unter den Rädern: jede Betonfarbe gilt als Fahrbahn,
Gras/Erde/Schlamm/Schnee als weich, alle Eisarten als spiegelglatt, alles andere Default.

## Fahrphysik in Kürze

- **Vektor statt Skalar:** Zustand ist `velX/velZ` + `yaw`; die Fahrtrichtungskomponente
  trägt Gas und Bremse, der Rest ist Schlupf und wird von der Querreibung gefressen.
- **Grip nur am Boden:** Bodenkontakt und Grip stammen aus vier Rad-Samples (±0,9 längs,
  ±0,7 quer). Halb über der Kante = halber Grip; in der Luft gibt es weder Antrieb noch
  Bremse noch Lenkung — nur Ballistik und Luftwiderstand.
- **Lenkung mit Budget:** Lenkrate = min(Lenkrad-Anschlag × Tempo, verfügbares Grip-Budget).
  Der Geschwindigkeitsvektor folgt der Rollrichtung nur zu einem Anteil des Budgets nach —
  daraus entstehen Untersteuern und Drift von selbst.
- **Kollision:** achsenweise Substeps alle 0,4 Blöcke mit yaw-ausgerichtetem Footprint in
  realen Maßen (1,8 × 2,5 Blöcke), andere Autos inklusive. Stufen bis 1 Block werden
  befahren, bergab folgt das Auto dem Boden statt abzuheben.
- **Crash:** gedeckelter Abprall plus Drehimpuls aus dem Aufprall-Hebel — die Karosserie
  dreht sich, der Vektor zieht grip-begrenzt nach: emergentes Schleudern.
- **Vertikal:** Fall bis `max-fall-speed`, Landung auf der echten Blockoberkante (Slabs
  eingerechnet), harte Landungen dämpfen quer. Wasser trägt nicht, bremst aber den Fall
  auf `max-sink-speed`; Lava bleibt eine Wand.
- **Optik:** Das Modell nickt in Steigungen und beim Bremsen/Beschleunigen und rollt in
  Kurven — reine Anzeige, ohne Rückwirkung auf die Physik.

## Build

Maven, Java 25:

```bash
mvn -B clean package
```

Artefakt: `target/auto-<version>.jar`. Die Version aus der `pom.xml` wird per Filtering in
die `paper-plugin.yml` injiziert. Die paper-api kommt vom PaperMC-Repo, nicht von Maven
Central — das steht in der `pom.xml` und braucht keine extra Einrichtung.

## Quellcode

| Datei | Aufgabe |
| --- | --- |
| `AutoPlugin.java` | Enable, Config-Migration, Befehls-Registrierung, Wiederherstellung bestehender Autos |
| `CarManager.java` | Entity-Aufbau (Sitz + Modell + Hitbox), Registry, Re-Registrierung |
| `DriveTask.java` | Physik-Tick: Eingaben, Grip, Lenkung, Kollision, Fall, Actionbar, Optik |
| `GripCalculator.java` | Grip-Faktor je Untergrundmaterial |
| `CarListener.java` | Platzieren, Einsteigen, Abbauen, Chunk-Load |
| `CarCommand.java` | `/car` mit `help`/`prefs`/`give`/`config`/`sim` |
| `CarConfig.java` | Einheiten-Umrechnung, Clamping, Live-Reload |
| `PlayerPrefs.java` | Fahreinstellungen pro Spieler (`prefs.yml`) |
| `CarPermissions.java` | Permission-Nodes inkl. der Pro-Key-Nodes aus der Config-Key-Liste |
| `Car.java`, `CarItem.java` | Fahrzeugzustand bzw. Item-/Modell-Definition |

Details zu Paper-Fallstricken, Physik-Interna und Konventionen stehen in
[AGENTS.md](AGENTS.md).

## Konventionen

Deutsche User-Meldungen und Kommentare, englische Bezeichner. Commits semantisch und
deutsch (`feat(physik): …`), Hauptzweig `main`. Es gibt keine Tests — verifiziert wird
über Build (Exit 0) und einen Headless-Server-Smoke mit `/car sim` auf der Konsole.
