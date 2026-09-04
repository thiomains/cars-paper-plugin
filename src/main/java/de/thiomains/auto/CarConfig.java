package de.thiomains.auto;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
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
            "understeer-pitch", "understeer-range", "understeer-cooldown", "understeer-min-slip",
            "landing-hard-speed", "landing-speed-keep", "landing-pitch", "landing-range",
            "tire-smoke-grip",
            "water-drag", "mouse-deadzone", "mouse-full-lock", "crawl-turn-rate",
            "standstill-speed", "standstill-min-grip",
            "crash-rebound-max", "crash-min-speed", "car-push-max", "crash-spin-max",
            "horn-pitch", "horn-range", "horn-cooldown",
            "impact-damage", "impact-min-speed", "impact-knockback", "impact-knockback-max",
            "impact-lift"
    );
    public static final List<String> BOOL_KEYS = List.of(
            "understeer-sound-enabled", "field-damage-enabled", "debug", "debug-wheels");
    /** Keys mit freiem Text als Wert. Dritte Kategorie neben Zahlen und Schaltern: Anzeige,
     *  Autocomplete, Permission-Nodes und Migration lesen alle drei Listen. */
    public static final List<String> STRING_KEYS = List.of(
            "horn-sound", "understeer-sound", "landing-sound");

    /** Fallbacks der Sound-Keys. Als Konstanten statt als Namen, damit ein Tippfehler nicht
     *  erst im Spiel auffaellt — der Compiler kennt sie. */
    public static final Sound DEFAULT_HORN_SOUND = Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO;
    public static final Sound DEFAULT_UNDERSTEER_SOUND = Sound.ENTITY_HORSE_DEATH;
    public static final Sound DEFAULT_LANDING_SOUND = Sound.ENTITY_GENERIC_BIG_FALL;

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
    /** Sound des Quietschens (Config-Key understeer-sound). */
    public Sound understeerSoundName;
    public double understeerPitch;
    /** Hoerweite des Quietschens in Bloecken; playSound rechnet daraus die Lautstaerke. */
    public double understeerRange;
    /** Pause zwischen zwei Quietschern in Ticks (Config-Wert steht in Sekunden). */
    public double understeerCooldownTicks;
    /** Schraeglaufwinkel in Grad, ab dem es quietscht. */
    public double understeerMinSlip;
    /** Ab dieser Fallgeschwindigkeit ist die Landung hart (Bloecke/Tick). */
    public double landingHardSpeed;
    /** Anteil der Quergeschwindigkeit, den eine harte Landung uebrig laesst. */
    public double landingSpeedKeep;
    /** Sound der harten Landung (Config-Key landing-sound). */
    public Sound landingSound;
    public double landingPitch;
    /** Hoerweite der Landung in Bloecken. */
    public double landingRange;
    /** Grip-Verbrauch, ab dem die Reifen qualmen (Traktionskreis, 1.0 = Rad am Limit). Der
     *  Default liegt knapp unter dem Limit statt genau darauf, sonst wirkt der Rauch bei
     *  Vollgasstarts sprunghaft an/aus. */
    public double tireSmokeGrip;
    /** Geschwindigkeitsverlust je Tick im Wasser. */
    public double waterDrag;
    /** Totzone der Mauslenkung in Grad. */
    public double mouseDeadzone;
    /** Winkel zur Karosserie, ab dem das Lenkrad voll anliegt (Grad). */
    public double mouseFullLock;
    /** Rangier-Lenkrate an der Wand in Grad je Tick. */
    public double crawlTurnRate;
    /** Darunter rastet das Auto in den Stillstand (Bloecke/Tick). */
    public double standstillSpeed;
    /** ... aber nur ab diesem Grip; darunter (glatt) rollt das Auto aus statt zu rasten. */
    public double standstillMinGrip;
    /** Deckel des Wand-Rueckprallers (Bloecke/Tick) — ohne ihn rollt der Rueckprall ewig weiter. */
    public double crashReboundMax;
    /** Darunter gibt es weder Rueckpraller noch Andrehen, sondern den ruhigen Rangier-Stopp
     *  (Bloecke/Tick). */
    public double crashMinSpeed;
    /** Deckel des Impulses beim Auto-Auto-Crash (Bloecke/Tick): ein Rempler schiebt beiseite,
     *  er katapultiert nicht. */
    public double carPushMax;
    /** Deckel der Crash-Drehrate in Grad je Tick, gegen unansehnliche Vollrotation. */
    public double crashSpinMax;
    /** Pflanzen brechen beim Umfahren, Ackerland wird unter den Raedern zu Erde. */
    public boolean fieldDamage;
    /** Schaden beim Anfahren, gerechnet bei 100 km/h (0 = Lebewesen bleiben unbehelligt). */
    public double impactDamage;
    /** Ab diesem Tempo tut das Anfahren weh (Bloecke/Tick). */
    public double impactMinSpeed;
    /** Anteil der Fahrzeuggeschwindigkeit, der als Stoss weitergegeben wird. */
    public double impactKnockback;
    /** Deckel des Stosses beim Anfahren (Bloecke/Tick) — sonst fliegt ein Schaf ueber die
     *  halbe Karte. */
    public double impactKnockbackMax;
    /** Anteil des Stosses, der nach oben geht. Nicht Deko, sondern der Grund, warum man vom
     *  Stoss ueberhaupt etwas sieht: am Boden frisst die Reibung die Querbewegung binnen
     *  weniger Ticks (0,6 je Tick), in der Luft nur 0,09. Wer angefahren wird, hebt ab und
     *  fliegt dann weit — ohne Auftrieb bleibt es bei einem halben Block Geschubse. */
    public double impactLift;
    /** Sound der Hupe (Config-Key horn-sound, aufgeloest ueber die Sound-Registry). */
    public Sound hornSound;
    public double hornPitch;
    /** Hoerweite der Hupe in Bloecken; playSound rechnet daraus die Lautstaerke. */
    public double hornRange;
    /** Pause zwischen zwei Hupern in Ticks (Config-Wert steht in Sekunden). */
    public double hornCooldownTicks;
    public boolean debug;
    /** Zeigt Rad-Aufstandspunkte und Karosserie-Raster als Partikel (live umschaltbar). */
    public boolean debugWheels;

    private final JavaPlugin plugin;
    /** Was beim letzten reload() stillschweigend haette passieren koennen — jede Korrektur
     *  einmal im Klartext. Wird geloggt UND von /car reload an den Aufrufer zurueckgegeben:
     *  ein ignorierter Wert, den niemand sieht, kostet sonst eine Stunde Fehlersuche. */
    private final List<String> lastCorrections = new ArrayList<>();

    public CarConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Korrekturen des letzten reload(), in Lesereihenfolge. */
    public List<String> getLastCorrections() {
        return List.copyOf(lastCorrections);
    }

    private void correction(String message) {
        lastCorrections.add(message);
        plugin.getLogger().warning("config.yml — " + message);
    }

    /**
     * Zahlenwert lesen und dabei sagen, was schiefging: ein Wert vom falschen Typ
     * (z. B. {@code max-speed: schnell}) faellt sonst still auf den Default zurueck, und ein
     * Wert ausserhalb des Sinnbereichs wird still geklemmt. Beides sieht der Admin nirgends —
     * genau das ist die Stunde Fehlersuche, die wir uns hier sparen.
     */
    private double number(FileConfiguration c, String key, double def) {
        Object raw = c.get(key);
        double value;
        if (raw == null) {
            value = def;
        } else if (raw instanceof Number n) {
            value = n.doubleValue();
        } else {
            correction(key + ": \"" + raw + "\" ist keine Zahl — es gilt " + def);
            value = def;
        }
        double clamped = clampHumanValue(key, value);
        if (clamped != value) {
            correction(key + ": " + value + " liegt ausserhalb des Sinnbereichs — es gilt " + clamped);
        }
        return clamped;
    }

    /** Schalter lesen, mit derselben Ansage bei falschem Typ. */
    private boolean bool(FileConfiguration c, String key, boolean def) {
        Object raw = c.get(key);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        correction(key + ": \"" + raw + "\" ist kein Schalter (true/false) — es gilt " + def);
        return def;
    }

    /** Keys in der Datei, die das Plugin nicht kennt — meist Tippfehler, wirken nie. */
    private void reportUnknownKeys(FileConfiguration c) {
        for (String key : c.getKeys(false)) {
            if (key.equals("config-version") || NUMBER_KEYS.contains(key)
                    || BOOL_KEYS.contains(key) || STRING_KEYS.contains(key)) {
                continue;
            }
            correction(key + ": unbekannter Key, wird ignoriert (Tippfehler?)");
        }
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();
        lastCorrections.clear();
        // Alle Werte werden beim Laden geclampt: Hand-Edits der config.yml koennen die
        // Physik sonst aus dem Sinn-Bereich werfen (z. B. negativer Grip invertiert approachZero).
        maxSpeed = kmh(number(c, "max-speed", 170.0));
        maxReverseSpeed = kmh(number(c, "max-reverse-speed", 20.0));
        maxFallSpeed = kmh(number(c, "max-fall-speed", 144.0));
        acceleration = metersPerSecondSquared(number(c, "acceleration", 5.0));
        reverseAcceleration = metersPerSecondSquared(number(c, "reverse-acceleration", 2.0));
        brakeDeceleration = metersPerSecondSquared(number(c, "brake-deceleration", 8.0));
        handbrakeDeceleration = metersPerSecondSquared(number(c, "handbrake-deceleration", 6.0));
        engineBraking = metersPerSecondSquared(number(c, "engine-braking", 1.2));
        drag = percentPerSecondToTick(number(c, "drag", 3.5));
        maxLatGrip = metersPerSecondSquared(number(c, "max-lateral-grip", 18.0));
        turnMinSpeed = kmh(number(c, "turn-min-speed", 0.0));
        turnCurvature = number(c, "turn-curvature", 30.0);
        downhillAssist = metersPerSecondSquared(number(c, "downhill-assist", 6.0));
        slopeResistance = number(c, "slope-resistance", 10.0) / 100.0;
        crashRestitution = number(c, "crash-restitution", 25.0) / 100.0;
        crashSpin = number(c, "crash-spin", 100.0) / 100.0;
        crashTransfer = number(c, "crash-transfer", 60.0) / 100.0;
        tipAcceleration = metersPerSecondSquared(number(c, "tip-acceleration", 16.0));
        maxSinkSpeed = kmh(number(c, "max-sink-speed", 9.0));
        gripConcrete = number(c, "grip-concrete", 100.0) / 100.0;
        gripGrass = number(c, "grip-grass", 50.0) / 100.0;
        gripIce = number(c, "grip-ice", 10.0) / 100.0;
        gripDefault = number(c, "grip-default", 70.0) / 100.0;
        handbrakeGrip = number(c, "handbrake-grip", 50.0) / 100.0;
        understeerSound = bool(c, "understeer-sound-enabled", false);
        understeerSoundName = resolveSound(c, "understeer-sound", DEFAULT_UNDERSTEER_SOUND);
        understeerPitch = number(c, "understeer-pitch", 0.0);
        understeerRange = number(c, "understeer-range", 8.0);
        understeerCooldownTicks = ticks(number(c, "understeer-cooldown", 0.9));
        understeerMinSlip = number(c, "understeer-min-slip", 12.0);
        landingHardSpeed = kmh(number(c, "landing-hard-speed", 36.0));
        landingSpeedKeep = number(c, "landing-speed-keep", 70.0) / 100.0;
        landingSound = resolveSound(c, "landing-sound", DEFAULT_LANDING_SOUND);
        landingPitch = number(c, "landing-pitch", 1.0);
        landingRange = number(c, "landing-range", 12.8);
        tireSmokeGrip = number(c, "tire-smoke-grip", 85.0) / 100.0;
        waterDrag = percentPerSecondToTick(number(c, "water-drag", 87.8));
        mouseDeadzone = number(c, "mouse-deadzone", 4.0);
        mouseFullLock = number(c, "mouse-full-lock", 90.0);
        crawlTurnRate = perSecond(number(c, "crawl-turn-rate", 40.0));
        standstillSpeed = kmh(number(c, "standstill-speed", 0.5));
        standstillMinGrip = number(c, "standstill-min-grip", 40.0) / 100.0;
        crashReboundMax = kmh(number(c, "crash-rebound-max", 7.2));
        crashMinSpeed = kmh(number(c, "crash-min-speed", 5.0));
        carPushMax = kmh(number(c, "car-push-max", 36.0));
        crashSpinMax = perSecond(number(c, "crash-spin-max", 360.0));
        fieldDamage = bool(c, "field-damage-enabled", true);
        hornPitch = number(c, "horn-pitch", 0.5);
        hornRange = number(c, "horn-range", 80.0);
        hornCooldownTicks = ticks(number(c, "horn-cooldown", 0.5));
        hornSound = resolveSound(c, "horn-sound", DEFAULT_HORN_SOUND);
        impactDamage = number(c, "impact-damage", 12.0);
        impactMinSpeed = kmh(number(c, "impact-min-speed", 15.0));
        impactKnockback = number(c, "impact-knockback", 60.0) / 100.0;
        impactKnockbackMax = kmh(number(c, "impact-knockback-max", 50.4));
        impactLift = number(c, "impact-lift", 50.0) / 100.0;
        debug = bool(c, "debug", false);
        debugWheels = bool(c, "debug-wheels", false);
        reportUnknownKeys(c);
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
            // Tempo-Schwellen und -Deckel (km/h). Alle unkritisch fuer die Serverlast,
            // aber jeder Zahlen-Key braucht eine Grenze (siehe default-Zweig).
            case "landing-hard-speed", "crash-rebound-max", "car-push-max",
                 "impact-knockback-max" -> clamp(value, 0.0, 200.0);
            case "crash-min-speed", "standstill-speed" -> clamp(value, 0.0, 100.0);
            // Laengs- und Querkraefte (m/s²)
            case "acceleration", "reverse-acceleration", "brake-deceleration",
                 "handbrake-deceleration", "max-lateral-grip" -> clamp(value, 0.0, 200.0);
            case "engine-braking", "downhill-assist", "tip-acceleration" -> clamp(value, 0.0, 100.0);
            // Lenkrad-Anschlag in Grad pro Meter: darueber dreht sich das Auto im Substep um
            // mehr als eine halbe Umdrehung und die Spur wird unansehnlich.
            case "turn-curvature" -> clamp(value, 0.0, 180.0);
            case "slope-resistance" -> clamp(value, 0.0, 200.0);
            // Anteile in Prozent
            case "landing-speed-keep", "standstill-min-grip" -> clamp(value, 0.0, 100.0);
            case "tire-smoke-grip", "impact-lift" -> clamp(value, 0.0, 300.0);
            // Wasser bremst wie drag: derselbe %/s-Bereich
            case "water-drag" -> clamp(value, 0.0, 100.0);
            // Winkel in Grad. mouse-full-lock darf nicht auf die Totzone fallen — die
            // Lenkkennlinie teilt durch die Differenz (applyInput faengt den Rest ab).
            case "understeer-min-slip", "mouse-deadzone" -> clamp(value, 0.0, 90.0);
            case "mouse-full-lock" -> clamp(value, 1.0, 180.0);
            // Drehraten in Grad pro Sekunde: mehr als eine halbe Umdrehung je Tick
            // (3600 °/s) ergibt nur noch Bildrauschen.
            case "crawl-turn-rate" -> clamp(value, 0.0, 3600.0);
            case "crash-spin-max" -> clamp(value, 0.0, 3600.0);
            // Cooldowns in Sekunden: eine Minute Pause ist schon jenseits von "selten".
            case "understeer-cooldown", "horn-cooldown" -> clamp(value, 0.0, 60.0);
            case "crash-restitution" -> clamp(value, 0.0, 60.0);
            case "crash-spin" -> clamp(value, 0.0, 400.0);
            case "crash-transfer" -> clamp(value, 0.0, 200.0);
            case "drag" -> clamp(value, 0.0, 100.0);
            // Pitch-Bereich des Protokolls; 0 ist erlaubt und gewollt (siehe understeer-sound-enabled).
            case "horn-pitch", "understeer-pitch", "landing-pitch" -> clamp(value, 0.0, 2.0);
            // Schaden in Schadenspunkten (2 = ein Herz) bei 100 km/h; 20 legt einen Spieler
            // ohne Ruestung genau um, mehr als 200 waere nur noch Zahlenspielerei.
            case "impact-damage" -> clamp(value, 0.0, 200.0);
            case "impact-min-speed" -> clamp(value, 0.0, 200.0);
            case "impact-knockback" -> clamp(value, 0.0, 300.0);
            // Obergrenze ist Serverschutz, kein Geschmack: der Sound geht an JEDEN Spieler im Radius.
            case "horn-range" -> clamp(value, 1.0, 160.0);
            // Dieselbe Obergrenze; 0 ist hier erlaubt und heisst schlicht "stumm".
            case "understeer-range", "landing-range" -> clamp(value, 0.0, 160.0);
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
    private Sound resolveSound(FileConfiguration c, String key, Sound def) {
        String name = c.getString(key);
        Sound sound = lookupSound(name);
        if (sound != null) {
            return sound;
        }
        if (name != null && !name.isBlank()) {
            correction(key + ": \"" + name + "\" ist kein Sound der Registry — es gilt "
                    + soundName(def));
        }
        return def;
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

    /** Sekunden -> Ticks (20 TPS). Bewusst als double: der Selftest rechnet die Einheit
     *  aus dem Rohwert nach, eine Rundung auf ganze Ticks liefe da auseinander. */
    private static double ticks(double seconds) {
        return seconds * 20.0;
    }

    /** Pro Sekunde -> pro Tick (Drehraten in Grad/s). */
    private static double perSecond(double v) {
        return v / 20.0;
    }

    private static double percentPerSecondToTick(double percent) {
        double clamped = Math.min(100.0, Math.max(0.0, percent));
        return 1.0 - Math.pow(1.0 - clamped / 100.0, 1.0 / 20.0);
    }
}
