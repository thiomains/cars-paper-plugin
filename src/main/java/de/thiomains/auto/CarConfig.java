package de.thiomains.auto;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Physik-Werte, intern in der Tick-Einheit (Blöcke/Tick) gehalten. Die config.yml
 * verwendet menschenlesbare Einheiten (km/h, m/s², %), reload() rechnet um;
 * /car config <key> <wert> wirkt sofort. Die Key-Listen teilen sich Command-Komplettierung
 * und Config-Migration.
 */
public final class CarConfig {

    public static final List<String> NUMBER_KEYS = List.of(
            "max-speed", "max-reverse-speed", "max-fall-speed", "acceleration", "reverse-acceleration",
            "brake-deceleration", "handbrake-deceleration", "engine-braking", "drag", "max-lateral-grip",
            "turn-curvature", "turn-min-speed", "downhill-assist", "slope-resistance",
            "crash-restitution", "crash-spin", "max-sink-speed",
            "grip-concrete", "grip-grass", "grip-ice", "grip-default", "handbrake-grip"
    );
    public static final List<String> BOOL_KEYS = List.of("understeer-sound", "debug");

    public double maxSpeed;
    public double maxReverseSpeed;
    public double maxFallSpeed;
    public double acceleration;
    public double reverseAcceleration;
    public double brakeDeceleration;
    public double handbrakeDeceleration;
    public double engineBraking;
    public double drag;
    public double maxLatGrip;
    /** Lenkrad-Anschlag: maximale Krümmung der Spur in Grad pro Meter (radlaengen-basiert). */
    public double turnCurvature;
    public double turnMinSpeed;
    public double downhillAssist;
    /** Skalierer des Steigungs-Energieaustauschs (1.0 = physikalisch, 0 = Steigungen gratis). */
    public double slopeResistance;
    /** Anteil der Aufprall-Geschwindigkeit, der an Wänden reflektiert wird (0..0.6). */
    public double crashRestitution;
    /** Skalierer des Crash-Drehimpulses aus dem Aufprall-Hebel (1.0 = Standard). */
    public double crashSpin;
    /** Maximale Sinkgeschwindigkeit in Wasser. */
    public double maxSinkSpeed;
    public double gripConcrete;
    public double gripGrass;
    public double gripIce;
    public double gripDefault;
    public double handbrakeGrip;
    public boolean understeerSound;
    public boolean debug;

    private final JavaPlugin plugin;

    public CarConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();
        // Alle Werte werden beim Laden geclampt: Hand-Edits der config.yml koennen die
        // Physik sonst aus dem Sinn-Bereich werfen (z. B. negativer Grip invertiert approachZero).
        maxSpeed = kmh(clampHumanValue("max-speed", c.getDouble("max-speed", 162.0)));
        maxReverseSpeed = kmh(clampHumanValue("max-reverse-speed", c.getDouble("max-reverse-speed", 8.6)));
        maxFallSpeed = kmh(clampHumanValue("max-fall-speed", c.getDouble("max-fall-speed", 144.0)));
        acceleration = metersPerSecondSquared(clampHumanValue("acceleration", c.getDouble("acceleration", 12.0)));
        reverseAcceleration = metersPerSecondSquared(clampHumanValue("reverse-acceleration", c.getDouble("reverse-acceleration", 3.2)));
        brakeDeceleration = metersPerSecondSquared(clampHumanValue("brake-deceleration", c.getDouble("brake-deceleration", 24.0)));
        handbrakeDeceleration = metersPerSecondSquared(clampHumanValue("handbrake-deceleration", c.getDouble("handbrake-deceleration", 10.0)));
        engineBraking = metersPerSecondSquared(clampHumanValue("engine-braking", c.getDouble("engine-braking", 1.6)));
        drag = percentPerSecondToTick(clampHumanValue("drag", c.getDouble("drag", 1.0)));
        maxLatGrip = metersPerSecondSquared(clampHumanValue("max-lateral-grip", c.getDouble("max-lateral-grip", 22.0)));
        turnMinSpeed = kmh(clampHumanValue("turn-min-speed", c.getDouble("turn-min-speed", 3.6)));
        turnCurvature = clampHumanValue("turn-curvature", c.getDouble("turn-curvature", 40.0));
        downhillAssist = metersPerSecondSquared(clampHumanValue("downhill-assist", c.getDouble("downhill-assist", 6.0)));
        slopeResistance = clampHumanValue("slope-resistance", c.getDouble("slope-resistance", 100.0)) / 100.0;
        crashRestitution = clampHumanValue("crash-restitution", c.getDouble("crash-restitution", 25.0)) / 100.0;
        crashSpin = clampHumanValue("crash-spin", c.getDouble("crash-spin", 100.0)) / 100.0;
        maxSinkSpeed = kmh(clampHumanValue("max-sink-speed", c.getDouble("max-sink-speed", 9.0)));
        gripConcrete = clampHumanValue("grip-concrete", c.getDouble("grip-concrete", 100.0)) / 100.0;
        gripGrass = clampHumanValue("grip-grass", c.getDouble("grip-grass", 50.0)) / 100.0;
        gripIce = clampHumanValue("grip-ice", c.getDouble("grip-ice", 15.0)) / 100.0;
        gripDefault = clampHumanValue("grip-default", c.getDouble("grip-default", 80.0)) / 100.0;
        handbrakeGrip = clampHumanValue("handbrake-grip", c.getDouble("handbrake-grip", 35.0)) / 100.0;
        understeerSound = c.getBoolean("understeer-sound", true);
        debug = c.getBoolean("debug", false);
    }

    /**
     * Sinn-Bereich eines Keys in MENSCHENLESBARER Einheit (wie in config.yml geschrieben).
     * Wird von reload() UND von der Kommando-Anzeige genutzt, damit beide denselben Wert zeigen.
     */
    public static double clampHumanValue(String key, double value) {
        return switch (key) {
            case "slope-resistance" -> clamp(value, 0.0, 200.0);
            case "crash-restitution" -> clamp(value, 0.0, 60.0);
            case "crash-spin" -> clamp(value, 0.0, 400.0);
            case "max-sink-speed" -> Math.max(3.6, value);
            case "drag" -> clamp(value, 0.0, 100.0);
            case "grip-concrete", "grip-grass", "grip-ice", "grip-default", "handbrake-grip" ->
                    clamp(value, 0.0, 150.0);
            default -> Math.max(0.0, value);
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
