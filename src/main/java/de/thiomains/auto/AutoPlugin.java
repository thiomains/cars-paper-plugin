package de.thiomains.auto;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.plugin.java.JavaPlugin;

public final class AutoPlugin extends JavaPlugin {

    /** Bei jeder Aenderung an Keys oder Einheiten hochzaehlen — muss zum config-version
     *  in der ausgelieferten config.yml passen (der Selftest prueft genau das). */
    static final int CONFIG_VERSION = 10;

    private NamespacedKey carKey;
    private NamespacedKey carPartKey;
    private CarManager carManager;

    @Override
    public void onEnable() {
        ensureConfigIsCurrent();
        reloadConfig();

        carKey = new NamespacedKey(this, "car");
        carPartKey = new NamespacedKey(this, "car_part");

        CarConfig carConfig = new CarConfig(this);
        GripCalculator gripCalculator = new GripCalculator(carConfig);
        PlayerPrefs playerPrefs = new PlayerPrefs(this);
        carManager = new CarManager(this, carKey, carPartKey);

        CarListener listener = new CarListener(carManager, carConfig, getLogger());
        getServer().getPluginManager().registerEvents(listener, this);

        CarPermissions.register(getServer().getPluginManager());
        registerCommand("car", "Auto-Verwaltung", java.util.List.of("auto"),
                new CarCommand(this, carManager, carConfig, playerPrefs));

        new DriveTask(carManager, carConfig, gripCalculator, playerPrefs, getLogger()).runTaskTimer(this, 1L, 1L);

        // Restart/Reload-Festigkeit: markierte Autos aus bereits geladenen Chunks wieder registrieren
        int restored = 0;
        for (World world : getServer().getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (stand.getPersistentDataContainer().has(carKey)) {
                    carManager.reRegister(stand);
                    restored++;
                }
            }
        }
        getLogger().info("Sweep: " + restored + " Auto(s) aus geladenen Chunks wiederhergestellt.");

        // Verzögerter Sweep: Entitychunks laden unter Moonrise träge/nachgelagert;
        // getEntitiesByClass zwingt das Laden und reRegister fängt alles ab.
        getServer().getScheduler().runTaskLater(this, () -> {
            int delayed = 0;
            for (World world : getServer().getWorlds()) {
                for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                    if (stand.getPersistentDataContainer().has(carKey)) {
                        carManager.reRegister(stand);
                        delayed++;
                    }
                }
            }
            getLogger().info("Verzögerter Sweep (100 Ticks nach Start): " + delayed + " Auto(s) geprüft.");
        }, 100L);

        getLogger().info("Auto-Plugin aktiviert.");
    }

    /** Sichert veraltete Configs nach Schlüssel-/Einheiten-Änderungen, erzeugt frische Defaults
     *  und übernimmt dabei alle Werte von Keys, die es in beiden Versionen gibt. */
    private void ensureConfigIsCurrent() {
        saveDefaultConfig();
        int stored = getConfig().getInt("config-version", -1);
        if (stored == CONFIG_VERSION) {
            return;
        }
        java.io.File file = new java.io.File(getDataFolder(), "config.yml");
        org.bukkit.configuration.file.YamlConfiguration old = file.exists()
                ? org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file) : null;
        if (file.exists()) {
            java.io.File backup = new java.io.File(getDataFolder(), "config.veraltet.yml");
            if (file.renameTo(backup)) {
                getLogger().warning("Alte config.yml nach " + backup.getName()
                        + " verschoben (Format geändert). Neue config.yml wird erzeugt.");
            }
        }
        saveResource("config.yml", false);
        reloadConfig();
        if (old != null && stored >= 3) {
            int carried = carryOver(old, getConfig());
            if (carried > 0) {
                saveConfig();
                getLogger().info(carried + " Einstellungen aus der alten Konfiguration übernommen.");
            }
        }
    }

    /**
     * Übernimmt jeden Key, den alte und neue Konfiguration gemeinsam kennen, mit dem
     * gesetzten Typ. Unbekannte Keys der alten Datei fallen weg — sie sind entweder
     * umbenannt oder entfallen. Rückgabe: Anzahl der übernommenen Werte.
     * Herausgezogen, damit der Selftest die Migration ohne echte Dateien prüfen kann.
     */
    static int carryOver(org.bukkit.configuration.ConfigurationSection old,
                         org.bukkit.configuration.ConfigurationSection target) {
        int carried = 0;
        for (String key : CarConfig.NUMBER_KEYS) {
            if (old.isSet(key)) {
                target.set(key, old.getDouble(key));
                carried++;
            }
        }
        for (String key : CarConfig.BOOL_KEYS) {
            if (old.isSet(key)) {
                target.set(key, old.getBoolean(key));
                carried++;
            }
        }
        return carried;
    }

    @Override
    public void onDisable() {
        getLogger().info("Auto-Plugin deaktiviert." + (carManager != null && carManager.size() > 0
                ? " (" + carManager.size() + " Auto(s) bleiben in der Welt bestehen.)" : ""));
    }
}
