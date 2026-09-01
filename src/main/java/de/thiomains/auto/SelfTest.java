package de.thiomains.auto;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.type.Snow;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Automatische Verifikation: fährt kontrollierte Szenarien ab und prüft sie gegen fest
 * hinterlegte Erwartungen. Jedes Szenario baut seine Strecke auf einer eigenen Bahn
 * (60 Blöcke Abstand) und räumt auch HINTER dem Startpunkt frei — die hinteren
 * Kollisions-Samples liegen bei z−1,25, sonst steht das Auto in generiertem Gelände sofort an.
 *
 * <p>Ausgabe ist zeilenweise maschinenlesbar: {@code [Selftest] PASS|FAIL|KNOWN-FAIL|UNEXPECTED-PASS <name> <detail>}
 * und zum Schluss eine SUMMARY-Zeile. {@code knownFail} markiert Szenarien für bekannte,
 * noch offene Bugs: sie dürfen fehlschlagen, ohne den Lauf rot zu färben — bestehen sie
 * plötzlich, meldet der Lauf UNEXPECTED-PASS, damit das Flag entfernt wird.
 */
public final class SelfTest extends BukkitRunnable {

    private static boolean running;

    private static final int LANE_SPACING = 60;
    private static final int GROUND_Y = 60;
    private static final int TIMEOUT_MARGIN = 40;
    /** Gleichzeitig fahrende Faelle eines Sweeps. Die Baehnen liegen LANE_SPACING auseinander,
     *  weit jenseits des Auto-Auto-Kollisionsradius — die Autos sehen einander nicht. */
    private static final int BATCH = 12;
    private static final int SWEEP_TICKS = 50;

    // ---- Erwartungswerte (menschenlesbar in Blöcken, relativ zum Bahn-Ursprung) ----
    private static final double WALL_CONTACT_MIN = 4.4;
    private static final double WALL_CONTACT_MAX = 4.9;
    private static final double WALL_TUNNEL_LIMIT = 6.0;
    private static final double WALL_REBOUND_MIN = 0.8;
    private static final double WALL_REBOUND_MAX = 2.0;
    private static final double ICE_GRIP = 0.15;
    private static final double ICE_ROLLOUT_FACTOR = 1.05;
    private static final int GAP_MIN_AIR_TICKS = 2;
    private static final double MICRO_STEP_MIN_TRAVEL = 8.0;

    private final Plugin plugin;
    private final CarManager carManager;
    private final CarConfig config;
    private final boolean verbose;
    private final String filter;

    private final List<Scenario> scenarios = new ArrayList<>();
    private final List<Car> extraCars = new ArrayList<>();
    private final Map<String, Double> measurements = new LinkedHashMap<>();

    /** Ein laufender Fall mit seiner Bahn, seinem Auto und seinen Tick-Werten. */
    private static final class Active {
        private final Case spec;
        private final Lane lane;
        private final List<SimSample> samples = new ArrayList<>();
        private Car car;

        private Active(Case spec, Lane lane) {
            this.spec = spec;
            this.lane = lane;
        }
    }

    private final List<Active> active = new ArrayList<>();
    private final List<long[]> tickets = new ArrayList<>();
    private final List<Case> pending = new ArrayList<>();
    private final List<String> caseNotes = new ArrayList<>();

    private int index = -1;
    private Scenario current;
    private int laneCounter;
    private int waited;
    private int caseOk;
    private int caseBad;
    private int caseKnown;
    private int caseTotal;
    private int totalCases;
    private String lastDetail = "";
    private long startedAt;
    private int passed;
    private int failed;
    private int knownFailed;

    public SelfTest(Plugin plugin, CarManager carManager, CarConfig config, boolean verbose, String filter) {
        this.plugin = plugin;
        this.carManager = carManager;
        this.config = config;
        this.verbose = verbose;
        this.filter = filter;
        defineScenarios();
    }

