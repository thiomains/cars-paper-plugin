package de.thiomains.auto;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
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
    private static final List<String> MAX_100_KEYS = List.of(
            "drag", "grip-concrete", "grip-grass", "grip-ice", "grip-default", "handbrake-grip");

    private static final List<String> PREF_KEYS = List.of(
            "mouse_steer", "reverse_invert", "actionbar", "actionbar_speed", "actionbar_grip");

    /** Ein Unterbefehl mit seiner Node; speist Hilfe, Rechteprüfung und Autocomplete. */
    private record Sub(String name, String usage, String description, String permission, boolean consoleOnly) {
    }

    private static final List<Sub> SUBS = List.of(
            new Sub("help", "/car help", "Diese Übersicht", CarPermissions.USE, false),
            new Sub("prefs", "/car prefs [<key> [on|off]]", "Eigene Fahreinstellungen", CarPermissions.PREFS, false),
            new Sub("give", "/car give", "Auto-Item ins Inventar", CarPermissions.GIVE, false),
            new Sub("config", "/car config [<key> [wert]]", "Fahrwerte anzeigen/ändern", CarPermissions.CONFIG, false),
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
        if (name.equals("config") && args.length >= 3) {
            String key = args[1].toLowerCase();
            if ((NUMBER_KEYS.contains(key) || BOOL_KEYS.contains(key)) && !has.test(CarPermissions.config(key))) {
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
            return;
        }
        String key = args[1].toLowerCase();
        if (key.equals("get") || key.equals("set")) {
            sender.sendMessage(Component.text("»get«/»set« entfallen — nutze /car config [<key> [wert]].",
                    NamedTextColor.YELLOW));
            return;
        }
        boolean isNumber = NUMBER_KEYS.contains(key);
        if (!isNumber && !BOOL_KEYS.contains(key)) {
            unknownKey(sender, args[1]);
            return;
        }
        if (args.length == 2) {
            sender.sendMessage(Component.text(key + " = " + (isNumber ? humanValue(key) : boolValue(key)),
                    NamedTextColor.YELLOW));
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
        if (isNumber) {
            double value;
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
            String old = humanValue(key);
            plugin.getConfig().set(key, value);
            plugin.saveConfig();
            carConfig.reload();
            sender.sendMessage(Component.text(key + ": " + old + " → " + value + unitSuffix(key) + " (live aktiv)", NamedTextColor.GREEN));
            return;
        }
        if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) {
            sender.sendMessage(Component.text("Wert muss true oder false sein.", NamedTextColor.RED));
            return;
        }
        boolean value = Boolean.parseBoolean(raw);
        boolean old = boolValue(key);
        plugin.getConfig().set(key, value);
        plugin.saveConfig();
        carConfig.reload();
        sender.sendMessage(Component.text(key + ": " + old + " → " + value + " (live aktiv)", NamedTextColor.GREEN));
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
        SelfTest selfTest = new SelfTest(plugin, carManager, carConfig, verbose, filter);
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

    private String humanValue(String key) {
        return CarConfig.clampHumanValue(key, plugin.getConfig().getDouble(key)) + unitSuffix(key);
    }

    private String unitSuffix(String key) {
        return switch (key) {
            case "max-speed", "max-reverse-speed", "max-fall-speed", "turn-min-speed", "max-sink-speed" -> " km/h";
            case "acceleration", "reverse-acceleration", "brake-deceleration", "handbrake-deceleration",
                 "engine-braking", "max-lateral-grip", "downhill-assist" -> " m/s²";
            case "drag" -> " %/s";
            case "turn-curvature" -> " °/m";
            case "grip-concrete", "grip-grass", "grip-ice", "grip-default", "handbrake-grip",
                 "slope-resistance", "crash-restitution", "crash-spin" -> " %";
            default -> "";
        };
    }

    private boolean boolValue(String key) {
        return key.equals("understeer-sound") ? carConfig.understeerSound : carConfig.debug;
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
                return filter(CarPermissions.configKeys(), args[1]);
            }
            if (args.length == 3) {
                String key = args[1].toLowerCase();
                if (!sender.hasPermission(CarPermissions.config(key))) {
                    return List.of();
                }
                if (NUMBER_KEYS.contains(key)) {
                    return filter(List.of(String.valueOf(CarConfig.clampHumanValue(key,
                            plugin.getConfig().getDouble(key)))), args[2]);
                }
                if (BOOL_KEYS.contains(key)) {
                    return filter(List.of("true", "false"), args[2]);
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
