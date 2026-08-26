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

import java.util.List;

/**
 * Per-Tick-Fahrphysik aller registrierten Autos.
 * Gas/Bremse über semantischen Input (respektiert Keybinds), Motorbremse ohne Input,
 * mausgesteuerte Lenkung mit geschwindigkeits- und gripabhängiger Rate,
 * Untersteuern bei zu hoher geforderter Rate, 1-Block-Stufen, einfache Gravitation.
 */
public final class DriveTask extends BukkitRunnable {

    private static final double UNDERSTEER_SOUND_COOLDOWN_MS = 300;
    private static final double MAX_FALL_SPEED = 0.5;
    private static final double GRAVITY_ACCEL = 0.08;

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

        double speed = car.getSpeed();
        double speedStart = speed;
        float oldYaw = car.getYaw();
        float yaw = oldYaw;

        double grip = gripCalculator.gripFor(groundMaterial(world, loc.getX(), loc.getY(), loc.getZ()));

        if (driver != null) {
            Input input = driver.getCurrentInput();
            speed = applyInput(speed, input, grip);

            double absSpeed = Math.abs(speed);
            if (absSpeed >= config.turnMinSpeed) {
                double speedScale = Math.min(1.0, absSpeed / (0.25 * config.maxSpeed));
                double rateFactor = config.turnLowSpeedFactor + (1.0 - config.turnLowSpeedFactor) * speedScale;
                double allowed = config.turnRateMax * grip * rateFactor;
                double diff = steerDemand(input, driver, yaw, speed, allowed);
                if (Math.abs(diff) > allowed) {
                    // Untersteuern: Räder drehen nur teilweise ein
                    diff = Math.signum(diff) * allowed;
                    playUndersteerSound(car, world, loc);
                }
                yaw = wrapDeg(yaw + (float) diff);
            }
        }
        // Motorbremse und Luftwiderstand wirken immer — auch unter Gas oder Bremse
        speed = approachZero(speed, config.engineBraking);
        speed -= speed * config.drag;
        double speedAfterLogic = speed;

        double rad = Math.toRadians(yaw);
        double dx = -Math.sin(rad) * speed;
        double dz = Math.cos(rad) * speed;

        double nx = loc.getX() + dx;
        double nz = loc.getZ() + dz;
        double ny = loc.getY();

        double targetX = nx;
        double targetY = ny;
        double targetZ = nz;
        boolean stepBlocked = false;
        if (dx != 0 || dz != 0) {
            StepResult step = resolveStep(world, loc.getX(), loc.getY(), loc.getZ(), nx, nz);
            if (step.blocked()) {
                stepBlocked = true;
                speed = 0;
                nx = loc.getX();
                nz = loc.getZ();
            } else {
                ny = step.y();
            }
        }

        ny = applyGravity(world, nx, ny, nz, car);

        if (config.debug && driver != null && tickCount % 20 == 0) {
            Block bf = world.getBlockAt(floor(targetX), floor(targetY), floor(targetZ));
            Block bh = world.getBlockAt(floor(targetX), floor(targetY) + 1, floor(targetZ));
            Block bhu = world.getBlockAt(floor(targetX), floor(targetY) + 2, floor(targetZ));
            logger.info("[Debug] Kolli: ziel=" + String.format("%.2f %.2f %.2f", targetX, targetY, targetZ)
                    + " grip=" + grip + " ground=" + groundMaterial(world, loc.getX(), loc.getY(), loc.getZ()).name()
                    + " feet=" + bf.getType() + "/" + blockedByCar(bf)
                    + " head=" + bh.getType() + "/" + blockedByCar(bh)
                    + " headUp=" + bhu.getType() + "/" + blockedByCar(bhu));
        }

        car.setSpeed(speed);
        car.setYaw(yaw);

        boolean moved = nx != loc.getX() || ny != loc.getY() || nz != loc.getZ();
        boolean turned = Math.abs(wrapDeg(yaw - oldYaw)) > 0.01f;
        if (moved || turned) {
            base.teleport(new Location(world, nx, ny, nz, yaw, 0f));
            if (turned) {
                // Display rotiert mit, damit das Modell in Fahrtrichtung zeigt
                car.getModel().setRotation(yaw, 0f);
            }
        }

