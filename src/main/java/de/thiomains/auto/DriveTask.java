package de.thiomains.auto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Per-Tick-Fahrphysik aller registrierten Autos.
 * Zustand ist ein Geschwindigkeitsvektor (velX/velZ) plus Fahrwerk-Yaw mit Schwung (yawVel).
 * Grip wirkt nur bei Bodenkontakt: in der Luft keine Kraefte, der Vektor bleibt ballistisch.
 * Der Vektor folgt der Rollrichtung (travelYaw) begrenzt durchs laterale Grip-Budget;
 * zusaetzlich frisst direkte laterale Reibung die Querkomponente (Schlupfabbau). Die
 * Handbremse schwaecht nur die Folgefaehigkeit (Drift), nicht die Lenkrate. Kollision tastet
 * die Route achsenweise substep-weise mit einem yaw-ausgerichteten 3x3-Footprint ab
 * (Karosserie mit Nase/Heck statt Punkt); eine blockierte Achse wird gedeckelt reflektiert und
 * der Aufprall-Hebel versetzt zusaetzlich nur die Karosserie in Drehung (spinVel), sodass der
 * Vektor anschliessend grip-begrenzt hinterherzieht (emergentes Schleudern). Steigungen
 * tauschen kinetische Energie (2·g·dy), bergab gibt es zusaetzlich gefaelle-skalenten Schub.
 * Motorbremse wirkt nur ohne Fahrpedal.
 */
public final class DriveTask extends BukkitRunnable {

