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

/**
 * Per-Tick-Fahrphysik aller registrierten Autos.
 * Zustand ist ein Geschwindigkeitsvektor (velX/velZ) plus Fahrwerk-Yaw. Antrieb und Bremse
 * wirken auf die Fahrtrichtungs-Komponente; Motorbremse und Luftwiderstand wirken immer.
 * Die Lenkung dreht den Yaw; der Geschwindigkeitsvektor folgt dem Fahrwerk begrenzt durch
 * das laterale Grip-Budget (max-lateral-grip × Oberflächen-Grip): zu enges Lenken bei Tempo
 * lässt den Weg dem Fahrwerk hinterherhinken — das ist der Schlupf (quer rutschen).
 */
public final class DriveTask extends BukkitRunnable {

    private static final double UNDERSTEER_SOUND_COOLDOWN_MS = 300;
    private static final double MAX_FALL_SPEED = 0.5;
    private static final double GRAVITY_ACCEL = 0.08;
    private static final double ALIGN_FRACTION = 0.65;
    private static final double SPEED_EPSILON = 0.05;
    private static final double SLIP_SOUND_MIN_DEG = 12.0;
    private static final double SAMPLE_STEP = 0.4;

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
        double lx = vx - vf * fx;
        double lz = vz - vf * fz;

        double grip = gripCalculator.gripFor(groundMaterial(world, loc.getX(), loc.getY(), loc.getZ()));

        if (driver != null) {
            Input input = driver.getCurrentInput();
            vf = applyInput(vf, input, grip);

            if (startAbs >= config.turnMinSpeed) {
                // Lenkrate: Lenkrad-Deckel UND physikalische Grip-Grenze (Budget ÷ Tempo)
                double gripCapDeg = Math.toDegrees((config.maxLatGrip * grip) / Math.max(startAbs, SPEED_EPSILON));
                double allowed = Math.min(config.turnRateMax, gripCapDeg);
                double diff = steerDemand(input, driver, yaw, vf, allowed);
                double applied = Math.abs(diff) > allowed ? Math.signum(diff) * allowed : diff;
                yaw = wrapDeg(yaw + (float) applied);
            }
        }

        // Simulations-Driftmodus: erzwungene Drehung ohne Fahrer, um Schlupf zu provozieren
        if (car.isSimDrift() && driver == null) {
            yaw = wrapDeg(yaw + 6f);
        }

        // Motorbremse auf die Fahrtrichtungskomponente, Luftwiderstand auf den ganzen Vektor
        vf = approachZero(vf, config.engineBraking);
        vx = vf * fx + lx;
        vz = vf * fz + lz;
        vx -= vx * config.drag;
        vz -= vz * config.drag;

        // Der Weg folgt dem Fahrwerk begrenzt um das laterale Budget mal Nachlauf-Faktor.
        double abs = Math.hypot(vx, vz);
        if (abs > SPEED_EPSILON) {
            double velAngle = wrapDeg((float) Math.toDegrees(Math.atan2(-vx, vz)));
            double budgetDeg = Math.toDegrees((config.maxLatGrip * grip) / Math.max(abs, SPEED_EPSILON)) * ALIGN_FRACTION;
            double angleDiff = wrapDeg(yaw - velAngle);
            double alignStep = Math.abs(angleDiff) > budgetDeg ? Math.signum(angleDiff) * budgetDeg : angleDiff;
            double newAng = Math.toRadians(wrapDeg(velAngle + (float) alignStep));
            vx = -Math.sin(newAng) * abs;
            vz = Math.cos(newAng) * abs;
        }
        double velAngleAfter = wrapDeg((float) Math.toDegrees(Math.atan2(-vx, vz)));
        double slipDeg = Math.abs(wrapDeg(yaw - velAngleAfter));

        double targetX = loc.getX() + vx;
        double targetZ = loc.getZ() + vz;
        double targetY = loc.getY();
        boolean stepBlocked = false;
        if (vx != 0 || vz != 0) {
            StepResult step = resolveStep(world, loc.getX(), targetY, loc.getZ(), targetX, targetZ);
            if (step.blocked()) {
                stepBlocked = true;
                targetX = loc.getX();
                targetZ = loc.getZ();
                vx = 0;
                vz = 0;
            } else {
                targetY = step.y();
            }
        }

        targetY = applyGravity(world, targetX, targetY, targetZ, car);

        car.setVelX(vx);
        car.setVelZ(vz);
        car.setYaw(yaw);

