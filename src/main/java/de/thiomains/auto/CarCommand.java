package de.thiomains.auto;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * /car (Alias /auto) mit help|prefs|give|config|sim. Als Paper-Plugin werden Befehle zur
 * Laufzeit via registerCommand registriert statt per YAML deklariert.
 * Die Werte von /car config sind menschenlesbar (km/h, m/s², %); Lesen braucht car.config,
 * Setzen zusätzlich car.config.<key>. /car sim ist internes Testwerkzeug und nur auf der
 * Konsole erreichbar (weder Autocomplete noch Ausführung für Spieler).
 */
public final class CarCommand implements BasicCommand {

    private static final List<String> NUMBER_KEYS = CarConfig.NUMBER_KEYS;
    private static final List<String> BOOL_KEYS = CarConfig.BOOL_KEYS;
    private static final List<String> STRING_KEYS = CarConfig.STRING_KEYS;
    private static final List<String> MAX_100_KEYS = List.of(
            "drag", "water-drag", "grip-concrete", "grip-grass", "grip-ice", "grip-default",
            "handbrake-grip", "landing-speed-keep", "standstill-min-grip");

    private static final List<String> PREF_KEYS = List.of(
            "mouse_steer", "reverse_invert", "actionbar", "actionbar_speed", "actionbar_grip");

    /** Ein Unterbefehl mit seiner Node; speist Hilfe, Rechteprüfung und Autocomplete. */
    private record Sub(String name, String usage, String description, String permission, boolean consoleOnly) {
    }

    private static final List<Sub> SUBS = List.of(
            new Sub("help", "/car help", "Diese Übersicht", CarPermissions.USE, false),
            new Sub("prefs", "/car prefs [<key> [on|off]]", "Eigene Fahreinstellungen", CarPermissions.PREFS, false),
            new Sub("give", "/car give", "Auto-Item ins Inventar", CarPermissions.GIVE, false),
            new Sub("config", "/car config [<key> [wert|reset]] | reset", "Fahrwerte anzeigen/ändern/zuruecksetzen", CarPermissions.CONFIG, false),
            new Sub("reload", "/car reload", "Config von der Platte neu einlesen (Hand-Edits ohne Neustart)",
                    CarPermissions.CONFIG_ALL, false),
            new Sub("sim", "/car sim <speed> [drift] [gap] [ice] [stairs] [drive]",
                    "Headless-Testfahrt (nur Konsole)", null, true),
            new Sub("selftest", "/car selftest [--verbose] [muster]",
                    "Automatische Verifikation (nur Konsole)", null, true));

    private final JavaPlugin plugin;
    private final CarManager carManager;
    private final CarConfig carConfig;
    private final PlayerPrefs prefs;

    public CarCommand(JavaPlugin plugin, CarManager carManager, CarConfig carConfig, PlayerPrefs prefs) {
        this.plugin = plugin;
        this.carManager = carManager;
        this.carConfig = carConfig;
        this.prefs = prefs;
    }

    /** Ergebnis der Rechteprüfung; von execute() und vom SelfTest identisch genutzt. */
    public record Decision(Kind kind, String sub, String node) {
        public enum Kind { HELP, ALLOW, MISSING_PERMISSION, CONSOLE_ONLY, UNKNOWN }
    }

