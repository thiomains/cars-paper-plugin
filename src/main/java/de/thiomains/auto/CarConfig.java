package de.thiomains.auto;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Physik-Werte, intern in der Tick-Einheit (Blöcke/Tick) gehalten. Die config.yml
 * verwendet menschenlesbare Einheiten (km/h, m/s², %), reload() rechnet um;
 * /auto config set wirkt sofort.
 */
public final class CarConfig {

    public double maxSpeed;
    public double maxReverseSpeed;
    public double acceleration;
    public double reverseAcceleration;
    public double brakeDeceleration;
    public double engineBraking;
    public double drag;
    public double turnRateMax;
    public double turnMinSpeed;
    public double turnLowSpeedFactor;
    public double gripConcrete;
    public double gripGrass;
    public double gripDefault;
    public boolean understeerSound;
    public boolean debug;

    private final JavaPlugin plugin;

    public CarConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();
        maxSpeed = kmh(c.getDouble("max-speed", 162.0));
        maxReverseSpeed = kmh(c.getDouble("max-reverse-speed", 8.6));
        acceleration = metersPerSecondSquared(c.getDouble("acceleration", 12.0));
        reverseAcceleration = metersPerSecondSquared(c.getDouble("reverse-acceleration", 3.2));
        brakeDeceleration = metersPerSecondSquared(c.getDouble("brake-deceleration", 24.0));
        engineBraking = metersPerSecondSquared(c.getDouble("engine-braking", 1.6));
        drag = percentPerSecondToTick(c.getDouble("drag", 1.0));
        turnRateMax = c.getDouble("turn-rate-max", 140.0) / 20.0;
        turnMinSpeed = kmh(c.getDouble("turn-min-speed", 3.6));
        turnLowSpeedFactor = c.getDouble("turn-low-speed-factor", 45.0) / 100.0;
        gripConcrete = c.getDouble("grip-concrete", 100.0) / 100.0;
        gripGrass = c.getDouble("grip-grass", 50.0) / 100.0;
        gripDefault = c.getDouble("grip-default", 80.0) / 100.0;
        understeerSound = c.getBoolean("understeer-sound", true);
        debug = c.getBoolean("debug", false);
    }

    private static double kmh(double v) {
        // 1 Block/Tick = 72 km/h (1 Block = 1 m, 20 Ticks/s, *3.6)
        return v / 72.0;
    }

    private static double metersPerSecondSquared(double v) {
        // 1 Block = 1 m, 20 Ticks/s -> m/s² in Block/Tick² = v / 400
        return v / 400.0;
    }

    private static double percentPerSecondToTick(double percent) {
        double clamped = Math.min(100.0, Math.max(0.0, percent));
        return 1.0 - Math.pow(1.0 - clamped / 100.0, 1.0 / 20.0);
    }
}
