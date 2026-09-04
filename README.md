# Auto

Fahrbare Autos für Minecraft als Paper-Plugin — mit einer Fahrphysik, die auf einem
echten Geschwindigkeitsvektor beruht: Grip pro Rad, Traktionskreis, Schlupf, Driften,
Stufen, Steigungen und Crashs mit Drehimpuls.

- **Plugin:** `Auto` (`de.thiomains:auto`), aktuell **1.5.0**
- **Server:** Paper, `api-version: '26.2'` (Minecraft 26.2)
- **Java:** 25
- **Build:** Maven, kein Testframework, kein CI
- **Lizenz:** GPL-3.0 (siehe LICENSE)

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
| **Rechtsklick**, während man fährt | Hupen. Der Klick gehört ganz der Hupe: aus dem Auto heraus wird nichts platziert oder benutzt |
| **W / S** | Gas vorwärts / rückwärts; die Gegentaste bremst mit voller Bremskraft |
| **Maus** oder **A / D** | Lenken (A/D hat Vorrang; Mauslenkung abschaltbar). Die Maus wirkt wie ein Lenkrad: geradeaus nach vorn **oder** nach hinten schauen heißt Lenkrad gerade, quer zur Karosserie (90°) voller Einschlag, dazwischen linear |
| **Springen** | Handbremse — blockiert die Räder, Grip bricht auf `handbrake-grip` ein |
| **Sneak** | Aussteigen |
| Schlag aufs Auto | Abbauen (nur wenn niemand fährt), Item droppt — im Kreativmodus nicht, dort kostet das Platzieren ja auch keines |

