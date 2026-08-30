package de.thiomains.auto;

import org.bukkit.Bukkit;
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
    private final List<SimSample> samples = new ArrayList<>();
    private final List<Car> extraCars = new ArrayList<>();
    private final Map<String, Double> measurements = new LinkedHashMap<>();

    private int index = -1;
    private Scenario current;
    private Lane lane;
    private Car car;
    private int waited;
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

    /** ticks = 0 bedeutet: kein Fahrszenario, die Prüfung läuft sofort. */
    private record Scenario(String name, boolean knownFail, int ticks, double startSpeed, boolean drive,
                            double minY, Consumer<Lane> build, Function<Run, Result> check) {
    }

    private void add(String name, boolean knownFail, int ticks, double speed, boolean drive,
                     Consumer<Lane> build, Function<Run, Result> check) {
        add(name, knownFail, ticks, speed, drive, GROUND_Y - 3.0, build, check);
    }

    /** minY ist die Absturz-Sicherung: faellt das Auto tiefer, hat es die Bahn verlassen und
     *  jede weitere Auswertung waere Unsinn (die Flachwelt liegt 120 Bloecke tiefer). */
    private void add(String name, boolean knownFail, int ticks, double speed, boolean drive,
                     double minY, Consumer<Lane> build, Function<Run, Result> check) {
        if (filter == null || name.contains(filter)) {
            scenarios.add(new Scenario(name, knownFail, ticks, speed, drive, minY, build, check));
        }
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
        boolean done = car == null || car.getSimTicks() <= 0 || carManager.getCarByBase(car.getBase().getUniqueId()) == null;
        if (!done && waited < current.ticks() + TIMEOUT_MARGIN) {
            return;
        }
        Run run = new Run(lane, List.copyOf(samples));
        double lowest = samples.stream().mapToDouble(SimSample::y).min().orElse(current.minY());
        Result result;
        if (!done) {
            result = Result.fail("Timeout nach " + waited + " Ticks");
        } else if (lowest < current.minY()) {
            result = Result.fail(fmt("aus der Bahn gefallen (y=%.3f, Untergrenze %.3f)", lowest, current.minY()));
        } else {
            result = current.check().apply(run);
        }
        finishScenario(result);
    }

    private void startNext() {
        index++;
        if (index >= scenarios.size()) {
            summary();
            return;
        }
        current = scenarios.get(index);
        samples.clear();
        waited = 0;
        World world = Bukkit.getWorlds().get(0);
        lane = new Lane(world, 200 + index * LANE_SPACING, 200, GROUND_Y);
        clearLane(lane);
        current.build().accept(lane);
        if (current.ticks() == 0) {
            car = null;
            finishScenario(current.check().apply(new Run(lane, List.of())));
            return;
        }
        car = carManager.spawnCar(new Location(world, lane.baseX() + 0.5, lane.groundY(),
                lane.baseZ() + 0.5, 0f, 0f), 0f);
        car.setSpeed(current.startSpeed());
        car.setSimDrive(current.drive());
        car.setSimObserver(samples::add);
        car.setSimTicks(current.ticks());
    }

    private void finishScenario(Result result) {
        report(current, result);
        cleanup();
        current = null;
    }

    private void cleanup() {
        if (car != null && carManager.getCarByBase(car.getBase().getUniqueId()) != null) {
            carManager.removeCar(car, false);
        }
        for (Car extra : extraCars) {
            if (carManager.getCarByBase(extra.getBase().getUniqueId()) != null) {
                carManager.removeCar(extra, false);
            }
        }
        extraCars.clear();
        car = null;
    }

    private void report(Scenario scenario, Result result) {
        String name = pad(scenario.name());
        if (result.ok() && !scenario.knownFail()) {
            passed++;
            log("PASS " + name + " " + result.detail());
        } else if (result.ok()) {
            failed++;
            log("UNEXPECTED-PASS " + name + " " + result.detail()
                    + " — knownFail-Flag entfernen, der Bug ist gefixt");
        } else if (scenario.knownFail()) {
            knownFailed++;
            log("KNOWN-FAIL " + name + " " + result.detail());
        } else {
            failed++;
            log("FAIL " + name + " " + result.detail());
            for (SimSample sample : trace()) {
                log("  " + sample.describe());
            }
        }
        if (verbose && !samples.isEmpty()) {
            for (SimSample sample : samples) {
                log("  " + sample.describe());
            }
        }
    }

    /** Die aussagekraeftigen Ticks fuer eine Fehlermeldung: rund um den ersten Blockade-Tick und das Ende. */
    private List<SimSample> trace() {
        List<SimSample> out = new ArrayList<>();
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
        log(String.format(Locale.ROOT, "SUMMARY passed=%d failed=%d known-fail=%d dauer=%ds",
                passed, failed, knownFailed, seconds));
        running = false;
        cancel();
    }

    private void log(String line) {
        plugin.getLogger().info("[Selftest] " + line);
    }

    // ────────────────────────────── Streckenbau ──────────────────────────────

    /** Raeumt die Bahn frei — inklusive drei Blöcken HINTER dem Start (hintere Samples bei z−1,25). */
    private void clearLane(Lane lane) {
        for (int x = lane.baseX() - 3; x <= lane.baseX() + 3; x++) {
            for (int z = lane.baseZ() - 5; z <= lane.baseZ() + 50; z++) {
                for (int y = lane.groundY() - 26; y <= lane.groundY() + 8; y++) {
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
}
