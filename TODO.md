# TODO — offene Ideen

Die vier gesammelten Punkte (Farmland, Crops, Mobs/Spieler, Auto-Auto) sind in **1.6.0**
umgesetzt, die Hupe in 1.5.0. Was hier steht, ist **nur gesammelt und noch nicht entschieden** —
keiner der Punkte ist beauftragt.

## Anschlüsse an das, was jetzt drin ist

- **Fahrer nimmt bei hartem Crash Schaden.** Aussen tut das Auto inzwischen weh, der Fahrer
  steigt aus einem 170-km/h-Frontalcrash unversehrt aus. Die Aufprall-Geschwindigkeit liegt in
  `DriveTask.resolveCrashVelocity` schon vor, es fehlen Schwelle und Schaden auf
  `car.getDriver()`.
- **Zerbrechliche Blöcke beim Crash.** Glas, Blätter, Zäune, Blumentöpfe blockieren wie Beton.
  Dieselbe Schleife wie `damageField`, nur mit Materialliste und Tempo-Schwelle.
- **Schaden am Auto selbst.** Crashs kosten nichts — man rammt beliebig oft eine Wand. Ein
  Zustandswert im PDC (summierte Crash-Energie), der ab einem Schwellwert das Auto zerstört
  oder die Höchstgeschwindigkeit drückt. Der Punkt mit dem meisten Spieldesign drin.

## Eigenständig

- **`/car config <key> default` bzw. `reset`.** Geänderte Default-Werte erreichen einen
  bestehenden Server nie (`carryOver` übernimmt jeden gemeinsamen Key); heute hilft nur, die
  `config.yml` zu löschen. Die Defaults stehen in `CarConfig.reload()` schon ausgeschrieben und
  müssten nur in eine Tabelle wandern, die Reload und Reset teilen. Billigste echte Verbesserung
  auf dieser Liste.
- **Motorgeräusch am Tempo.** Es gibt drei Sounds (harte Landung, Untersteuern, Hupe). Ein
  tempoabhängiger Loop bringt fürs Fahrgefühl mehr als jede weitere Physik-Feinheit; das
  Cooldown-Muster über `tickCount` gibt es schon.
- **Reifenrauch und Untergrund-Partikel.** `gripUsage` wird pro Tick berechnet und wandert
  bisher nur in die Actionbar — ab ~100 % Partikel an den Rad-Aufstandspunkten, Typ nach
  Material. Die Aufstandspunkte zeichnet `debug-wheels` bereits.
- **Beifahrer.** `onEnterCar` weist jeden ab, sobald ein Fahrer sitzt; technisch passt mehr als
  einer auf den ArmorStand. Braucht eine Entscheidung, wo Passagiere sitzen.
- **Besitzer am Auto.** Im PDC stehen nur `auto:car` und der Part-Marker — jeder darf jedes Auto
  fahren und abbauen.