    /**
     * Entscheidet ohne CommandSender, ob ein Aufruf durchgehen darf — damit die
     * Rechte-Matrix headless prüfbar ist (siehe SelfTest). Reihenfolge: car.use, dann
     * Konsolen-Bindung, dann die Node des Unterbefehls, zuletzt car.config.&lt;key&gt; beim Setzen.
     */
    public static Decision decide(String[] args, boolean console, java.util.function.Predicate<String> has) {
        if (!has.test(CarPermissions.USE)) {
            return new Decision(Decision.Kind.MISSING_PERMISSION, args.length == 0 ? "help" : args[0],
                    CarPermissions.USE);
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            return new Decision(Decision.Kind.HELP, "help", null);
        }
        String name = args[0].toLowerCase();
        Sub sub = SUBS.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElse(null);
        if (sub == null) {
            return new Decision(Decision.Kind.UNKNOWN, name, null);
        }
        if (sub.consoleOnly() && !console) {
            return new Decision(Decision.Kind.CONSOLE_ONLY, name, null);
        }
        if (sub.permission() != null && !has.test(sub.permission())) {
            return new Decision(Decision.Kind.MISSING_PERMISSION, name, sub.permission());
        }
        if (name.equals("config") && args.length == 2 && args[1].equalsIgnoreCase("reset")) {
            // Voller Reset aendert JEDEN Key, also die Sammel-Node und nicht nur car.config
            // (das deckt bisher nur Lesen ab).
            if (!has.test(CarPermissions.CONFIG_ALL)) {
                return new Decision(Decision.Kind.MISSING_PERMISSION, name, CarPermissions.CONFIG_ALL);
            }
        }
        if (name.equals("config") && args.length >= 3) {
            String key = args[1].toLowerCase();
            if ((NUMBER_KEYS.contains(key) || BOOL_KEYS.contains(key) || STRING_KEYS.contains(key))
                    && !has.test(CarPermissions.config(key))) {
                return new Decision(Decision.Kind.MISSING_PERMISSION, name, CarPermissions.config(key));
            }
        }
        return new Decision(Decision.Kind.ALLOW, name, null);
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        Decision decision = decide(args, sender instanceof ConsoleCommandSender, sender::hasPermission);
        switch (decision.kind()) {
            case HELP -> sendHelp(sender);
            case MISSING_PERMISSION -> noPermission(sender, decision.node());
            case CONSOLE_ONLY -> sender.sendMessage(Component.text("/car " + decision.sub()
                    + " ist ein internes Testwerkzeug und läuft nur über die Server-Konsole.", NamedTextColor.RED));
            case UNKNOWN -> {
                sender.sendMessage(Component.text("Unbekannter Unterbefehl: " + args[0], NamedTextColor.RED));
                sendHelp(sender);
            }
            case ALLOW -> {
                switch (decision.sub()) {
                    case "config" -> handleConfig(sender, args);
                    case "reload" -> handleReload(sender);
                    case "prefs" -> handlePrefs(sender, args);
                    case "give" -> handleGive(sender);
                    case "sim" -> runSim(sender, args);
                    case "selftest" -> runSelfTest(sender, args);
                    default -> sendHelp(sender);
                }
            }
        }
    }

    /** Plugin-Kopf plus die Unterbefehle, die dieser Sender wirklich nutzen darf. */
    private void sendHelp(CommandSender sender) {
        var meta = plugin.getPluginMeta();
        String authors = String.join(", ", meta.getAuthors());
        sender.sendMessage(Component.text(meta.getName() + " v" + meta.getVersion()
                + (authors.isEmpty() ? "" : " — von " + authors), NamedTextColor.GOLD));
        String description = meta.getDescription();
        if (description != null && !description.isBlank()) {
            sender.sendMessage(Component.text(description, NamedTextColor.GRAY));
        }
        int pad = SUBS.stream().filter(sub -> allowed(sender, sub)).mapToInt(sub -> sub.usage().length()).max().orElse(0);
        for (Sub sub : SUBS) {
            if (!allowed(sender, sub)) {
                continue;
            }
            sender.sendMessage(Component.text("  " + sub.usage(), NamedTextColor.YELLOW)
                    .append(Component.text(" ".repeat(pad - sub.usage().length() + 2) + sub.description(),
                            NamedTextColor.GRAY)));
        }
    }

    private boolean allowed(CommandSender sender, Sub sub) {
        if (sub.consoleOnly()) {
            return sender instanceof ConsoleCommandSender;
        }
        return sub.permission() == null || sender.hasPermission(sub.permission());
    }

    private void noPermission(CommandSender sender, String permission) {
        sender.sendMessage(Component.text("Dazu fehlt dir die Berechtigung (" + permission + ").", NamedTextColor.RED));
    }

