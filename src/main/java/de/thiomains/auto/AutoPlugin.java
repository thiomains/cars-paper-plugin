package de.thiomains.auto;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.plugin.java.JavaPlugin;

public final class AutoPlugin extends JavaPlugin {

    private static final int CONFIG_VERSION = 3;

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

        registerCommand("auto", "Auto-Verwaltung", new CarCommand(this, carManager, carConfig, playerPrefs));

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

    /** Sichert veraltete Configs nach Einheiten-Änderungen und erzeugt frische Defaults. */
    private void ensureConfigIsCurrent() {
        saveDefaultConfig();
        if (getConfig().getInt("config-version", -1) == CONFIG_VERSION) {
            return;
        }
        java.io.File file = new java.io.File(getDataFolder(), "config.yml");
        if (file.exists()) {
            java.io.File backup = new java.io.File(getDataFolder(), "config.veraltet.yml");
            if (file.renameTo(backup)) {
                getLogger().warning("Alte config.yml nach " + backup.getName()
                        + " verschoben (Einheiten geändert). Neue config.yml wird erzeugt.");
            }
        }
        saveResource("config.yml", false);
    }

    @Override
    public void onDisable() {
        getLogger().info("Auto-Plugin deaktiviert." + (carManager != null && carManager.size() > 0
                ? " (" + carManager.size() + " Auto(s) bleiben in der Welt bestehen.)" : ""));
    }
}
