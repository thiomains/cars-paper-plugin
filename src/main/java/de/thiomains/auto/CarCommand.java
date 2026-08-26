package de.thiomains.auto;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * /auto give|sim|config. Als Paper-Plugin werden Befehle zur Laufzeit via
 * registerCommand registriert statt per YAML deklariert.
 * Die Werte von /auto config sind menschenlesbar (km/h, m/s², %).
 */
public final class CarCommand implements BasicCommand {

    private static final List<String> NUMBER_KEYS = CarConfig.NUMBER_KEYS;
    private static final List<String> BOOL_KEYS = CarConfig.BOOL_KEYS;
    private static final List<String> MAX_100_KEYS = List.of(
            "drag", "grip-concrete", "grip-grass", "grip-default");

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

    private static final List<String> PREF_KEYS = List.of("mouse_steer", "reverse_invert");

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        if (args.length >= 1 && args[0].equalsIgnoreCase("config")) {
            handleConfig(sender, args);
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("prefs")) {
            handlePrefs(sender, args);
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("sim")) {
            runSim(sender, args);
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("give")) {
            handleGive(sender);
            return;
        }
        sender.sendMessage(Component.text(
                "Verwendung: /auto give | /auto config get [key] | /auto config set <key> <wert> | /auto prefs <key> <on|off>",
                NamedTextColor.YELLOW));
    }

    /** Spielerweite Fahreinstellungen: akzeptiert true/false/on/off/an/aus. */
    private void handlePrefs(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Fahreinstellungen sind nur für Spieler.", NamedTextColor.RED));
            return;
        }
        if (args.length == 2 && PREF_KEYS.contains(args[1].toLowerCase())) {
            String key = args[1].toLowerCase();
            boolean current = prefValue(player.getUniqueId(), key);
            player.sendMessage(Component.text(key + " = " + (current ? "an" : "aus"), NamedTextColor.YELLOW));
            return;
        }
        if (args.length == 3 && PREF_KEYS.contains(args[1].toLowerCase())) {
            String key = args[1].toLowerCase();
            Boolean value = parseToggle(args[2]);
            if (value == null) {
                player.sendMessage(Component.text("Gültig: true/false/on/off/an/aus", NamedTextColor.RED));
                return;
            }
            if (key.equals("mouse_steer")) {
                prefs.setMouseSteer(player.getUniqueId(), value);
            } else {
                prefs.setReverseInvert(player.getUniqueId(), value);
            }
            player.sendMessage(Component.text(key + " ist jetzt " + (value ? "an" : "aus") + ".", NamedTextColor.GREEN));
            return;
        }
        player.sendMessage(Component.text("Verwendung: /auto prefs <mouse_steer|reverse_invert> <on|off>", NamedTextColor.YELLOW));
    }

    private boolean prefValue(java.util.UUID playerId, String key) {
        return key.equals("mouse_steer") ? prefs.mouseSteer(playerId) : prefs.reverseInvert(playerId);
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

    private void handleConfig(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("get")) {
            sender.sendMessage(Component.text("Auto-Config:", NamedTextColor.GOLD));
            for (String key : NUMBER_KEYS) {
                sender.sendMessage(Component.text("  " + key + " = " + humanValue(key), NamedTextColor.YELLOW));
            }
            for (String key : BOOL_KEYS) {
                sender.sendMessage(Component.text("  " + key + " = " + boolValue(key), NamedTextColor.YELLOW));
            }
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("get")) {
            String key = args[2].toLowerCase();
            if (NUMBER_KEYS.contains(key)) {
                sender.sendMessage(Component.text(key + " = " + humanValue(key), NamedTextColor.YELLOW));
                return;
            }
            if (BOOL_KEYS.contains(key)) {
                sender.sendMessage(Component.text(key + " = " + boolValue(key), NamedTextColor.YELLOW));
                return;
            }
            unknownKey(sender, key);
            return;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
            String key = args[2].toLowerCase();
            String raw = args[3];
            if (NUMBER_KEYS.contains(key)) {
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
            if (BOOL_KEYS.contains(key)) {
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
                return;
            }
            unknownKey(sender, key);
            return;
        }
        sender.sendMessage(Component.text("Verwendung: /auto config get [key] | /auto config set <key> <wert>", NamedTextColor.YELLOW));
    }

    // Headless-Selbsttest: kontrollierte Teststrecke (flach, Steinwand 6 Blöcke voraus) bei x/z=200/200.
    private void runSim(CommandSender sender, String[] args) {
        double simSpeed = 0.3;
        if (args.length >= 2) {
            try {
                simSpeed = Math.min(3.0, Math.max(0.01, Double.parseDouble(args[1])));
            } catch (NumberFormatException ignored) {
            }
        }
        org.bukkit.World world = org.bukkit.Bukkit.getWorlds().get(0);
        int baseX = 200, baseZ = 200, groundY = 60;
        for (int rz = 0; rz <= 8; rz++) {
            for (int ry = groundY; ry <= groundY + 7; ry++) {
                world.getBlockAt(baseX, ry, baseZ + rz).setType(org.bukkit.Material.AIR);
            }
            world.getBlockAt(baseX, groundY - 1, baseZ + rz).setType(org.bukkit.Material.STONE);
        }
        world.getBlockAt(baseX, groundY, baseZ + 6).setType(org.bukkit.Material.STONE);
        world.getBlockAt(baseX, groundY + 1, baseZ + 6).setType(org.bukkit.Material.STONE);
            org.bukkit.Location loc = new org.bukkit.Location(world, baseX + 0.5, groundY, baseZ + 0.5, 0f, 0f);
            Car car = carManager.spawnCar(loc, 0f);
            car.setSpeed(simSpeed);
            car.setSimTicks(100);
            boolean drift = args.length >= 3 && args[2].equalsIgnoreCase("drift");
            car.setSimDrift(drift);
            sender.sendMessage(Component.text("Simulation mit speed=" + simSpeed + (drift ? " + Drift" : "")
                    + " gestartet (Wand bei z=" + (baseZ + 6) + ", Strecke y=" + groundY + ").", NamedTextColor.GREEN));
            return;
        }

    private String humanValue(String key) {
        return plugin.getConfig().getDouble(key) + unitSuffix(key);
    }

    private String unitSuffix(String key) {
        return switch (key) {
            case "max-speed", "max-reverse-speed", "turn-min-speed" -> " km/h";
            case "acceleration", "reverse-acceleration", "brake-deceleration", "engine-braking" -> " m/s²";
            case "drag" -> " %/s";
            case "max-lateral-grip" -> " m/s²";
            case "turn-rate-max" -> " °/s";
            case "grip-concrete", "grip-grass", "grip-default" -> " %";
            default -> "";
        };
    }

    private boolean boolValue(String key) {
        return key.equals("understeer-sound") ? carConfig.understeerSound : carConfig.debug;
    }

    private void unknownKey(CommandSender sender, String key) {
        List<String> all = new ArrayList<>(NUMBER_KEYS);
        all.addAll(BOOL_KEYS);
        sender.sendMessage(Component.text("Unbekannter Key: " + key, NamedTextColor.RED));
        sender.sendMessage(Component.text("Gültig: " + String.join(", ", all), NamedTextColor.YELLOW));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length == 0) {
            return List.of("give", "sim", "config", "prefs");
        }
        if (args.length == 1) {
            return filter(List.of("give", "sim", "config", "prefs"), args[0]);
        }
        if (args[0].equalsIgnoreCase("config")) {
            if (args.length == 2) {
                return filter(List.of("get", "set"), args[1]);
            }
            if (args.length == 3 && (args[1].equalsIgnoreCase("get") || args[1].equalsIgnoreCase("set"))) {
                List<String> all = new ArrayList<>(NUMBER_KEYS);
                all.addAll(BOOL_KEYS);
                return filter(all, args[2]);
            }
        }
        if (args[0].equalsIgnoreCase("prefs")) {
            if (args.length == 2) {
                return filter(PREF_KEYS, args[1]);
            }
            if (args.length == 3) {
                return filter(List.of("on", "off", "true", "false"), args[2]);
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(o -> o.startsWith(lower)).toList();
    }

    @Override
    public String permission() {
        return "auto.give";
    }
}
