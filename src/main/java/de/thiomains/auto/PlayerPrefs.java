package de.thiomains.auto;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spielerweite Fahreinstellungen (Mauslenkung an/aus, Maus-Invertierung im Rückwärtsgang),
 * persistiert in prefs.yml. Beides standardmäßig aktiviert.
 */
public final class PlayerPrefs {

    private static final Prefs DEFAULT = new Prefs(true, true);

    private record Prefs(boolean mouseSteer, boolean reverseInvert) {
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Prefs> store = new HashMap<>();

    public PlayerPrefs(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "prefs.yml");
        load();
    }

    public boolean mouseSteer(UUID playerId) {
        return store.getOrDefault(playerId, DEFAULT).mouseSteer();
    }

    public boolean reverseInvert(UUID playerId) {
        return store.getOrDefault(playerId, DEFAULT).reverseInvert();
    }

    public void setMouseSteer(UUID playerId, boolean enabled) {
        put(playerId, new Prefs(enabled, reverseInvert(playerId)));
    }

    public void setReverseInvert(UUID playerId, boolean enabled) {
        put(playerId, new Prefs(mouseSteer(playerId), enabled));
    }

    private void put(UUID playerId, Prefs prefs) {
        store.put(playerId, prefs);
        save();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                // Migration: früher hieß der Key reverse_invert_mouse
                boolean invert = yml.isSet(key + ".reverse_invert")
                        ? yml.getBoolean(key + ".reverse_invert")
                        : yml.getBoolean(key + ".reverse_invert_mouse", true);
                store.put(id, new Prefs(yml.getBoolean(key + ".mouse_steer", true), invert));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ungültiger Eintrag in prefs.yml: " + key);
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (var entry : store.entrySet()) {
            String base = entry.getKey().toString();
            yml.set(base + ".mouse_steer", entry.getValue().mouseSteer());
            yml.set(base + ".reverse_invert", entry.getValue().reverseInvert());
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("prefs.yml konnte nicht gespeichert werden: " + e.getMessage());
        }
    }
}