    /** Spielerweite Fahreinstellungen: /car prefs [<key> [on|off]], akzeptiert true/false/on/off/an/aus. */
    private void handlePrefs(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Fahreinstellungen gibt es nur pro Spieler.", NamedTextColor.RED));
            return;
        }
        if (args.length == 1) {
            player.sendMessage(Component.text("Deine Fahreinstellungen:", NamedTextColor.GOLD));
            for (String key : PREF_KEYS) {
                player.sendMessage(Component.text("  " + key + " = "
                        + (prefValue(player.getUniqueId(), key) ? "an" : "aus"), NamedTextColor.YELLOW));
            }
            return;
        }
        String key = args[1].toLowerCase();
        if (!PREF_KEYS.contains(key)) {
            player.sendMessage(Component.text("Unbekannter Key: " + args[1], NamedTextColor.RED));
            player.sendMessage(Component.text("Gültig: " + String.join(", ", PREF_KEYS), NamedTextColor.YELLOW));
            return;
        }
        if (args.length == 2) {
            boolean current = prefValue(player.getUniqueId(), key);
            player.sendMessage(Component.text(key + " = " + (current ? "an" : "aus"), NamedTextColor.YELLOW));
            return;
        }
        if (args.length == 3) {
            Boolean value = parseToggle(args[2]);
            if (value == null) {
                player.sendMessage(Component.text("Gültig: true/false/on/off/an/aus", NamedTextColor.RED));
                return;
            }
            setPref(player.getUniqueId(), key, value);
            player.sendMessage(Component.text(key + " ist jetzt " + (value ? "an" : "aus") + ".", NamedTextColor.GREEN));
            return;
        }
        player.sendMessage(Component.text("Verwendung: /car prefs [<key> [on|off]]", NamedTextColor.YELLOW));
    }

    private void setPref(java.util.UUID playerId, String key, boolean value) {
        switch (key) {
            case "mouse_steer" -> prefs.setMouseSteer(playerId, value);
            case "reverse_invert" -> prefs.setReverseInvert(playerId, value);
            case "actionbar" -> prefs.setActionbar(playerId, value);
            case "actionbar_speed" -> prefs.setActionbarSpeed(playerId, value);
            case "actionbar_grip" -> prefs.setActionbarGrip(playerId, value);
            default -> throw new IllegalArgumentException("unbekannter Pref-Key: " + key);
        }
    }

    private boolean prefValue(java.util.UUID playerId, String key) {
        return switch (key) {
            case "mouse_steer" -> prefs.mouseSteer(playerId);
            case "reverse_invert" -> prefs.reverseInvert(playerId);
            case "actionbar" -> prefs.actionbar(playerId);
            case "actionbar_speed" -> prefs.actionbarSpeed(playerId);
            case "actionbar_grip" -> prefs.actionbarGrip(playerId);
            default -> throw new IllegalArgumentException("unbekannter Pref-Key: " + key);
        };
    }

    private Boolean parseToggle(String raw) {
        return switch (raw.toLowerCase()) {
            case "true", "on", "an", "ein", "1" -> true;
            case "false", "off", "aus", "0" -> false;
            default -> null;
        };
    }

    private void handleGive(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Nur für Spieler.", NamedTextColor.RED));
            return;
        }
        ItemStack item = CarItem.createCarItem(carManager.getCarKey());
        var leftover = player.getInventory().addItem(item);
        leftover.values().forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        player.sendMessage(Component.text("Du hast ein Auto erhalten. Rechtsklick auf den Boden zum Platzieren.", NamedTextColor.GREEN));
    }

    /** /car config [<key> [wert]] — Lesen deckt car.config ab, Setzen braucht car.config.<key>. */
    private void handleConfig(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sender.sendMessage(Component.text("Auto-Config:", NamedTextColor.GOLD));
            for (String key : NUMBER_KEYS) {
                sender.sendMessage(Component.text("  " + key + " = " + humanValue(key), NamedTextColor.YELLOW));
            }
            for (String key : BOOL_KEYS) {
                sender.sendMessage(Component.text("  " + key + " = " + boolValue(key), NamedTextColor.YELLOW));
            }
            for (String key : STRING_KEYS) {
                sender.sendMessage(Component.text("  " + key + " = " + stringValue(key), NamedTextColor.YELLOW));
            }
            return;
        }
        String key = args[1].toLowerCase();
        if (key.equals("get") || key.equals("set")) {
            sender.sendMessage(Component.text("»get«/»set« entfallen — nutze /car config [<key> [wert]].",
                    NamedTextColor.YELLOW));
            return;
        }
        if (key.equals("reset") && args.length == 2) {
            handleConfigResetAll(sender);
            return;
        }
        boolean isNumber = NUMBER_KEYS.contains(key);
        boolean isString = STRING_KEYS.contains(key);
        if (!isNumber && !isString && !BOOL_KEYS.contains(key)) {
            unknownKey(sender, args[1]);
            return;
        }
        if (args.length == 2) {
            String shown = isNumber ? humanValue(key) : isString ? stringValue(key) : String.valueOf(boolValue(key));
            sender.sendMessage(Component.text(key + " = " + shown, NamedTextColor.YELLOW));
            return;
        }
        if (args.length != 3) {
            sender.sendMessage(Component.text("Verwendung: /car config [<key> [wert]]", NamedTextColor.YELLOW));
            return;
        }
        if (!sender.hasPermission(CarPermissions.config(key))) {
            noPermission(sender, CarPermissions.config(key));
            return;
        }
        String raw = args[2];
        boolean resetting = raw.equalsIgnoreCase("reset");
        String suffix = resetting ? " (Default, live aktiv)" : " (live aktiv)";
        if (isNumber) {
            double value;
            if (resetting) {
                YamlConfiguration shipped = shippedOrWarn(sender);
                if (shipped == null || !shipped.isSet(key)) {
                    resetKeyMissing(sender, shipped, key);
                    return;
                }
                value = shipped.getDouble(key);
            } else {
                try {
                    value = Double.parseDouble(raw.replace(',', '.'));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Ungültige Zahl: " + raw, NamedTextColor.RED));
                    return;
                }
                if (!Double.isFinite(value) || value < 0 || (MAX_100_KEYS.contains(key) && value > 100)) {
                    sender.sendMessage(Component.text("Wert ungültig für " + key + " (erlaubt: 0"
                            + (MAX_100_KEYS.contains(key) ? "–100" : " und größer") + ").", NamedTextColor.RED));
                    return;
                }
            }
            String old = humanValue(key);
            plugin.getConfig().set(key, value);
            plugin.saveConfig();
            carConfig.reload();
            sender.sendMessage(Component.text(key + ": " + old + " → " + value + unitSuffix(key) + suffix, NamedTextColor.GREEN));
            return;
        }
        if (isString) {
            String value;
            if (resetting) {
                YamlConfiguration shipped = shippedOrWarn(sender);
                if (shipped == null || !shipped.isSet(key)) {
                    resetKeyMissing(sender, shipped, key);
                    return;
                }
                value = shipped.getString(key);
            } else {
                // Bisher ist jeder String-Key ein Sound-Name; ein neuer Key mit anderer Bedeutung
                // braucht hier eine eigene Pruefung statt der Registry-Abfrage.
                Sound sound = CarConfig.lookupSound(raw);
                if (sound == null) {
                    sender.sendMessage(Component.text("Unbekannter Sound: " + raw, NamedTextColor.RED));
                    sender.sendMessage(Component.text("Beispiel: minecraft:block.note_block.bass",
                            NamedTextColor.YELLOW));
                    return;
                }
                // Normalisiert speichern (Namensraum ergaenzt, klein), damit Anzeige und Datei gleich lauten.
                value = CarConfig.soundName(sound);
            }
            String old = stringValue(key);
            plugin.getConfig().set(key, value);
            plugin.saveConfig();
            carConfig.reload();
            sender.sendMessage(Component.text(key + ": " + old + " → " + value + suffix, NamedTextColor.GREEN));
            return;
        }
        boolean value;
        if (resetting) {
            YamlConfiguration shipped = shippedOrWarn(sender);
            if (shipped == null || !shipped.isSet(key)) {
                resetKeyMissing(sender, shipped, key);
                return;
            }
            value = shipped.getBoolean(key);
        } else {
            if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) {
                sender.sendMessage(Component.text("Wert muss true oder false sein.", NamedTextColor.RED));
                return;
            }
            value = Boolean.parseBoolean(raw);
        }
        boolean old = boolValue(key);
        plugin.getConfig().set(key, value);
        plugin.saveConfig();
        carConfig.reload();
        sender.sendMessage(Component.text(key + ": " + old + " → " + value + suffix, NamedTextColor.GREEN));
    }

    // Headless-Selbsttest: kontrollierte Teststrecke (flach, Steinwand 6 Blöcke voraus) bei x/z=200/200.
    // Die Strecke ist drei Spalten breit (baseX-1..+1): die Rad-Samples der Grip-Physik liegen bei
    // ±0,7 quer zur Fahrtrichtung und brauchen Boden unter dem Footprint.
    // Flags: drift (Drehung), gap (4-Block-Loch -> Flugphase; Loecher bis 2 Bloecke ueberbrueckt
    // der Footprint, weil stets ein Rad gestuetzt bleibt), ice (Eisbahn), stairs (Abstieg ab z+3).
    private void runSim(CommandSender sender, String[] args) {
        // Negative Werte = Rückwärtsfahrt entgegen dem Blick-Yaw (prüft das Ausrichten auf die Rollrichtung)
        double simSpeed = 0.3;
        if (args.length >= 2) {
            try {
                simSpeed = Math.min(3.0, Math.max(-3.0, Double.parseDouble(args[1])));
                if (simSpeed == 0) {
                    simSpeed = 0.3;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        boolean drift = hasFlag(args, "drift");
        boolean gap = hasFlag(args, "gap");
        boolean ice = hasFlag(args, "ice");
        boolean stairs = hasFlag(args, "stairs");
        boolean drive = hasFlag(args, "drive");
        org.bukkit.World world = org.bukkit.Bukkit.getWorlds().get(0);
        int baseX = 200, baseZ = 200, groundY = 60;
        org.bukkit.Material ground = ice ? org.bukkit.Material.PACKED_ICE : org.bukkit.Material.STONE;
        for (int bx = baseX - 1; bx <= baseX + 1; bx++) {
            for (int rz = 0; rz <= 8; rz++) {
                int gy = stairs ? groundY - Math.max(0, rz - 2) : groundY;
                for (int ry = gy; ry <= groundY + 7; ry++) {
                    world.getBlockAt(bx, ry, baseZ + rz).setType(org.bukkit.Material.AIR);
                }
                world.getBlockAt(bx, gy - 1, baseZ + rz).setType(ground);
            }
            if (gap) {
                for (int rz = 2; rz <= 5; rz++) {
                    world.getBlockAt(bx, groundY - 1, baseZ + rz).setType(org.bukkit.Material.AIR);
                    world.getBlockAt(bx, groundY - 2, baseZ + rz).setType(org.bukkit.Material.AIR);
                    world.getBlockAt(bx, groundY - 3, baseZ + rz).setType(ground);
                }
            }
            int wallY = stairs ? groundY - 4 : groundY;
            for (int ry = wallY; ry <= groundY + 3; ry++) {
                world.getBlockAt(bx, ry, baseZ + 6).setType(org.bukkit.Material.STONE);
            }
        }
        org.bukkit.Location loc = new org.bukkit.Location(world, baseX + 0.5, groundY, baseZ + 0.5, 0f, 0f);
        Car car = carManager.spawnCar(loc, 0f);
        car.setSpeed(simSpeed);
        car.setSimTicks(100);
        car.setSimDrift(drift);
        car.setSimDrive(drive);
        sender.sendMessage(Component.text("Simulation mit speed=" + simSpeed
                + (drift ? " + Drift" : "") + (gap ? " + Loch" : "") + (ice ? " + Eis" : "")
                + (stairs ? " + Treppe" : "") + (drive ? " + Gas" : "")
                + " gestartet (Wand bei z=" + (baseZ + 6) + ", Strecke y=" + groundY + ").", NamedTextColor.GREEN));
    }

    /** Startet die automatische Verifikation; optionales Muster filtert die Szenarien. */
    private void runSelfTest(CommandSender sender, String[] args) {
        boolean verbose = false;
        String filter = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--verbose")) {
                verbose = true;
            } else {
                filter = args[i].toLowerCase();
            }
        }
        SelfTest selfTest = new SelfTest(plugin, carManager, carConfig, prefs, verbose, filter);
        if (!selfTest.start()) {
            sender.sendMessage(Component.text("Es läuft bereits ein Selftest.", NamedTextColor.RED));
        }
    }

    private boolean hasFlag(String[] args, String flag) {
        for (int i = 2; i < args.length; i++) {
            if (args[i].equalsIgnoreCase(flag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Liest die config.yml neu von der Platte — fuer Hand-Edits am laufenden Server, ohne
     * Neustart. Anders als /car config reset (Werte aus dem JAR) uebernimmt das genau das,
     * was gerade auf der Platte steht; wer die Datei zwischen Speichern und /car reload nicht
     * angefasst hat, aendert nichts. Gleiche Node wie der volle Reset (car.config.*), weil beide
     * potenziell JEDEN Fahrwert auf einen Schlag aendern.
     */
    private void handleReload(CommandSender sender) {
        // Erst SELBST parsen, dann erst uebernehmen: plugin.reloadConfig() schluckt einen
        // Syntaxfehler (Bukkit loggt ihn nur) und liefert eine LEERE Konfiguration — der
        // Aufrufer bekaeme eine gruene Erfolgsmeldung, waehrend in Wahrheit jeder Fahrwert
        // auf den Default zurueckgefallen ist. Das naechste /car config <key> <wert> wuerde
        // das per saveConfig() in die Datei schreiben und die kaputte, aber reparierbare
        // Konfiguration endgueltig ueberbuegeln. Beim Serverstart schuetzt das Backup nach
        // config.veraltet.yml davor, hier gibt es keins.
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        try {
            new YamlConfiguration().load(file);
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            String detail = e.getMessage() == null ? e.toString() : e.getMessage().split("\n")[0];
            plugin.getLogger().warning("/car reload abgelehnt, config.yml nicht lesbar: " + detail);
            sender.sendMessage(Component.text("config.yml nicht lesbar — es bleibt beim bisherigen Stand.",
                    NamedTextColor.RED));
            sender.sendMessage(Component.text(detail, NamedTextColor.YELLOW));
            return;
        }
        plugin.reloadConfig();
        carConfig.reload();
        sender.sendMessage(Component.text("Konfiguration von der Platte neu eingelesen (live aktiv).",
                NamedTextColor.GREEN));
        // Korrekturen nicht nur ins Log: wer den Befehl tippt, soll direkt sehen, welche
        // seiner Werte NICHT so uebernommen wurden, wie sie in der Datei stehen.
        List<String> corrections = carConfig.getLastCorrections();
        if (!corrections.isEmpty()) {
            sender.sendMessage(Component.text(corrections.size() + " Wert(e) korrigiert:",
                    NamedTextColor.YELLOW));
            for (String correction : corrections) {
                sender.sendMessage(Component.text("  " + correction, NamedTextColor.YELLOW));
            }
        }
    }

    /** Setzt ALLE Keys auf die ausgelieferten Defaults zurueck — braucht car.config.* (siehe decide()). */
    private void handleConfigResetAll(CommandSender sender) {
        YamlConfiguration shipped = shippedOrWarn(sender);
        if (shipped == null) {
            return;
        }
        int changed = 0;
        for (String k : NUMBER_KEYS) {
            if (shipped.isSet(k)) {
                plugin.getConfig().set(k, shipped.getDouble(k));
                changed++;
            }
        }
        for (String k : BOOL_KEYS) {
            if (shipped.isSet(k)) {
                plugin.getConfig().set(k, shipped.getBoolean(k));
                changed++;
            }
        }
        for (String k : STRING_KEYS) {
            if (shipped.isSet(k)) {
                plugin.getConfig().set(k, shipped.getString(k));
                changed++;
            }
        }
        plugin.saveConfig();
        carConfig.reload();
        sender.sendMessage(Component.text(changed + " Fahrwerte auf die ausgelieferten Defaults zurueckgesetzt (live aktiv).",
                NamedTextColor.GREEN));
    }

    /** Die ausgelieferte config.yml oder null (mit Fehlermeldung an den Sender). */
    private YamlConfiguration shippedOrWarn(CommandSender sender) {
        YamlConfiguration shipped = CarConfig.shippedDefaults(plugin);
        if (shipped == null) {
            sender.sendMessage(Component.text("config.yml liegt nicht im Jar — reset nicht möglich.",
                    NamedTextColor.RED));
        }
        return shipped;
    }

    /** Meldet, warum ein Einzel-Reset nicht ging: fehlende Datei oder ein Key ohne Default
     *  (z. B. frisch angelegt und noch nicht in der ausgelieferten config.yml). */
    private void resetKeyMissing(CommandSender sender, YamlConfiguration shipped, String key) {
        if (shipped != null) {
            sender.sendMessage(Component.text(key + " hat keinen ausgelieferten Default.", NamedTextColor.RED));
        }
    }

    private String humanValue(String key) {
        return CarConfig.clampHumanValue(key, plugin.getConfig().getDouble(key)) + unitSuffix(key);
    }

    private String unitSuffix(String key) {
        return switch (key) {
            case "max-speed", "max-reverse-speed", "max-fall-speed", "turn-min-speed", "max-sink-speed",
                 "impact-min-speed", "impact-knockback-max", "landing-hard-speed", "standstill-speed",
                 "crash-rebound-max", "crash-min-speed", "car-push-max" -> " km/h";
            case "acceleration", "reverse-acceleration", "brake-deceleration", "handbrake-deceleration",
                 "engine-braking", "max-lateral-grip", "downhill-assist" -> " m/s²";
            case "drag", "water-drag" -> " %/s";
            case "turn-curvature" -> " °/m";
            case "horn-range", "understeer-range", "landing-range" -> " Blöcke";
            case "understeer-cooldown", "horn-cooldown" -> " s";
            case "understeer-min-slip", "mouse-deadzone", "mouse-full-lock" -> " °";
            case "crawl-turn-rate", "crash-spin-max" -> " °/s";
            case "grip-concrete", "grip-grass", "grip-ice", "grip-default", "handbrake-grip",
                 "slope-resistance", "crash-restitution", "crash-spin", "crash-transfer",
                 "impact-knockback", "landing-speed-keep", "tire-smoke-grip",
                 "standstill-min-grip", "impact-lift" -> " %";
            default -> "";
        };
    }

    private boolean boolValue(String key) {
        return switch (key) {
            case "understeer-sound-enabled" -> carConfig.understeerSound;
            case "field-damage-enabled" -> carConfig.fieldDamage;
            case "debug-wheels" -> carConfig.debugWheels;
            default -> carConfig.debug;
        };
    }

    /** Aktueller Wert eines String-Keys — bisher ist jeder davon ein Sound-Name. */
    private String stringValue(String key) {
        return switch (key) {
            case "horn-sound" -> CarConfig.soundName(carConfig.hornSound);
            case "understeer-sound" -> CarConfig.soundName(carConfig.understeerSoundName);
            case "landing-sound" -> CarConfig.soundName(carConfig.landingSound);
            default -> "";
        };
    }

    private void unknownKey(CommandSender sender, String key) {
        sender.sendMessage(Component.text("Unbekannter Key: " + key, NamedTextColor.RED));
        sender.sendMessage(Component.text("Gültig: " + String.join(", ", CarPermissions.configKeys()),
                NamedTextColor.YELLOW));
    }

    /** Autocomplete zeigt nur, was der Sender auch ausführen darf; sim taucht nie auf. */
    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        List<String> subs = new ArrayList<>();
        for (Sub sub : SUBS) {
            if (!sub.consoleOnly() && allowed(sender, sub)) {
                subs.add(sub.name());
            }
        }
        if (args.length == 0) {
            return subs;
        }
        if (args.length == 1) {
            return filter(subs, args[0]);
        }
        if (args[0].equalsIgnoreCase("config") && sender.hasPermission(CarPermissions.CONFIG)) {
            if (args.length == 2) {
                List<String> keys = new ArrayList<>(CarPermissions.configKeys());
                // "reset" nur anbieten, wer den vollen Reset auch ausfuehren darf.
                if (sender.hasPermission(CarPermissions.CONFIG_ALL)) {
                    keys.add("reset");
                }
                return filter(keys, args[1]);
            }
            if (args.length == 3) {
                String key = args[1].toLowerCase();
                if (!sender.hasPermission(CarPermissions.config(key))) {
                    return List.of();
                }
                if (NUMBER_KEYS.contains(key)) {
                    // Der aktuelle Wert selbst wird bewusst nicht vorgeschlagen (stuende sonst
                    // schon im Feld) — "reset" schon, das ist ein eigenstaendiges Kommando.
                    return filter(List.of("reset"), args[2]);
                }
                if (BOOL_KEYS.contains(key)) {
                    return filter(List.of("true", "false", "reset"), args[2]);
                }
                if (STRING_KEYS.contains(key)) {
                    // Sound-Namen selbst bleiben unvorgeschlagen (die Registry haette
                    // vierstellig viele Eintraege), "reset" schon.
                    return filter(List.of("reset"), args[2]);
                }
            }
            return List.of();
        }
        if (args[0].equalsIgnoreCase("prefs") && sender.hasPermission(CarPermissions.PREFS)) {
            if (args.length == 2) {
                return filter(PREF_KEYS, args[1]);
            }
            if (args.length == 3 && PREF_KEYS.contains(args[1].toLowerCase())) {
                return filter(List.of("on", "off", "true", "false"), args[2]);
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(lower)).toList();
    }

    @Override
    public String permission() {
        return CarPermissions.USE;
    }
}
