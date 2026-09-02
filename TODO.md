# TODO — offene Ideen

Gesammelt am 2026-09-02, noch nichts davon umgesetzt. Reihenfolge = Aufwand aufsteigend.
(Die Hupe stand hier als Punkt 5 und ist in 1.5.0 umgesetzt.)
Jeder Punkt nennt die Code-Stelle, an der er landet, und die Fragen, die vor der Umsetzung
zu klären sind. Konventionen siehe `AGENTS.md`.

## 1. Farmland wird beim Drüberfahren zu Erde

Wie Vanilla-Trampling, nur durch das Auto statt durch Sprünge.

- **Wo:** neuer Schritt in `DriveTask` nach der Bewegung, über die **Aufstandsfläche**
  (`SUPPORT_*`, die Räder — nicht die Karosserie): Block unter jedem tragenden Rad, wenn
  `FARMLAND` → `DIRT`.
- **Fallen:**
  - Grip ändert sich dabei mit: `GripCalculator` führt `FARMLAND` unter `grip-grass` (50 %),
    `DIRT` fällt auf `grip-default` (70 %). Der Untergrund wird also mitten in der Fahrt
    griffiger.
  - Der Selftest baut in vielen Szenarien Farmland-Bahnen (`step-up-1-from-farmland`,
    `step-micro-farmland-*`, `SelfTest.java:641/659/682/700/715`). Wenn Fahren den Belag
    umwandelt, prüfen die Fälle ab Sample 2 einen anderen Untergrund → Erwartungen nachziehen.
  - Pflanze darüber muss mitgehen (Vanilla dropt sie) → hängt an Punkt 2.
- **Offen:** nur bei Bodenkontakt/ab welchem Tempo? Config-Schalter oder immer an?

## 2. Crops gehen beim Umfahren kaputt

- **Wo:** dieselbe Schleife wie Punkt 1, aber über die **Karosserie** (`GRID_*`, ±1,25/±0,9)
  und die Zelle auf Fahrniveau statt darunter — Weizen & Co. blockieren nicht (`isPassable`),
  das Auto fährt hindurch.
- **Offen:**
  - Drops ja/nein — `Block.breakNaturally()` (droppt, Item-Flut auf großen Feldern) vs.
    `setType(AIR, false)` (spurlos). Vorschlag: `breakNaturally`, aber nur ab einem Mindesttempo.
  - Welche Blöcke? Nur `Ageable` (Weizen, Karotten, Kartoffeln, Rüben) oder alles Zerbrechliche
    (Gräser, Blumen, Fackeln, Melonen-/Kürbisstiele)?
  - Schutz-Plugins: Blockänderungen des Autos gehen aktuell an keinem Event vorbei — für
    WorldGuard & Co. bräuchte es ein `BlockBreakEvent` mit dem Fahrer als Verursacher.
  - Bremst Ernte das Auto? (Vorschlag: nein, sonst wird jedes Feld zur Schlammgrube.)

## 3. Mobs und Spieler nehmen beim Umfahren Schaden und werden weggestoßen

- **Wo:** neuer Schritt pro Tick in `DriveTask`, `world.getNearbyEntities` um die Karosserie-Box
  (2,5 × 1,8 × 2,5, yaw-gedreht).
- **Auszuschließen:** eigene Teile (`carManager.isCarPart`), Fahrer und Passagiere
  (`Car.getBase().getPassengers()`), andere Autos (die sind Punkt 4).
- **Offen:**
  - Schadensformel: proportional zur Aufprallgeschwindigkeit, unterhalb `CRASH_MIN_SPEED`
    (~5 km/h) gar nichts — sonst schiebt ein rangierendes Auto Schafe zu Tode.
  - Wegstoßen über `setVelocity` in Fahrtrichtung + Auswärtskomponente; Deckel nötig, sonst
    fliegen Mobs über die halbe Karte.
  - Verursacher-Zuordnung: Schaden über den Fahrer (`EntityDamageByEntityEvent` mit dem Spieler)
    oder anonym? Ersteres respektiert PvP-Flags und schreibt eine sinnvolle Todesmeldung.
  - Bremst der Treffer das Auto (Impulserhaltung) oder pflügt es ungebremst durch?
  - Cooldown pro Entity, sonst trifft dasselbe Ziel bei 20 TPS zwanzigmal je Sekunde.

## 4. Andere Autos werden bei Unfällen abgestoßen

Heute ist ein anderes Auto nur eine Wand: `nearOtherCar` blockiert, `resolveCrashVelocity`
gibt dem **Auffahrenden** halbe Restitution und keinen Spin (`impactAxis() == 3`) — das
getroffene Auto merkt nichts.

- **Wo:** `DriveTask.resolveCrashVelocity` (`:721`) plus `StepResult` (`:1230`): der Record führt
  bisher nur die Achse `3`, nicht das getroffene `Car`. Für die Impulsübertragung muss die
  Referenz mit — `otherCarLocations` (`:915`) liefert dafür schon die Kandidaten, gibt aber nur
  `Location`s zurück.
- **Offen:**
  - Impulsmodell: einfacher Stoß (Anteil der Achsen-Geschwindigkeit auf das Ziel addieren) oder
    ein echter Zweikörperstoß über die Verbindungsachse? Empfehlung: Verbindungsachse, sonst
    schiebt ein seitlicher Streifer geradeaus.
  - Bekommt das getroffene Auto auch Drehimpuls (`spinVel`)? Bei Wänden gibt es den, bei Autos
    ist er bewusst abgeschaltet — für Rempler wäre er reizvoll.
  - Kein Doppel-Impuls: beide Autos ticken im selben Task, der Stoß darf nicht in beiden
    Richtungen einmal voll gerechnet werden.
  - Selftest: `car-car` (`SelfTest.java:360`) prüft heute nur, dass geblockt wird — braucht
    einen Fall „getroffenes Auto bewegt sich".