    /** Startet den Lauf; false, wenn bereits einer läuft. */
    public boolean start() {
        if (running) {
            return false;
        }
        running = true;
        startedAt = System.currentTimeMillis();
        // Die Baehnen werden per Chunk-Ticket geladen gehalten — geladene Chunks ticken aber
        // auch: Ackerland trocknet zu Dirt aus, Schnee schmilzt, Kaktus waechst. Das aendert
        // die Strecke waehrend der Messung und macht Laeufe unreproduzierbar.
        World world = Bukkit.getWorlds().get(0);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        log("START " + scenarios.size() + " Szenarien"
                + (filter != null ? " (Filter: " + filter + ")" : ""));
        runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    // ────────────────────────────── Szenarien ──────────────────────────────

    private record Lane(World world, int baseX, int baseZ, int groundY) {
    }

    private record Run(Lane lane, List<SimSample> samples) {
    }

    private record Result(boolean ok, String detail) {
        static Result pass(String detail) {
            return new Result(true, detail);
        }

        static Result fail(String detail) {
            return new Result(false, detail);
        }
    }

    /** Ein einzelner Fahrfall: eigene Bahn, eigenes Auto, eigene Pruefung.
     *  ticks = 0 bedeutet: kein Fahrszenario, die Pruefung laeuft sofort.
     *  Ein normales Szenario besteht aus genau einem Fall, ein Sweep aus vielen. */
    private record Case(String label, boolean knownFail, int ticks, double startSpeed, SimInput input,
                        double minY, float yaw, boolean negativeLane, Area clear, Consumer<Car> tune,
                        Consumer<Lane> build, Function<Run, Result> check) {
    }

    /** Freizuraeumender Bereich einer Bahn: x relativ zur Bahnmitte, z zum Bahnursprung,
     *  y zu GROUND_Y. Sweeps raeumen deutlich weniger als die langen Einzelstrecken —
     *  bei ueber 200 Baehnen faellt jeder ueberfluessige setType ins Gewicht. */
    private record Area(int halfWidth, int zFrom, int zTo, int yFrom, int yTo) {
    }

    private static final Area FULL_CLEAR = new Area(3, -5, 50, -26, 8);
    private static final Area SWEEP_CLEAR = new Area(3, -5, 22, -6, 6);
    private static final Area SLOPE_CLEAR = new Area(3, -5, 34, -22, 6);

    private record Scenario(String name, boolean knownFail, List<Case> cases) {
    }

    /** true = Vollgas, false = niemand am Steuer (Auto rollt aus). */
    private static SimInput gas(boolean drive) {
        return drive ? SimInput.GAS : null;
    }

    private void addCase(String name, boolean knownFail, Case single) {
        if (filter == null || name.contains(filter)) {
            scenarios.add(new Scenario(name, knownFail, List.of(single)));
        }
    }

    private void add(String name, boolean knownFail, int ticks, double speed, boolean drive,
                     Consumer<Lane> build, Function<Run, Result> check) {
        add(name, knownFail, ticks, speed, drive, GROUND_Y - 3.0, build, check);
    }

    /** Variante mit Nachjustierung direkt nach dem Spawn (Drift, Querbewegung). */
    private void add(String name, boolean knownFail, int ticks, double speed, boolean drive, float yaw,
                     Consumer<Car> tune, Consumer<Lane> build, Function<Run, Result> check) {
        addCase(name, knownFail, new Case("", knownFail, ticks, speed, gas(drive), GROUND_Y - 3.0, yaw,
                false, FULL_CLEAR, tune, build, check));
    }

    /** Variante mit Blickrichtung und optional negativen Weltkoordinaten (Rundungs-Verhalten von floor). */
    private void add(String name, boolean knownFail, int ticks, double speed, boolean drive,
                     float yaw, boolean negativeLane, Consumer<Lane> build, Function<Run, Result> check) {
        addCase(name, knownFail, new Case("", knownFail, ticks, speed, gas(drive), GROUND_Y - 3.0, yaw,
                negativeLane, FULL_CLEAR, null, build, check));
    }

    /** minY ist die Absturz-Sicherung: faellt das Auto tiefer, hat es die Bahn verlassen und
     *  jede weitere Auswertung waere Unsinn (die Flachwelt liegt 120 Bloecke tiefer). */
    private void add(String name, boolean knownFail, int ticks, double speed, boolean drive,
                     double minY, Consumer<Lane> build, Function<Run, Result> check) {
        addCase(name, knownFail, new Case("", knownFail, ticks, speed, gas(drive), minY, 0f, false,
                FULL_CLEAR, null, build, check));
    }

    /**
     * Sammelt viele kurze Faelle unter einem Namen. Die Faelle laufen in Gruppen von
     * {@link #BATCH} gleichzeitig auf eigenen Baehnen — anders waere eine Matrix ueber
     * alle Stufenhoehen und Belaege nicht in vertretbarer Zeit zu fahren.
     */
    private final class Sweep {

        private final String name;
        private final Area clear;
        private final List<Case> cases = new ArrayList<>();

        private Sweep(String name, Area clear) {
            this.name = name;
            this.clear = clear;
        }

        Sweep run(String label, boolean knownFail, double speed, boolean drive, double minY,
                  Consumer<Lane> build, Function<Run, Result> check) {
            return run(label, knownFail, SWEEP_TICKS, speed, gas(drive), 0f, minY, build, check);
        }

        Sweep run(String label, boolean knownFail, double speed, boolean drive,
                  Consumer<Lane> build, Function<Run, Result> check) {
            return run(label, knownFail, speed, drive, GROUND_Y - 3.0, build, check);
        }

        /** Vollform mit eigener Tick-Zahl, Fahrer-Eingabe und Blickrichtung. */
        Sweep run(String label, boolean knownFail, int ticks, double speed, SimInput input, float yaw,
                  double minY, Consumer<Lane> build, Function<Run, Result> check) {
            return run(label, knownFail, ticks, speed, input, yaw, minY, clear, build, check);
        }

        /** Vollform mit eigenem Raeum-Bereich — Rueckwaertsfahrt und Vollgas brauchen
         *  andere Strecken als die kurze Standard-Bahn. */
        Sweep run(String label, boolean knownFail, int ticks, double speed, SimInput input, float yaw,
                  double minY, Area area, Consumer<Lane> build, Function<Run, Result> check) {
            return run(label, knownFail, ticks, speed, input, yaw, minY, area, null, build, check);
        }

        /** Vollform mit Nachjustierung direkt nach dem Spawn (Drift, Querbewegung). */
        Sweep run(String label, boolean knownFail, int ticks, double speed, SimInput input, float yaw,
                  double minY, Area area, Consumer<Car> tune, Consumer<Lane> build,
                  Function<Run, Result> check) {
            if (filter == null || name.contains(filter) || label.contains(filter)) {
                cases.add(new Case(label, knownFail, ticks, speed, input, minY, yaw, false,
                        area, tune, build, check));
            }
            return this;
        }

        void done() {
            if (!cases.isEmpty()) {
                scenarios.add(new Scenario(name, false, List.copyOf(cases)));
            }
        }
    }

    private Sweep sweep(String name) {
        return new Sweep(name, SWEEP_CLEAR);
    }

    private Sweep sweep(String name, Area clear) {
        return new Sweep(name, clear);
    }

    private void defineScenarios() {
        // Jede Fahrstrecke endet in einer Wand: so kann kein Auto hinten herausfahren und
        // unbemerkt in der Flachwelt weiterfahren — das faelscht sonst jede Distanzmessung.

        // 1 — Wandkontakt: Nase beruehrt bei z≈4,6 hinter dem Bahnursprung, danach gedeckelter Rueckprall.
        add("wall", false, 100, 1.5, false, lane -> {
            track(lane, -4, 10, GROUND_Y - 1, Material.STONE);
            wall(lane, 6, GROUND_Y);
        }, run -> {
            double contact = maxZ(run);
            double rebound = contact - minZAfterMax(run);
            if (contact < WALL_CONTACT_MIN || contact > WALL_CONTACT_MAX) {
                return Result.fail(fmt("Wandkontakt z=%.3f, erwartet %.1f..%.1f", contact,
                        WALL_CONTACT_MIN, WALL_CONTACT_MAX));
            }
            if (contact > WALL_TUNNEL_LIMIT) {
                return Result.fail(fmt("Tunneling: z=%.3f hinter der Wand", contact));
            }
            if (rebound < WALL_REBOUND_MIN || rebound > WALL_REBOUND_MAX) {
                return Result.fail(fmt("Rueckprall %.3f, erwartet %.1f..%.1f", rebound,
                        WALL_REBOUND_MIN, WALL_REBOUND_MAX));
            }
            return Result.pass(fmt("Kontakt z=%.3f Rueckprall=%.3f kein Tunneling", contact, rebound));
        });

        // 2 — unter CRASH_MIN_SPEED (~5 km/h) darf es keinen Abpraller geben (Hitch-Guard).
        add("wall-slow", false, 100, 0.05, false, lane -> {
            track(lane, -4, 10, GROUND_Y - 1, Material.STONE);
            wall(lane, 6, GROUND_Y);
        }, run -> {
            double contact = maxZ(run);
            double rebound = contact - minZAfterMax(run);
            if (rebound > 0.15) {
                return Result.fail(fmt("Rueckprall %.3f, erwartet <= 0,15 (Hitch-Guard)", rebound));
            }
            return Result.pass(fmt("Kontakt z=%.3f ohne Abpraller (%.3f)", contact, rebound));
        });

        // 3 — Auto gegen Auto: blockiert, kein Andrehen.
        add("car-car", false, 100, 1.0, false, lane -> {
            track(lane, -4, 20, GROUND_Y - 1, Material.STONE);
            wall(lane, 20, GROUND_Y);
            extraCars.add(carManager.spawnCar(new Location(lane.world(), lane.baseX() + 0.5,
                    lane.groundY(), lane.baseZ() + 6.5, 0f, 0f), 0f));
        }, run -> {
            double contact = maxZ(run);
            double spin = Math.abs(lastSample(run).yaw());
            if (contact > 5.5) {
                return Result.fail(fmt("durch das andere Auto gefahren (z=%.3f)", contact));
            }
            if (spin > 2.0) {
                return Result.fail(fmt("Auto-Auto darf nicht andrehen, yaw=%.1f", spin));
            }
            return Result.pass(fmt("blockiert bei z=%.3f, yaw=%.1f", contact, spin));
        });

        // 4 — Rueckwaerts: der Vektor richtet sich auf die Rollrichtung aus, nicht stur auf yaw.
        add("reverse", false, 80, -1.0, false, lane -> {
            track(lane, -24, 8, GROUND_Y - 1, Material.STONE);
            wall(lane, -24, GROUND_Y);
            wall(lane, 8, GROUND_Y);
        }, run -> {
            double min = minZ(run);
            if (min > -3.0) {
                return Result.fail(fmt("nur %.3f Bloecke rueckwaerts, erwartet <= -3", min));
            }
            if (maxZ(run) > 1.1) {
                return Result.fail(fmt("faehrt vorwaerts statt rueckwaerts (max z=%.3f)", maxZ(run)));
            }
            return Result.pass(fmt("%.3f Bloecke rueckwaerts", min));
        });

        // 5 — 4-Block-Loch: Flugphase ohne Grip, danach Landung auf dem tieferen Boden.
        add("gap", false, 100, 0.8, false, GROUND_Y - 3.2, lane -> {
            track(lane, -4, 20, GROUND_Y - 1, Material.STONE);
            wall(lane, 20, GROUND_Y);
            for (int z = 2; z <= 5; z++) {
                clearColumn(lane, z, GROUND_Y - 2, GROUND_Y - 1);
                floorAt(lane, z, GROUND_Y - 3, Material.STONE);
            }
        }, run -> {
            long air = run.samples().stream().filter(s -> !s.grounded() && s.grip() < 0.01).count();
            double minY = run.samples().stream().mapToDouble(SimSample::y).min().orElse(0);
            if (air < GAP_MIN_AIR_TICKS) {
                return Result.fail(fmt("nur %d Ticks Flugphase, erwartet >= %d", air, GAP_MIN_AIR_TICKS));
            }
            if (!lastSample(run).grounded()) {
                return Result.fail("landet nicht wieder");
            }
            return Result.pass(fmt("%d Ticks Flug, tiefster Punkt y=%.3f", air, minY));
        });

        // 6 — 2-Block-Loch: der Footprint stuetzt sich weiter ab, keine Flugphase.
        add("short-gap", false, 80, 1.2, false, lane -> {
            track(lane, -4, 20, GROUND_Y - 1, Material.STONE);
            wall(lane, 20, GROUND_Y);
            for (int z = 2; z <= 3; z++) {
                clearColumn(lane, z, GROUND_Y - 3, GROUND_Y - 1);
            }
        }, run -> {
            long air = run.samples().stream().filter(s -> !s.grounded()).count();
            if (air > 0) {
                return Result.fail(air + " Ticks ohne Bodenkontakt, erwartet 0 (Footprint ueberbrueckt)");
            }
            return Result.pass(fmt("durchgehend grounded, %.3f Bloecke weit", maxZ(run)));
        });

        // 7 + 8 — Grip-Vergleich ueber den Tempoverlust: die Motorbremse wirkt x grip,
        // auf Eis muss nach gleicher Zeit deutlich mehr Tempo uebrig sein. (Der Weg bis zum
        // Stillstand liegt bei ueber 100 Bloecken und ist als Strecke nicht praktikabel.)
        add("rollout-stone", false, 40, 1.0, false, lane -> {
            track(lane, -4, 60, GROUND_Y - 1, Material.STONE);
            wall(lane, 60, GROUND_Y);
        }, run -> {
            double kept = lastSample(run).speed() / run.samples().get(0).speed();
            measurements.put("rollout-stone", kept);
            return Result.pass(fmt("Tempo nach 40 Ticks: %.1f %% (%.3f Bloecke weit)", kept * 100, maxZ(run)));
        });

        add("ice", false, 40, 1.0, false, lane -> {
            track(lane, -4, 60, GROUND_Y - 1, Material.PACKED_ICE);
            wall(lane, 60, GROUND_Y);
        }, run -> {
            double grip = run.samples().stream().filter(SimSample::grounded)
                    .mapToDouble(SimSample::grip).max().orElse(-1);
            double kept = lastSample(run).speed() / run.samples().get(0).speed();
            Double stone = measurements.get("rollout-stone");
            if (Math.abs(grip - ICE_GRIP) > 0.01) {
                return Result.fail(fmt("grip=%.2f, erwartet %.2f", grip, ICE_GRIP));
            }
            if (stone != null && kept < stone * ICE_ROLLOUT_FACTOR) {
                return Result.fail(fmt("Tempo-Erhalt %.1f %%, erwartet >= %.1f %% (%.2fx Stein)",
                        kept * 100, stone * ICE_ROLLOUT_FACTOR * 100, ICE_ROLLOUT_FACTOR));
            }
            return Result.pass(fmt("grip=%.2f, Tempo-Erhalt %.1f %% (Stein %.1f %%)", grip, kept * 100,
                    stone == null ? -1 : stone * 100));
        });

        // 9 — Sanftes Gefaelle (eine Stufe je zwei Bloecke): hier muss das Auto dem Boden
        // durchgehend folgen (followGroundDown) und dabei Tempo gewinnen.
        add("stairs-down", false, 100, 0.6, false, GROUND_Y - 12.0, lane -> {
            trackStairs(lane, -4, 20, 2, 8);
            track(lane, 21, 26, GROUND_Y - 9, Material.STONE);
            wall(lane, 26, GROUND_Y - 8);
        }, run -> {
            long air = run.samples().stream().filter(sample -> !sample.grounded()).count();
            double startSpeed = run.samples().get(0).speed();
            double topSpeed = run.samples().stream().mapToDouble(SimSample::speed).max().orElse(0);
            if (air > 0) {
                return Result.fail(air + " Ticks ohne Bodenkontakt (sanftes Gefaelle darf nicht abheben)");
            }
            if (topSpeed < startSpeed) {
                return Result.fail(fmt("Tempo faellt bergab: %.3f -> %.3f", startSpeed, topSpeed));
            }
            return Result.pass(fmt("durchgehend grounded, Tempo %.3f -> %.3f", startSpeed, topSpeed));
        });

        // 9b — 45°-Gefaelle: hier DARF das Auto abheben (es wird schneller als followGroundDown
        // pro Tick nachfuehren kann). Geprueft wird nur, dass es wieder landet und nicht durchfaellt.
        add("stairs-steep", false, 100, 0.6, false, GROUND_Y - 12.0, lane -> {
            trackStairs(lane, -4, 20, 1, 8);
            track(lane, 21, 26, GROUND_Y - 9, Material.STONE);
            wall(lane, 26, GROUND_Y - 8);
        }, run -> {
            long air = run.samples().stream().filter(sample -> !sample.grounded()).count();
            double minY = run.samples().stream().mapToDouble(SimSample::y).min().orElse(0);
            if (!lastSample(run).grounded()) {
                return Result.fail("landet nach dem Gefaelle nicht wieder");
            }
            if (minY < GROUND_Y - 9.1) {
                return Result.fail(fmt("faellt unter die Strecke: y=%.3f", minY));
            }
            return Result.pass(fmt("%d Ticks Flug am steilen Hang, landet sauber bei y=%.3f", air, minY));
        });

        // 10 + 11 — ganze Stufen: 1 Block hoch ist befahrbar, 2 Bloecke blockieren.
        add("step-up-1", false, 100, 1.0, true, lane -> {
            track(lane, -4, 4, GROUND_Y - 1, Material.STONE);
            track(lane, 5, 20, GROUND_Y, Material.STONE);
            wall(lane, 20, GROUND_Y + 1);
        }, run -> {
            double reached = maxZ(run);
            double top = run.samples().stream().mapToDouble(SimSample::y).max().orElse(0);
            if (reached < 7.0) {
                return Result.fail(fmt("kommt nur bis z=%.3f, erwartet >= 7 (1 Block ist befahrbar)", reached));
            }
            if (top < GROUND_Y + 0.9) {
                return Result.fail(fmt("steigt nicht auf: max y=%.3f", top));
            }
            return Result.pass(fmt("Stufe genommen, z=%.3f y=%.3f", reached, top));
        });

        add("step-up-2", false, 100, 1.0, true, lane -> {
            track(lane, -4, 4, GROUND_Y - 1, Material.STONE);
            track(lane, 5, 20, GROUND_Y + 1, Material.STONE);
            wall(lane, 20, GROUND_Y + 2);
        }, run -> {
            double reached = maxZ(run);
            if (reached > 4.5) {
                return Result.fail(fmt("2-Block-Stufe wurde erklommen (z=%.3f)", reached));
            }
            return Result.pass(fmt("blockiert wie erwartet bei z=%.3f", reached));
        });

        // 11b — ganze Stufe von einem flachen Belag aus: die Oberkante des Hindernisses liegt
        // 1/16 (Farmland) bzw. 1/8 (Schlamm) ueber dem, was von Stein aus noch befahrbar ist.
        // Genau hier wird der gemeldete "bleibt haengen"-Fall vermutet.
        stepUpFrom("step-up-1-from-farmland", Material.FARMLAND, true);
        stepUpFrom("step-up-1-from-mud", Material.MUD, true);

        // 12–16 — Mikro-Stufen: 1/16 Block hoch (Farmland/Grasweg/Schnee -> Vollblock).
        microStep("step-micro-farmland-slow", Material.FARMLAND, 0.2, false);
        microStep("step-micro-farmland-fast", Material.FARMLAND, 1.0, false);
        microStep("step-micro-mud", Material.MUD, 1.0, false);
        microStep("step-micro-path", Material.DIRT_PATH, 1.0, false);
        add("step-micro-snow", false, 120, 1.0, true, lane -> {
            track(lane, -4, 20, GROUND_Y - 1, Material.STONE);
            for (int z = 0; z <= 4; z++) {
                snowLayer(lane, z, 2);
            }
            wall(lane, 20, GROUND_Y);
        }, microCheck("Schnee (2 Lagen) -> Vollblock"));

        // abwaerts: 1/16 tiefer, darf nicht stocken
        add("step-micro-down", false, 120, 1.0, true, lane -> {
            track(lane, -4, 4, GROUND_Y - 1, Material.STONE);
            track(lane, 5, 20, GROUND_Y - 1, Material.FARMLAND);
            wall(lane, 20, GROUND_Y);
        }, run -> {
            double reached = maxZ(run);
            long air = run.samples().stream().filter(s -> !s.grounded()).count();
            if (reached < MICRO_STEP_MIN_TRAVEL) {
                return Result.fail(fmt("bleibt bei z=%.3f haengen, erwartet >= %.1f", reached,
                        MICRO_STEP_MIN_TRAVEL));
            }
            if (air > 0) {
                return Result.fail(air + " Ticks ohne Bodenkontakt beim 1/16-Abstieg");
            }
            return Result.pass(fmt("Abstieg sauber, z=%.3f", reached));
        });

        add("step-micro-repeat", false, 140, 1.0, true, lane -> {
            track(lane, -4, 20, GROUND_Y - 1, Material.STONE);
            for (int z = 0; z <= 18; z += 4) {
                floorAt(lane, z, GROUND_Y - 1, Material.FARMLAND);
                floorAt(lane, z + 1, GROUND_Y - 1, Material.FARMLAND);
            }
            wall(lane, 20, GROUND_Y);
        }, microCheck("Wechselstreifen Farmland/Stein"));

        // 16b — Farmland-Kante unter realistischen Bedingungen: die gerade Anfahrt im
        // 5-Bloecke-Streifen ist nachweislich in Ordnung, gemeldet wird trotzdem Steckenbleiben.
        // Deshalb hier die Bedingungen, die der schmale Streifen NICHT hat.
        edgeCase("edge-diag-20", 20f, false, 1.0, true, 24, false);
        edgeCase("edge-diag-45", 45f, false, 1.0, true, 24, false);
        edgeCase("edge-negative-coords", 0f, true, 1.0, true, 24, false);
        edgeCase("edge-creep", 0f, false, 0.02, true, 24, false);
        edgeCase("edge-standstill", 0f, false, 0.0, true, 24, false);
        edgeCase("edge-coast", 0f, false, 0.3, false, 24, false);
        edgeCase("edge-x-axis", 90f, false, 1.0, true, 24, false);

        // Fahrt genau AUF der Kante: linke Raeder auf Farmland, rechte auf Stein.
        add("edge-seam", false, 120, 1.0, true, 0f, false, lane -> {
            plate(lane, 10, -6, 24, 99, Material.STONE, Material.STONE);
            for (int x = lane.baseX() - 9; x <= lane.baseX(); x++) {
                for (int z = -5; z <= 23; z++) {
                    lane.world().getBlockAt(x, GROUND_Y - 1, lane.baseZ() + z)
                            .setType(Material.FARMLAND, false);
                }
            }
        }, run -> {
            double far = travelled(run);
            if (far < MICRO_STEP_MIN_TRAVEL) {
                return Result.fail(fmt("bleibt nach %.3f Bloecken auf der Laengskante haengen (v=%.3f)",
                        far, lastSample(run).speed()));
            }
            return Result.pass(fmt("%.3f Bloecke auf der Laengskante", far));
        });

        // Farmland-Feld mitten in Stein: das Auto faehrt hinein und muss wieder heraus.
        add("edge-patch", false, 120, 1.0, true, 0f, false, lane -> {
            plate(lane, 10, -6, 24, 99, Material.STONE, Material.STONE);
            for (int x = lane.baseX() - 2; x <= lane.baseX() + 2; x++) {
                for (int z = 2; z <= 8; z++) {
                    lane.world().getBlockAt(x, GROUND_Y - 1, lane.baseZ() + z)
                            .setType(Material.FARMLAND, false);
                }
            }
        }, run -> {
            double far = travelled(run);
            if (far < 12.0) {
                return Result.fail(fmt("kommt aus dem Farmland-Feld nicht heraus: %.3f Bloecke (v=%.3f)",
                        far, lastSample(run).speed()));
            }
            return Result.pass(fmt("durch das Feld und wieder heraus, %.3f Bloecke", far));
        });

        // Beim echten Fahren ist immer Lenkung im Spiel: einmal drehend ueber die Kante,
        // einmal rein seitlich hineinrutschend (Querbewegung trifft die Kante an der Flanke).
        add("edge-drift", false, 120, 1.0, true, 0f, car -> car.setSimDrift(true), lane ->
                plate(lane, 14, -6, 24, 4, Material.FARMLAND, Material.STONE), run -> {
            double far = travelled(run);
            if (far < MICRO_STEP_MIN_TRAVEL) {
                return Result.fail(fmt("bleibt drehend nach %.3f Bloecken an der Kante haengen (v=%.3f)",
                        far, lastSample(run).speed()));
            }
            return Result.pass(fmt("%.3f Bloecke drehend ueber die Kante", far));
        });

        add("edge-sideways", false, 120, 0.0, false, 90f, car -> {
            car.setVelX(0.0);
            car.setVelZ(0.8);
        }, lane -> plate(lane, 14, -6, 24, 4, Material.FARMLAND, Material.STONE), run -> {
            double far = travelled(run);
            if (far < 4.0) {
                return Result.fail(fmt("rutscht quer nur %.3f Bloecke bis zur Kante (v=%.3f)",
                        far, lastSample(run).speed()));
            }
            return Result.pass(fmt("%.3f Bloecke quer ueber die Kante", far));
        });

        // 17 — Diagnose statt Test: echte Kollisionshoehen der Kandidaten-Materialien.
        add("probe-support-tops", false, 0, 0, false, lane -> {
        }, run -> {
            World world = Bukkit.getWorlds().get(0);
            List<Material> probes = List.of(Material.STONE, Material.FARMLAND, Material.DIRT_PATH,
                    Material.MUD, Material.SOUL_SAND, Material.SNOW_BLOCK, Material.STONE_SLAB,
                    Material.STONE_BRICK_STAIRS, Material.WHITE_CARPET);
            StringBuilder out = new StringBuilder();
            int x = run.lane().baseX();
            int z = run.lane().baseZ();
            for (Material material : probes) {
                world.getBlockAt(x, GROUND_Y + 4, z).setType(material, false);
                out.append(fmt("%s=%.4f ", material.name(), supportTop(world, x, GROUND_Y + 4, z)));
            }
            for (int layers = 1; layers <= 3; layers++) {
                world.getBlockAt(x, GROUND_Y + 4, z).setType(Material.SNOW, false);
                Snow snow = (Snow) world.getBlockAt(x, GROUND_Y + 4, z).getBlockData();
                snow.setLayers(layers);
                world.getBlockAt(x, GROUND_Y + 4, z).setBlockData(snow, false);
                out.append(fmt("SNOW[%d]=%.4f ", layers, supportTop(world, x, GROUND_Y + 4, z)));
            }
            world.getBlockAt(x, GROUND_Y + 4, z).setType(Material.AIR, false);
            return Result.pass(out.toString().trim());
        });

        // 18 — Rechte-Matrix ueber die reine Entscheidungsfunktion (kein Spieler noetig).
        add("perms-matrix", false, 0, 0, false, lane -> {
        }, run -> {
            Set<String> guest = Set.of(CarPermissions.USE, CarPermissions.PREFS);
            Set<String> tuner = Set.of(CarPermissions.USE, CarPermissions.CONFIG,
                    CarPermissions.config("acceleration"));
            List<String> errors = new ArrayList<>();
            expect(errors, "Gast: help", guest, false, CarCommand.Decision.Kind.HELP, null);
            expect(errors, "Gast: prefs", guest, false, CarCommand.Decision.Kind.ALLOW, null, "prefs");
            expect(errors, "Gast: give", guest, false, CarCommand.Decision.Kind.MISSING_PERMISSION,
                    CarPermissions.GIVE, "give");
            expect(errors, "Gast: config lesen", guest, false, CarCommand.Decision.Kind.MISSING_PERMISSION,
                    CarPermissions.CONFIG, "config");
            expect(errors, "Gast: sim", guest, false, CarCommand.Decision.Kind.CONSOLE_ONLY, null, "sim");
            expect(errors, "Gast: selftest", guest, false, CarCommand.Decision.Kind.CONSOLE_ONLY, null, "selftest");
            expect(errors, "Tuner: config lesen", tuner, false, CarCommand.Decision.Kind.ALLOW, null, "config");
            expect(errors, "Tuner: acceleration lesen", tuner, false, CarCommand.Decision.Kind.ALLOW, null,
                    "config", "acceleration");
            expect(errors, "Tuner: acceleration setzen", tuner, false, CarCommand.Decision.Kind.ALLOW, null,
                    "config", "acceleration", "14");
            expect(errors, "Tuner: max-speed setzen", tuner, false,
                    CarCommand.Decision.Kind.MISSING_PERMISSION, CarPermissions.config("max-speed"),
                    "config", "max-speed", "100");
            expect(errors, "Tuner: prefs", tuner, false, CarCommand.Decision.Kind.MISSING_PERMISSION,
                    CarPermissions.PREFS, "prefs");
            expect(errors, "OP: sim", null, false, CarCommand.Decision.Kind.CONSOLE_ONLY, null, "sim");
            expect(errors, "Konsole: sim", null, true, CarCommand.Decision.Kind.ALLOW, null, "sim");
            expect(errors, "Konsole: selftest", null, true, CarCommand.Decision.Kind.ALLOW, null, "selftest");
            expect(errors, "Konsole: unbekannt", null, true, CarCommand.Decision.Kind.UNKNOWN, null, "quatsch");
            expect(errors, "Ohne car.use", Set.of(), false, CarCommand.Decision.Kind.MISSING_PERMISSION,
                    CarPermissions.USE, "help");
            if (!errors.isEmpty()) {
                return Result.fail(String.join(" | ", errors));
            }
            return Result.pass("16 Kombinationen korrekt");
        });

        // 19 — Registrierung: jeder Config-Key hat seine Node, Default OP, Kind von car.config.*
        add("perms-registry", false, 0, 0, false, lane -> {
        }, run -> {
            List<String> errors = new ArrayList<>();
            Permission all = Bukkit.getPluginManager().getPermission(CarPermissions.CONFIG_ALL);
            if (all == null) {
                return Result.fail(CarPermissions.CONFIG_ALL + " ist nicht registriert");
            }
            for (String key : CarPermissions.configKeys()) {
                String node = CarPermissions.config(key);
                Permission permission = Bukkit.getPluginManager().getPermission(node);
                if (permission == null) {
                    errors.add(node + " fehlt");
                    continue;
                }
                if (permission.getDefault() != PermissionDefault.OP) {
                    errors.add(node + " default=" + permission.getDefault());
                }
                if (!Boolean.TRUE.equals(all.getChildren().get(node))) {
                    errors.add(node + " nicht Kind von " + CarPermissions.CONFIG_ALL);
                }
            }
            checkDefault(errors, CarPermissions.USE, PermissionDefault.TRUE);
            checkDefault(errors, CarPermissions.PREFS, PermissionDefault.TRUE);
            checkDefault(errors, CarPermissions.GIVE, PermissionDefault.OP);
            checkDefault(errors, CarPermissions.CONFIG, PermissionDefault.OP);
            if (!errors.isEmpty()) {
                return Result.fail(String.join(" | ", errors));
            }
            return Result.pass(CarPermissions.configKeys().size() + " Key-Nodes + 4 feste Nodes korrekt");
        });

        // 20 — Clamping: angezeigter Wert = wirksamer Wert, auch an den Raendern.
        add("config-clamp", false, 0, 0, false, lane -> {
        }, run -> {
            List<String> errors = new ArrayList<>();
            clamp(errors, "crash-restitution", 200, 60);
            clamp(errors, "crash-restitution", -5, 0);
            clamp(errors, "crash-spin", 999, 400);
            clamp(errors, "slope-resistance", 500, 200);
            clamp(errors, "max-sink-speed", 0, 3.6);
            clamp(errors, "drag", 500, 100);
            clamp(errors, "grip-ice", -5, 0);
            clamp(errors, "acceleration", -3, 0);
            clamp(errors, "max-speed", 162, 162);
            if (!errors.isEmpty()) {
                return Result.fail(String.join(" | ", errors));
            }
            return Result.pass("9 Randfaelle korrekt geclamped");
        });

        // 21 — crash-restitution 0 muss den alten harten Stopp ergeben: kein Abpraller.
        // Die Umstellung passiert im Streckenbau; startNext() setzt vor jedem Szenario zurueck.
        add("crash-restitution-null", false, 100, 1.5, false, lane -> {
            config.crashRestitution = 0.0;
            track(lane, -4, 10, GROUND_Y - 1, Material.STONE);
            wall(lane, 6, GROUND_Y);
        }, run -> {
            double contact = maxZ(run);
            double rebound = contact - minZAfterMax(run);
            if (contact < WALL_CONTACT_MIN || contact > WALL_CONTACT_MAX) {
                return Result.fail(fmt("Wandkontakt z=%.3f, erwartet %.1f..%.1f", contact,
                        WALL_CONTACT_MIN, WALL_CONTACT_MAX));
            }
            if (rebound > 0.05) {
                return Result.fail(fmt("prallt trotz crash-restitution 0 um %.3f Bloecke ab",
                        rebound));
            }
            return Result.pass(fmt("harter Stopp bei z=%.3f ohne Abpraller (%.3f)", contact, rebound));
        });

        // 21-26 — Sweeps: systematisch ueber alle Stufenhoehen, Neigungen, Belagswechsel
        // und einen breiten Querschnitt echter Bloecke.
        driverInputs();
        environment();
        gripAndCrash();
        stepUpHeights();
        stepUpFromSurfaces();
        stepDownHeights();
        slopes();
        surfaceTransitions();
        driveOverObstacles();
    }

    /** Farmland-Kante quer zur Fahrt auf breiter Platte — variiert Winkel, Tempo und Koordinaten. */
    private void edgeCase(String name, float yaw, boolean negative, double speed, boolean drive,
                          int length, boolean knownFail) {
        add(name, knownFail, 120, speed, drive, yaw, negative, lane ->
                plate(lane, 14, -6, length, 4, Material.FARMLAND, Material.STONE), run -> {
            double far = travelled(run);
            if (far < MICRO_STEP_MIN_TRAVEL) {
                return Result.fail(fmt("bleibt nach %.3f Bloecken an der Kante haengen (v=%.3f, z=%.3f)",
                        far, lastSample(run).speed(), lastSample(run).z() - run.lane().baseZ()));
            }
            return Result.pass(fmt("%.3f Bloecke ueber die Kante", far));
        });
    }

    /** Ganze Stufe (1 Block) von einem Belag mit reduzierter Oberkante aus. */
    private void stepUpFrom(String name, Material surface, boolean knownFail) {
        add(name, knownFail, 100, 1.0, true, lane -> {
            track(lane, -4, 4, GROUND_Y - 1, surface);
            track(lane, 5, 20, GROUND_Y, Material.STONE);
            wall(lane, 20, GROUND_Y + 1);
        }, run -> {
            double reached = maxZ(run);
            // Oberkante des Belags aus der echten Kollisionsform, nicht aus dem Gedaechtnis
            double top = supportTop(run.lane().world(), run.lane().baseX(), GROUND_Y - 1, run.lane().baseZ());
            if (reached < 7.0) {
                return Result.fail(fmt("bleibt bei z=%.3f stehen — von %s (Oberkante %.4f) aus reisst die "
                        + "1-Block-Stufe MAX_STEP: %.4f > 1,0", reached, surface.name(), top, 2.0 - top));
            }
            return Result.pass(fmt("Stufe von %s (Oberkante %.4f) aus genommen, z=%.3f",
                    surface.name(), top, reached));
        });
    }

    /** Mikro-Stufe: 1/16 Block hoch von einem flachen Belag auf einen Vollblock. */
    private void microStep(String name, Material surface, double speed, boolean knownFail) {
        add(name, knownFail, 120, speed, true, lane -> {
            track(lane, -4, 4, GROUND_Y - 1, surface);
            track(lane, 5, 20, GROUND_Y - 1, Material.STONE);
            wall(lane, 20, GROUND_Y);
        }, microCheck(surface.name() + " -> Vollblock"));
    }

    private Function<Run, Result> microCheck(String what) {
        return run -> {
            double reached = maxZ(run);
            if (reached < MICRO_STEP_MIN_TRAVEL) {
                return Result.fail(fmt("bleibt bei z=%.3f stehen (v=%.3f), erwartet >= %.1f — %s",
                        reached, lastSample(run).speed(), MICRO_STEP_MIN_TRAVEL, what));
            }
            return Result.pass(fmt("%.3f Bloecke weit — %s", reached, what));
        };
    }

    // ────────────────────────────── Ablaufsteuerung ──────────────────────────────

    @Override
    public void run() {
        if (current == null) {
            startNext();
            return;
        }
        waited++;
        int budget = 0;
        boolean allDone = true;
        for (Active a : active) {
            budget = Math.max(budget, a.spec.ticks());
            if (!isDone(a)) {
                allDone = false;
            }
        }
        if (!allDone && waited < budget + TIMEOUT_MARGIN) {
            return;
        }
        for (Active a : active) {
            evaluate(a, isDone(a));
        }
        cleanup();
        if (!pending.isEmpty()) {
            startBatch();
            return;
        }
        reportScenario();
        current = null;
    }

    private boolean isDone(Active a) {
        return a.car == null || a.car.getSimTicks() <= 0
                || carManager.getCarByBase(a.car.getBase().getUniqueId()) == null;
    }

    /** Wertet einen gefahrenen Fall aus und schreibt ihn in die Zaehler des Szenarios. */
    private void evaluate(Active a, boolean done) {
        if (a.spec.ticks() == 0) {
            recordCase(a, a.spec.check().apply(new Run(a.lane, List.of())));
            return;
        }
        Run run = new Run(a.lane, List.copyOf(a.samples));
        double lowest = a.samples.stream().mapToDouble(SimSample::y).min().orElse(a.spec.minY());
        Result result;
        if (!done) {
            result = Result.fail("Timeout nach " + waited + " Ticks");
        } else if (lowest < a.spec.minY()) {
            result = Result.fail(fmt("aus der Bahn gefallen (y=%.3f, Untergrenze %.3f)", lowest, a.spec.minY()));
        } else if (a.samples.isEmpty()) {
            result = Result.fail("kein einziger Tick aufgezeichnet (Chunk nicht geladen?)");
        } else {
            result = a.spec.check().apply(run);
        }
        recordCase(a, result);
    }

    private void recordCase(Active a, Result result) {
        caseTotal++;
        totalCases++;
        String label = a.spec.label();
        boolean sweepCase = !label.isEmpty();
        lastDetail = result.detail();
        if (result.ok() && !a.spec.knownFail()) {
            caseOk++;
            if (verbose && sweepCase) {
                log("  ok   " + label + " — " + result.detail());
            }
        } else if (result.ok()) {
            caseBad++;
            lastDetail = result.detail() + " — knownFail-Flag entfernen, der Bug ist gefixt";
            if (sweepCase) {
                caseNotes.add("UNEXPECTED-PASS " + label + ": " + lastDetail);
            }
        } else if (a.spec.knownFail()) {
            caseKnown++;
            if (sweepCase) {
                caseNotes.add("KNOWN-FAIL " + label + ": " + result.detail());
            }
        } else {
            caseBad++;
            if (sweepCase) {
                caseNotes.add(label + ": " + result.detail());
            }
            for (SimSample sample : trace(a)) {
                caseNotes.add((sweepCase ? "    " : "  ") + sample.describe());
            }
        }
        if (verbose && !a.samples.isEmpty()) {
            for (SimSample sample : a.samples) {
                log("  " + (label.isEmpty() ? "" : label + " ") + sample.describe());
            }
        }
    }

    private void startNext() {
        index++;
        if (index >= scenarios.size()) {
            summary();
            return;
        }
        current = scenarios.get(index);
        // Ein Szenario darf die Physik-Konfiguration umstellen (z. B. crash-restitution 0).
        // Vor jedem Szenario zurueck auf die config.yml, damit sich das nicht fortpflanzt —
        // auch dann, wenn der Lauf vorher abgebrochen ist und die Pruefung nie lief.
        config.reload();
        pending.clear();
        pending.addAll(current.cases());
        caseNotes.clear();
        caseOk = 0;
        caseBad = 0;
        caseKnown = 0;
        caseTotal = 0;
        startBatch();
    }

    /** Startet die naechste Gruppe wartender Faelle gleichzeitig, jeden auf einer eigenen Bahn. */
    private void startBatch() {
        active.clear();
        waited = 0;
        World world = Bukkit.getWorlds().get(0);
        int count = Math.min(BATCH, pending.size());
        List<Case> batch = new ArrayList<>(pending.subList(0, count));
        pending.subList(0, count).clear();
        for (Case spec : batch) {
            Lane lane = spec.negativeLane()
                    ? new Lane(world, -600 - laneCounter * LANE_SPACING, -600, GROUND_Y)
                    : new Lane(world, 200 + laneCounter * LANE_SPACING, 200, GROUND_Y);
            laneCounter++;
            ticketChunks(lane, spec.clear());
            clearLane(lane, spec.clear());
            spec.build().accept(lane);
            active.add(new Active(spec, lane));
        }
        // Erst bauen, dann spawnen: ein Auto, das waehrend des Streckenbaus schon tickt,
        // faellt durch den noch nicht gesetzten Boden.
        for (Active a : active) {
            if (a.spec.ticks() == 0) {
                continue;
            }
            Case spec = a.spec;
            a.car = carManager.spawnCar(new Location(a.lane.world(), a.lane.baseX() + 0.5,
                    a.lane.groundY(), a.lane.baseZ() + 0.5, spec.yaw(), 0f), spec.yaw());
            double rad = Math.toRadians(spec.yaw());
            a.car.setVelX(-Math.sin(rad) * spec.startSpeed());
            a.car.setVelZ(Math.cos(rad) * spec.startSpeed());
            a.car.setSimInput(spec.input());
            if (spec.tune() != null) {
                spec.tune().accept(a.car);
            }
            a.car.setSimObserver(a.samples::add);
            a.car.setSimTicks(spec.ticks());
        }
    }

    /** Haelt die Bahn geladen: ohne Spieler in der Naehe entlaedt der Server sie sonst und
     *  DriveTask ueberspringt das Auto ("kein Ticken in ungeladenen Chunks"). */
    private void ticketChunks(Lane lane, Area area) {
        World world = lane.world();
        int margin = Math.max(area.halfWidth(), 16);
        for (int cx = (lane.baseX() - margin) >> 4; cx <= (lane.baseX() + margin) >> 4; cx++) {
            for (int cz = (lane.baseZ() + area.zFrom()) >> 4; cz <= (lane.baseZ() + area.zTo()) >> 4; cz++) {
                world.addPluginChunkTicket(cx, cz, plugin);
                tickets.add(new long[]{cx, cz});
            }
        }
    }

    private void releaseChunks() {
        World world = Bukkit.getWorlds().get(0);
        for (long[] c : tickets) {
            world.removePluginChunkTicket((int) c[0], (int) c[1], plugin);
        }
        tickets.clear();
    }

    private void cleanup() {
        releaseChunks();
        for (Active a : active) {
            if (a.car != null && carManager.getCarByBase(a.car.getBase().getUniqueId()) != null) {
                carManager.removeCar(a.car, false);
            }
        }
        active.clear();
        for (Car extra : extraCars) {
            if (carManager.getCarByBase(extra.getBase().getUniqueId()) != null) {
                carManager.removeCar(extra, false);
            }
        }
        extraCars.clear();
    }

    /** Eine Ergebniszeile je Szenario; bei Sweeps zusaetzlich eine Zeile je auffaelligem Fall. */
    private void reportScenario() {
        String name = pad(current.name());
        // Einzelszenarien tragen ihr Ergebnis in der Kopfzeile, Sweeps eine Bilanz plus
        // eine Zeile je auffaelligem Fall. Am leeren Label haengt der Unterschied, nicht an
        // der Anzahl — sonst zaehlt ein gefilterter Sweep mit genau einem Treffer falsch.
        boolean single = current.cases().size() == 1 && current.cases().get(0).label().isEmpty();
        String detail = single ? lastDetail
                : fmt("%d Faelle: %d ok, %d bekannte Bugs, %d fehlgeschlagen", caseTotal, caseOk,
                        caseKnown, caseBad);
        knownFailed += caseKnown;
        if (caseBad > 0) {
            failed++;
            log("FAIL " + name + " " + detail);
        } else if (caseKnown == caseTotal) {
            log("KNOWN-FAIL " + name + " " + detail);
        } else {
            passed++;
            log("PASS " + name + " " + detail);
        }
        for (String note : caseNotes) {
            log("  " + note);
        }
    }

    /** Die aussagekraeftigen Ticks fuer eine Fehlermeldung: rund um den ersten Blockade-Tick und das Ende. */
    private List<SimSample> trace(Active a) {
        List<SimSample> out = new ArrayList<>();
        List<SimSample> samples = a.samples;
        int firstBlocked = -1;
        for (int i = 0; i < samples.size(); i++) {
            if (samples.get(i).blocked()) {
                firstBlocked = i;
                break;
            }
        }
        if (firstBlocked >= 0) {
            for (int i = Math.max(0, firstBlocked - 1); i < Math.min(samples.size(), firstBlocked + 3); i++) {
                out.add(samples.get(i));
            }
        }
        for (int i = Math.max(0, samples.size() - 3); i < samples.size(); i++) {
            if (!out.contains(samples.get(i))) {
                out.add(samples.get(i));
            }
        }
        return out;
    }

    private void summary() {
        long seconds = (System.currentTimeMillis() - startedAt) / 1000;
        log(String.format(Locale.ROOT, "SUMMARY passed=%d failed=%d known-fail=%d faelle=%d dauer=%ds",
                passed, failed, knownFailed, totalCases, seconds));
        running = false;
        cancel();
    }

    private void log(String line) {
        plugin.getLogger().info("[Selftest] " + line);
    }

    // ────────────────────────────── Streckenbau ──────────────────────────────

    /** Raeumt die Bahn frei — inklusive Bloecken HINTER dem Start (hintere Samples bei z−1,25). */
    private void clearLane(Lane lane, Area area) {
        for (int x = lane.baseX() - area.halfWidth(); x <= lane.baseX() + area.halfWidth(); x++) {
            for (int z = lane.baseZ() + area.zFrom(); z <= lane.baseZ() + area.zTo(); z++) {
                for (int y = lane.groundY() + area.yFrom(); y <= lane.groundY() + area.yTo(); y++) {
                    lane.world().getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private void track(Lane lane, int zFrom, int zTo, int y, Material material) {
        for (int z = zFrom; z <= zTo; z++) {
            floorAt(lane, z, y, material);
        }
    }

    /** Gefaelle ab z=3: eine Stufe pro {@code stepEvery} Bloecke, maximal {@code maxDrop} tief. */
    private void trackStairs(Lane lane, int zFrom, int zTo, int stepEvery, int maxDrop) {
        for (int z = zFrom; z <= zTo; z++) {
            int drop = Math.min(maxDrop, Math.max(0, z - 2) / stepEvery);
            floorAt(lane, z, GROUND_Y - 1 - drop, Material.STONE);
        }
    }

    private void floorAt(Lane lane, int zRel, int y, Material material) {
        for (int x = lane.baseX() - 2; x <= lane.baseX() + 2; x++) {
            lane.world().getBlockAt(x, y, lane.baseZ() + zRel).setType(material, false);
        }
    }

    /** Breite Platte mit Umrandung: unterhalb von zSplit der Belag, ab zSplit der Vollblock.
     *  Die Mauer rundherum haelt das Auto auf der Platte — sonst faehrt es bei schraeger
     *  Fahrt seitlich herunter und jede Messung ist wertlos. */
    private void plate(Lane lane, int halfWidth, int zFrom, int zTo, int zSplit,
                       Material surface, Material beyond) {
        for (int x = lane.baseX() - halfWidth; x <= lane.baseX() + halfWidth; x++) {
            for (int z = zFrom; z <= zTo; z++) {
                boolean border = x == lane.baseX() - halfWidth || x == lane.baseX() + halfWidth
                        || z == zFrom || z == zTo;
                for (int y = GROUND_Y; y <= GROUND_Y + 6; y++) {
                    lane.world().getBlockAt(x, y, lane.baseZ() + z)
                            .setType(border && y <= GROUND_Y + 3 ? Material.STONE : Material.AIR, false);
                }
                lane.world().getBlockAt(x, GROUND_Y - 1, lane.baseZ() + z)
                        .setType(z < zSplit ? surface : beyond, false);
            }
        }
    }

    private void snowLayer(Lane lane, int zRel, int layers) {
        for (int x = lane.baseX() - 2; x <= lane.baseX() + 2; x++) {
            var block = lane.world().getBlockAt(x, GROUND_Y, lane.baseZ() + zRel);
            block.setType(Material.SNOW, false);
            Snow snow = (Snow) block.getBlockData();
            snow.setLayers(layers);
            block.setBlockData(snow, false);
        }
    }

    private void clearColumn(Lane lane, int zRel, int yFrom, int yTo) {
        for (int x = lane.baseX() - 2; x <= lane.baseX() + 2; x++) {
            for (int y = yFrom; y <= yTo; y++) {
                lane.world().getBlockAt(x, y, lane.baseZ() + zRel).setType(Material.AIR, false);
            }
        }
    }

    private void wall(Lane lane, int zRel, int fromY) {
        for (int x = lane.baseX() - 2; x <= lane.baseX() + 2; x++) {
            for (int y = fromY; y <= fromY + 3; y++) {
                lane.world().getBlockAt(x, y, lane.baseZ() + zRel).setType(Material.STONE, false);
            }
        }
    }

    private double supportTop(World world, int x, int y, int z) {
        double max = 0;
        for (BoundingBox box : world.getBlockAt(x, y, z).getCollisionShape().getBoundingBoxes()) {
            max = Math.max(max, box.getMaxY());
        }
        return max;
    }

    // ────────────────────────────── Auswertungs-Helfer ──────────────────────────────

    private double maxZ(Run run) {
        return run.samples().stream().mapToDouble(s -> s.z() - run.lane().baseZ()).max().orElse(0);
    }

    private double minZ(Run run) {
        return run.samples().stream().mapToDouble(s -> s.z() - run.lane().baseZ()).min().orElse(0);
    }

    private double minZAfterMax(Run run) {
        double max = Double.NEGATIVE_INFINITY;
        int maxIndex = 0;
        List<SimSample> list = run.samples();
        for (int i = 0; i < list.size(); i++) {
            double z = list.get(i).z();
            if (z > max) {
                max = z;
                maxIndex = i;
            }
        }
        double min = max;
        for (int i = maxIndex; i < list.size(); i++) {
            min = Math.min(min, list.get(i).z());
        }
        return min - run.lane().baseZ();
    }

    /** Luftlinie vom Startpunkt — fuer Szenarien, die nicht entlang +Z fahren. */
    private double travelled(Run run) {
        double sx = run.lane().baseX() + 0.5;
        double sz = run.lane().baseZ() + 0.5;
        return run.samples().stream().mapToDouble(s -> Math.hypot(s.x() - sx, s.z() - sz)).max().orElse(0);
    }

    private SimSample lastSample(Run run) {
        return run.samples().get(run.samples().size() - 1);
    }

    private void expect(List<String> errors, String label, Set<String> permissions, boolean console,
                        CarCommand.Decision.Kind expectedKind, String expectedNode, String... args) {
        CarCommand.Decision decision = CarCommand.decide(args, console,
                permissions == null ? node -> true : permissions::contains);
        if (decision.kind() != expectedKind) {
            errors.add(label + ": " + decision.kind() + " statt " + expectedKind);
            return;
        }
        if (expectedNode != null && !expectedNode.equals(decision.node())) {
            errors.add(label + ": Node " + decision.node() + " statt " + expectedNode);
        }
    }

    private void checkDefault(List<String> errors, String node, PermissionDefault expected) {
        Permission permission = Bukkit.getPluginManager().getPermission(node);
        if (permission == null) {
            errors.add(node + " fehlt");
        } else if (permission.getDefault() != expected) {
            errors.add(node + " default=" + permission.getDefault() + " statt " + expected);
        }
    }

    private void clamp(List<String> errors, String key, double input, double expected) {
        double actual = CarConfig.clampHumanValue(key, input);
        if (Math.abs(actual - expected) > 1.0e-6) {
            errors.add(fmt("%s: %.1f -> %.4f statt %.4f", key, input, actual, expected));
        }
    }

    private static String fmt(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }

    private static String pad(String name) {
        return name.length() >= 24 ? name : name + " ".repeat(24 - name.length());
    }


    // ────────────────────────────── Fahrer-Eingaben ──────────────────────────────
    //
    // Gas, Bremse, Handbremse, Rueckwaertsgang und Lenkung liefen bis hierher in KEINEM Test:
    // ohne Fahrer gab es keine Eingabe, und die Simulation hatte die Gas-Formel nachgebaut
    // statt sie aufzurufen. Mit SimInput (org.bukkit.Input ist eine reine Boolean-Schnittstelle)
    // laeuft alles durch DriveTask.applyInput — dieselbe Stelle, die auch der Spieler trifft.

    private static final Area INPUT_CLEAR = new Area(3, -5, 46, -4, 5);
    private static final Area REVERSE_CLEAR = new Area(3, -46, 8, -4, 5);
    private static final Area SPEED_CLEAR = new Area(3, -5, 344, -4, 5);
    private static final Area PLATE_CLEAR = new Area(3, -5, 8, -4, 5);

    private void driverInputs() {
        Sweep sweep = sweep("driver-input", INPUT_CLEAR);

        // Fussbremse: Verzoegerung = brake-deceleration x Grip. Auf Stein (Grip 0,8) sind das
        // 0,048 Bloecke/Tick^2 — aus 1 Block/Tick also gut 20 Ticks bis zum Stillstand.
        sweep.run("bremse", false, 60, 1.0, SimInput.BREMSE, 0f, GROUND_Y - 3.0, lane -> {
            flat(lane, -4, 44);
        }, run -> stopCheck(run, "Fussbremse", config.brakeDeceleration));

        // Handbremse (Sprungtaste): schwaecher als die Fussbremse, dafuer bricht der
        // Folge-Grip ein. Wirkt auch ohne Fahrpedal.
        sweep.run("handbremse", false, 90, 1.0, SimInput.HANDBREMSE, 0f, GROUND_Y - 3.0, lane -> {
            flat(lane, -4, 44);
        }, run -> stopCheck(run, "Handbremse", config.handbrakeDeceleration));

        // W und S gleichzeitig bremsen mit voller Bremskraft (kein Patt, kein Vortrieb).
        sweep.run("gas-und-bremse", false, 60, 1.0, new SimInput(true, true, false, false, false),
                0f, GROUND_Y - 3.0, lane -> {
            flat(lane, -4, 44);
        }, run -> stopCheck(run, "W+S", config.brakeDeceleration));

        // Motorbremse zum Vergleich: ohne Pedal rollt das Auto weit aus (rollout-stone deckt
        // die Zahl ab) — hier zaehlt nur, dass sie DEUTLICH schwaecher ist als jede Bremse.
        sweep.run("ausrollen", false, 40, 1.0, null, 0f, GROUND_Y - 3.0, lane -> {
            flat(lane, -4, 44);
        }, run -> {
            double kept = lastSample(run).speed() / run.samples().get(0).speed();
            measurements.put("ausrollen", kept);
            if (kept < 0.5) {
                return Result.fail(fmt("ohne Pedal nur %.1f %% Tempo uebrig — die Motorbremse "
                        + "bremst wie eine Bremse", kept * 100));
            }
            return Result.pass(fmt("nach 40 Ticks ohne Pedal noch %.1f %% Tempo", kept * 100));
        });

        // Rueckwaerts anfahren: S aus dem Stand beschleunigt bis max-reverse-speed und
        // nicht darueber. Die Strecke liegt HINTER dem Start.
        sweep.run("rueckwaerts-anfahren", false, 90, 0.0, SimInput.BREMSE, 0f, GROUND_Y - 3.0,
                REVERSE_CLEAR, lane -> {
            track(lane, -44, 4, GROUND_Y - 1, Material.STONE);
            wall(lane, -44, GROUND_Y);
            wall(lane, 4, GROUND_Y);
        }, run -> {
            double max = config.maxReverseSpeed;
            double fastest = run.samples().stream().mapToDouble(SimSample::speed).max().orElse(0);
            double last = lastSample(run).speed();
            double travelled = minZ(run);
            if (travelled > -3.0) {
                return Result.fail(fmt("faehrt nicht rueckwaerts an: nur %.3f Bloecke", travelled));
            }
            if (fastest > max + 1.0e-6) {
                return Result.fail(fmt("ueberschreitet max-reverse-speed: %.4f > %.4f", fastest, max));
            }
            if (last < max * 0.9) {
                return Result.fail(fmt("erreicht max-reverse-speed nicht: %.4f von %.4f (%.0f %%)",
                        last, max, last / max * 100));
            }
            return Result.pass(fmt("%.3f Bloecke rueckwaerts, %.4f von %.4f Bl/Tick (%.0f %%)",
                    travelled, last, max, last / max * 100));
        });

        // Vollgas aus dem Stand: der weiche Limiter muss sich max-speed naehern, ohne sie
        // je zu ueberschreiten. Dafuer braucht es eine wirklich lange Gerade.
        sweep.run("max-speed", false, 200, 0.0, SimInput.GAS, 0f, GROUND_Y - 3.0, SPEED_CLEAR,
                lane -> {
            track(lane, -4, 342, GROUND_Y - 1, Material.STONE);
            wall(lane, 342, GROUND_Y);
        }, run -> {
            double max = config.maxSpeed;
            double fastest = run.samples().stream().mapToDouble(SimSample::speed).max().orElse(0);
            if (fastest > max + 1.0e-6) {
                return Result.fail(fmt("ueberschreitet max-speed: %.4f > %.4f Bl/Tick (%.1f km/h)",
                        fastest, max, fastest * 72));
            }
            if (fastest < max * 0.8) {
                return Result.fail(fmt("kommt nur auf %.1f km/h von %.1f (%.0f %%) — der weiche "
                        + "Limiter bremst zu frueh", fastest * 72, max * 72, fastest / max * 100));
            }
            return Result.pass(fmt("%.1f km/h von %.1f (%.0f %%), nie darueber",
                    fastest * 72, max * 72, fastest / max * 100));
        });

        // Lenkung: die Drehrate darf nie ueber dem kleineren der beiden Deckel liegen —
        // Lenkrad-Anschlag (turn-curvature x Tempo) und Grip-Budget (maxLatGrip x grip / Tempo).
        // Ohne Gas und mit halbem Tempo: unter Vollgas waechst der Radius mit dem Quadrat der
        // Geschwindigkeit (Grip-Deckel!), das Auto untersteuert bis in die Bande — und ein
        // Crash-Spin dreht die Karosse voellig ausserhalb jedes Lenk-Deckels.
        sweep.run("lenkung", false, 80, 0.5, SimInput.RECHTS, 0f, GROUND_Y - 3.0, PLATE_CLEAR,
                lane -> plate(lane, 20, -20, 20, 9999, Material.STONE, Material.STONE), run -> {
            List<SimSample> samples = run.samples();
            double turned = 0;
            double fastest = 0;
            double fastestCap = 0;
            double violation = 0;
            double violationCap = 0;
            double previous = 0;
            for (int i = 1; i < samples.size(); i++) {
                SimSample before = samples.get(i - 1);
                double rate = Math.abs(wrapDeg(samples.get(i).yaw() - before.yaw()));
                turned += rate;
                if (rate > fastest) {
                    fastest = rate;
                    fastestCap = steerCap(before.speed(), before.grip());
                }
                // Die Drehrate folgt dem Bedarf geglaettet (YAW_SMOOTH_*): sie darf ueber dem
                // aktuellen Deckel liegen, solange sie von einem frueheren, hoeheren Wert
                // ABKLINGT. Neu aufbauen darf sie sich nur bis zum Deckel.
                double allowed = Math.max(steerCap(before.speed(), before.grip()), previous);
                if (rate - allowed > violation - violationCap) {
                    violation = rate;
                    violationCap = allowed;
                }
                previous = rate;
            }
            measurements.put("lenkung-slip", maxSlip(run));
            measurements.put("lenkung-drehung-10", turnedIn(run, 10));
            if (run.samples().stream().anyMatch(SimSample::blocked)) {
                return Result.fail("faehrt in die Bande — der Lenk-Deckel ist so nicht messbar");
            }
            if (turned < 90.0) {
                return Result.fail(fmt("dreht in %d Ticks nur %.1f Grad", samples.size(), turned));
            }
            if (violation > violationCap + 0.5) {
                return Result.fail(fmt("Drehrate %.2f Grad/Tick ueber dem Deckel %.2f",
                        violation, violationCap));
            }
            return Result.pass(fmt("%.0f Grad gedreht, schnellste Rate %.2f von dort erlaubten "
                    + "%.2f Grad/Tick, kein Tick ueber dem Lenk-/Grip-Deckel",
                    turned, fastest, fastestCap));
        });

        // Handbremse beim Lenken: die Lenkung behaelt vollen Grip (Vorderraeder), aber der
        // Vektor folgt schlechter -> messbar mehr Schlupf als ohne.
        sweep.run("lenkung-handbremse", false, 80, 0.5, new SimInput(false, false, false, true, true),
                0f, GROUND_Y - 3.0, PLATE_CLEAR,
                lane -> plate(lane, 20, -20, 20, 9999, Material.STONE, Material.STONE), run -> {
            // Verglichen wird nur das erste Stueck: die Handbremse bremst hart, danach sind
            // die Tempi zu verschieden, um noch etwas ueber den Grip auszusagen.
            double slip = maxSlip(run);
            double turned = turnedIn(run, 10);
            Double slipNormal = measurements.get("lenkung-slip");
            Double turnedNormal = measurements.get("lenkung-drehung-10");
            if (slipNormal != null && slip <= slipNormal + 1.0) {
                return Result.fail(fmt("Handbremse erzeugt keinen zusaetzlichen Schlupf: "
                        + "%.1f Grad gegen %.1f Grad ohne", slip, slipNormal));
            }
            // Einseitig: die Lenkung rechnet mit grip, nicht mit gripEff, also darf die
            // Handbremse sie nicht schwaechen. Mehr Drehung ist dagegen zu erwarten — die
            // Handbremse nimmt Tempo, und der Grip-Deckel waechst mit sinkendem Tempo.
            if (turnedNormal != null && turned < turnedNormal * 0.95) {
                return Result.fail(fmt("die Lenkung verliert mit Handbremse Grip: %.1f Grad in "
                        + "10 Ticks gegen %.1f ohne — sie soll vollen Grip behalten (Vorderraeder)",
                        turned, turnedNormal));
            }
            return Result.pass(fmt("Schlupf %.1f Grad statt %.1f, Lenkung mindestens so wirksam "
                    + "(%.1f gegen %.1f Grad in 10 Ticks)", slip,
                    slipNormal == null ? -1 : slipNormal, turned,
                    turnedNormal == null ? -1 : turnedNormal));
        });

        // Unterhalb turn-min-speed dreht sich gar nichts — sonst koennte man im Stand
        // auf der Stelle rotieren. Ausnahme ist nur der Rangier-Fall bei Wandkontakt.
        sweep.run("turn-min-speed", false, 40, 0.0, SimInput.RECHTS, 0f, GROUND_Y - 3.0, lane -> {
            flat(lane, -4, 44);
        }, run -> {
            float start = run.samples().get(0).yaw();
            double worst = run.samples().stream()
                    .mapToDouble(s -> Math.abs(wrapDeg(s.yaw() - start))).max().orElse(0);
            if (worst > 0.01) {
                return Result.fail(fmt("dreht im Stand um %.2f Grad, erwartet 0 (turn-min-speed "
                        + "%.4f Bl/Tick)", worst, config.turnMinSpeed));
            }
            return Result.pass("im Stand keine Drehung");
        });

        sweep.done();
    }

    /** Flache Steinbahn mit Wand am Ende. */
    private void flat(Lane lane, int zFrom, int zTo) {
        track(lane, zFrom, zTo, GROUND_Y - 1, Material.STONE);
        wall(lane, zTo, GROUND_Y);
    }

    /** Erlaubte Drehrate eines Ticks: das Kleinere aus Lenkrad-Anschlag und Grip-Budget. */
    private double steerCap(double speed, double grip) {
        double gripCap = Math.toDegrees((config.maxLatGrip * grip)
                / Math.max(speed, DriveTask.SPEED_EPSILON));
        // Unter turn-min-speed steht das Lenkrad still — ausser beim Rangieren am Hindernis.
        double wheelCap = speed >= config.turnMinSpeed
                ? config.turnCurvature * speed
                : DriveTask.CRAWL_TURN_DEG;
        return Math.min(wheelCap, gripCap);
    }

    /** Winkeldifferenz auf −180..180 normiert (yaw springt bei ±180 um). */
    private static double wrapDeg(double angle) {
        double a = angle % 360.0;
        if (a > 180.0) {
            a -= 360.0;
        } else if (a < -180.0) {
            a += 360.0;
        }
        return a;
    }

    /** Summe der Drehbetraege ueber die ersten {@code ticks} Ticks. */
    private double turnedIn(Run run, int ticks) {
        List<SimSample> samples = run.samples();
        double sum = 0;
        for (int i = 1; i < Math.min(samples.size(), ticks + 1); i++) {
            sum += Math.abs(wrapDeg(samples.get(i).yaw() - samples.get(i - 1).yaw()));
        }
        return sum;
    }

    /** Schlupfwinkel nach genau {@code ticks} Ticks (oder am Ende, wenn der Lauf kuerzer ist). */
    private double slipAt(Run run, int ticks) {
        List<SimSample> samples = run.samples();
        return samples.get(Math.min(ticks, samples.size() - 1)).slipDeg();
    }

    private double maxSlip(Run run) {
        return run.samples().stream().mapToDouble(SimSample::slipDeg).max().orElse(0);
    }

    /**
     * Gemeinsame Pruefung aller Bremsvorgaenge: das Auto muss stehen, und zwar nach der
     * Tick-Zahl, die aus der konfigurierten Verzoegerung x Grip folgt. So faellt auf, wenn
     * eine Bremse den Grip nicht mehr beruecksichtigt oder die Einheit verrutscht.
     */
    private Result stopCheck(Run run, String what, double deceleration) {
        List<SimSample> samples = run.samples();
        double start = samples.get(0).speed();
        double grip = samples.get(0).grip();
        int stopped = -1;
        for (int i = 0; i < samples.size(); i++) {
            if (samples.get(i).speed() < 0.05) {
                stopped = i;
                break;
            }
        }
        double expected = start / (deceleration * grip);
        if (stopped < 0) {
            return Result.fail(fmt("%s bringt das Auto in %d Ticks nicht zum Stehen (v=%.4f), "
                    + "erwartet nach rund %.0f Ticks", what, samples.size(),
                    lastSample(run).speed(), expected));
        }
        if (stopped < expected * 0.7 || stopped > expected * 1.4) {
            return Result.fail(fmt("%s haelt nach %d Ticks, erwartet %.0f (+-30 %%) aus "
                    + "%.4f Bl/Tick^2 x Grip %.2f", what, stopped, expected, deceleration, grip));
        }
        return Result.pass(fmt("%s haelt nach %d Ticks (erwartet rund %.0f)", what, stopped, expected));
    }


    // ────────────────────────────── Umgebung ──────────────────────────────
    //
    // Wasser, Lava, freier Fall und Steigungsenergie: alles dokumentiert, bis hierher
    // von keinem Test beruehrt. Die Erwartungen kommen aus der config.yml (max-sink-speed,
    // max-fall-speed, slope-resistance), damit eine Einheiten-Verrutschung auffliegt.

    private static final Area BASIN_CLEAR = new Area(3, -5, 26, -14, 6);
    private static final Area FALL_CLEAR = new Area(3, -5, 38, -52, 6);

    private void environment() {
        Sweep sweep = sweep("environment", INPUT_CLEAR);

        // Wasser traegt nicht: das Auto sinkt. Der Fall wird dabei gebremst — geprueft wird
        // gegen max-sink-speed aus der config.yml.
        // OFFEN (knownFail): die gemessene Endgeschwindigkeit liegt bei rund 30 km/h statt der
        // konfigurierten 9. applyGravity addiert je Tick erst die volle Erdbeschleunigung und
        // daempft danach nur 15 % des Ueberschusses je 0,25-Bloecke-Substep. Der Fixpunkt
        // dieser Folge liegt weit ueber max-sink-speed — der Wert wirkt als Richtgroesse,
        // nicht als Grenze. Entweder die Daempfung anziehen oder den Key umbenennen.
        sweep.run("wasser-sinken", true, 80, 1.0, null, 0f, GROUND_Y - 13.0, BASIN_CLEAR, lane -> {
            track(lane, -4, 5, GROUND_Y - 1, Material.STONE);
            basin(lane, 6, 24, 10, Material.WATER);
        }, run -> {
            double fastest = fastestSink(run);
            double lowest = run.samples().stream().mapToDouble(SimSample::y).min().orElse(GROUND_Y);
            if (lowest > GROUND_Y - 2.0) {
                return Result.fail(fmt("sinkt nicht ein: tiefster Punkt y=%.3f", lowest));
            }
            if (fastest > config.maxSinkSpeed * 1.15) {
                return Result.fail(fmt("sinkt mit %.4f Bl/Tick (%.1f km/h), erlaubt sind "
                        + "%.4f (%.1f km/h) aus max-sink-speed", fastest, fastest * 72,
                        config.maxSinkSpeed, config.maxSinkSpeed * 72));
            }
            return Result.pass(fmt("sinkt mit hoechstens %.1f km/h auf y=%.3f", fastest * 72, lowest));
        });

        // Wasser bremst die Querbewegung stark (WATER_DRAG, 10 % je Tick).
        sweep.run("wasser-bremst", false, 60, 1.5, null, 0f, GROUND_Y - 13.0, BASIN_CLEAR, lane -> {
            track(lane, -4, 5, GROUND_Y - 1, Material.STONE);
            basin(lane, 6, 24, 10, Material.WATER);
        }, run -> {
            List<SimSample> samples = run.samples();
            double before = 0;
            double after = 0;
            for (int i = 1; i < samples.size(); i++) {
                if (samples.get(i).y() < GROUND_Y - 1.0 && before == 0) {
                    before = samples.get(i - 1).speed();
                    after = samples.get(Math.min(samples.size() - 1, i + 5)).speed();
                }
            }
            if (before == 0) {
                return Result.fail("kommt gar nicht ins Wasser");
            }
            double kept = after / before;
            if (kept > 0.75) {
                return Result.fail(fmt("Wasser bremst kaum: nach 5 Ticks noch %.1f %% Tempo "
                        + "(erwartet hoechstens 75 %% bei 10 %% je Tick)", kept * 100));
            }
            return Result.pass(fmt("Tempo von %.3f auf %.3f in 5 Ticks Wasser (%.0f %%)",
                    before, after, kept * 100));
        });

        // Lava ist eine Wand (columnObstacleTop liefert +unendlich), kein Bad.
        sweep.run("lava-wand", false, 60, 1.0, SimInput.GAS, 0f, GROUND_Y - 3.0, BASIN_CLEAR, lane -> {
            track(lane, -4, 24, GROUND_Y - 1, Material.STONE);
            for (int z = 6; z <= 10; z++) {
                floorAt(lane, z, GROUND_Y, Material.LAVA);
            }
            wall(lane, 24, GROUND_Y);
        }, run -> {
            double reached = maxZ(run);
            if (reached > 5.5) {
                return Result.fail(fmt("faehrt in die Lava: z=%.3f", reached));
            }
            return Result.pass(fmt("blockiert vor der Lava bei z=%.3f", reached));
        });

        // Freier Fall: der Deckel max-fall-speed muss halten und die Landung auf der echten
        // Blockoberkante einrasten.
        // Langsam anfahren: waehrend der 34 Ticks Fall traegt der Restschwung das Auto sonst
        // ueber das Auffangpodest hinaus, und der Lauf misst nur noch den Flug in die Leere.
        sweep.run("freier-fall", false, 90, 0.3, null, 0f, GROUND_Y - 48.0, FALL_CLEAR, lane -> {
            track(lane, -4, 5, GROUND_Y - 1, Material.STONE);
            track(lane, 6, 34, GROUND_Y - 45, Material.STONE);
            wall(lane, 34, GROUND_Y - 44);
        }, run -> {
            double fastest = fastestSink(run);
            SimSample last = lastSample(run);
            if (fastest > config.maxFallSpeed + 1.0e-6) {
                return Result.fail(fmt("faellt mit %.4f Bl/Tick (%.1f km/h) ueber max-fall-speed "
                        + "%.4f (%.1f km/h)", fastest, fastest * 72, config.maxFallSpeed,
                        config.maxFallSpeed * 72));
            }
            if (fastest < config.maxFallSpeed * 0.9) {
                return Result.fail(fmt("erreicht den Deckel gar nicht: %.4f von %.4f — die "
                        + "Fallhoehe im Szenario reicht nicht", fastest, config.maxFallSpeed));
            }
            if (!last.grounded() || Math.abs(last.y() - (GROUND_Y - 44)) > 0.01) {
                return Result.fail(fmt("landet bei y=%.4f statt %d, grounded=%s", last.y(),
                        GROUND_Y - 44, last.grounded()));
            }
            return Result.pass(fmt("faellt mit hoechstens %.1f km/h (Deckel %.1f), landet exakt "
                    + "auf y=%.1f", fastest * 72, config.maxFallSpeed * 72, last.y()));
        });

        // Harte Landung: nach einem echten Fall bricht der Querschwung auf 70 % ein.
        sweep.run("harte-landung", false, 60, 1.2, null, 0f, GROUND_Y - 12.0, BASIN_CLEAR, lane -> {
            track(lane, -4, 5, GROUND_Y - 1, Material.STONE);
            track(lane, 6, 24, GROUND_Y - 9, Material.STONE);
            wall(lane, 24, GROUND_Y - 8);
        }, run -> {
            List<SimSample> samples = run.samples();
            int landing = -1;
            for (int i = 1; i < samples.size(); i++) {
                if (!samples.get(i - 1).grounded() && samples.get(i).grounded()) {
                    landing = i;
                    break;
                }
            }
            if (landing < 0) {
                return Result.fail("es gibt gar keinen Fall mit Landung");
            }
            // SimSample.speed ist das Tempo zu TICK-BEGINN: die Daempfung des Aufsetz-Ticks
            // steht erst im naechsten Sample.
            if (landing + 1 >= samples.size()) {
                return Result.fail("landet erst im letzten Tick, die Daempfung ist nicht messbar");
            }
            double before = samples.get(landing).speed();
            double after = samples.get(landing + 1).speed();
            double kept = after / before;
            if (kept > 0.95) {
                return Result.fail(fmt("harte Landung daempft nicht: %.3f -> %.3f (%.0f %%)",
                        before, after, kept * 100));
            }
            return Result.pass(fmt("Aufsetzen daempft %.3f -> %.3f (%.0f %%)", before, after,
                    kept * 100));
        });

        // Landung auf einer Stufe muss auf die halbe Blockhoehe einrasten, nicht auf den
        // ganzen Block darunter.
        sweep.run("landung-auf-stufe", false, 60, 1.0, null, 0f, GROUND_Y - 12.0, BASIN_CLEAR,
                lane -> {
            track(lane, -4, 5, GROUND_Y - 1, Material.STONE);
            track(lane, 6, 24, GROUND_Y - 6, Material.STONE);
            track(lane, 6, 24, GROUND_Y - 5, Material.STONE_SLAB);
            wall(lane, 24, GROUND_Y - 4);
        }, run -> {
            SimSample last = lastSample(run);
            double expected = GROUND_Y - 4.5;
            if (!last.grounded() || Math.abs(last.y() - expected) > 0.01) {
                return Result.fail(fmt("landet bei y=%.4f statt auf der Stufenoberkante %.4f",
                        last.y(), expected));
            }
            return Result.pass(fmt("rastet exakt auf der Stufenoberkante y=%.4f ein", last.y()));
        });

        // Steigungsenergie: mit zu wenig Schwung bleibt das Auto am Berg stehen (v^2 geht auf
        // null). Ohne Gas, damit wirklich die Energie entscheidet.
        sweep.run("steigung-totalstopp", false, 60, 0.35, null, 0f, GROUND_Y - 3.0, SLOPE_CLEAR,
                lane -> {
            for (int z = -4; z < SEAM; z++) {
                floorAt(lane, z, GROUND_Y - 1, Material.STONE);
            }
            for (int z = SEAM; z <= SEAM + 20; z++) {
                fillTo(lane, z, GROUND_Y + Math.min(6, (z - SEAM) / 2 + 1));
            }
        }, run -> {
            SimSample last = lastSample(run);
            double reached = maxZ(run);
            if (last.speed() > 1.0e-9) {
                return Result.fail(fmt("rollt am Berg weiter: v=%.5f bei z=%.3f y=%.3f",
                        last.speed(), reached, last.y()));
            }
            if (reached < SEAM || last.y() <= GROUND_Y) {
                return Result.fail(fmt("bleibt schon vor der Steigung stehen: z=%.3f y=%.3f",
                        reached, last.y()));
            }
            return Result.pass(fmt("bleibt am Berg stehen: z=%.3f y=%.3f", reached, last.y()));
        });

        // Gegenprobe: bergab wird die Energie zurueckgegeben, das Auto wird schneller.
        sweep.run("gefaelle-gewinnt", false, 60, 0.3, null, 0f, GROUND_Y - 12.0, SLOPE_CLEAR,
                lane -> {
            for (int z = -4; z < SEAM; z++) {
                floorAt(lane, z, GROUND_Y - 1, Material.STONE);
            }
            for (int z = SEAM; z <= SEAM + 20; z++) {
                fillTo(lane, z, GROUND_Y - Math.min(8, (z - SEAM) / 2 + 1));
            }
            wall(lane, SEAM + 20, GROUND_Y - 8);
        }, run -> {
            double start = run.samples().get(0).speed();
            double fastest = run.samples().stream().mapToDouble(SimSample::speed).max().orElse(0);
            if (fastest <= start * 1.5) {
                return Result.fail(fmt("bergab kaum schneller: %.3f -> %.3f", start, fastest));
            }
            return Result.pass(fmt("bergab von %.3f auf %.3f Bl/Tick", start, fastest));
        });

        sweep.done();
    }

    /** Groesste Sinkgeschwindigkeit eines Ticks (Bloecke pro Tick, positiv). */
    private double fastestSink(Run run) {
        List<SimSample> samples = run.samples();
        double fastest = 0;
        for (int i = 1; i < samples.size(); i++) {
            fastest = Math.max(fastest, samples.get(i - 1).y() - samples.get(i).y());
        }
        return fastest;
    }

    /** Steinwanne mit Fluessigkeit: Boden, Seitenwaende bei x=+-3 und Fuellung bis GROUND_Y-1. */
    private void basin(Lane lane, int zFrom, int zTo, int depth, Material fluid) {
        for (int z = zFrom; z <= zTo; z++) {
            floorAt(lane, z, GROUND_Y - 1 - depth, Material.STONE);
            for (int y = GROUND_Y - depth; y <= GROUND_Y - 1; y++) {
                lane.world().getBlockAt(lane.baseX() - 3, y, lane.baseZ() + z)
                        .setType(Material.STONE, false);
                lane.world().getBlockAt(lane.baseX() + 3, y, lane.baseZ() + z)
                        .setType(Material.STONE, false);
                floorAt(lane, z, y, fluid);
            }
        }
        for (int y = GROUND_Y - depth; y <= GROUND_Y - 1; y++) {
            for (int x = lane.baseX() - 3; x <= lane.baseX() + 3; x++) {
                lane.world().getBlockAt(x, y, lane.baseZ() + zTo + 1).setType(Material.STONE, false);
            }
        }
    }


    // ────────────────────────────── Grip und Crash ──────────────────────────────

    private static final Area PLATE20_CLEAR = new Area(3, -5, 8, -4, 5);

    private void gripAndCrash() {
        Sweep sweep = sweep("grip-crash", INPUT_CLEAR);

        // Die Grip-Tabelle als reine Funktionspruefung. Beton greift ueber den NAMEN
        // (endsWith CONCRETE) — dass Betonpulver dabei NICHT mitzaehlt, haengt an genau
        // diesem Suffix und ist sonst nirgends abgesichert.
        sweep.run("grip-tabelle", false, 0, 0, null, 0f, GROUND_Y - 3.0, lane -> {
        }, run -> {
            GripCalculator grip = new GripCalculator(config);
            List<String> errors = new ArrayList<>();
            gripIs(errors, grip, Material.WHITE_CONCRETE, config.gripConcrete);
            gripIs(errors, grip, Material.BLACK_CONCRETE, config.gripConcrete);
            gripIs(errors, grip, Material.WHITE_CONCRETE_POWDER, config.gripDefault);
            gripIs(errors, grip, Material.GRASS_BLOCK, config.gripGrass);
            gripIs(errors, grip, Material.DIRT, config.gripGrass);
            gripIs(errors, grip, Material.COARSE_DIRT, config.gripGrass);
            gripIs(errors, grip, Material.ROOTED_DIRT, config.gripGrass);
            gripIs(errors, grip, Material.PODZOL, config.gripGrass);
            gripIs(errors, grip, Material.MYCELIUM, config.gripGrass);
            gripIs(errors, grip, Material.DIRT_PATH, config.gripGrass);
            gripIs(errors, grip, Material.FARMLAND, config.gripGrass);
            gripIs(errors, grip, Material.MUD, config.gripGrass);
            gripIs(errors, grip, Material.SNOW, config.gripGrass);
            gripIs(errors, grip, Material.SNOW_BLOCK, config.gripGrass);
            gripIs(errors, grip, Material.ICE, config.gripIce);
            gripIs(errors, grip, Material.PACKED_ICE, config.gripIce);
            gripIs(errors, grip, Material.BLUE_ICE, config.gripIce);
            gripIs(errors, grip, Material.FROSTED_ICE, config.gripIce);
            gripIs(errors, grip, Material.STONE, config.gripDefault);
            gripIs(errors, grip, Material.OAK_PLANKS, config.gripDefault);
            gripIs(errors, grip, Material.IRON_BLOCK, config.gripDefault);
            gripIs(errors, grip, Material.SOUL_SAND, config.gripDefault);
            if (!errors.isEmpty()) {
                return Result.fail(String.join(" | ", errors));
            }
            return Result.pass("22 Materialien korrekt eingestuft (inkl. Betonpulver != Beton)");
        });

        // Haengt die halbe Karosse ueber der Kante, tragen nur zwei Raeder — der wirksame
        // Grip halbiert sich. Das ist der eigentliche Zweck der vier Rad-Samples.
        sweep.run("halber-grip-kante", false, 40, 0.8, null, 0f, GROUND_Y - 3.0, lane -> {
            for (int z = -4; z <= 30; z++) {
                for (int x = lane.baseX() - 2; x <= lane.baseX(); x++) {
                    lane.world().getBlockAt(x, GROUND_Y - 1, lane.baseZ() + z)
                            .setType(Material.STONE, false);
                }
                // Rechts liegt der Boden so tief, dass die Federungstoleranz (1,05) reisst
                for (int x = lane.baseX() + 1; x <= lane.baseX() + 2; x++) {
                    lane.world().getBlockAt(x, GROUND_Y - 4, lane.baseZ() + z)
                            .setType(Material.STONE, false);
                }
            }
            wall(lane, 30, GROUND_Y);
        }, run -> {
            double full = config.gripDefault;
            double half = full / 2.0;
            List<SimSample> grounded = run.samples().stream().filter(SimSample::grounded).toList();
            if (grounded.size() < 10) {
                return Result.fail("verliert den Boden ganz — die Bahn ist falsch gebaut");
            }
            double worst = grounded.stream().mapToDouble(s -> Math.abs(s.grip() - half)).max().orElse(9);
            if (worst > 0.02) {
                return Result.fail(fmt("Grip an der Kante %.3f statt %.3f (halber Wert von %.3f)",
                        grounded.get(grounded.size() - 1).grip(), half, full));
            }
            return Result.pass(fmt("zwei von vier Raedern tragen: Grip %.3f statt %.3f", half, full));
        });

        // Schlupf: bei erzwungener Drehung (simDrift, 6 Grad je Tick) folgt der Vektor auf
        // Stein spuerbar mit, auf Eis kaum. Verglichen wird der Aufbau in den ersten Ticks —
        // laesst man es laufen, saettigt der Winkel auf beiden Belaegen bei ueber 90 Grad
        // und der Unterschied verschwindet.
        sweep.run("schlupf-stein", false, 60, 1.0, null, 0f, GROUND_Y - 3.0, PLATE20_CLEAR,
                car -> car.setSimDrift(true),
                lane -> plate(lane, 20, -20, 20, 9999, Material.STONE, Material.STONE), run -> {
            double early = slipAt(run, 10);
            measurements.put("schlupf-stein", early);
            return Result.pass(fmt("Schlupf auf Stein nach 10 Ticks %.1f Grad (Maximum %.1f)",
                    early, maxSlip(run)));
        });

        sweep.run("schlupf-eis", false, 60, 1.0, null, 0f, GROUND_Y - 3.0, PLATE20_CLEAR,
                car -> car.setSimDrift(true),
                lane -> plate(lane, 20, -20, 20, 9999, Material.PACKED_ICE, Material.PACKED_ICE),
                run -> {
            double early = slipAt(run, 10);
            Double stone = measurements.get("schlupf-stein");
            if (stone != null && early < stone * 1.15) {
                return Result.fail(fmt("auf Eis nur %.1f Grad Schlupf nach 10 Ticks gegen %.1f "
                        + "auf Stein — der Grip macht keinen Unterschied", early, stone));
            }
            return Result.pass(fmt("Schlupf nach 10 Ticks: Eis %.1f Grad, Stein %.1f Grad", early,
                    stone == null ? -1 : stone));
        });

        // Standfest: unter der Kriechgrenze rastet das Auto hart ein — aber nur, wenn die
        // Reifen tragen. Auf Eis (Grip unter 0,4) rollt der Restschwung aus.
        sweep.run("standfest-stein", false, 30, 0.02, null, 0f, GROUND_Y - 3.0, lane -> {
            flat(lane, -4, 44);
        }, run -> {
            double last = lastSample(run).speed();
            if (last != 0.0) {
                return Result.fail(fmt("rollt weiter statt hart zu stehen: v=%.6f", last));
            }
            return Result.pass("steht exakt still");
        });

        sweep.run("standfest-eis", false, 30, 0.02, null, 0f, GROUND_Y - 3.0, lane -> {
            track(lane, -4, 44, GROUND_Y - 1, Material.PACKED_ICE);
            wall(lane, 44, GROUND_Y);
        }, run -> {
            double last = lastSample(run).speed();
            if (last <= 0.0) {
                return Result.fail("rastet auf Eis hart ein — dort soll der Schwung auslaufen");
            }
            return Result.pass(fmt("rollt auf Eis weiter aus (v=%.5f)", last));
        });

        // Aussermittiger Wandtreffer dreht die Karosse ueber den Aufprall-Hebel.
        sweep.run("wand-spin", false, 60, 1.2, null, 0f, GROUND_Y - 3.0, lane -> {
            track(lane, -4, 20, GROUND_Y - 1, Material.STONE);
            for (int x = lane.baseX() + 1; x <= lane.baseX() + 3; x++) {
                for (int y = GROUND_Y; y <= GROUND_Y + 3; y++) {
                    lane.world().getBlockAt(x, y, lane.baseZ() + 8).setType(Material.STONE, false);
                }
            }
            wall(lane, 20, GROUND_Y);
        }, run -> {
            float start = run.samples().get(0).yaw();
            double turned = run.samples().stream()
                    .mapToDouble(s -> Math.abs(wrapDeg(s.yaw() - start))).max().orElse(0);
            if (turned < 3.0) {
                return Result.fail(fmt("aussermittiger Treffer dreht die Karosse nicht: %.2f Grad",
                        turned));
            }
            return Result.pass(fmt("Aufprall-Hebel dreht um %.1f Grad", turned));
        });

        // In der Geometrie steckend (Fremdeingriff, Alt-Faelle) muss man herausfahren koennen:
        // die Kollision setzt dann bewusst aus.
        sweep.run("eingebettet-ausweg", false, 60, 0.0, SimInput.GAS, 0f, GROUND_Y - 3.0, lane -> {
            track(lane, -4, 30, GROUND_Y - 1, Material.STONE);
            track(lane, -2, 0, GROUND_Y, Material.STONE);
            wall(lane, 30, GROUND_Y);
        }, run -> {
            double reached = maxZ(run);
            if (reached < 5.0) {
                return Result.fail(fmt("kommt aus dem Block nicht heraus: z=%.3f (v=%.3f)",
                        reached, lastSample(run).speed()));
            }
            return Result.pass(fmt("faehrt aus der Geometrie heraus, z=%.3f", reached));
        });

        // Hoechstgeschwindigkeit gegen die Wand: die Substep-Abtastung (0,4 Bloecke) muss
        // auch bei 2,25 Bloecken pro Tick greifen.
        sweep.run("tunneling-vollgas", false, 60, config.maxSpeed, SimInput.GAS, 0f,
                GROUND_Y - 3.0, INPUT_CLEAR, lane -> {
            track(lane, -4, 30, GROUND_Y - 1, Material.STONE);
            wall(lane, 30, GROUND_Y);
        }, run -> {
            double reached = maxZ(run);
            double limit = 30 - LONG_HALF + 0.45;
            if (reached > limit) {
                return Result.fail(fmt("Tunneling bei %.1f km/h: z=%.3f, die Nase steht damit "
                        + "%.3f Bloecke in der Wand", config.maxSpeed * 72, reached,
                        reached + LONG_HALF - 30));
            }
            if (reached < 20) {
                return Result.fail(fmt("erreicht die Wand gar nicht: z=%.3f", reached));
            }
            return Result.pass(fmt("Kontakt bei z=%.3f mit %.1f km/h, kein Tunneling",
                    reached, config.maxSpeed * 72));
        });

        sweep.done();
    }

    /** Halbe Fahrzeuglaenge — die Nase steht so weit vor der Fahrzeugmitte. */
    private static final double LONG_HALF = 1.25;

    private void gripIs(List<String> errors, GripCalculator calculator, Material material,
                        double expected) {
        double actual = calculator.gripFor(material);
        if (Math.abs(actual - expected) > 1.0e-9) {
            errors.add(fmt("%s: %.2f statt %.2f", material.name(), actual, expected));
        }
    }

    // ────────────────────────────── Sweeps ──────────────────────────────
    //
    // Systematisch statt stichprobenartig: jede erreichbare Stufenhoehe, jeder Belagswechsel,
    // jede Rampenneigung und ein breiter Querschnitt echter Bloecke. Die Erwartung kommt
    // ueberall aus der ECHTEN Kollisionsform des gebauten Blocks (supportTop) und den
    // Physik-Konstanten (DriveTask.MAX_STEP / MAX_STEP_DOWN) — eine handgepflegte
    // Hoehentabelle waere mit der naechsten Minecraft-Version falsch, ohne dass es auffaellt.

    /** Anlauf bis SEAM, ab dort das Testobjekt, Wand am Ende der kurzen Sweep-Strecke. */
    private static final int SEAM = 6;
    private static final int SWEEP_END = 18;
    private static final double SWEEP_MIN_TRAVEL = 10.0;
    private static final Area DEEP_CLEAR = new Area(3, -5, 22, -14, 6);

    /** Ein Belag mit definierter Oberkante; snowLayers > 0 setzt zusaetzlich die Schneehoehe. */
    private record Surface(String label, Material material, int snowLayers) {
    }

    private static final List<Surface> RISERS = List.of(
            new Surface("teppich", Material.WHITE_CARPET, 0),
            new Surface("schnee2", Material.SNOW, 2),
            new Surface("falltuer", Material.OAK_TRAPDOOR, 0),
            new Surface("schnee3", Material.SNOW, 3),
            new Surface("schnee4", Material.SNOW, 4),
            new Surface("tageslichtsensor", Material.DAYLIGHT_DETECTOR, 0),
            new Surface("steinstufe", Material.STONE_SLAB, 0),
            new Surface("bett", Material.WHITE_BED, 0),
            new Surface("steinsaege", Material.STONECUTTER, 0),
            new Surface("schnee6", Material.SNOW, 6),
            new Surface("zaubertisch", Material.ENCHANTING_TABLE, 0),
            new Surface("schnee7", Material.SNOW, 7),
            new Surface("schlamm", Material.MUD, 0),
            new Surface("truhe", Material.CHEST, 0),
            new Surface("schnee8", Material.SNOW, 8),
            new Surface("ackerland", Material.FARMLAND, 0),
            new Surface("pfad", Material.DIRT_PATH, 0),
            new Surface("vollblock", Material.STONE, 0),
            new Surface("treppe", Material.STONE_BRICK_STAIRS, 0),
            new Surface("zaun", Material.OAK_FENCE, 0),
            new Surface("mauer", Material.COBBLESTONE_WALL, 0));

    /** Flache Belaege, die eine ganze Bahnzeile ausfuellen (fuer Stufen- und Wechsel-Matrix). */
    private static final List<Surface> FLAT_SURFACES = List.of(
            new Surface("stein", Material.STONE, 0),
            new Surface("gras", Material.GRASS_BLOCK, 0),
            new Surface("beton", Material.WHITE_CONCRETE, 0),
            new Surface("kies", Material.GRAVEL, 0),
            new Surface("packeis", Material.PACKED_ICE, 0),
            new Surface("pfad", Material.DIRT_PATH, 0),
            new Surface("ackerland", Material.FARMLAND, 0),
            new Surface("schlamm", Material.MUD, 0),
            new Surface("seelensand", Material.SOUL_SAND, 0),
            new Surface("steinstufe", Material.STONE_SLAB, 0));

    /** 1 — Aufstieg ueber jede erreichbare Stufenhoehe von 1/16 bis 1 1/2 Bloecken. */
    private void stepUpHeights() {
        Sweep sweep = sweep("step-up-heights");
        for (Surface riser : RISERS) {
            sweep.run(riser.label(), false, 1.0, true, lane -> {
                track(lane, -4, SWEEP_END, GROUND_Y - 1, Material.STONE);
                for (int z = SEAM; z <= SWEEP_END; z++) {
                    placeSurface(lane, z, GROUND_Y, riser);
                }
                wall(lane, SWEEP_END, GROUND_Y + 1);
            }, run -> {
                Lane l = run.lane();
                double step = supportTop(l.world(), l.baseX(), GROUND_Y, l.baseZ() + SEAM + 2);
                double reached = maxZ(run);
                SimSample last = lastSample(run);
                if (step <= DriveTask.MAX_STEP + 1.0e-9) {
                    if (reached < SWEEP_MIN_TRAVEL) {
                        return Result.fail(fmt("Stufe %.4f nicht genommen: z=%.3f (v=%.3f)",
                                step, reached, last.speed()));
                    }
                    if (last.y() < GROUND_Y + step - 0.05) {
                        return Result.fail(fmt("Stufe %.4f: steht bei y=%.3f statt %.3f",
                                step, last.y(), GROUND_Y + step));
                    }
                    return Result.pass(fmt("Stufe %.4f genommen (z=%.3f)", step, reached));
                }
                if (reached > SEAM - 0.5) {
                    return Result.fail(fmt("Stufe %.4f ueber MAX_STEP wurde ueberfahren: z=%.3f",
                            step, reached));
                }
                return Result.pass(fmt("Stufe %.4f blockiert wie erwartet (z=%.3f)", step, reached));
            });
        }
        sweep.done();
    }

    /** 2 — dieselbe GANZE Stufe von jedem Belag aus. Erwartet wird die Nutzer-Sicht:
     *  was wie ein Block aussieht, muss befahrbar sein. Belaege mit gekappter Oberkante
     *  reissen dabei MAX_STEP — das ist der offene Bug, deshalb knownFail. */
    private void stepUpFromSurfaces() {
        Sweep sweep = sweep("step-up-from");
        for (Surface start : FLAT_SURFACES) {
            if (start.material() == Material.PACKED_ICE) {
                continue; // Eis testet Grip, nicht Geometrie — der Anlauf waere zu schwach
            }
            boolean looksLikeFullBlock = start.material() != Material.STONE_SLAB;
            sweep.run(start.label(), looksLikeFullBlock && !isFullHeight(start), 1.0, true, lane -> {
                track(lane, -4, SWEEP_END, GROUND_Y - 2, Material.STONE);
                for (int z = -4; z < SEAM; z++) {
                    placeSurface(lane, z, GROUND_Y - 1, start);
                }
                track(lane, SEAM, SWEEP_END, GROUND_Y - 1, Material.STONE);
                track(lane, SEAM, SWEEP_END, GROUND_Y, Material.STONE);
                wall(lane, SWEEP_END, GROUND_Y + 1);
            }, run -> {
                Lane l = run.lane();
                double top = supportTop(l.world(), l.baseX(), GROUND_Y - 1, l.baseZ());
                double step = 2.0 - top;
                double reached = maxZ(run);
                boolean shouldClimb = step <= DriveTask.MAX_STEP + 1.0e-9 || top >= 0.75;
                if (shouldClimb) {
                    if (reached < SWEEP_MIN_TRAVEL) {
                        return Result.fail(fmt("von %s (Oberkante %.4f) aus bleibt die 1-Block-Stufe "
                                + "bei z=%.3f stehen — sie misst %.4f > MAX_STEP %.1f",
                                start.label(), top, reached, step, DriveTask.MAX_STEP));
                    }
                    return Result.pass(fmt("1-Block-Stufe von %s (Oberkante %.4f, Stufe %.4f) genommen",
                            start.label(), top, step));
                }
                if (reached > SEAM - 0.5) {
                    return Result.fail(fmt("Stufe %.4f von %s aus wurde ueberfahren: z=%.3f",
                            step, start.label(), reached));
                }
                return Result.pass(fmt("Stufe %.4f von %s aus blockiert wie erwartet",
                        step, start.label()));
            });
        }
        sweep.done();
    }

    /** 3 — Abstieg ueber jede Hoehe: bis MAX_STEP_DOWN muss das Auto dem Boden folgen,
     *  darueber darf es fallen — landen und weiterfahren muss es immer. */
    private void stepDownHeights() {
        Sweep sweep = sweep("step-down-heights", DEEP_CLEAR);
        double[] drops = {0.0625, 0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875, 0.9375,
                1.0, 1.0625, 1.125, 1.1875, 1.2, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 5.0};
        for (double drop : drops) {
            double target = GROUND_Y - drop;
            // Offener Bug: ein Abstieg zwischen MAX_STEP und MAX_STEP_DOWN ist eine Falle.
            // Das Auto folgt dem Boden hinunter (erlaubt bis MAX_STEP_DOWN), steht dann aber
            // mit dem Heck-Sample vor einer Kante, die hoeher als MAX_STEP ist — es kann die
            // Stufe, die es gerade heruntergefahren ist, nicht mehr verlassen und steht fuer
            // immer. Tiefere Abstiege sind harmlos, weil sie als Fall behandelt werden.
            boolean trap = drop > DriveTask.MAX_STEP + 1.0e-9
                    && drop <= DriveTask.MAX_STEP_DOWN + 1.0e-9;
            sweep.run(fmt("abstieg-%.4f", drop), trap, 1.0, true, target - 3.0, lane -> {
                track(lane, -4, SEAM - 1, GROUND_Y - 1, Material.STONE);
                for (int z = SEAM; z <= SWEEP_END; z++) {
                    surfaceAtTop(lane, z, target);
                }
                wall(lane, SWEEP_END, (int) Math.floor(target));
            }, run -> {
                double reached = maxZ(run);
                SimSample last = lastSample(run);
                long air = run.samples().stream().filter(s -> !s.grounded()).count();
                double lowest = run.samples().stream().mapToDouble(SimSample::y).min().orElse(0);
                if (reached < SWEEP_MIN_TRAVEL) {
                    return Result.fail(fmt("Abstieg %.4f: bleibt bei z=%.3f haengen (v=%.3f)",
                            drop, reached, last.speed()));
                }
                if (!last.grounded() || Math.abs(last.y() - target) > 0.05) {
                    return Result.fail(fmt("Abstieg %.4f: endet bei y=%.3f (erwartet %.3f), grounded=%s",
                            drop, last.y(), target, last.grounded()));
                }
                if (lowest < target - 0.05) {
                    return Result.fail(fmt("Abstieg %.4f: faellt unter die Strecke (y=%.3f)", drop, lowest));
                }
                if (drop <= DriveTask.MAX_STEP_DOWN && air > 0) {
                    return Result.fail(fmt("Abstieg %.4f: %d Ticks ohne Bodenkontakt, "
                            + "bis MAX_STEP_DOWN %.1f muss das Auto dem Boden folgen",
                            drop, air, DriveTask.MAX_STEP_DOWN));
                }
                return Result.pass(fmt("Abstieg %.4f sauber (%d Ticks Flug, z=%.3f)", drop, air, reached));
            });
        }
        sweep.done();
    }

    /** 4 — Steigungen und Gefaelle. Zwei Familien, weil sie sich grundlegend unterscheiden:
     *  ganze Blockstufen (echtes Minecraft-Gelaende, muss fahrbar sein) und Teilblock-Rampen
     *  aus Schnee/Stufen (dort stoesst das zellenweise Kollisionsmodell an seine Grenze). */
    private void slopes() {
        int[] runs = {1, 2, 3, 4, 6, 8};
        Sweep up = sweep("slope-up", SLOPE_CLEAR);
        for (int run : runs) {
            addBlockSlope(up, "hoch-1-auf-" + run, run, 1);
        }
        up.done();

        Sweep down = sweep("slope-down", SLOPE_CLEAR);
        for (int run : runs) {
            addBlockSlope(down, "runter-1-auf-" + run, run, -1);
        }
        down.done();

        // Teilblock-Rampen: die Oberkanten liegen zwischen den Zellgrenzen. Sobald das Auto
        // auf einer solchen Hoehe steht, ragt der Nachbarbelag in seine KOPF-Zelle — und die
        // gilt pauschal als unueberwindbar, egal wie flach die Stufe wirklich ist. Genau
        // dieselbe Wurzel wie die 1-Block-Stufe von Ackerland aus.
        Sweep sub = sweep("slope-subblock", SLOPE_CLEAR);
        for (double rise : new double[]{0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875}) {
            // Bergauf blockiert JEDE Teilblock-Rampe. Bergab haengt es davon ab, wie die
            // Oberkanten der Nachbarzeilen zufaellig zu den Zellgrenzen liegen — 3/8, 5/8 und
            // 7/8 je Block bleiben stecken, 1/8, 1/4, 1/2 und 3/4 kommen durch. Diese Liste
            // ist bewusst der IST-Stand: aendert sich das Kollisionsmodell, meldet der Lauf
            // UNEXPECTED-PASS bzw. FAIL und die Werte gehoeren neu vermessen.
            boolean stuckDownhill = rise == 0.375 || rise == 0.625 || rise == 0.875;
            addSubBlockSlope(sub, fmt("hoch-%.3f-je-block", rise), rise, 1, true);
            addSubBlockSlope(sub, fmt("runter-%.3f-je-block", rise), rise, -1, stuckDownhill);
        }
        sub.done();
    }

    /** Rampe aus ganzen Bloecken: eine Stufe je {@code run} Bloecke, insgesamt drei Bloecke. */
    private void addBlockSlope(Sweep sweep, String label, int run, int dir) {
        // Bergauf braucht eine Stufe Vorlauf: auf Stufe N steht die 1,25 Bloecke lange Nase
        // schon ueber Stufe N+1. Bei 45 Grad (1 Block je Block) ist das eine ganze Stufe
        // hoeher — canStandAt lehnt ab, das Auto kommt die Treppe nicht hinauf.
        boolean expectBlocked = dir > 0 && run == 1;
        int steps = 3;
        int length = steps * run;
        int end = GROUND_Y + dir * steps;
        double lowest = Math.min(GROUND_Y, end);
        sweep.run(label, false, dir > 0 ? 1.0 : 0.6, dir > 0, lowest - 3.0, lane -> {
            for (int z = -4; z < SEAM; z++) {
                floorAt(lane, z, GROUND_Y - 1, Material.STONE);
            }
            for (int z = SEAM; z <= SEAM + length; z++) {
                int level = GROUND_Y + dir * Math.min(steps, (z - SEAM + run) / run);
                fillTo(lane, z, level);
            }
            for (int z = SEAM + length + 1; z <= SEAM + length + 6; z++) {
                fillTo(lane, z, end);
            }
            wall(lane, SEAM + length + 6, end);
        }, run2 -> {
            SimSample last = lastSample(run2);
            double reached = maxZ(run2);
            long air = run2.samples().stream().filter(s -> !s.grounded()).count();
            double lowestY = run2.samples().stream().mapToDouble(SimSample::y).min().orElse(0);
            double topSpeed = run2.samples().stream().mapToDouble(SimSample::speed).max().orElse(0);
            if (expectBlocked) {
                if (reached > SEAM) {
                    return Result.fail(fmt("45-Grad-Treppe wurde erklommen: z=%.3f y=%.3f",
                            reached, last.y()));
                }
                return Result.pass(fmt("45-Grad-Treppe blockiert wie erwartet (z=%.3f) — die Nase "
                        + "steht auf der naechsten Stufe auf", reached));
            }
            if (lowestY < lowest - 0.05) {
                return Result.fail(fmt("faellt unter die Rampe: y=%.3f statt mindestens %.3f",
                        lowestY, lowest));
            }
            if (reached < SEAM + length) {
                return Result.fail(fmt("erreicht das Rampenende nicht: z=%.3f von %d (v=%.3f, y=%.3f)",
                        reached, SEAM + length, last.speed(), last.y()));
            }
            if (!last.grounded() || Math.abs(last.y() - end) > 0.05) {
                return Result.fail(fmt("endet bei y=%.3f (erwartet %d), grounded=%s",
                        last.y(), end, last.grounded()));
            }
            if (dir < 0 && run >= 2 && air > 0) {
                return Result.fail(fmt("%d Ticks Flug am sanften Gefaelle (1 Block auf %d)", air, run));
            }
            return Result.pass(fmt("z=%.3f y=%.3f vmax=%.3f, %d Ticks Flug", reached, last.y(),
                    topSpeed, air));
        });
    }

    /** Rampe mit Teilblock-Neigung: Oberkante steigt/faellt um {@code rise} je Block. */
    private void addSubBlockSlope(Sweep sweep, String label, double rise, int dir, boolean knownFail) {
        int length = 12;
        double end = GROUND_Y + dir * rise * length;
        double lowest = Math.min(GROUND_Y, end);
        sweep.run(label, knownFail, dir > 0 ? 1.0 : 0.6, dir > 0, lowest - 3.0, lane -> {
            for (int z = -4; z < SEAM; z++) {
                surfaceAtTop(lane, z, GROUND_Y);
            }
            for (int z = SEAM; z <= SEAM + length; z++) {
                surfaceAtTop(lane, z, GROUND_Y + dir * rise * (z - SEAM));
            }
            for (int z = SEAM + length + 1; z <= SEAM + length + 6; z++) {
                surfaceAtTop(lane, z, end);
            }
            wall(lane, SEAM + length + 6, (int) Math.floor(end));
        }, run -> {
            SimSample last = lastSample(run);
            double reached = maxZ(run);
            double lowestY = run.samples().stream().mapToDouble(SimSample::y).min().orElse(0);
            if (lowestY < lowest - 0.05) {
                return Result.fail(fmt("faellt unter die Rampe: y=%.3f statt mindestens %.3f",
                        lowestY, lowest));
            }
            if (reached < SEAM + length) {
                return Result.fail(fmt("bleibt bei z=%.3f von %d stehen (y=%.3f, v=%.3f) — die "
                        + "Nachbarzeile ragt in die Kopf-Zelle floor(y)+1 und gilt als unueberwindbar",
                        reached, SEAM + length, last.y(), last.speed()));
            }
            if (!last.grounded() || Math.abs(last.y() - end) > 0.05) {
                return Result.fail(fmt("endet bei y=%.3f (erwartet %.3f), grounded=%s",
                        last.y(), end, last.grounded()));
            }
            return Result.pass(fmt("Teilblock-Rampe %.3f je Block durchfahren, z=%.3f y=%.3f",
                    rise, reached, last.y()));
        });
    }

    /** 5 — Belagswechsel: jede geordnete Kombination aus der flachen Palette.
     *  Deckt "von Pfad auf normalen Block" in beide Richtungen vollstaendig ab. */
    private void surfaceTransitions() {
        Sweep sweep = sweep("surface-transition");
        for (Surface from : FLAT_SURFACES) {
            for (Surface to : FLAT_SURFACES) {
                if (from == to) {
                    continue;
                }
                sweep.run(from.label() + "->" + to.label(), false, 1.0, true, lane -> {
                    track(lane, -4, SWEEP_END, GROUND_Y - 2, Material.STONE);
                    for (int z = -4; z < SEAM; z++) {
                        placeSurface(lane, z, GROUND_Y - 1, from);
                    }
                    for (int z = SEAM; z <= SWEEP_END; z++) {
                        placeSurface(lane, z, GROUND_Y - 1, to);
                    }
                    wall(lane, SWEEP_END, GROUND_Y);
                }, run -> {
                    Lane l = run.lane();
                    double a = supportTop(l.world(), l.baseX(), GROUND_Y - 1, l.baseZ());
                    double b = supportTop(l.world(), l.baseX(), GROUND_Y - 1, l.baseZ() + SEAM + 2);
                    double reached = maxZ(run);
                    long air = run.samples().stream().filter(s -> !s.grounded()).count();
                    SimSample last = lastSample(run);
                    if (reached < SWEEP_MIN_TRAVEL) {
                        return Result.fail(fmt("bleibt an der Kante %.4f->%.4f bei z=%.3f haengen (v=%.3f)",
                                a, b, reached, last.speed()));
                    }
                    if (Math.abs(b - a) <= 0.5 && air > 0) {
                        return Result.fail(fmt("%d Ticks ohne Bodenkontakt an der flachen Kante %.4f->%.4f",
                                air, a, b));
                    }
                    return Result.pass(fmt("%.4f->%.4f, z=%.3f", a, b, reached));
                });
            }
        }
        sweep.done();
    }

    /** Ein einzelnes Hindernis auf der Strecke; base ersetzt bei Bedarf den Boden darunter
     *  (Weizen braucht Ackerland, Seerose braucht Wasser). */
    private record Obstacle(String label, Material material, Material base) {
        Obstacle(String label, Material material) {
            this(label, material, null);
        }
    }

    private static final List<Obstacle> OBSTACLES = List.of(
            new Obstacle("kuchen", Material.CAKE),
            new Obstacle("bett", Material.WHITE_BED),
            new Obstacle("teppich", Material.WHITE_CARPET),
            new Obstacle("steinstufe", Material.STONE_SLAB),
            new Obstacle("treppe", Material.STONE_BRICK_STAIRS),
            new Obstacle("falltuer", Material.OAK_TRAPDOOR),
            new Obstacle("zaubertisch", Material.ENCHANTING_TABLE),
            new Obstacle("tageslichtsensor", Material.DAYLIGHT_DETECTOR),
            new Obstacle("truhe", Material.CHEST),
            new Obstacle("trichter", Material.HOPPER),
            new Obstacle("kessel", Material.CAULDRON),
            new Obstacle("komposter", Material.COMPOSTER),
            new Obstacle("schleifstein", Material.GRINDSTONE),
            new Obstacle("steinsaege", Material.STONECUTTER),
            new Obstacle("lesepult", Material.LECTERN),
            new Obstacle("fass", Material.BARREL),
            new Obstacle("amboss", Material.ANVIL),
            new Obstacle("glocke", Material.BELL),
            new Obstacle("blumentopf", Material.FLOWER_POT),
            new Obstacle("laterne", Material.LANTERN),
            new Obstacle("kerze", Material.CANDLE),
            new Obstacle("schildkroetenei", Material.TURTLE_EGG),
            new Obstacle("meergurke", Material.SEA_PICKLE),
            new Obstacle("braustand", Material.BREWING_STAND),
            new Obstacle("endstab", Material.END_ROD),
            new Obstacle("kette", Material.IRON_CHAIN),
            new Obstacle("eisengitter", Material.IRON_BARS),
            new Obstacle("glasscheibe", Material.GLASS_PANE),
            new Obstacle("zaun", Material.OAK_FENCE),
            new Obstacle("zauntor", Material.OAK_FENCE_GATE),
            new Obstacle("mauer", Material.COBBLESTONE_WALL),
            new Obstacle("geruest", Material.SCAFFOLDING),
            new Obstacle("spinnennetz", Material.COBWEB),
            new Obstacle("pulverschnee", Material.POWDER_SNOW),
            new Obstacle("gras", Material.SHORT_GRASS),
            new Obstacle("fackel", Material.TORCH),
            new Obstacle("schiene", Material.RAIL),
            new Obstacle("druckplatte", Material.STONE_PRESSURE_PLATE),
            new Obstacle("knopf", Material.STONE_BUTTON),
            new Obstacle("hebel", Material.LEVER),
            new Obstacle("redstone", Material.REDSTONE_WIRE),
            new Obstacle("schild", Material.OAK_SIGN),
            new Obstacle("leiter", Material.LADDER),
            new Obstacle("bambus", Material.BAMBOO),
            new Obstacle("tropfstein", Material.POINTED_DRIPSTONE),
            new Obstacle("amethyst", Material.AMETHYST_CLUSTER),
            new Obstacle("lagerfeuer", Material.CAMPFIRE),
            new Obstacle("leuchtfeuer", Material.BEACON),
            new Obstacle("magmablock", Material.MAGMA_BLOCK),
            new Obstacle("schleimblock", Material.SLIME_BLOCK),
            new Obstacle("honigblock", Material.HONEY_BLOCK),
            new Obstacle("kaktus", Material.CACTUS),
            new Obstacle("weizen", Material.WHEAT, Material.FARMLAND),
            new Obstacle("seerose", Material.LILY_PAD, Material.WATER));

    /** 6 — ueber einen einzelnen Block fahren: Kuchen, Truhe, Zaun, Schiene, Weizen …
     *  Was passierbar ist, darf gar nicht bremsen; was bis MAX_STEP hoch ist, wird ueberfahren;
     *  was hoeher ist, blockiert. Die Entscheidung faellt aus der echten Kollisionsform. */
    private void driveOverObstacles() {
        Sweep sweep = sweep("drive-over");
        for (Obstacle obstacle : OBSTACLES) {
            sweep.run(obstacle.label(), false, 1.0, true, lane -> {
                track(lane, -4, SWEEP_END, GROUND_Y - 1, Material.STONE);
                if (obstacle.base() != null) {
                    floorAt(lane, SEAM, GROUND_Y - 1, obstacle.base());
                }
                floorAt(lane, SEAM, GROUND_Y, obstacle.material());
                wall(lane, SWEEP_END, GROUND_Y);
            }, run -> {
                Lane l = run.lane();
                var block = l.world().getBlockAt(l.baseX(), GROUND_Y, l.baseZ() + SEAM);
                double top = supportTop(l.world(), l.baseX(), GROUND_Y, l.baseZ() + SEAM);
                boolean passable = block.getType().isAir() || block.isPassable();
                double reached = maxZ(run);
                SimSample last = lastSample(run);
                String what = fmt("%s (Oberkante %.4f%s)", block.getType().name(), top,
                        passable ? ", passierbar" : "");
                if (passable || top <= DriveTask.MAX_STEP + 1.0e-9) {
                    if (reached < SWEEP_MIN_TRAVEL) {
                        return Result.fail(fmt("%s haelt das Auto bei z=%.3f auf (v=%.3f)",
                                what, reached, last.speed()));
                    }
                    if (Math.abs(last.y() - GROUND_Y) > 0.05) {
                        return Result.fail(fmt("%s: das Auto kommt nicht wieder herunter (y=%.3f)",
                                what, last.y()));
                    }
                    return Result.pass(fmt("%s ueberfahren (z=%.3f)", what, reached));
                }
                if (reached > SEAM - 0.5) {
                    return Result.fail(fmt("%s ist hoeher als MAX_STEP und wurde ueberfahren: z=%.3f",
                            what, reached));
                }
                return Result.pass(fmt("%s blockiert wie erwartet (z=%.3f)", what, reached));
            });
        }
        sweep.done();
    }

    // ── Bau-Helfer der Sweeps ──

    /** Ganze Bloecke, deren Oberkante bei {@code level} liegt (drei Bloecke Unterbau). */
    private void fillTo(Lane lane, int zRel, int level) {
        for (int y = level - 3; y < level; y++) {
            floorAt(lane, zRel, y, Material.STONE);
        }
    }

    private void placeSurface(Lane lane, int zRel, int y, Surface surface) {
        for (int x = lane.baseX() - 2; x <= lane.baseX() + 2; x++) {
            var block = lane.world().getBlockAt(x, y, lane.baseZ() + zRel);
            block.setType(surface.material(), false);
            if (surface.snowLayers() > 0) {
                Snow snow = (Snow) block.getBlockData();
                snow.setLayers(surface.snowLayers());
                block.setBlockData(snow, false);
            }
        }
    }

    private boolean isFullHeight(Surface surface) {
        return switch (surface.material()) {
            case STONE, GRASS_BLOCK, WHITE_CONCRETE, GRAVEL, SNOW_BLOCK, PACKED_ICE -> true;
            default -> false;
        };
    }

    /**
     * Baut die Bahnzeile so auf, dass ihre Kollisions-Oberkante GENAU topAbs ist:
     * volle Bloecke bis darunter, den Rest ueber Schneelagen (SNOW[L] endet bei (L−1)/8),
     * ein Sechzehntel ueber Teppich und fuenfzehn Sechzehntel ueber Ackerland.
     * Damit sind Rampen mit beliebiger Achtel-Neigung baubar.
     */
    private void surfaceAtTop(Lane lane, int zRel, double topAbs) {
        int base = (int) Math.floor(topAbs + 1.0e-9);
        double frac = topAbs - base;
        // Drei Bloecke Unterbau genuegen und halten den Streckenbau billig; ein fester
        // Boden bei GROUND_Y-12 riss bei tiefen Rampen unten ab und das Auto fiel durch.
        for (int y = base - 3; y < base; y++) {
            floorAt(lane, zRel, y, Material.STONE);
        }
        if (frac < 1.0e-6) {
            return;
        }
        if (Math.abs(frac - 0.0625) < 1.0e-6) {
            floorAt(lane, zRel, base, Material.WHITE_CARPET);
            return;
        }
        if (Math.abs(frac - 0.9375) < 1.0e-6) {
            floorAt(lane, zRel, base, Material.FARMLAND);
            return;
        }
        int layers = (int) Math.round(frac * 8.0) + 1;
        placeSurface(lane, zRel, base, new Surface("", Material.SNOW, layers));
    }
}