    private static final long UNDERSTEER_SOUND_COOLDOWN_TICKS = 18;
    static final double GRAVITY_ACCEL = 0.08;
    private static final double ALIGN_FRACTION = 0.65;
    private static final double FRICTION_FRACTION = 0.5;
    static final double SPEED_EPSILON = 0.05;
    private static final double SLIP_SOUND_MIN_DEG = 12.0;
    private static final double SAMPLE_STEP = 0.4;
    // Stufenhoehe, die die Raeder noch nehmen: ein GANZER Block, auch wenn das Auto auf einem
    // Belag mit gekappter Oberkante steht (Schlamm/Seelensand 0,875 -> Stufe misst 1,125).
    static final double MAX_STEP = 1.125;
    private static final double LONG_HALF = 1.25;
    private static final double LAT_HALF = 0.9;
    private static final double CAR_COLLISION_RADIUS = 1.4;
    private static final double STANDSTILL_SPEED = 0.007; // ~0,5 km/h
    private static final double STANDSTILL_MIN_GRIP = 0.4; // darunter (glatt) rollt das Auto aus statt zu rasten
    private static final double WATER_DRAG = 0.10;
    private static final double WATER_SINK_DAMPING = 0.85; // Rest des Abstands zu max-sink-speed je Substep
    private static final double LANDING_SOUND_MIN_FALL = 0.5; // ~36 km/h vertikal
    private static final double LANDING_SPEED_KEEP = 0.7;
    static final double MAX_STEP_DOWN = 1.2;
    static final double CRAWL_TURN_DEG = 2.0; // Rangier-Lenkrate bei Stillstand-Kontakt
    private static final double OVERSPEED_DOWNHILL_FACTOR = 1.5;
    // Strecke, ueber die eine Hoehendifferenz energetisch verrechnet wird (Fahrzeuglaenge).
    // Je Tick wird der Anteil der Schuld faellig, der auf die gefahrene Strecke entfaellt.
    private static final double SLOPE_SPREAD = 2.5;
    // Restschuld verfaellt, sobald es nicht mehr bergauf geht. Ohne das ueberlebt die Steigung
    // sich selbst: abgetragen wird nur ueber Fahrstrecke, und der Abzug ist tempo-unabhaengig
    // (g x slope-resistance). Wer oben langsam ankommt, haengt dann in einer Rueckkopplung fest
    // — kaum Beschleunigung, kaum Strecke, Schuld bleibt. Auf der Ebene 1 km/h, live gesehen.
    private static final double SLOPE_DEBT_FADE = 0.90;
    private static final double CRASH_MIN_SPEED = 0.07; // ~5 km/h: darunter ruhiger Rangier-Stopp statt Abpraller
    private static final double CRASH_REBOUND_MAX = 0.10; // ~7 km/h: gedeckelt, sonst rollt der Rueckprall ewig weiter
    private static final double SPIN_SCALE = 3.0; // deg/tick pro (Hebel-Blocks × Impact-Bl/tick)
    private static final double MAX_SPIN = 18.0; // deg/tick, gegen unansehnliche Vollrotation
    private static final double SPIN_DEADBAND = 0.05;
    private static final double PITCH_ACCEL_DEG = 150.0; // Grad pro Bl/tick² Pedal-Kraft (Squat/Dive), gedeckelt
    private static final double PITCH_ACCEL_MAX_DEG = 8.0;
    private static final double YAW_SMOOTH_IN = 0.30;
    private static final double YAW_SMOOTH_OUT = 0.40;
    private static final double YAW_DEADBAND = 0.03;
    private static final double MOUSE_DEADZONE_DEG = 4.0; // darunter bleibt das Lenkrad gerade
    private static final double MOUSE_FULL_LOCK_DEG = 90.0; // quer zur Karosse = voller Einschlag
    // Zwei Raster mit zwei Aufgaben. Karosserie (GRID_*, reale Masse 1,8 x 2,5): was das Auto
    // BLOCKIERT — die Stossstange darf nicht in eine Wand fahren. Aufstandsflaeche (SUPPORT_*,
    // Achsen plus Unterboden): was das Auto TRAEGT. Wer beides vermischt, hebt das Auto schon
    // an, wenn die Stossstange ueber einer Stufe haengt: auf einer Treppe greift die Ecke bei
    // Yaw bis 1,54 Bloecke voraus, das Auto springt zwei Stufen hoch und faellt eine zurueck.
    private static final double[] GRID_LONG = {-LONG_HALF, 0.0, LONG_HALF};
    private static final double[] GRID_LAT = {-LAT_HALF, 0.0, LAT_HALF};
    private static final double[] WHEEL_LONG = {-0.7, 0.7};
    private static final double[] WHEEL_LAT = {-0.7, 0.7};
    private static final double AXLE_SPAN = 1.4; // Abstand Vorder- zu Hinterachse (2 x 0,7)
    private static final double TRACK_WIDTH = 1.4; // Spurweite (2 x 0,7)
    // Federweg: so weit darf ein Rad unter seinem Gegenstueck DERSELBEN Achse haengen. Darunter
    // hebt es ab. Ohne diese Kopplung verwindet sich eine Achse um einen ganzen Block, sobald
    // ein Rad neben einem Bordstein faehrt — und traegt trotzdem.
    private static final double AXLE_TRAVEL = 0.5;
    private static final double MODEL_MAX_SINK = 1.5; // wie tief das Modell unter das Fahrniveau darf
    /** Was das Auto umfaehrt. Bewusst eine feste Liste statt "alles Ageable": darunter fielen
     *  auch Zuckerrohr und Kaktus, und Gras oder Blumen sollen stehen bleiben. */
    private static final Set<Material> CROPS = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP,
            Material.MELON_STEM, Material.PUMPKIN_STEM,
            Material.ATTACHED_MELON_STEM, Material.ATTACHED_PUMPKIN_STEM);

    private static final double[] SUPPORT_LONG = {-0.7, 0.0, 0.7};
    private static final double[] SUPPORT_LAT = {-0.7, 0.0, 0.7};

    private final CarManager carManager;
    private final CarConfig config;
    private final GripCalculator gripCalculator;
    private final PlayerPrefs prefs;
    private final java.util.logging.Logger logger;
    private long tickCount;

    public DriveTask(CarManager carManager, CarConfig config, GripCalculator gripCalculator,
                     PlayerPrefs prefs, java.util.logging.Logger logger) {
        this.carManager = carManager;
        this.config = config;
        this.gripCalculator = gripCalculator;
        this.prefs = prefs;
        this.logger = logger;
    }

    @Override
    public void run() {
        tickCount++;
        for (Car car : carManager.getCars()) {
            if (car.getBase().isDead()) {
                // Safety-Net: Basis weg ohne Event -> Rest aufräumen
                carManager.removeCar(car, true);
                continue;
            }
            // Kein Ticken in ungeladenen Chunks (sonst lädt die Physik den Chunk ständig neu)
            if (!car.getBase().getChunk().isLoaded()) {
                continue;
            }
            // Safety-Net: Display/Hitbox von Fremdeingriff entfernt -> Teile neu spawnen
            if (!car.getModel().isValid() || !car.getHitbox().isValid()) {
                carManager.ensureParts(car);
            }
            tick(car);
        }
    }

    private void tick(Car car) {
        ArmorStand base = car.getBase();
        Player driver = car.getDriver();
        Location loc = base.getLocation();
        World world = loc.getWorld();

        float yaw = car.getYaw();
        float oldYaw = yaw;
        double yawRad = Math.toRadians(yaw);
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double vx = car.getVelX();
        double vz = car.getVelZ();
        double startAbs = Math.hypot(vx, vz);
        double vf = vx * fx + vz * fz;
        // Zerlegung MUSS vor jeder Veraenderung von vf passieren: lx/lz sind der Seitenrest
        // des ALTEN Zustands; nach vf-Aenderung berechnet, wuerde sie die Kraft wieder aufheben.
        double lx = vx - vf * fx;
        double lz = vz - vf * fz;

        // Bodenkontakt bestimmt alles Weitere: nur am Boden gibt es Grip und Kraefte. Beides
        // kommt aus den vier Rad-Aufstandspunkten (siehe probeWheels, mit starrer Achse); nicht
        // tragende Raeder zaehlen mit 0 in die Division durch 4: haengt die halbe Karosse ueber
        // einer echten Kante, halbiert sich der wirksame Grip.
        Wheels wheels = probeWheels(world, loc.getX(), loc.getY(), loc.getZ(), yaw, false);
        Material groundType = world.getBlockAt(floor(loc.getX()), floor(loc.getY() - 0.05),
                floor(loc.getZ())).getType();
        boolean grounded = wheels.carrying() >= 1;
        double grip = grounded ? wheels.gripSum() / 4.0 : 0.0;
        double gripEff = grip;

        // Ohne Fahrer greift die Simulations-Eingabe: damit laufen Gas, Bremse, Handbremse
        // und Lenkung im Selftest durch genau diesen Code und nicht durch eine Kopie.
        Input input = driver != null ? driver.getCurrentInput() : car.getSimInput();
        boolean pedals = input != null && (input.isForward() || input.isBackward());
        boolean handbrake = grounded && input != null && input.isJump() && startAbs > SPEED_EPSILON;
        double steerDemandDeg = 0.0;
        double vfBeforeForces = vf;

        if (input != null && grounded) {
            if (handbrake) {
                gripEff = grip * config.handbrakeGrip;
                vf = approachZero(vf, config.handbrakeDeceleration * grip);
            } else {
                vf = applyInput(vf, input, grip);
            }
            // Lenkung behaelt bei Handbremse vollen Grip (Vorderraeder!), nur die Folgefaehigkeit
            // bricht ein. Das Lenkrad begrenzt die Kruemmung (Grad pro Meter, realistisch);
            // darueber deckelt die Grip-Grenze. Nur am Kontakt darf im Stand rangiert werden.
            if (grip > 0) {
                double gripCapDeg = Math.toDegrees((config.maxLatGrip * grip) / Math.max(startAbs, SPEED_EPSILON));
                double wheelCapDeg = startAbs >= config.turnMinSpeed
                        ? config.turnCurvature * startAbs
                        : (car.wasStepBlocked() ? CRAWL_TURN_DEG : 0.0);
                double allowed = Math.min(wheelCapDeg, gripCapDeg);
                if (allowed > 1.0e-6) {
                    double diff = steerDemand(input, driver, yaw, vf, allowed);
                    steerDemandDeg = Math.abs(diff);
                    double target = Math.abs(diff) > allowed ? Math.signum(diff) * allowed : diff;
                    // Lenk-Schwung: Drehrate folgt dem Bedarf geglaettet, statt hart einzurasten
                    double yawVel = car.getYawVel();
                    yawVel += (target - yawVel) * (target == 0 ? YAW_SMOOTH_OUT : YAW_SMOOTH_IN);
                    if (target == 0 && Math.abs(yawVel) < YAW_DEADBAND) {
                        yawVel = 0;
                    }
                    car.setYawVel(yawVel);
                    yaw = wrapDeg(yaw + (float) yawVel);
                } else {
                    car.setYawVel(0);
                }
            } else {
                car.setYawVel(0);
            }
        } else {
            car.setYawVel(0);
        }

        // Simulations-Driftmodus: erzwungene Drehung ohne Fahrer, um Schlupf zu provozieren
        if (car.isSimDrift() && driver == null) {
            yaw = wrapDeg(yaw + 6f);
        }

        // Crash-Spin: dreht nur die Karosserie (yaw), nicht den Geschwindigkeitsvektor —
        // der Vektor folgt ueber das uebliche Grip-Budget (ALIGN), daraus entsteht das
        // Schleudern. Am Boden frisst Reifenreibung den Drehimpuls grip-abhaengig,
        // in der Luft bleibt er nahezu erhalten (Drehimpulserhaltung).
        double spinVel = car.getSpinVel();
        if (spinVel != 0.0) {
            yaw = wrapDeg(yaw + (float) spinVel);
            spinVel *= grounded ? (1.0 - 0.25 * grip) : 0.995;
            if (Math.abs(spinVel) < SPIN_DEADBAND) {
                spinVel = 0.0;
            }
            car.setSpinVel(spinVel);
        }

        // Motorbremse nur bei losgelassenem Pedal, Luftwiderstand immer.
        // Die Motorbremse wirkt ueber die Raeder: bei wenig Grip bremst sie entsprechend wenig.
        if (grounded && !pedals) {
            vf = approachZero(vf, config.engineBraking * grip);
        }
        // Longitudinale Pedal-Kraft des Ticks (positiv = Schub, negativ = Bremse/Handbremse);
        // treibt Modell-Squat/Dive und den Laengs-Anteil im Grip-Budget der Actionbar.
        double longForce = vf - vfBeforeForces;
        vx = vf * fx + lx;
        vz = vf * fz + lz;
        vx -= vx * config.drag;
        vz -= vz * config.drag;

        double abs = Math.hypot(vx, vz);
        // Folge- und Reibungsrahmen ist die Rollrichtung: bei Rückwärtsfahrt yaw+180°.
        double travelYaw = vf < 0 ? wrapDeg(yaw + 180.0f) : yaw;
        if (grounded && abs > 1.0e-9) {
            double dirX = -Math.sin(Math.toRadians(travelYaw));
            double dirZ = Math.cos(Math.toRadians(travelYaw));
            // Kein Rotieren im Kriechtum: bei Wandkontakt darf die Maus den Restweg nicht
            // zurück in die Wand drehen; der Creep wird stattdessen von der Reibung gefressen.
            if (abs >= config.turnMinSpeed) {
                double velAngle = wrapDeg((float) Math.toDegrees(Math.atan2(-vx, vz)));
                double budgetDeg = Math.toDegrees((config.maxLatGrip * gripEff) / Math.max(abs, SPEED_EPSILON)) * ALIGN_FRACTION;
                double angleDiff = wrapDeg(travelYaw - velAngle);
                double alignStep = Math.abs(angleDiff) > budgetDeg ? Math.signum(angleDiff) * budgetDeg : angleDiff;
                double newAng = Math.toRadians(wrapDeg(velAngle + (float) alignStep));
                vx = -Math.sin(newAng) * abs;
                vz = Math.cos(newAng) * abs;
            }
            // Laterale Reibung frisst die Querkomponente gegen die Rollrichtung direkt
            double fComp = vx * dirX + vz * dirZ;
            double latX = vx - fComp * dirX;
            double latZ = vz - fComp * dirZ;
            double latAbs = Math.hypot(latX, latZ);
            if (latAbs > 1.0e-9) {
                double latNew = approachZero(latAbs, config.maxLatGrip * gripEff * FRICTION_FRACTION);
                double scale = latNew / latAbs;
                vx = fComp * dirX + latX * scale;
                vz = fComp * dirZ + latZ * scale;
            }
        }

        // Kippen statt balancieren: mit weniger als drei tragenden Raedern gibt es keine stabile
        // Auflage. Das Auto bekommt einen Schub zur unbelasteten Seite, rutscht von der Kante
        // (oder dem einzelnen Rad) ab und faellt dort ganz normal herunter, sobald die Stuetze
        // weg ist. Das ist die einzige Art, ein Umkippen zu zeigen: die Karosserie bleibt
        // waagerecht, echte Rotation um die Kippachse gibt es im Modell nicht.
        // tip-acceleration muss deutlich ueber dem liegen, was die Querreibung je Tick wegnimmt
        // (FRICTION_FRACTION frisst die Haelfte), sonst zappelt das Auto nur auf der Kante.
        if (grounded && !wheels.stable()) {
            vx += wheels.tipX() * config.tipAcceleration;
            vz += wheels.tipZ() * config.tipAcceleration;
        }

        // Standfest: ohne Pedal unterhalb der Kriechgrenze hart anhalten — aber nur, wenn die
        // Reifen tragen koennen; auf Glatteis laeuft der Restschwung stattdessen natuerlich aus.
        if (grounded && !pedals && grip >= STANDSTILL_MIN_GRIP && Math.hypot(vx, vz) < STANDSTILL_SPEED) {
            vx = 0;
            vz = 0;
        }

        double velAngleAfter = Math.hypot(vx, vz) > 1.0e-9
                ? wrapDeg((float) Math.toDegrees(Math.atan2(-vx, vz)))
                : travelYaw;
        double slipDeg = Math.abs(wrapDeg(travelYaw - velAngleAfter));

        // Grip-Auslastung fuer die Actionbar: geforderte Querkraft / verfuegbare, plus Schlupf.
        // >=100 % heisst: Reifen am Limit (Untersteuern bzw. Vollschlupf).
        double gripUsage = 0.0;
        if (grounded && gripEff > 0) {
            double budget = config.maxLatGrip * gripEff;
            // Traktionskreis: Quer- UND Pedal-Anforderung zehren am selben Grip-Budget —
            // Vollgas oder Vollbremsung zeigt die Anzeige auch ohne Lenkung am Limit.
            double needed = Math.hypot(startAbs * Math.sin(Math.toRadians(steerDemandDeg)), longForce);
            gripUsage = Math.min(1.5, needed / budget);
            gripUsage = Math.max(gripUsage, Math.sin(Math.toRadians(Math.min(slipDeg, 90.0))));
        }

        double targetX = loc.getX();
        double targetY = loc.getY();
        double targetZ = loc.getZ();
        boolean stepBlocked = false;
        boolean wantsMove = vx != 0 || vz != 0;
        if (wantsMove) {
            if (embedded(world, targetX, targetY, targetZ)) {
                // Mitte steckt bereits in der Geometrie (Fremdeingriff, Alt-Faelle):
                // Kollision aussetzen, damit man immer wieder herausfahren kann.
                targetX += vx;
                targetZ += vz;
            } else {
                List<Location> others = otherCarLocations(world, car);
                boolean canSnapDown = car.getFallSpeed() == 0;
                // Achsenweise: blockierte Achse verliert ihre Geschwindigkeit, die freie gleitet weiter
                if (vx != 0) {
                    StepResult sx = resolveStep(world, others, yaw, canSnapDown, 1,
                            targetX, targetY, targetZ, targetX + vx, targetZ);
                    if (sx.blocked()) {
                        stepBlocked = true;
                        vx = resolveCrashVelocity(car, vx, sx, targetX, targetZ);
                    }
                    targetX = sx.x();
                    targetY = sx.y();
                }
                if (vz != 0) {
                    StepResult sz = resolveStep(world, others, yaw, canSnapDown, 2,
                            targetX, targetY, targetZ, targetX, targetZ + vz);
                    if (sz.blocked()) {
                        stepBlocked = true;
                        vz = resolveCrashVelocity(car, vz, sz, targetX, targetZ);
                    }
                    targetZ = sz.z();
                    targetY = sz.y();
                }
            }
        }
        car.setStepBlocked(stepBlocked);

        double fallBefore = car.getFallSpeed();
        GravityResult gravity = applyGravity(world, targetX, targetY, targetZ, yaw, car, wantsMove);
        targetY = gravity.y();
        // Steigungs-Energie: einmal pro Tick, aber NICHT am rohen dy des Ticks. Hoehe kommt in
        // Spruengen (die Nase nimmt eine ganze Stufe in einem Tick), Vortrieb dagegen stetig —
        // die volle Lageenergie einer Stufe gegen die Momentangeschwindigkeit gerechnet ergibt
        // eine Rechnung, die im Kriechtempo niemand bezahlen kann: das Auto bleibt an einer
        // Treppe fuer immer stehen, egal wie klein slope-resistance ist.
        // Deshalb wird die Hoehendifferenz als Schuld gefuehrt und ueber eine Fahrzeuglaenge
        // abgetragen: je Tick faellt der Anteil an, der auf die gefahrene Strecke entfaellt.
        // Das ist ANTEILIG und nicht auf 45 Grad gedeckelt — sonst faehrt das Auto nach jeder
        // einzelnen Stufe weiter wie an einer Dauersteigung (Abzug g x slope-resistance je Tick,
        // unabhaengig vom Tempo) und das Gaspedal fuehlt sich tot an. Am Berg konvergiert die
        // Schuld von selbst auf die echte Steigung: was je Block dazukommt, wird je Block faellig.
        // downhill-assist bleibt ein Arcade-Bonus und skaliert mit dem verrechneten Gefaelle
        // (volle Wirkung ab 0,5 Block). Nicht bei echtem Fall (fallBefore == 0 UND
        // fallSpeed == 0): eine Landung aus einem Fall darf keinen Schub erzeugen.
        if (wantsMove && fallBefore == 0 && car.getFallSpeed() == 0) {
            double travelled = Math.hypot(targetX - loc.getX(), targetZ - loc.getZ());
            double rise = targetY - loc.getY();
            double debt = car.getSlopeDebt() + rise;
            double dy = debt * clamp(travelled / SLOPE_SPREAD, 0.0, 1.0);
            debt -= dy;
            if (rise <= 0.0) {
                debt *= SLOPE_DEBT_FADE;
            }
            car.setSlopeDebt(debt);
            double speed = Math.hypot(vx, vz);
            if (dy != 0 && speed > 1.0e-9) {
                double v2 = vx * vx + vz * vz;
                // vx/vz sind die WAAGERECHTE Komponente, die Lageenergie haengt an der Bahn:
                // v_bahn = v_horizontal / cos(Neigung), also faellt die Umrechnung mit cos².
                // Ohne den Faktor kostet eine 45-Grad-Steigung doppelt so viel wie physikalisch
                // richtig — flaches Gelaende merkt davon nichts (cos² ~ 1), steiles sehr wohl.
                double slopeCos2 = travelled > 1.0e-9
                        ? (travelled * travelled) / (travelled * travelled + dy * dy) : 1.0;
                double v2New = v2 - 2.0 * GRAVITY_ACCEL * config.slopeResistance * dy * slopeCos2;
                if (v2New <= 0) {
                    // kompletter Energieverlust am Berg: Totalstopp
                    vx = 0;
                    vz = 0;
                } else {
                    double newSpeed = Math.sqrt(v2New);
                    if (dy < 0) {
                        newSpeed += config.downhillAssist * clamp(-dy / 0.5, 0.0, 1.0);
                        newSpeed = Math.min(newSpeed, config.maxSpeed * OVERSPEED_DOWNHILL_FACTOR);
                    }
                    double scale = newSpeed / speed;
                    vx *= scale;
                    vz *= scale;
                }
            }
        } else if (fallBefore != 0 || car.getFallSpeed() != 0) {
            // Nur im echten Fall wird die Schuld gestrichen — eine alte darf die Landung nicht
            // treffen. Beim blossen Stillstand bleibt sie stehen: sonst schenkt schon ein
            // kurzes Loslassen des Gases die ganze aufgelaufene Steigungsenergie (der
            // Standfest-Hartschnapp greift nur ohne Pedal, danach ist wantsMove false).
            car.setSlopeDebt(0);
        }
        // Harte Landung nur nach echtem Fall: Querschwung bricht ein, Aufsetzen ist hörbar
        if (fallBefore > LANDING_SOUND_MIN_FALL && car.getFallSpeed() == 0 && !gravity.snapped()) {
            vx *= LANDING_SPEED_KEEP;
            vz *= LANDING_SPEED_KEEP;
            world.playSound(new Location(world, targetX, targetY, targetZ), Sound.ENTITY_GENERIC_BIG_FALL, 0.8f, 1.0f);
        }

        // Wasser bremst stark (und trägt nicht -> das Auto sinkt, siehe supportsCar)
        if (world.getBlockAt(floor(targetX), floor(targetY), floor(targetZ)).getType() == Material.WATER) {
            vx *= 1.0 - WATER_DRAG;
            vz *= 1.0 - WATER_DRAG;
        }

        car.setVelX(vx);
        car.setVelZ(vz);
        car.setYaw(yaw);

        if (config.debug && driver != null && tickCount % 20 == 0) {
            Block bf = world.getBlockAt(floor(targetX), floor(targetY), floor(targetZ));
            Block bh = world.getBlockAt(floor(targetX), floor(targetY) + 1, floor(targetZ));
            logger.info("[Debug] Fahrer=" + driver.getName()
                    + " vf=" + String.format("%.3f", vf) + " |v|=" + String.format("%.3f", Math.hypot(vx, vz))
                    + " slip=" + String.format("%.1f", slipDeg) + "° yaw=" + String.format("%.1f", yaw)
                    + " yawVel=" + String.format("%.2f", car.getYawVel())
                    + " fwd=" + input.isForward() + " bwd=" + input.isBackward()
                    + " grounded=" + grounded + " handbremse=" + handbrake
                    + " grip=" + String.format("%.2f", grip) + " ground=" + groundType.name()
                    + " feet=" + bf.getType() + "/" + blockedByCar(bf)
                    + " head=" + bh.getType() + "/" + blockedByCar(bh));
        }

        boolean moved = targetX != loc.getX() || targetY != loc.getY() || targetZ != loc.getZ();
        boolean turned = Math.abs(wrapDeg(yaw - oldYaw)) > 0.01f;
        if (moved || turned) {
            base.teleport(new Location(world, targetX, targetY, targetZ, yaw, 0f));
            if (turned) {
                // Display rotiert mit, damit das Modell in Fahrtrichtung zeigt
                car.getModel().setRotation(yaw, 0f);
            }
        }

        // Feldschaden: Pflanzen brechen, Ackerland wird zu Erde. Nur wenn sich das Auto auch
        // bewegt hat — ein parkendes Auto pfluegt kein Feld um.
        if (config.fieldDamage && moved) {
            double lifted = damageField(world, car, targetX, targetY, targetZ, yaw);
            if (lifted > targetY && lifted <= targetY + MAX_STEP) {
                // Ackerland ist 0,9375 hoch, Erde 1,0: der Boden unter dem Auto STEIGT beim
                // Umpfluegen um 1/16. Ohne diese Korrektur steht das Auto bis zum naechsten
                // Tick in seinem eigenen Untergrund — und genau dann meldet embedded() ein
                // Steckenbleiben und setzt die Kollision aus (Tunnel-Gefahr an einem Zaun am
                // Feldrand). Wer den Boden unter sich anhebt, steht danach auch darauf.
                targetY = lifted;
                base.teleport(new Location(world, targetX, targetY, targetZ, yaw, 0f));
            }
        }

        // Modell-Neigung und Quer-Neigung, beides EMA-geglaettet. Reine Optik — die Physik
        // bleibt davon unberuehrt. Pitch = Achslage (siehe axlePitchDeg) plus Squat/Dive aus der
        // Pedal-Kraft (Gas: Nase hoch, Bremse: Nase runter); Roll = Tempo × Drehrate.
        // Sichtpruefungs-Stand: Steigungs-Term und Roll korrekt, der Pedal-Term stand Kopf und
        // ist daher invertiert (Minus davor!) — nicht die Achsen pauschal flippen.
        boolean showWheels = config.debugWheels && tickCount % 2 == 0;
        Wheels stance = probeWheels(world, targetX, targetY, targetZ, yaw, showWheels);
        // Nicken aus der Achslage, Wanken aus der Achsverschraenkung plus dem Kurven-Anteil.
        // VORZEICHEN: beide Gelaende-Terme sind headless nicht pruefbar — stimmt der Drehsinn im
        // Spiel nicht, hier das Minus kippen (nicht die Quaternion-Achsen tauschen).
        double pitchGoal = -Math.toDegrees(Math.atan2(
                stance.frontTop() - stance.rearTop(), AXLE_SPAN))
                - clamp(longForce * PITCH_ACCEL_DEG, -PITCH_ACCEL_MAX_DEG, PITCH_ACCEL_MAX_DEG);
        double pitch = clamp(car.getLastPitchDeg() + (pitchGoal - car.getLastPitchDeg()) * 0.3, -25.0, 25.0);
        double rollGoal = clamp(-Math.toDegrees(Math.atan2(
                stance.rightTop() - stance.leftTop(), TRACK_WIDTH))
                - 200.0 * Math.hypot(vx, vz) * Math.toRadians(car.getYawVel()), -12.0, 12.0);
        double roll = clamp(car.getLastRollDeg() + (rollGoal - car.getLastRollDeg()) * 0.3, -12.0, 12.0);
        // Karosserie sitzt zwischen den Achsen, nicht auf der hoechsten Stuetze: beim Herunter-
        // fahren einer Stufe haelt die Mitte das Fahrniveau oben, waehrend die Vorderachse schon
        // unten steht — ohne diesen Versatz schwebt das Modell sichtbar. Reine Optik, die
        // Kollisionshoehe (und damit Sitz und Klick-Hitbox) bleibt, wo die Physik sie hat.
        double axleMean = mid(stance.frontTop(), stance.rearTop());
        double sinkGoal = axleMean > Double.NEGATIVE_INFINITY
                ? clamp(axleMean - targetY, -MODEL_MAX_SINK, 0.0) : 0.0;
        double sink = clamp(car.getLastSinkOffset() + (sinkGoal - car.getLastSinkOffset()) * 0.3,
                -MODEL_MAX_SINK, 0.0);
        if (Math.abs(pitch - car.getLastPitchDeg()) > 0.5 || Math.abs(roll - car.getLastRollDeg()) > 0.5
                || Math.abs(sink - car.getLastSinkOffset()) > 0.02) {
            car.getModel().setTransformation(new Transformation(
                    new Vector3f(0f, (float) (CarManager.MODEL_Y_OFFSET + sink), 0f),
                    new Quaternionf().rotationX((float) Math.toRadians(pitch))
                            .rotateZ((float) Math.toRadians(roll)),
                    new Vector3f(CarManager.MODEL_SCALE, CarManager.MODEL_SCALE, CarManager.MODEL_SCALE),
                    new Quaternionf()));
            car.setLastPitchDeg(pitch);
            car.setLastRollDeg(roll);
            car.setLastSinkOffset(sink);
        }

        if (driver != null) {
            if (tickCount % 4 == 0) {
                sendActionBar(driver, Math.hypot(vx, vz), vf, gripUsage);
            }
            double speedNow = Math.hypot(vx, vz);
            if (grounded && config.understeerSound && slipDeg > SLIP_SOUND_MIN_DEG && speedNow > config.turnMinSpeed * 2) {
                playUndersteerSound(car, world, loc);
            }
        }

        if (car.getSimTicks() > 0) {
            car.setSimTicks(car.getSimTicks() - 1);
            if (car.getSimObserver() != null) {
                car.getSimObserver().accept(new SimSample(car.getSimTicks(), startAbs, vf, slipDeg,
                        grounded, grip, stepBlocked, targetX, targetY, targetZ, car.getYaw()));
                if (car.getSimTicks() == 0) {
                    carManager.removeCar(car, false);
                }
                return;
            }
            Block bf = world.getBlockAt(floor(targetX), floor(targetY), floor(targetZ));
            Block bh = world.getBlockAt(floor(targetX), floor(targetY) + 1, floor(targetZ));
            logger.info("[Sim] t=" + car.getSimTicks()
                    + " cars=" + carManager.size()
                    + " start=" + String.format("%.4f", startAbs)
                    + " vf=" + String.format("%.4f", vf)
                    + " slip=" + String.format("%.1f", slipDeg)
                    + " grounded=" + grounded
                    + " grip=" + String.format("%.2f", grip)
                    + " blocked=" + stepBlocked
                    + " feet=" + bf.getType() + "/" + blockedByCar(bf)
                    + " head=" + bh.getType() + "/" + blockedByCar(bh)
                    + " pos=" + String.format("%.3f %.3f %.3f", targetX, targetY, targetZ));
            if (car.getSimTicks() == 0) {
                logger.info("[Sim] fertig, Auto entfernt.");
                carManager.removeCar(car, false);
            }
        }
    }

    /**
     * Arcade-Fahrmodell auf der Fahrtrichtungs-Komponente: Am Stand beschleunigt W vorwärts
     * und S rückwärts; bei Bewegung bremst die jeweils entgegengesetzte Taste mit voller
     * Bremskraft. Motorbremse/Luftwiderstand laufen zentral.
     */
    private double applyInput(double vf, Input input, double grip) {
        boolean forward = input.isForward();
        boolean backward = input.isBackward();

        if (forward && backward) {
            vf = approachZero(vf, config.brakeDeceleration * grip);
        } else if (forward) {
            if (vf < -0.01) {
                vf = Math.min(vf + config.brakeDeceleration * grip, 0);
            } else {
                // Weicher Limiter: Antriebskraft geht Richtung Vmax gegen null statt hartem Cut
                vf = Math.min(vf + config.acceleration * grip
                        * clamp(1.0 - vf / Math.max(config.maxSpeed, 1.0e-9), 0.0, 1.0), config.maxSpeed);
            }
        } else if (backward) {
            if (vf > 0.01) {
                vf = Math.max(vf - config.brakeDeceleration * grip, 0);
            } else {
                vf = Math.max(vf - config.reverseAcceleration * grip
                        * clamp(1.0 + vf / Math.max(config.maxReverseSpeed, 1.0e-9), 0.0, 1.0),
                        -config.maxReverseSpeed);
            }
        }
        return vf;
    }

    /** Actionbar nach Spieler-Prefs: Strich-Tacho (km/h, R bei Rückwärts) und/oder Grip-Budget-Balken. */
    private void sendActionBar(Player driver, double speedAbs, double vf, double gripUsage) {
        java.util.UUID id = driver.getUniqueId();
        if (!prefs.actionbar(id)) {
            return;
        }
        boolean showSpeed = prefs.actionbarSpeed(id);
        boolean showGrip = prefs.actionbarGrip(id);
        if (!showSpeed && !showGrip) {
            return;
        }
        Component out = Component.empty();
        if (showSpeed) {
            double maxRef = Math.max(config.maxSpeed, config.maxReverseSpeed);
            int filled = (int) Math.round(Math.min(1.0, speedAbs / maxRef) * 10);
            StringBuilder bar = new StringBuilder(10);
            for (int i = 0; i < 10; i++) {
                bar.append(i < filled ? '█' : '░');
            }
            int kmh = (int) Math.round(speedAbs * 72.0);
            String gear = vf < -0.001 ? " R" : "";
            out = out.append(Component.text(bar + "  " + kmh + " km/h" + gear, NamedTextColor.GOLD));
        }
        if (showGrip) {
            if (showSpeed) {
                out = out.append(Component.text("   ", NamedTextColor.DARK_GRAY));
            }
            int filled = (int) Math.round(Math.min(1.0, gripUsage) * 5);
            StringBuilder gauge = new StringBuilder(5);
            for (int i = 0; i < 5; i++) {
                gauge.append(i < filled ? '▰' : '▱');
            }
            NamedTextColor color = gripUsage >= 1.0 ? NamedTextColor.RED
                    : gripUsage >= 0.6 ? NamedTextColor.GOLD : NamedTextColor.GREEN;
            int pct = (int) Math.round(gripUsage * 100);
            out = out.append(Component.text("Grip " + gauge + " " + pct + " %", color));
        }
        driver.sendActionBar(out);
    }

    /** Geforderte Lenkrate in Grad/Tick: A/D hat Vorrang, sonst Mausfolge (optional abschaltbar);
     *  die Rückwärts-Invertierung gilt für beide Eingabewege, wenn der Spieler sie nicht deaktiviert hat.
     *  driver ist null, wenn die Simulation lenkt — dann gibt es weder Maus noch Prefs. */
    private double steerDemand(Input input, Player driver, float yaw, double vf, double allowed) {
        boolean left = input.isLeft();
        boolean right = input.isRight();
        double diff;
        if (left && right) {
            diff = 0;
        } else if (left) {
            diff = -allowed;
        } else if (right) {
            diff = allowed;
        } else if (driver != null && prefs.mouseSteer(driver.getUniqueId())) {
            diff = mouseSteer(driver.getLocation().getYaw(), yaw, allowed);
        } else {
            diff = 0;
        }
        if (vf < 0 && driver != null && prefs.reverseInvert(driver.getUniqueId())) {
            diff = -diff;
        }
        return diff;
    }

    /**
     * Mauslenkung als Lenkrad: der Blickwinkel gegenueber der FAHRZEUGACHSE wird auf den
     * Lenkeinschlag abgebildet. Beide Achsenrichtungen sind dabei "geradeaus" — nach vorn UND
     * nach hinten schauen laesst das Rad gerade, quer (MOUSE_FULL_LOCK_DEG = 90°) liegt es voll
     * an, dazwischen linear; innerhalb der Totzone passiert nichts. Dadurch gilt dieselbe
     * Abbildung, egal ob der Fahrer nach vorn blickt oder beim Rueckwaertsfahren ueber die
     * Schulter — ohne sie stand das Lenkrad beim Zurueckschauen sofort am Anschlag, weil der
     * Rohwinkel (rund 180°) direkt gegen das Limit gedeckelt wurde.
     * Ob "mein Blick nach links" beim Rueckwaertsfahren auch nach links lenkt, entscheidet
     * anschliessend die Pref reverse_invert — nicht diese Abbildung.
     */
    private double mouseSteer(float playerYaw, float carYaw, double allowed) {
        double diff = wrapDeg(playerYaw - carYaw);
        double magnitude = Math.abs(diff);
        if (magnitude > 90.0) {
            // hinter der Quere geht es wieder Richtung geradeaus: 180° = Blick nach hinten
            magnitude = 180.0 - magnitude;
        }
        if (magnitude <= MOUSE_DEADZONE_DEG) {
            return 0;
        }
        double fraction = Math.min(1.0,
                (magnitude - MOUSE_DEADZONE_DEG) / (MOUSE_FULL_LOCK_DEG - MOUSE_DEADZONE_DEG));
        return Math.signum(diff) * fraction * allowed;
    }

    /**
     * Tastet eine horizontale Route substep-weise ab und liefert den erreichbaren Endpunkt.
     * Karosserie = yaw-ausgerichtetes 3x3-Sample-Raster (Nase, Heck, Ecken); andere Autos
     * zaehlen als Hindernis. Hindernisse bis MAX_STEP ueber dem Niveau sind Stufen
     * (Hoehe aus der Kollisionsform, Slabs eingerechnet): das Auto steigt auf und die Route
     * laeuft weiter. Bei echter Blockade steht das Auto am letzten freien Sample.
     */
    private StepResult resolveStep(World world, List<Location> others, float carYaw, boolean canSnapDown,
                                   int axis, double fromX, double fromY, double fromZ, double toX, double toZ) {
        double distX = toX - fromX;
        double distZ = toZ - fromZ;
        double dist = Math.hypot(distX, distZ);
        if (dist <= 0) {
            return new StepResult(false, fromX, fromY, fromZ, 0.0, 0.0, 0, 0.0);
        }
        int samples = Math.max(1, (int) Math.ceil(dist / SAMPLE_STEP));
        double stepX = distX / samples;
        double stepZ = distZ / samples;
        double yawRad = Math.toRadians(carYaw);
        double fwdX = -Math.sin(yawRad);
        double fwdZ = Math.cos(yawRad);
        double sideX = fwdZ;
        double sideZ = -fwdX;
        double curY = fromY;
        double freeX = fromX;
        double freeZ = fromZ;
        for (int i = 1; i <= samples; i++) {
            double sx = fromX + stepX * i;
            double sz = fromZ + stepZ * i;
            if (nearOtherCar(others, sx, sz)) {
                return new StepResult(true, freeX, curY, freeZ, sx, sz, 3, dist);
            }
            // Zuerst die Achsen: sie tragen das Auto, also bestimmen sie das Fahrniveau.
            Probe support = footprintObstacle(world, SUPPORT_LONG, SUPPORT_LAT, sx, sz, curY,
                    fwdX, fwdZ, sideX, sideZ, stepX, stepZ);
            if (support.top() > curY + MAX_STEP + 1.0e-9) {
                return new StepResult(true, freeX, curY, freeZ, support.x(), support.z(), axis, dist);
            }
            if (support.top() > curY + 1.0e-4) {
                if (!canStandAt(world, sx, support.top(), sz, fwdX, fwdZ, sideX, sideZ, stepX, stepZ)) {
                    return new StepResult(true, freeX, curY, freeZ, support.x(), support.z(), axis, dist);
                }
                curY = support.top();
            }
            // Dann die Karosserie auf dem so gefundenen Niveau: die Stossstange steht 1,25
            // Bloecke vor der Mitte und trifft die Wand vor den Raedern.
            Probe body = footprintObstacle(world, GRID_LONG, GRID_LAT, sx, sz, curY,
                    fwdX, fwdZ, sideX, sideZ, stepX, stepZ);
            if (body.top() > curY + MAX_STEP + 1.0e-9) {
                return new StepResult(true, freeX, curY, freeZ, body.x(), body.z(), axis, dist);
            }
            // Gelaendeverfolgung nach unten: kurze Abstiege nimmt das Auto direkt mit,
            // sonst fliegt es bei mehreren Zellen pro Tick den Abhang ballistisch herab.
            curY = followGroundDown(world, sx, sz, curY, canSnapDown,
                    fwdX, fwdZ, sideX, sideZ, stepX, stepZ);
            freeX = sx;
            freeZ = sz;
        }
        return new StepResult(false, toX, curY, toZ, 0.0, 0.0, 0, 0.0);
    }

    /**
     * Crash-Physik bei Blockade: statt die Achsen-Komponente still zu loeschen, wird sie teilweise
     * reflektiert (crash-restitution, auf CRASH_REBOUND_MAX gedeckelt, gegen andere Autos nur halb)
     * und Wandkontakte versetzen die Karosserie ueber den Aufprall-Hebel in Drehung
     * (tau = Hebel x Impuls, 2D-Kreuzprodukt). Unter CRASH_MIN_SPEED bleibt es der ruhige
     * Rangier-Stopp. Rueckgabe: die neue Achsen-Geschwindigkeit.
     */
    private double resolveCrashVelocity(Car car, double vAxis, StepResult sr, double fromX, double fromZ) {
        double impact = sr.impactSpeed();
        if (impact < CRASH_MIN_SPEED) {
            return 0.0;
        }
        boolean hitCar = sr.impactAxis() == 3;
        double restitution = hitCar ? config.crashRestitution * 0.5 : config.crashRestitution;
        if (!hitCar && config.crashSpin > 0) {
            double leverX = sr.impactX() - fromX;
            double leverZ = sr.impactZ() - fromZ;
            double normX = sr.impactAxis() == 1 ? -Math.signum(vAxis) : 0.0;
            double normZ = sr.impactAxis() == 2 ? -Math.signum(vAxis) : 0.0;
            double torque = leverX * (normZ * impact) - leverZ * (normX * impact);
            car.setSpinVel(clamp(car.getSpinVel() + config.crashSpin * SPIN_SCALE * torque, -MAX_SPIN, MAX_SPIN));
        }
        return -clamp(restitution * impact, 0.0, CRASH_REBOUND_MAX) * Math.signum(vAxis);
    }

    /**
     * Gelaendeverfolgung nach unten ueber den GANZEN Footprint: das Auto sinkt nur so weit, wie
     * die hoechste Stuetze darunter es zulaesst — es ist ein starrer Koerper, kein Punkt. Wuerde
     * nur die Mitte zaehlen, fiele es an jeder Rampe sofort wieder auf das Niveau seiner Mitte
     * zurueck und muesste die Steigung mit der Nase immer wieder von unten nehmen; ab etwa 5/8
     * Block Anstieg je Block reisst das MAX_STEP und die Rampe blockiert.
     * Rasterpunkte hinter der Fahrtrichtung zaehlen nicht (siehe footprintObstacle), sonst haelt
     * die gerade verlassene Kante das Auto oben fest.
     */
    private double followGroundDown(World world, double sx, double sz, double curY, boolean canSnapDown,
                                    double fwdX, double fwdZ, double sideX, double sideZ,
                                    double dirX, double dirZ) {
        double best = Double.NEGATIVE_INFINITY;
        for (double fl : SUPPORT_LONG) {
            for (double sl : SUPPORT_LAT) {
                double ox = fwdX * fl + sideX * sl;
                double oz = fwdZ * fl + sideZ * sl;
                if (ox * dirX + oz * dirZ < -1.0e-9) {
                    continue;
                }
                double top = groundBelow(world, sx + ox, sz + oz, curY, canSnapDown);
                if (top > best) {
                    best = top;
                    if (best >= curY - 1.0e-9) {
                        return best; // hoeher als das aktuelle Niveau geht nicht, Rest sparen
                    }
                }
            }
        }
        return best > Double.NEGATIVE_INFINITY ? best : curY;
    }

    /** Hoechste Oberkante unter (x,z), die hoechstens {@code reach} unter {@code y} liegt;
     *  -Unendlich, wenn dort nichts traegt. Zellenweise, damit auch Teilblock-Oberkanten zaehlen. */
    private double contactGround(World world, double x, double z, double y, double reach) {
        int from = floor(y - 0.05);
        int to = floor(y - reach);
        for (int by = from; by >= to; by--) {
            Block block = world.getBlockAt(floor(x), by, floor(z));
            if (supportsCar(block)) {
                double top = supportTop(block, by);
                if (top <= y + 0.05 && y - top <= reach) {
                    return top;
                }
            }
        }
        return Double.NEGATIVE_INFINITY;
    }

    /** Boden bei (x,z) auf Hoehe curY oder bis MAX_STEP_DOWN darunter; sonst -Unendlich. */
    private double groundBelow(World world, double x, double z, double curY, boolean canSnapDown) {
        int by = floor(curY - 0.05);
        Block direct = world.getBlockAt(floor(x), by, floor(z));
        if (supportsCar(direct)) {
            double top = supportTop(direct, by);
            if (curY - top < 0.4) {
                return top;
            }
        }
        if (!canSnapDown) {
            return Double.NEGATIVE_INFINITY;
        }
        for (double d = 0.2; d <= MAX_STEP_DOWN; d += 0.2) {
            int sy = floor(curY - d - 0.05);
            Block b = world.getBlockAt(floor(x), sy, floor(z));
            if (supportsCar(b)) {
                double top = supportTop(b, sy);
                if (top <= curY && curY - top <= MAX_STEP_DOWN) {
                    return top;
                }
            }
        }
        return Double.NEGATIVE_INFINITY;
    }

    /** Hoechstes Hindernis unter dem Footprint samt Fundort (fuer den Aufprall-Hebel). */
    private record Probe(double top, double x, double z) { }

    /**
     * Sondiert ein yaw-ausgerichtetes Punktraster an (sx,sz) auf Niveau curY — je nach
     * uebergebenem Raster die Karosserie (blockiert) oder die Aufstandsflaeche (traegt).
     * Rasterpunkte HINTER der Bewegungsrichtung zaehlen nicht: aus einer Lage, in die das Auto
     * hineingefahren ist, muss es immer wieder herausfahren koennen — sonst sperrt genau die
     * Kante, die es gerade heruntergefahren ist, es fuer immer ein (Abstieg zwischen MAX_STEP
     * und MAX_STEP_DOWN: das Heck-Sample steht danach vor einer Kante ueber MAX_STEP).
     */
    private Probe footprintObstacle(World world, double[] longs, double[] lats,
                                    double sx, double sz, double curY,
                                    double fwdX, double fwdZ, double sideX, double sideZ,
                                    double dirX, double dirZ) {
        double top = Double.NEGATIVE_INFINITY;
        double hitX = sx;
        double hitZ = sz;
        for (double fl : longs) {
            for (double sl : lats) {
                double ox = fwdX * fl + sideX * sl;
                double oz = fwdZ * fl + sideZ * sl;
                if (ox * dirX + oz * dirZ < -1.0e-9) {
                    continue;
                }
                double columnTop = columnObstacleTop(world, floor(sx + ox), curY, floor(sz + oz));
                if (columnTop > top) {
                    top = columnTop;
                    hitX = sx + ox;
                    hitZ = sz + oz;
                }
            }
        }
        return new Probe(top, hitX, hitZ);
    }

    /**
     * Oberkante des hoechsten Hindernisses in der Saeule (bx,bz), gesehen von Niveau curY.
     * Betrachtet wird jede Zelle von der Fuss-Zelle bis floor(curY + MAX_STEP): was dort an
     * Kollisionsform steht, ist eine Stufe — und was ueber curY + MAX_STEP hinausragt, ist eine
     * Wand (+Unendlich). Damit zaehlt die ECHTE Hoehe des Hindernisses und nicht die Zelle, in
     * der es zufaellig sitzt: steht das Auto auf einer gekappten Oberkante (Ackerland, Schlamm,
     * Schnee, Stufen), ragt der Nachbarbelag in die Kopf-Zelle, ohne deshalb unueberwindbar zu
     * sein. Luft, Wasser und passierbare Bloecke sind frei, Lava ist immer Wand.
     */
    private double columnObstacleTop(World world, int bx, double curY, int bz) {
        double reach = curY + MAX_STEP;
        int from = floor(curY);
        int to = floor(reach + 1.0e-9);
        double top = Double.NEGATIVE_INFINITY;
        for (int by = from; by <= to; by++) {
            Block block = world.getBlockAt(bx, by, bz);
            Material type = block.getType();
            if (type == Material.LAVA) {
                return Double.POSITIVE_INFINITY;
            }
            if (type.isAir() || type == Material.WATER || block.isPassable()) {
                continue;
            }
            double blockTop = supportTop(block, by);
            if (blockTop > reach + 1.0e-9) {
                return Double.POSITIVE_INFINITY;
            }
            if (blockTop > top) {
                top = blockTop;
            }
        }
        return top;
    }

    /**
     * Prueft nach einem Stufenaufstieg, ob der Footprint auf dem Zielniveau frei steht: dieselbe
     * Sondierung, nur vom Zielniveau aus. Das verschiebt das Suchfenster nach oben und findet
     * damit Hindernisse, die von unten noch gar nicht sichtbar waren — eine niedrige Decke ueber
     * der Stufe zum Beispiel.
     * <p>Verboten ist dort aber NUR eine Wand (+Unendlich, also alles ueber Zielniveau +
     * MAX_STEP). Eine weitere befahrbare Stufe ist kein Grund, die aktuelle nicht zu nehmen —
     * genau daran sind Treppen gescheitert: sobald die Nase (bei Yaw sogar bis 1,54 Bloecke
     * voraus) ueber der uebernaechsten Stufe stand, verweigerte der alte Test {@code top <= y}
     * den Aufstieg auf die naechste. Das Auto rammte stattdessen die Kante, bekam Drehimpuls
     * statt Hoehe und kam nur im Kriechtempo und mit Ruckeln hoch.
     */
    private boolean canStandAt(World world, double x, double y, double z,
                               double fwdX, double fwdZ, double sideX, double sideZ,
                               double dirX, double dirZ) {
        return footprintObstacle(world, GRID_LONG, GRID_LAT, x, z, y,
                fwdX, fwdZ, sideX, sideZ, dirX, dirZ).top() <= y + MAX_STEP + 1.0e-9;
    }

    private boolean nearOtherCar(List<Location> others, double x, double z) {
        double limit = CAR_COLLISION_RADIUS * CAR_COLLISION_RADIUS;
        for (Location other : others) {
            double dx = other.getX() - x;
            double dz = other.getZ() - z;
            if (dx * dx + dz * dz < limit) {
                return true;
            }
        }
        return false;
    }

    private List<Location> otherCarLocations(World world, Car self) {
        List<Location> others = new ArrayList<>();
        for (Car other : carManager.getCars()) {
            if (other != self && !other.getBase().isDead() && other.getBase().getWorld() == world) {
                others.add(other.getBase().getLocation());
            }
        }
        return others;
    }

    /** Steckt die Fahrzeugmitte bereits in einem harten Block (Soll-Zustand: nie)?
     *  Nur die Mitte zaehlt: Fuss-Zelle, wenn der Block tatsaechlich ueber das Niveau hinausragt
     *  (sonst galte Stehen auf Slabs als eingebettet), plus massiv belegte Kopf-Zelle.
     *  WICHTIG: Nase/Heck duerfen hier nicht mitzaehlen — in der Luft neben einer Boeschung
     *  schwebt die Nase zwangsläufig ueber Erdreich, das ist kein Einbetten. */
    private boolean embedded(World world, double x, double y, double z) {
        int by = floor(y);
        Block feet = world.getBlockAt(floor(x), by, floor(z));
        Material t = feet.getType();
        if (!t.isAir() && t != Material.WATER && !feet.isPassable()
                && supportTop(feet, by) > y + 0.05) {
            return true;
        }
        Block head = world.getBlockAt(floor(x), by + 1, floor(z));
        Material ht = head.getType();
        return !ht.isAir() && ht != Material.WATER && !head.isPassable();
    }

    /**
     * Gravitation mit Substep-Abtastung. Kurze Abstiege (<= MAX_STEP_DOWN) rasten sofort ein,
     * wenn das Auto vorher am Boden war und sich bewegt hat: bergab bleibt man fahrbar, ohne
     * ballistische Loser-Phase. Groessere Luecken starten den ballistischen Fall bis
     * max-fall-speed. Die Landung snappt auf die echte Blockoberkante (Slab-/Pfad-Hoehe).
     */
    private GravityResult applyGravity(World world, double x, double y, double z, float carYaw,
                                       Car car, boolean moved) {
        // Getragen wird das Auto von seiner AUFSTANDSFLAECHE, nicht von seiner Mitte — dieselbe
        // Regel wie beim Fahrniveau. Auf einer Treppe steht die Vorderachse eine Stufe hoeher,
        // waehrend unter der Mitte die Luft vor der Stufenkante liegt: mit der Mitte allein zog
        // die Schwerkraft das Auto nach jedem Aufstieg sofort wieder herunter, und die
        // Stossstange rammte anschliessend die uebernaechste Stufe.
        double top = supportTopBelow(world, x, z, y, carYaw);
        if (top > Double.NEGATIVE_INFINITY) {
            // Kurze Reststrecke (z. B. nach Step-down) direkt einrasten; aus dem Block herausheben
            if (y - top < 0.4) {
                car.setFallSpeed(0);
                return new GravityResult(top, false);
            }
            // Step-Down: vorher geerdet (kein aktiver Fall) und kurz darunter stuetzt Boden
            if (car.getFallSpeed() == 0 && moved) {
                return new GravityResult(top, true);
            }
        }

        // Im Wasser baut sich KEINE Gravitation mehr auf: die Sinkgeschwindigkeit laeuft
        // asymptotisch auf max-sink-speed zu (Auftrieb + Widerstand). Wuerde erst die volle
        // Erdbeschleunigung addiert und danach nur der Ueberschuss gedaempft, laege der Fixpunkt
        // der Folge weit ueber dem konfigurierten Wert (gemessen rund 30 statt 9 km/h).
        boolean inWater = world.getBlockAt(floor(x), floor(y), floor(z)).getType() == Material.WATER;
        double fallSpeed = inWater
                ? sinkSpeed(car.getFallSpeed())
                : Math.min(car.getFallSpeed() + GRAVITY_ACCEL, config.maxFallSpeed);
        double newY = y;
        double remaining = fallSpeed;
        while (remaining > 1.0e-9) {
            double step = Math.min(remaining, 0.25);
            double candidate = newY - step;
            int fy = floor(candidate - 0.05);
            Block b = world.getBlockAt(floor(x), fy, floor(z));
            if (supportsCar(b)) {
                car.setFallSpeed(0);
                return new GravityResult(supportTop(b, fy), false);
            }
            newY = candidate;
            // Eintauchen mitten im Tick: ab hier bremst das Wasser schon diesen Fall
            if (world.getBlockAt(floor(x), floor(candidate), floor(z)).getType() == Material.WATER) {
                fallSpeed = sinkSpeed(fallSpeed);
                remaining = Math.min(remaining, fallSpeed);
            }
            remaining -= step;
        }
        car.setFallSpeed(fallSpeed);
        return new GravityResult(newY, false);
    }

    /**
     * Aufstandslage der vier Raeder. {@code -Unendlich} heisst: das Rad traegt nicht.
     * {@code carrying}/{@code gripSum} speisen den Grip, die vier Achs- und Seitenhoehen die
     * Modell-Optik (Nicken, Wanken, Absetzen der Karosserie).
     */
    private record Wheels(int carrying, double gripSum, double frontTop, double rearTop,
                          double leftTop, double rightTop, double tipX, double tipZ) {

        /** Weniger als drei tragende Raeder heisst: der Schwerpunkt liegt ausserhalb der
         *  Auflageflaeche. Ein Punkt traegt gar nicht, zwei bilden nur eine Kippachse — egal ob
         *  laengs, quer oder diagonal. Das Auto balanciert dann nicht, es kippt ab. */
        boolean stable() {
            return carrying >= 3;
        }
    }

    /**
     * Sondiert die vier Raeder und verbindet sie achsweise. Jedes Rad sucht seinen Boden bis zwei
     * Stufen unter dem Fahrniveau (auf einer Treppe steht die Hinterachse so tief); je Achse gilt
     * dann der Federweg: wer mehr als AXLE_TRAVEL unter seinem Gegenstueck haengt, hebt ab und
     * traegt nicht mehr. Ohne diese Kopplung verwindet sich eine Achse um einen ganzen Block —
     * ein Rad auf dem Bordstein, das andere auf der Strasse, und beide melden Grip.
     * <p>Zeichnet dabei die Rad-Anzeige, wenn {@code draw}: gruen = Rad traegt (Punkt auf seiner
     * Aufstandshoehe), rot = Rad haengt (Punkt auf Achshoehe), blau = die acht Karosserie-Punkte
     * auf Fahrniveau, die gegen Waende blockieren.
     */
    /**
     * Was das Auto auf einem Acker anrichtet — dieselbe Aufteilung wie bei der Kollision:
     * Pflanzen brechen unter der KAROSSERIE (sie blockieren nicht, das Auto faehrt hindurch),
     * Ackerland wird unter der AUFSTANDSFLAECHE zu Erde (dort steht das Gewicht auf dem Boden).
     * Beides laeuft ueber {@link EntityChangeBlockEvent}, wie Vanilla es fuer trampelnde Mobs
     * und den Ravager tut — Schutz-Plugins koennen es damit abfangen. Verursacher ist der
     * Fahrer, wenn einer sitzt, sonst das Auto selbst (ein fuehrerloses Auto rollt seltener,
     * aber es rollt).
     */
    private double damageField(World world, Car car, double x, double y, double z, float carYaw) {
        double yawRad = Math.toRadians(carYaw);
        double fwdX = -Math.sin(yawRad);
        double fwdZ = Math.cos(yawRad);
        double sideX = fwdZ;
        double sideZ = -fwdX;
        Entity source = car.getDriver() != null ? car.getDriver() : car.getBase();
        for (double fl : GRID_LONG) {
            for (double sl : GRID_LAT) {
                int bx = floor(x + fwdX * fl + sideX * sl);
                int bz = floor(z + fwdZ * fl + sideZ * sl);
                // Die Pflanze steht je nach Belag auf Fahrniveau ODER eine Zelle darueber:
                // Ackerland ist nur 0,9375 hoch, das Auto steht dann unter der Zellgrenze.
                int feet = floor(y + 0.05);
                breakCrop(world.getBlockAt(bx, feet, bz), source);
                breakCrop(world.getBlockAt(bx, feet + 1, bz), source);
            }
        }
        double lifted = Double.NEGATIVE_INFINITY;
        for (double fl : SUPPORT_LONG) {
            for (double sl : SUPPORT_LAT) {
                int bx = floor(x + fwdX * fl + sideX * sl);
                int bz = floor(z + fwdZ * fl + sideZ * sl);
                int by = floor(y - 0.05);
                if (trample(world.getBlockAt(bx, by, bz), source)) {
                    // Erde ist ein voller Block: die neue Oberkante liegt bei by + 1.
                    lifted = Math.max(lifted, by + 1.0);
                }
            }
        }
        return lifted;
    }

    /** Bricht eine Nutzpflanze samt Drop — wer sie umfaehrt, soll sie auch einsammeln koennen. */
    private void breakCrop(Block block, Entity source) {
        if (!CROPS.contains(block.getType())) {
            return;
        }
        EntityChangeBlockEvent event =
                new EntityChangeBlockEvent(source, block, Material.AIR.createBlockData());
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            block.breakNaturally();
        }
    }

    /** Ackerland unter einem Rad wird zu Erde, genau wie beim Vanilla-Trampeln.
     *  Rueckgabe: true, wenn tatsaechlich umgewandelt wurde (der Boden steigt dann um 1/16). */
    private boolean trample(Block block, Entity source) {
        if (block.getType() != Material.FARMLAND) {
            return false;
        }
        BlockData dirt = Material.DIRT.createBlockData();
        EntityChangeBlockEvent event = new EntityChangeBlockEvent(source, block, dirt);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        // Mit Physik-Update, damit eine Pflanze darueber wie in Vanilla abfaellt.
        block.setBlockData(dirt, true);
        return true;
    }

    /** true, wenn dieses Material vom Auto umgefahren wird (fuer den Selftest sichtbar). */
    static boolean isCrop(Material material) {
        return CROPS.contains(material);
    }

    private Wheels probeWheels(World world, double x, double y, double z, float carYaw, boolean draw) {
        double yawRad = Math.toRadians(carYaw);
        double fwdX = -Math.sin(yawRad);
        double fwdZ = Math.cos(yawRad);
        double sideX = fwdZ;
        double sideZ = -fwdX;
        // Reihenfolge: 0/1 = Hinterachse (-0,9), 2/3 = Vorderachse (+0,9); je Achse erst die
        // Seite bei -0,7 quer, dann +0,7.
        // Bezug der Rad-Auflage ist der Boden unter der MITTE: von dort darf ein Rad noch
        // MAX_STEP_DOWN tiefer stehen. Auf einer Treppe liegt die Hinterachse zwei Stufen unter
        // dem Fahrniveau, aber nur eine unter dem Boden der Mitte — sie traegt also. Ueber einem
        // Loch und im freien Fall findet die Mitte dagegen nichts, dann bleibt das Fahrniveau
        // der Bezug und es traegt nur, was direkt darunter liegt (sonst greift ein Rad im Fall
        // nach Boden, der zwei Bloecke tiefer liegt).
        double centre = contactGround(world, x, z, y, MAX_STEP_DOWN);
        double reach = (centre > Double.NEGATIVE_INFINITY ? y - centre : 0.0) + MAX_STEP_DOWN;
        double[] tops = new double[4];
        double[] wx = new double[4];
        double[] wz = new double[4];
        int i = 0;
        for (double wLong : WHEEL_LONG) {
            for (double wLat : WHEEL_LAT) {
                wx[i] = x + fwdX * wLong + sideX * wLat;
                wz[i] = z + fwdZ * wLong + sideZ * wLat;
                tops[i] = contactGround(world, wx[i], wz[i], y, reach);
                i++;
            }
        }
        double[] axleTop = new double[2];
        for (int axle = 0; axle < 2; axle++) {
            int a = axle * 2;
            double high = Math.max(tops[a], tops[a + 1]);
            if (tops[a] < high - AXLE_TRAVEL) {
                tops[a] = Double.NEGATIVE_INFINITY;
            }
            if (tops[a + 1] < high - AXLE_TRAVEL) {
                tops[a + 1] = Double.NEGATIVE_INFINITY;
            }
            axleTop[axle] = mid(tops[a], tops[a + 1]);
        }
        int carrying = 0;
        double gripSum = 0.0;
        for (int w = 0; w < 4; w++) {
            boolean carries = tops[w] > Double.NEGATIVE_INFINITY;
            if (carries) {
                carrying++;
                gripSum += gripCalculator.gripFor(world.getBlockAt(floor(wx[w]),
                        floor(tops[w] - 0.05), floor(wz[w])).getType());
            }
            if (draw) {
                double axle = axleTop[w / 2];
                double dotY = carries ? tops[w] : (axle > Double.NEGATIVE_INFINITY ? axle : y);
                world.spawnParticle(Particle.DUST, wx[w], dotY + 0.08, wz[w], 1, 0.0, 0.0, 0.0, 0.0,
                        new Particle.DustOptions(carries ? Color.LIME : Color.RED, 0.8f));
            }
        }
        if (draw) {
            for (double fl : GRID_LONG) {
                for (double sl : GRID_LAT) {
                    if (fl == 0.0 && sl == 0.0) {
                        continue;
                    }
                    world.spawnParticle(Particle.DUST, x + fwdX * fl + sideX * sl, y + 0.08,
                            z + fwdZ * fl + sideZ * sl, 1, 0.0, 0.0, 0.0, 0.0,
                            new Particle.DustOptions(Color.AQUA, 0.5f));
                }
            }
        }
        // Kipprichtung: von der Auflageflaeche weg. Mit drei oder vier Raedern liegt der
        // Schwerpunkt drin, dann gibt es nichts zu kippen.
        double tipX = 0.0;
        double tipZ = 0.0;
        if (carrying > 0 && carrying < 3) {
            double sumX = 0.0;
            double sumZ = 0.0;
            for (int w = 0; w < 4; w++) {
                if (tops[w] > Double.NEGATIVE_INFINITY) {
                    sumX += wx[w];
                    sumZ += wz[w];
                }
            }
            double offX = x - sumX / carrying;
            double offZ = z - sumZ / carrying;
            double len = Math.hypot(offX, offZ);
            if (len > 1.0e-6) {
                tipX = offX / len;
                tipZ = offZ / len;
            }
        }
        return new Wheels(carrying, gripSum, axleTop[1], axleTop[0],
                mid(tops[1], tops[3]), mid(tops[0], tops[2]), tipX, tipZ);
    }

    /** Mittel zweier Aufstandshoehen; haengt eine, zaehlt die andere allein. */
    private double mid(double a, double b) {
        if (a <= Double.NEGATIVE_INFINITY) {
            return b;
        }
        if (b <= Double.NEGATIVE_INFINITY) {
            return a;
        }
        return (a + b) / 2.0;
    }

    /** Hoechste Stuetze unter der Aufstandsflaeche, hoechstens MAX_STEP_DOWN unter y;
     *  -Unendlich, wenn dort nichts traegt (dann faellt das Auto). */
    private double supportTopBelow(World world, double x, double z, double y, float carYaw) {
        double yawRad = Math.toRadians(carYaw);
        double fwdX = -Math.sin(yawRad);
        double fwdZ = Math.cos(yawRad);
        double sideX = fwdZ;
        double sideZ = -fwdX;
        double best = Double.NEGATIVE_INFINITY;
        for (double fl : SUPPORT_LONG) {
            for (double sl : SUPPORT_LAT) {
                double found = contactGround(world, x + fwdX * fl + sideX * sl,
                        z + fwdZ * fl + sideZ * sl, y, MAX_STEP_DOWN);
                if (found > best) {
                    best = found;
                }
            }
        }
        return best;
    }

    /** Sinkgeschwindigkeit im Wasser: naehert sich max-sink-speed an, von oben wie von unten. */
    private double sinkSpeed(double fallSpeed) {
        return config.maxSinkSpeed + (fallSpeed - config.maxSinkSpeed) * WATER_SINK_DAMPING;
    }

    /** Absolute Oberkante der Kollisionsform eines Blocks (volle Bloecke = cellY+1, Slab = +0.5). */
    private double supportTop(Block block, int cellY) {
        double max = 0.0;
        for (BoundingBox bb : block.getCollisionShape().getBoundingBoxes()) {
            max = Math.max(max, bb.getMaxY());
        }
        return cellY + max;
    }

    /** Harte Kollision fuer Log-Zwecke: Lava und solide Bloecke, Wasser laesst durch. */
    private boolean blockedByCar(Block block) {
        Material type = block.getType();
        if (type.isAir() || type == Material.WATER) {
            return false;
        }
        if (type == Material.LAVA) {
            return true;
        }
        return !block.isPassable();
    }

    /** Belastbarer Untergrund: solide und keine Flüssigkeit. */
    private boolean supportsCar(Block block) {
        Material type = block.getType();
        if (type.isAir() || type == Material.WATER || type == Material.LAVA) {
            return false;
        }
        return !block.isPassable();
    }

    private void playUndersteerSound(Car car, World world, Location loc) {
        if (tickCount - car.getLastUndersteerSoundTick() < UNDERSTEER_SOUND_COOLDOWN_TICKS) {
            return;
        }
        car.setLastUndersteerSoundTick(tickCount);
        world.playSound(loc, Sound.ENTITY_HORSE_DEATH, 0.5f, 0.0f);
    }

    private double approachZero(double value, double step) {
        if (value > 0) {
            return Math.max(0, value - step);
        }
        return Math.min(0, value + step);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Winkel in die Spanne (-180, 180] wickeln. */
    private float wrapDeg(float angle) {
        angle %= 360f;
        if (angle > 180f) {
            angle -= 360f;
        } else if (angle <= -180f) {
            angle += 360f;
        }
        return angle;
    }

    private double wrapDeg(double angle) {
        angle %= 360.0;
        if (angle > 180.0) {
            angle -= 360.0;
        } else if (angle <= -180.0) {
            angle += 360.0;
        }
        return angle;
    }

    private int floor(double v) {
        return (int) Math.floor(v);
    }

    private record StepResult(boolean blocked, double x, double y, double z,
                              double impactX, double impactZ, int impactAxis, double impactSpeed) {
        // blocked=true heißt: Route nicht frei; x/z zeigen dann auf den letzten freien Sample-Punkt.
        // impact*: Crash-Daten — Footprint-Grid-Punkt des Blockers, getroffene Achse
        // (0 = kein Aufprall, 1 = X-Wand, 2 = Z-Wand, 3 = anderes Auto) und |Achsen-Geschwindigkeit|.
    }

    private record GravityResult(double y, boolean snapped) {
        // snapped=true heisst: Step-Down-Einrastung statt Fall (Downhill-Assist-Kandidat)
    }
}
