package de.thiomains.auto;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.plugin.java.JavaPlugin;

public final class AutoPlugin extends JavaPlugin {

    /** Bei jeder Aenderung an Keys oder Einheiten hochzaehlen — muss zum config-version
     *  in der ausgelieferten config.yml passen (der Selftest prueft genau das). */
    static final int CONFIG_VERSION = 15;

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

    /** Umbenannte Keys: alter Name -> neuer Name. Ohne diese Tabelle faellt ein umbenannter
     *  Key bei der Migration weg und der Nutzer steht wieder auf dem Default.
     *  <p>Achtung: ein frei gewordener alter Name darf spaeter neu vergeben werden
     *  (understeer-sound war bis config-version 10 der Schalter und ist seit 15 der
     *  Sound-Name) — deshalb prueft carryOver zusaetzlich den TYP des alten Wertes. */
    private static final java.util.Map<String, String> RENAMED_KEYS = java.util.Map.of(
            "understeer-sound", "understeer-sound-enabled");

    /**
     * Übernimmt jeden Key, den alte und neue Konfiguration gemeinsam kennen, mit dem
     * gesetzten Typ; umbenannte Keys wandern über RENAMED_KEYS mit. Sonstige unbekannte Keys
     * der alten Datei fallen weg — sie sind entfallen. Rückgabe: Anzahl der übernommenen Werte.
     * Herausgezogen, damit der Selftest die Migration ohne echte Dateien prüfen kann.
     */
    static int carryOver(org.bukkit.configuration.ConfigurationSection old,
                         org.bukkit.configuration.ConfigurationSection target) {
        int carried = 0;
        for (String key : CarConfig.NUMBER_KEYS) {
            String from = sourceKey(old, key, value -> value instanceof Number);
            if (from != null) {
                target.set(key, old.getDouble(from));
                carried++;
            }
        }
        for (String key : CarConfig.BOOL_KEYS) {
            String from = sourceKey(old, key, value -> value instanceof Boolean);
            if (from != null) {
                target.set(key, old.getBoolean(from));
                carried++;
            }
        }
        for (String key : CarConfig.STRING_KEYS) {
            String from = sourceKey(old, key, value -> value instanceof String);
            if (from != null) {
                target.set(key, old.getString(from));
                carried++;
            }
        }
        return carried;
    }

    /**
     * Unter welchem Namen der Wert in der alten Datei steht (aktueller Name oder alter), sonst
     * null. Der Typ muss zur Key-Kategorie passen: sonst wandert ein wiederverwendeter Name in
     * die falsche Kategorie — der alte Schalter {@code understeer-sound: false} wuerde als
     * Sound-Name "false" uebernommen und beim naechsten Start als kaputt gemeldet. Passt der
     * Typ am aktuellen Namen nicht, zaehlt der Key als nicht gesetzt und der alte Name bekommt
     * seine Chance ueber RENAMED_KEYS.
     */
    private static String sourceKey(org.bukkit.configuration.ConfigurationSection old, String key,
                                    java.util.function.Predicate<Object> typeFits) {
        if (old.isSet(key) && typeFits.test(old.get(key))) {
            return key;
        }
        for (java.util.Map.Entry<String, String> renamed : RENAMED_KEYS.entrySet()) {
            if (renamed.getValue().equals(key) && old.isSet(renamed.getKey())
                    && typeFits.test(old.get(renamed.getKey()))) {
                return renamed.getKey();
            }
        }
        return null;
    }

    @Override
    public void onDisable() {
        getLogger().info("Auto-Plugin deaktiviert." + (carManager != null && carManager.size() > 0
                ? " (" + carManager.size() + " Auto(s) bleiben in der Welt bestehen.)" : ""));
    }
}
