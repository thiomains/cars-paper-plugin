package de.thiomains.auto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

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
    private static final double GRAVITY_ACCEL = 0.08;
    private static final double ALIGN_FRACTION = 0.65;
    private static final double FRICTION_FRACTION = 0.5;
    private static final double SPEED_EPSILON = 0.05;
    private static final double SLIP_SOUND_MIN_DEG = 12.0;
    private static final double SAMPLE_STEP = 0.4;
    static final double MAX_STEP = 1.0;
    private static final double LONG_HALF = 1.25;
    private static final double LAT_HALF = 0.9;
    private static final double CAR_COLLISION_RADIUS = 1.4;
    private static final double STANDSTILL_SPEED = 0.007; // ~0,5 km/h
    private static final double STANDSTILL_MIN_GRIP = 0.4; // darunter (glatt) rollt das Auto aus statt zu rasten
    private static final double WATER_DRAG = 0.10;
    private static final double LANDING_SOUND_MIN_FALL = 0.5; // ~36 km/h vertikal
    private static final double LANDING_SPEED_KEEP = 0.7;
    static final double MAX_STEP_DOWN = 1.2;
    private static final double CRAWL_TURN_DEG = 2.0; // Rangier-Lenkrate bei Stillstand-Kontakt
    private static final double OVERSPEED_DOWNHILL_FACTOR = 1.5;
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
    private static final double[] GRID_LONG = {-LONG_HALF, 0.0, LONG_HALF};
    private static final double[] GRID_LAT = {-LAT_HALF, 0.0, LAT_HALF};
    private static final double[] WHEEL_LONG = {-0.9, 0.9};
    private static final double[] WHEEL_LAT = {-0.7, 0.7};

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

        // Bodenkontakt bestimmt alles Weitere: nur am Boden gibt es Grip und Kraefte.
        // Grip/grounded kommen aus vier Rad-Samples (yaw-ausgerichtet, innerhalb des
        // Footprints) mit einem Block Federungstoleranz (siehe wheelSupport); nicht
        // gestuetzte Raeder zaehlen mit 0 in die Division durch 4: haengt die halbe
        // Karosse ueber einer echten Kante, halbiert sich der wirksame Grip.
        int gy = floor(loc.getY() - 0.05);
        Block below = world.getBlockAt(floor(loc.getX()), gy, floor(loc.getZ()));
        Material groundType = below.getType();
        int supported = 0;
        double gripSum = 0.0;
        for (double wLong : WHEEL_LONG) {
            for (double wLat : WHEEL_LAT) {
                Block support = wheelSupport(world, floor(loc.getX() + fx * wLong + fz * wLat), gy,
                        floor(loc.getZ() + fz * wLong - fx * wLat), loc.getY());
                if (support != null) {
                    supported++;
                    gripSum += gripCalculator.gripFor(support.getType());
                }
            }
        }
        boolean grounded = supported >= 1;
        double grip = grounded ? gripSum / 4.0 : 0.0;
        double gripEff = grip;

        Input input = driver != null ? driver.getCurrentInput() : null;
        boolean pedals = input != null && (input.isForward() || input.isBackward());
        boolean handbrake = grounded && input != null && input.isJump() && startAbs > SPEED_EPSILON;
        double steerDemandDeg = 0.0;
        double vfBeforeForces = vf;

        if (driver != null && grounded) {
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

        // Simulations-Gas: Gas geben ohne Fahrer (Spiegelbild des W-Falls aus applyInput),
        // damit das Losfahren aus dem Stand headless verifizierbar bleibt
        if (car.isSimDrive() && driver == null && grounded) {
            vf = Math.min(vf + config.acceleration * grip
                    * clamp(1.0 - vf / Math.max(config.maxSpeed, 1.0e-9), 0.0, 1.0), config.maxSpeed);
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
        GravityResult gravity = applyGravity(world, targetX, targetY, targetZ, car, wantsMove);
        targetY = gravity.y();
        // Steigungs-Energie: einmal pro Tick am Gesamt-dy. Bergauf kostet kinetische Energie
        // (2·g·dy), bergab wird sie symmetrisch gewonnen; downhill-assist bleibt als ein
        // Arcade-Bonus und skaliert mit dem echten Gefaelle des Ticks (volle Wirkung ab 0,5
        // Block Gefaelle pro Tick). Nicht bei echtem Fall (fallBefore == 0 UND fallSpeed == 0):
        // eine Landung aus einem Fall darf keinen Schub erzeugen.
        if (wantsMove && fallBefore == 0 && car.getFallSpeed() == 0) {
            double dy = targetY - loc.getY();
            double speed = Math.hypot(vx, vz);
            if (dy != 0 && speed > 1.0e-9) {
                double v2 = vx * vx + vz * vz;
                double v2New = v2 - 2.0 * GRAVITY_ACCEL * config.slopeResistance * dy;
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

        // Modell-Neigung und Quer-Neigung, beides EMA-geglaettet. Reine Optik — die Physik
        // bleibt davon unberuehrt. Pitch = Steigung (Vorzeichen nach Sichtpruefung geflippt)
        // plus Squat/Dive aus der Pedal-Kraft (Gas: Nase hoch, Bremse: Nase runter); Roll =
        // Tempo × Drehrate. Sichtpruefungs-Stand: Steigungs-Term und Roll korrekt, der
        // Pedal-Term stand Kopf und ist daher invertiert (Minus davor!) — nicht die Achsen
        // pauschal flippen.
        double horizDist = Math.hypot(targetX - loc.getX(), targetZ - loc.getZ());
        double pitchGoal = (grounded && horizDist > 1.0e-9
                ? -Math.toDegrees(Math.atan2(targetY - loc.getY(), horizDist)) : 0.0)
                - clamp(longForce * PITCH_ACCEL_DEG, -PITCH_ACCEL_MAX_DEG, PITCH_ACCEL_MAX_DEG);
        double pitch = clamp(car.getLastPitchDeg() + (pitchGoal - car.getLastPitchDeg()) * 0.3, -25.0, 25.0);
        double rollGoal = clamp(-200.0 * Math.hypot(vx, vz) * Math.toRadians(car.getYawVel()), -12.0, 12.0);
        double roll = clamp(car.getLastRollDeg() + (rollGoal - car.getLastRollDeg()) * 0.3, -12.0, 12.0);
        if (Math.abs(pitch - car.getLastPitchDeg()) > 0.5 || Math.abs(roll - car.getLastRollDeg()) > 0.5) {
            car.getModel().setTransformation(new Transformation(
                    new Vector3f(0f, CarManager.MODEL_Y_OFFSET, 0f),
                    new Quaternionf().rotationX((float) Math.toRadians(pitch))
                            .rotateZ((float) Math.toRadians(roll)),
                    new Vector3f(CarManager.MODEL_SCALE, CarManager.MODEL_SCALE, CarManager.MODEL_SCALE),
                    new Quaternionf()));
            car.setLastPitchDeg(pitch);
            car.setLastRollDeg(roll);
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
     *  die Rückwärts-Invertierung gilt für beide Eingabewege, wenn der Spieler sie nicht deaktiviert hat. */
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
        } else if (prefs.mouseSteer(driver.getUniqueId())) {
            diff = wrapDeg(driver.getLocation().getYaw() - yaw);
        } else {
            diff = 0;
        }
        if (vf < 0 && prefs.reverseInvert(driver.getUniqueId())) {
            diff = -diff;
        }
        return diff;
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
            double obstacleTop = Double.NEGATIVE_INFINITY;
            double obstaclePx = sx;
            double obstaclePz = sz;
            int by = floor(curY);
            for (double fl : GRID_LONG) {
                for (double sl : GRID_LAT) {
                    double px = sx + fwdX * fl + sideX * sl;
                    double pz = sz + fwdZ * fl + sideZ * sl;
                    double columnTop = columnObstacleTop(world, floor(px), by, floor(pz));
                    if (columnTop > obstacleTop) {
                        obstacleTop = columnTop;
                        obstaclePx = px;
                        obstaclePz = pz;
                    }
                }
            }
            if (obstacleTop > curY + MAX_STEP) {
                return new StepResult(true, freeX, curY, freeZ, obstaclePx, obstaclePz, axis, dist);
            }
            if (obstacleTop > curY + 1.0e-4) {
                if (!canStandAt(world, sx, obstacleTop, sz, fwdX, fwdZ, sideX, sideZ)) {
                    return new StepResult(true, freeX, curY, freeZ, obstaclePx, obstaclePz, axis, dist);
                }
                curY = obstacleTop;
            }
            // Gelaendeverfolgung nach unten: kurze Abstiege nimmt die Mitte direkt mit,
            // sonst fliegt das Auto bei mehreren Zellen pro Tick den Abhang ballistisch herab.
            curY = followGroundDown(world, sx, sz, curY, canSnapDown);
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

    /** Boden bei (x,z) auf Hoehe curY oder bis MAX_STEP_DOWN darunter; sonst unveraendert. */
    private double followGroundDown(World world, double x, double z, double curY, boolean canSnapDown) {
        int by = floor(curY - 0.05);
        Block direct = world.getBlockAt(floor(x), by, floor(z));
        if (supportsCar(direct)) {
            double top = supportTop(direct, by);
            if (curY - top < 0.4) {
                return top;
            }
        }
        if (!canSnapDown) {
            return curY;
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
        return curY;
    }

    /**
     * Oberkante des hoechsten Hindernisses in der Fuss-Zelle (by) eines Wegpunkts.
     * Luft, Wasser und passierbare Bloecke gelten als frei; Lava und jede Belegung der
     * Kopf-Zelle (by+1) sind unüberwindbar (+Unendlich), alles andere ist ggf. eine Stufe.
     */
    private double columnObstacleTop(World world, int bx, int by, int bz) {
        Block feet = world.getBlockAt(bx, by, bz);
        Material feetType = feet.getType();
        if (feetType == Material.LAVA) {
            return Double.POSITIVE_INFINITY;
        }
        double top = Double.NEGATIVE_INFINITY;
        if (!feetType.isAir() && feetType != Material.WATER && !feet.isPassable()) {
            top = supportTop(feet, by);
        }
        Block head = world.getBlockAt(bx, by + 1, bz);
        Material headType = head.getType();
        if (!headType.isAir() && headType != Material.WATER && !head.isPassable()) {
            return Double.POSITIVE_INFINITY;
        }
        return top;
    }

    /** Prueft nach einem Stufenaufstieg, ob der ganze Footprint auf dem Zielniveau frei steht. */
    private boolean canStandAt(World world, double x, double y, double z,
                               double fwdX, double fwdZ, double sideX, double sideZ) {
        for (double fl : GRID_LONG) {
            for (double sl : GRID_LAT) {
                int bx = floor(x + fwdX * fl + sideX * sl);
                int bz = floor(z + fwdZ * fl + sideZ * sl);
                int fy = floor(y);
                Block lower = world.getBlockAt(bx, fy, bz);
                Material lowerType = lower.getType();
                if (lowerType == Material.LAVA) {
                    return false;
                }
                if (!lowerType.isAir() && lowerType != Material.WATER && !lower.isPassable()
                        && supportTop(lower, fy) > y + 1.0e-4) {
                    return false;
                }
                Block upper = world.getBlockAt(bx, fy + 1, bz);
                Material upperType = upper.getType();
                if (!upperType.isAir() && upperType != Material.WATER && !upper.isPassable()) {
                    return false;
                }
            }
        }
        return true;
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
    private GravityResult applyGravity(World world, double x, double y, double z, Car car, boolean moved) {
        int gy = floor(y - 0.05);
        Block below = world.getBlockAt(floor(x), gy, floor(z));
        if (supportsCar(below)) {
            double groundTop = supportTop(below, gy);
            // Kurze Reststrecke (z. B. nach Step-down) direkt einrasten; aus dem Block herausheben
            if (y - groundTop < 0.4) {
                car.setFallSpeed(0);
                return new GravityResult(groundTop, false);
            }
            return new GravityResult(y, false);
        }

        // Step-Down: vorher geerdet (kein aktiver Fall) und kurz darunter stuetzt Boden
        if (car.getFallSpeed() == 0 && moved) {
            for (double d = 0.2; d <= MAX_STEP_DOWN; d += 0.2) {
                int sy = floor(y - d - 0.05);
                Block b2 = world.getBlockAt(floor(x), sy, floor(z));
                if (supportsCar(b2)) {
                    double top = supportTop(b2, sy);
                    if (top <= y && y - top <= MAX_STEP_DOWN) {
                        return new GravityResult(top, true);
                    }
                }
            }
        }

        double fallSpeed = Math.min(car.getFallSpeed() + GRAVITY_ACCEL, config.maxFallSpeed);
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
            // Wasser traegt nicht, bremst aber den Fall asymptotisch Richtung max-sink-speed
            if (world.getBlockAt(floor(x), floor(candidate), floor(z)).getType() == Material.WATER) {
                fallSpeed = config.maxSinkSpeed + (fallSpeed - config.maxSinkSpeed) * 0.85;
            }
            remaining -= step;
        }
        car.setFallSpeed(fallSpeed);
        return new GravityResult(newY, false);
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

    /** Rad-Auflage: Boden auf Fahrzeug-Niveau (Kontaktabstand < 0.05) ODER maximal einen
     *  Block tiefer (Federungs-Toleranz, damit z. B. ein Rad neben einem eine Zeile
     *  tieferen Gehweg weiter traegt — erst darueber gilt das Rad als haengend). */
    private Block wheelSupport(org.bukkit.World world, int wx, int gy, int wz, double carY) {
        Block level = world.getBlockAt(wx, gy, wz);
        if (supportsCar(level) && carY - supportTop(level, gy) < 0.05) {
            return level;
        }
        Block lower = world.getBlockAt(wx, gy - 1, wz);
        if (supportsCar(lower)) {
            double drop = carY - supportTop(lower, gy - 1);
            if (drop >= 0.05 && drop <= 1.05) {
                return lower;
            }
        }
        return null;
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