        if (driver != null) {
            if (tickCount % 4 == 0) {
                sendSpeedometer(driver, speed);
            }
            if (config.debug && tickCount % 20 == 0) {
                Input input = driver.getCurrentInput();
                logger.info("[Debug] Fahrer=" + driver.getName()
                        + " speed=" + String.format("%.3f", speed)
                        + " yaw=" + String.format("%.1f", yaw)
                        + " fwd=" + input.isForward() + " bwd=" + input.isBackward()
                        + " pos=" + String.format("%.1f %.1f %.1f", nx, ny, nz));
            }
        }

        if (car.getSimTicks() > 0) {
            car.setSimTicks(car.getSimTicks() - 1);
            Block bf = world.getBlockAt(floor(targetX), floor(targetY), floor(targetZ));
            Block bh = world.getBlockAt(floor(targetX), floor(targetY) + 1, floor(targetZ));
            logger.info("[Sim] t=" + car.getSimTicks()
                    + " cars=" + carManager.size()
                    + " start=" + String.format("%.4f", speedStart)
                    + " logic=" + String.format("%.4f", speedAfterLogic)
                    + " blocked=" + stepBlocked
                    + " feet=" + bf.getType() + "/" + blockedByCar(bf)
                    + " head=" + bh.getType() + "/" + blockedByCar(bh)
                    + " end=" + String.format("%.4f", speed)
                    + " pos=" + String.format("%.3f %.3f %.3f", nx, ny, nz));
            if (car.getSimTicks() == 0) {
                logger.info("[Sim] fertig, Auto entfernt.");
                carManager.removeCar(car, false);
            }
        }
    }

    /** Strich-Tacho in der Actionbar: gefüllte Segmente + km/h (1 Block = 1 m). */
    private void sendSpeedometer(Player driver, double speed) {
        double maxRef = Math.max(config.maxSpeed, config.maxReverseSpeed);
        int filled = (int) Math.round(Math.min(1.0, Math.abs(speed) / maxRef) * 10);
        StringBuilder bar = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        int kmh = (int) Math.round(Math.abs(speed) * 72.0);
        String gear = speed < -0.001 ? " R" : "";
        driver.sendActionBar(Component.text(bar + "  " + kmh + " km/h" + gear, NamedTextColor.GOLD));
    }

    /**
     * Arcade-Fahrmodell: Am Stand beschleunigt W vorwärts und S rückwärts; bei Bewegung bremst
     * die jeweils entgegengesetzte Taste mit voller Bremskraft. Motorbremse/Luftwiderstand laufen zentral.
     */
    private double applyInput(double speed, Input input, double grip) {
        boolean forward = input.isForward();
        boolean backward = input.isBackward();

        if (forward && backward) {
            speed = approachZero(speed, config.brakeDeceleration * grip);
        } else if (forward) {
            if (speed < -0.01) {
                speed = Math.min(speed + config.brakeDeceleration * grip, 0);
            } else {
                speed = Math.min(speed + config.acceleration * grip, config.maxSpeed);
            }
        } else if (backward) {
            if (speed > 0.01) {
                speed = Math.max(speed - config.brakeDeceleration * grip, 0);
            } else {
                speed = Math.max(speed - config.reverseAcceleration * grip, -config.maxReverseSpeed);
            }
        }
        return speed;
    }

    /** Geforderte Lenkrate in Grad/Tick: A/D hat Vorrang, sonst Mausfolge (optional abschaltbar);
     *  die Rückwärts-Invertierung gilt für beide Eingabewege, wenn der Spieler sie nicht deaktiviert hat. */
    private double steerDemand(Input input, Player driver, float yaw, double speed, double allowed) {
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
        if (speed < 0 && prefs.reverseInvert(driver.getUniqueId())) {
            diff = -diff;
        }
        return diff;
    }

    /** Untergrund, auf dem das Auto steht (Block unter der Fahrzeugmitte). */
    private Material groundMaterial(World world, double x, double y, double z) {
        int gy = (int) Math.floor(y - 0.05);
        return world.getBlockAt(floor(x), gy, floor(z)).getType();
    }

    /**
     * Prüft, ob die Route frei ist. Bei hohen Geschwindigkeiten werden mehrere Punkte
     * entlang der Strecke abgetastet, sonst würden dünne Hindernisse durchtunnelt werden.
     * Blockiert der erste belegte Punkt, versucht das Auto genau dort 1 Block hoch zu fahren.
     */
    private static final double SAMPLE_STEP = 0.4;

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
        if (!config.understeerSound) {
            return;
        }
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

    private int floor(double v) {
        return (int) Math.floor(v);
    }

    private record StepResult(boolean blocked, double y) {
        // blocked=true heißt: nicht bewegen; y ist die (ggf. um 1 erhöhte) Zielhöhe
    }
}