        if (config.debug && driver != null && tickCount % 20 == 0) {
            Block bf = world.getBlockAt(floor(targetX), floor(targetY), floor(targetZ));
            Block bh = world.getBlockAt(floor(targetX), floor(targetY) + 1, floor(targetZ));
            Input input = driver.getCurrentInput();
            logger.info("[Debug] Fahrer=" + driver.getName()
                    + " vf=" + String.format("%.3f", vf) + " |v|=" + String.format("%.3f", Math.hypot(vx, vz))
                    + " slip=" + String.format("%.1f", slipDeg) + "° yaw=" + String.format("%.1f", yaw)
                    + " fwd=" + input.isForward() + " bwd=" + input.isBackward()
                    + " grip=" + grip + " ground=" + groundMaterial(world, loc.getX(), loc.getY(), loc.getZ()).name()
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

        if (driver != null) {
            if (tickCount % 4 == 0) {
                sendSpeedometer(driver, Math.hypot(vx, vz), vf);
            }
            double speedNow = Math.hypot(vx, vz);
            if (config.understeerSound && slipDeg > SLIP_SOUND_MIN_DEG && speedNow > config.turnMinSpeed * 2) {
                playUndersteerSound(car, world, loc);
            }
        }

        if (car.getSimTicks() > 0) {
            car.setSimTicks(car.getSimTicks() - 1);
            Block bf = world.getBlockAt(floor(targetX), floor(targetY), floor(targetZ));
            Block bh = world.getBlockAt(floor(targetX), floor(targetY) + 1, floor(targetZ));
            logger.info("[Sim] t=" + car.getSimTicks()
                    + " cars=" + carManager.size()
                    + " start=" + String.format("%.4f", startAbs)
                    + " vf=" + String.format("%.4f", vf)
                    + " slip=" + String.format("%.1f", slipDeg)
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
                vf = Math.min(vf + config.acceleration * grip, config.maxSpeed);
            }
        } else if (backward) {
            if (vf > 0.01) {
                vf = Math.max(vf - config.brakeDeceleration * grip, 0);
            } else {
                vf = Math.max(vf - config.reverseAcceleration * grip, -config.maxReverseSpeed);
            }
        }
        return vf;
    }

    /** Strich-Tacho in der Actionbar: gefüllte Segmente + km/h (1 Block = 1 m); R bei Rückwärtskomponente. */
    private void sendSpeedometer(Player driver, double speedAbs, double vf) {
        double maxRef = Math.max(config.maxSpeed, config.maxReverseSpeed);
        int filled = (int) Math.round(Math.min(1.0, speedAbs / maxRef) * 10);
        StringBuilder bar = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        int kmh = (int) Math.round(speedAbs * 72.0);
        String gear = vf < -0.001 ? " R" : "";
        driver.sendActionBar(Component.text(bar + "  " + kmh + " km/h" + gear, NamedTextColor.GOLD));
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

    /** Untergrund, auf dem das Auto steht (Block unter der Fahrzeugmitte). */
    private Material groundMaterial(World world, double x, double y, double z) {
        int gy = (int) Math.floor(y - 0.05);
        return world.getBlockAt(floor(x), gy, floor(z)).getType();
    }

    private StepResult resolveStep(World world, double fromX, double fromY, double fromZ,
                                   double toX, double toZ) {
        double distX = toX - fromX;
        double distZ = toZ - fromZ;
        double dist = Math.hypot(distX, distZ);
        if (dist <= 0) {
            return new StepResult(false, fromY);
        }
        int samples = Math.max(1, (int) Math.ceil(dist / SAMPLE_STEP));
        double stepX = distX / samples;
        double stepZ = distZ / samples;
        for (int i = 1; i <= samples; i++) {
            double sx = fromX + stepX * i;
            double sz = fromZ + stepZ * i;
            int bx = floor(sx);
            int by = floor(fromY);
            int bz = floor(sz);

            Block feet = world.getBlockAt(bx, by, bz);
            Block head = world.getBlockAt(bx, by + 1, bz);
            if (!blockedByCar(feet) && !blockedByCar(head)) {
                continue;
            }
            Block feetUp = world.getBlockAt(bx, by + 1, bz);
            Block headUp = world.getBlockAt(bx, by + 2, bz);
            boolean canStepUp = !blockedByCar(feetUp) && !blockedByCar(headUp) && feet.getType().isSolid();
            if (canStepUp) {
                // Stufe: ab hier 1 Block höher weiterfahren
                return new StepResult(false, fromY + 1.0);
            }
            return new StepResult(true, fromY);
        }
        return new StepResult(false, fromY);
    }

    /** Einfache Gravitation: Fallen beschleunigt bis MAX_FALL_SPEED, Landen snappt auf die Blockoberkante. */
    private double applyGravity(World world, double x, double y, double z, Car car) {
        int gy = (int) Math.floor(y - 0.05);
        Block below = world.getBlockAt(floor(x), gy, floor(z));
        if (supportsCar(below)) {
            car.setFallSpeed(0);
            double groundTop = gy + 1.0;
            // Kurze Strecke nach unten (z. B. nach Step-down) direkt einrasten
            if (y > groundTop && y - groundTop < 0.35) {
                return groundTop;
            }
            return y;
        }

        double fallSpeed = Math.min(car.getFallSpeed() + GRAVITY_ACCEL, MAX_FALL_SPEED);
        double newY = y - fallSpeed;
        int newGy = (int) Math.floor(newY - 0.05);
        Block newBelow = world.getBlockAt(floor(x), newGy, floor(z));
        if (supportsCar(newBelow)) {
            car.setFallSpeed(0);
            return newGy + 1.0;
        }
        car.setFallSpeed(fallSpeed);
        return newY;
    }

    /** Für Kollision: Wasser/Lava gelten als blockierend, damit Autos nicht ins Wasser fahren. */
    private boolean blockedByCar(Block block) {
        Material type = block.getType();
        if (type.isAir()) {
            return false;
        }
        if (type == Material.WATER || type == Material.LAVA) {
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
        long now = System.currentTimeMillis();
        if (now - car.getLastUndersteerSoundMs() < UNDERSTEER_SOUND_COOLDOWN_MS) {
            return;
        }
        car.setLastUndersteerSoundMs(now);
        world.playSound(loc, Sound.BLOCK_WOOL_BREAK, 0.35f, 1.6f);
    }

    private double approachZero(double value, double step) {
        if (value > 0) {
            return Math.max(0, value - step);
        }
        return Math.min(0, value + step);
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

    private record StepResult(boolean blocked, double y) {
        // blocked=true heißt: nicht bewegen; y ist die (ggf. um 1 erhöhte) Zielhöhe
    }
}