Nahe am Grip-Limit (Traktionskreis, ≥85 %) staubt es unter den Reifen — die Partikel kommen
aus dem Untergrundblock selbst, Farbe und Textur passen sich also automatisch an (Gras, Beton,
Schnee, …).

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
/car config reset               ALLE Fahrwerte auf die ausgelieferten Defaults zurücksetzen
/car config <key> reset         Einen einzelnen Fahrwert zurücksetzen
/car reload                     Config von der Platte neu einlesen (Hand-Edits ohne Neustart)
```

`/car prefs`-Keys: `mouse_steer`, `reverse_invert` (Lenkung rückwärts spiegeln),
`actionbar` (Hauptschalter), `actionbar_speed`, `actionbar_grip`. Gespeichert in
`prefs.yml`, alles außer `actionbar_grip` standardmäßig an; als Wert gehen
`true/false/on/off/an/aus`.

Dazu kommen zwei interne Werkzeuge, die nur auf der Server-Konsole laufen und im Autocomplete
nicht auftauchen: `/car sim <speed> [flags]` baut eine kontrollierte Strecke, fährt sie ab und
loggt pro Tick den Fahrzustand; `/car selftest [--verbose] [muster]` fährt die komplette
Testsuite (siehe unten).

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

`config.yml` (`config-version: 14`). Die Werte sind menschenlesbar; `CarConfig.reload()`
rechnet in Blöcke/Tick um und clampt jeden Wert auf seinen Sinn-Bereich (jeder Zahlen-Key hat
auch eine Obergrenze — `max-speed 100000` würde den Server sonst lahmlegen) — genau diesen
wirksamen Wert zeigt `/car config`. Bei einem Versionssprung wird die alte Datei als
`config.veraltet.yml` gesichert und unveränderte Keys werden übernommen.

| Bereich | Keys |
| --- | --- |
| Tempo | `max-speed`, `max-reverse-speed`, `max-fall-speed`, `max-sink-speed` |
| Antrieb & Bremsen | `acceleration`, `reverse-acceleration`, `brake-deceleration`, `handbrake-deceleration`, `engine-braking`, `drag` |
| Lenkung & Grip | `max-lateral-grip`, `turn-curvature`, `turn-min-speed`, `handbrake-grip` |
| Untergrund | `grip-concrete`, `grip-grass`, `grip-ice`, `grip-default` |
| Gelände | `downhill-assist`, `slope-resistance` |
| Crash | `crash-restitution`, `crash-spin`, `crash-transfer`, `tip-acceleration` |
| Hupe | `horn-sound`, `horn-pitch`, `horn-range` |
| Anfahren | `impact-damage`, `impact-min-speed`, `impact-knockback` |
| Feld | `field-damage-enabled` |
| Sonstiges | `understeer-sound-enabled`, `debug`, `debug-wheels` |

`crash-transfer` ist der Anteil der Aufprallgeschwindigkeit, der beim Auto-Auto-Crash auf das
getroffene Auto übergeht (Standard 60 %, serverseitig auf rund 36 km/h gedeckelt). Die Richtung
kommt aus der Verbindungsachse der beiden Mitten, ein seitlicher Streifer schiebt also zur
Seite und nicht nach vorn. `0` macht Autos wieder zu Wänden füreinander.

Wer angefahren wird, nimmt Schaden und fliegt zur Seite: `impact-damage` ist der Schaden in
Schadenspunkten (2 = ein Herz) **bei 100 km/h** und skaliert linear mit dem Tempo, `0` schaltet
es ab. Unter `impact-min-speed` (Standard 15 km/h) passiert nichts — Rangieren tut nicht weh.
`impact-knockback` ist der Anteil der Fahrzeuggeschwindigkeit, der als Stoß weitergegeben wird
(serverseitig auf rund 50 km/h gedeckelt). Der Schaden läuft über den Fahrer, also greifen
PvP-Flags und Schutz-Plugins; Mitfahrer und Rüstungsständer sind ausgenommen. Das Auto wird
davon **nicht** langsamer.

`field-damage-enabled` (Standard an) heißt: Nutzpflanzen gehen beim Umfahren kaputt und
droppen, und Ackerland wird unter den Rädern zu Erde. Beides läuft über
`EntityChangeBlockEvent` — genau wie Vanilla es für trampelnde Mobs und den Ravager tut,
Schutz-Plugins können es also abfangen. Gras, Blumen und Zuckerrohr bleiben stehen; die Liste
der betroffenen Pflanzen ist bewusst fest.

`horn-sound` ist ein Name aus der Vanilla-Sound-Registry (`minecraft:block.note_block.didgeridoo`
ist der Standard); ein unbekannter Name wird mit einer Warnung im Log auf den Standard
zurückgesetzt. `horn-range` steht in Blöcken (Standard 80) — Minecraft rechnet daraus die
Lautstärke.

Beim Update auf 1.5.0: `understeer-sound` heißt jetzt `understeer-sound-enabled`, damit das
Suffix `-sound` eindeutig für den Sound-**Namen** steht. Ein gesetzter Wert wandert bei der
Migration automatisch mit.

`reset` holt den Wert aus der im Plugin mitgelieferten `config.yml`, nicht aus einer
Server-Konfiguration, die zufällig noch die alten Werte trug — geänderte Defaults einer neuen
Version erreichen einen bestehenden Server über die Migration sonst nie (siehe unten). Der
volle Reset braucht `car.config.*`, der Einzel-Reset wie jedes Setzen die passende
`car.config.<key>`-Node.

`/car reload` ist etwas anderes: es holt sich, was gerade tatsächlich in der Datei steht —
für Hand-Edits am laufenden Server, ohne Neustart. Braucht dieselbe Node wie der volle Reset,
weil beides potenziell jeden Fahrwert auf einen Schlag ändert. Der Live-Editor
(`/car config <key> <wert>`) deckt den Normalfall ab; `reload` ist für den seltenen Fall
gedacht, dass doch jemand von Hand in der `config.yml` schraubt. Ist die Datei syntaktisch
kaputt, lehnt `reload` sie ab und lässt den laufenden Stand unangetastet, statt still auf die
Defaults zurückzufallen.

Korrigiert wird nie stillschweigend: ein Wert vom falschen Typ (`max-speed: schnell`), ein Wert
außerhalb des Sinnbereichs (wird geklemmt) und ein vertippter Key (wirkt nie) landen jeweils als
Klartext-Warnung im Server-Log — `/car reload` zeigt sie zusätzlich direkt im Chat an. Ohne das
sucht man lange, warum eine Einstellung nicht greift.

Der Grip kommt aus dem Material unter den Rädern: jede Betonfarbe gilt als Fahrbahn,
Gras/Erde/Schlamm/Schnee als weich, alle Eisarten als spiegelglatt, alles andere Default.

## Fahrphysik in Kürze

- **Vektor statt Skalar:** Zustand ist `velX/velZ` + `yaw`; die Fahrtrichtungskomponente
  trägt Gas und Bremse, der Rest ist Schlupf und wird von der Querreibung gefressen.
- **Achsen tragen, Karosserie blockiert:** Wie hoch das Auto steht, entscheiden Achsen und
  Unterboden — die Stoßstange hebt es nicht an. Gegen Wände blockiert dagegen die volle
  Karosserie, die Nase trifft also vor den Rädern. Auf einer Treppe steigt das Auto damit eine
  Stufe pro Reihe, statt auf der Stoßstange zu reiten.
- **Grip nur am Boden:** Bodenkontakt und Grip stammen aus vier Rad-Samples (±0,7 längs und
  quer, also Radstand und Spurweite 1,4), gemessen gegen den Boden unter der Fahrzeugmitte — auf einer Treppe zählt die
  tief stehende Hinterachse trotzdem mit. Die beiden Räder einer Achse sind verbunden: hängt
  eines mehr als einen halben Block unter dem anderen, hebt es ab. Und unter drei tragenden
  Rädern **kippt** das Auto zur unbelasteten Seite ab, statt auf der Kante zu balancieren.
  In der Luft gibt es weder Antrieb noch Bremse noch Lenkung — nur Ballistik und
  Luftwiderstand.
- **Lenkung mit Budget:** Lenkrate = min(Lenkrad-Anschlag × Tempo, verfügbares Grip-Budget).
  Der Geschwindigkeitsvektor folgt der Rollrichtung nur zu einem Anteil des Budgets nach —
  daraus entstehen Untersteuern und Drift von selbst.
- **Kollision:** achsenweise Substeps alle 0,4 Blöcke mit yaw-ausgerichtetem Footprint in
  realen Maßen (1,8 × 2,5 Blöcke), andere Autos inklusive. Eine ganze Blockstufe wird immer
  befahren — auch von Belägen mit gekappter Oberkante (Ackerland, Schlamm) aus, wo sie
  rechnerisch etwas über einen Block misst. Bergab folgt das Auto dem Boden statt abzuheben,
  und zwar als starrer Körper auf der höchsten Stütze unter dem Footprint: Rampen und Treppen
  fährt es hoch, statt zwischendurch auf das Niveau seiner Mitte zurückzufallen.
- **Steigungen:** bergauf kostet Lageenergie, bergab gibt sie zurück (skaliert mit
  `slope-resistance`). Die Höhe wird dabei als Schuld geführt und über die gefahrene Strecke
  verrechnet, nicht in einer Rate — sonst wäre jede Stufe im Kriechtempo eine Wand. Ob eine
  Steigung fahrbar ist, entscheidet damit die Leistungsbilanz aus `acceleration`, Grip und
  `slope-resistance`.
- **Crash:** gedeckelter Abprall plus Drehimpuls aus dem Aufprall-Hebel — die Karosserie
  dreht sich, der Vektor zieht grip-begrenzt nach: emergentes Schleudern.
- **Vertikal:** Fall bis `max-fall-speed`, Landung auf der echten Blockoberkante (Slabs
  eingerechnet), harte Landungen dämpfen quer. Wasser trägt nicht, bremst aber den Fall
  auf `max-sink-speed`; Lava bleibt eine Wand.
- **Optik:** Das Modell nickt so, wie die Achsen stehen (Vorderachse gegen Hinterachse), dazu
  Squat/Dive beim Bremsen und Beschleunigen; es rollt in Kurven und mit der Achsverschränkung,
  und es sitzt zwischen den Achsen statt auf der höchsten Stütze — beim Herunterfahren einer
  Stufe folgt die Karosserie also den Rädern, statt oben zu schweben. Alles reine Anzeige,
  ohne Rückwirkung auf die Physik. `/car config debug-wheels true` zeigt die Aufstandspunkte als
  Partikel: grün = Rad trägt, rot = Rad hängt, blau = Karosserie-Raster.

## Tests

```bash
scripts/selftest.sh              # nur Ergebniszeilen und Fehlschläge
scripts/selftest.sh --verbose    # zusätzlich jeder Tick und das komplette Serverlog
scripts/selftest.sh --only step  # nur Szenarien, deren Name "step" enthält
```

Das Skript baut das Plugin, holt bei Bedarf ein Paper-JAR nach `.testserver/`, startet dort einen
Server mit Flachwelt und festem Seed und lässt `/car selftest` laufen. Geprüft werden Fahrphysik
(Wandkontakt und Rückprall, Löcher, Eis, Gefälle, Stufen), die Rechte-Matrix und das Clamping der
Config-Werte — die Erwartungen stehen in `SelfTest.java`. Exit 0 heißt grün, 1 Testfehler,
2 Harness-Fehler.

Szenarien für bekannte, noch offene Bugs sind als `knownFail` markiert: sie dürfen fehlschlagen,
ohne den Lauf rot zu färben, melden aber `UNEXPECTED-PASS`, sobald der Bug behoben ist. Derzeit
ist kein Szenario so markiert — der Lauf ist vollständig grün.

Nicht automatisiert und weiterhin manuell: echte Spielereingaben, die Modell-Optik und das
Resourcepack.

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
| `SelfTest.java` | Testszenarien mit ihren Erwartungen (`/car selftest`) |
| `CarPermissions.java` | Permission-Nodes inkl. der Pro-Key-Nodes aus der Config-Key-Liste |
| `Car.java`, `CarItem.java` | Fahrzeugzustand bzw. Item-/Modell-Definition |

Details zu Paper-Fallstricken, Physik-Interna und Konventionen stehen in
[AGENTS.md](AGENTS.md).

## Konventionen

Deutsche User-Meldungen und Kommentare, englische Bezeichner. Commits semantisch und
deutsch (`feat(physik): …`), Hauptzweig `main`. Es gibt kein JUnit — verifiziert wird
über `scripts/selftest.sh` (Build + Headless-Server + Szenarien).

## Lizenz

GPL-3.0 — siehe LICENSE. Wer das Plugin verändert und weitergibt, muss den Quellcode
unter derselben Lizenz offenlegen.
