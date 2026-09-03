package de.thiomains.auto;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

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
            "crash-restitution", "crash-spin", "crash-transfer", "tip-acceleration", "max-sink-speed",
            "grip-concrete", "grip-grass", "grip-ice", "grip-default", "handbrake-grip",
            "horn-pitch", "horn-range",
            "impact-damage", "impact-min-speed", "impact-knockback"
    );
    public static final List<String> BOOL_KEYS = List.of(
            "understeer-sound-enabled", "field-damage-enabled", "debug", "debug-wheels");
    /** Keys mit freiem Text als Wert. Dritte Kategorie neben Zahlen und Schaltern: Anzeige,
     *  Autocomplete, Permission-Nodes und Migration lesen alle drei Listen. */
    public static final List<String> STRING_KEYS = List.of("horn-sound");

    /** Fallback der Hupe. Als Konstante statt als Name, damit ein Tippfehler nicht erst im
     *  Spiel auffaellt — der Compiler kennt sie. */
    public static final Sound DEFAULT_HORN_SOUND = Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO;

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
    /** Anteil der Aufprall-Geschwindigkeit, der auf das getroffene Auto uebergeht. */
    public double crashTransfer;
    /** Schub zur unbelasteten Seite, wenn weniger als drei Raeder tragen (Abkippen). */
    public double tipAcceleration;
    /** Maximale Sinkgeschwindigkeit in Wasser. */
    public double maxSinkSpeed;
    public double gripConcrete;
    public double gripGrass;
    public double gripIce;
    public double gripDefault;
    public double handbrakeGrip;
    public boolean understeerSound;
    /** Pflanzen brechen beim Umfahren, Ackerland wird unter den Raedern zu Erde. */
    public boolean fieldDamage;
    /** Schaden beim Anfahren, gerechnet bei 100 km/h (0 = Lebewesen bleiben unbehelligt). */
    public double impactDamage;
    /** Ab diesem Tempo tut das Anfahren weh (Bloecke/Tick). */
    public double impactMinSpeed;
    /** Anteil der Fahrzeuggeschwindigkeit, der als Stoss weitergegeben wird. */
    public double impactKnockback;
    /** Sound der Hupe (Config-Key horn-sound, aufgeloest ueber die Sound-Registry). */
    public Sound hornSound;
    public double hornPitch;
    /** Hoerweite der Hupe in Bloecken; playSound rechnet daraus die Lautstaerke. */
    public double hornRange;
    public boolean debug;
    /** Zeigt Rad-Aufstandspunkte und Karosserie-Raster als Partikel (live umschaltbar). */
    public boolean debugWheels;

    private final JavaPlugin plugin;

    public CarConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();
        // Alle Werte werden beim Laden geclampt: Hand-Edits der config.yml koennen die
        // Physik sonst aus dem Sinn-Bereich werfen (z. B. negativer Grip invertiert approachZero).
        maxSpeed = kmh(clampHumanValue("max-speed", c.getDouble("max-speed", 170.0)));
        maxReverseSpeed = kmh(clampHumanValue("max-reverse-speed", c.getDouble("max-reverse-speed", 20.0)));
        maxFallSpeed = kmh(clampHumanValue("max-fall-speed", c.getDouble("max-fall-speed", 144.0)));
        acceleration = metersPerSecondSquared(clampHumanValue("acceleration", c.getDouble("acceleration", 5.0)));
        reverseAcceleration = metersPerSecondSquared(clampHumanValue("reverse-acceleration", c.getDouble("reverse-acceleration", 2.0)));
        brakeDeceleration = metersPerSecondSquared(clampHumanValue("brake-deceleration", c.getDouble("brake-deceleration", 8.0)));
        handbrakeDeceleration = metersPerSecondSquared(clampHumanValue("handbrake-deceleration", c.getDouble("handbrake-deceleration", 6.0)));
        engineBraking = metersPerSecondSquared(clampHumanValue("engine-braking", c.getDouble("engine-braking", 1.2)));
        drag = percentPerSecondToTick(clampHumanValue("drag", c.getDouble("drag", 3.5)));
        maxLatGrip = metersPerSecondSquared(clampHumanValue("max-lateral-grip", c.getDouble("max-lateral-grip", 18.0)));
        turnMinSpeed = kmh(clampHumanValue("turn-min-speed", c.getDouble("turn-min-speed", 0.0)));
        turnCurvature = clampHumanValue("turn-curvature", c.getDouble("turn-curvature", 30.0));
        downhillAssist = metersPerSecondSquared(clampHumanValue("downhill-assist", c.getDouble("downhill-assist", 6.0)));
        slopeResistance = clampHumanValue("slope-resistance", c.getDouble("slope-resistance", 10.0)) / 100.0;
        crashRestitution = clampHumanValue("crash-restitution", c.getDouble("crash-restitution", 25.0)) / 100.0;
        crashSpin = clampHumanValue("crash-spin", c.getDouble("crash-spin", 100.0)) / 100.0;
        crashTransfer = clampHumanValue("crash-transfer", c.getDouble("crash-transfer", 60.0)) / 100.0;
        tipAcceleration = metersPerSecondSquared(clampHumanValue("tip-acceleration", c.getDouble("tip-acceleration", 16.0)));
        maxSinkSpeed = kmh(clampHumanValue("max-sink-speed", c.getDouble("max-sink-speed", 9.0)));
        gripConcrete = clampHumanValue("grip-concrete", c.getDouble("grip-concrete", 100.0)) / 100.0;
        gripGrass = clampHumanValue("grip-grass", c.getDouble("grip-grass", 50.0)) / 100.0;
        gripIce = clampHumanValue("grip-ice", c.getDouble("grip-ice", 10.0)) / 100.0;
        gripDefault = clampHumanValue("grip-default", c.getDouble("grip-default", 70.0)) / 100.0;
        handbrakeGrip = clampHumanValue("handbrake-grip", c.getDouble("handbrake-grip", 50.0)) / 100.0;
        understeerSound = c.getBoolean("understeer-sound-enabled", false);
        fieldDamage = c.getBoolean("field-damage-enabled", true);
        hornPitch = clampHumanValue("horn-pitch", c.getDouble("horn-pitch", 0.5));
        hornRange = clampHumanValue("horn-range", c.getDouble("horn-range", 80.0));
        hornSound = resolveHornSound(c.getString("horn-sound"));
        impactDamage = clampHumanValue("impact-damage", c.getDouble("impact-damage", 12.0));
        impactMinSpeed = kmh(clampHumanValue("impact-min-speed", c.getDouble("impact-min-speed", 15.0)));
        impactKnockback = clampHumanValue("impact-knockback", c.getDouble("impact-knockback", 60.0)) / 100.0;
        debug = c.getBoolean("debug", false);
        debugWheels = c.getBoolean("debug-wheels", false);
    }

    /**
     * Sinn-Bereich eines Keys in MENSCHENLESBARER Einheit (wie in config.yml geschrieben).
     * Wird von reload() UND von der Kommando-Anzeige genutzt, damit beide denselben Wert zeigen.
     */
    public static double clampHumanValue(String key, double value) {
        return switch (key) {
            // Tempo (km/h). Der Deckel ist kein Geschmacksurteil, sondern Serverschutz:
            // resolveStep tastet die Route in 0,4-Bloecke-Schritten ab, also kostet jedes km/h
            // Substeps mal neun Rasterpunkte mal zwei Achsen — pro Tick und Auto.
            case "max-speed", "max-fall-speed" -> clamp(value, 0.0, 500.0);
            case "max-reverse-speed" -> clamp(value, 0.0, 200.0);
            case "max-sink-speed" -> clamp(value, 3.6, 200.0);
            case "turn-min-speed" -> clamp(value, 0.0, 100.0);
            // Laengs- und Querkraefte (m/s²)
            case "acceleration", "reverse-acceleration", "brake-deceleration",
                 "handbrake-deceleration", "max-lateral-grip" -> clamp(value, 0.0, 200.0);
            case "engine-braking", "downhill-assist", "tip-acceleration" -> clamp(value, 0.0, 100.0);
            // Lenkrad-Anschlag in Grad pro Meter: darueber dreht sich das Auto im Substep um
            // mehr als eine halbe Umdrehung und die Spur wird unansehnlich.
            case "turn-curvature" -> clamp(value, 0.0, 180.0);
            case "slope-resistance" -> clamp(value, 0.0, 200.0);
            case "crash-restitution" -> clamp(value, 0.0, 60.0);
            case "crash-spin" -> clamp(value, 0.0, 400.0);
            case "crash-transfer" -> clamp(value, 0.0, 200.0);
            case "drag" -> clamp(value, 0.0, 100.0);
            // Pitch-Bereich des Protokolls; 0 ist erlaubt und gewollt (siehe understeer-sound-enabled).
            case "horn-pitch" -> clamp(value, 0.0, 2.0);
            // Schaden in Schadenspunkten (2 = ein Herz) bei 100 km/h; 20 legt einen Spieler
            // ohne Ruestung genau um, mehr als 200 waere nur noch Zahlenspielerei.
            case "impact-damage" -> clamp(value, 0.0, 200.0);
            case "impact-min-speed" -> clamp(value, 0.0, 200.0);
            case "impact-knockback" -> clamp(value, 0.0, 300.0);
            // Obergrenze ist Serverschutz, kein Geschmack: der Sound geht an JEDEN Spieler im Radius.
            case "horn-range" -> clamp(value, 1.0, 160.0);
            case "grip-concrete", "grip-grass", "grip-ice", "grip-default", "handbrake-grip" ->
                    clamp(value, 0.0, 150.0);
            // Absichtlich OHNE Obergrenze: ein neuer Zahlen-Key soll hier auffallen
            // (der Selftest-Fall config-obergrenzen prueft genau das).
            default -> Math.max(0.0, value);
        };
    }

    /**
     * Sound-Name aus der Config in einen Sound. Unbekannte oder kaputte Namen sind kein
     * Grund, das Plugin lahmzulegen: eine Warnung, dann der Default — eine hand-editierte
     * config.yml darf reload() nicht sprengen.
     */
    private Sound resolveHornSound(String name) {
        Sound sound = lookupSound(name);
        if (sound != null) {
            return sound;
        }
        if (name != null && !name.isBlank()) {
            plugin.getLogger().warning("Unbekannter Sound in horn-sound: \"" + name
                    + "\" — es gilt " + soundName(DEFAULT_HORN_SOUND) + ".");
        }
        return DEFAULT_HORN_SOUND;
    }

    /** Sound zu einem Namen wie "minecraft:block.note_block.bass" oder null. */
    public static Sound lookupSound(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(name.trim().toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.SOUNDS.get(key);
    }

    /** Umkehrung von lookupSound — der Name, den /car config anzeigt. */
    public static String soundName(Sound sound) {
        return Registry.SOUNDS.getKeyOrThrow(sound).asString();
    }

    /**
     * Die ausgelieferte config.yml direkt aus dem Jar-Ressourcenordner — unabhaengig von einer
     * evtl. schon auf der Platte stehenden Datei. Basis fuer /car config reset.
     */
    public static YamlConfiguration shippedDefaults(JavaPlugin plugin) {
        try (java.io.InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            return null;
        }
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
