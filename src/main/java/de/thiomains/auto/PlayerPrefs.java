package de.thiomains.auto;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spielerweite Fahreinstellungen (Mauslenkung, Rückwärts-Invertierung, Actionbar-Anzeigen),
 * persistiert in prefs.yml. Alles standardmäßig aktiviert. Actionbar ist der Hauptschalter;
 * actionbar_speed/actionbar_grip steuern die einzelnen Segmente.
 */
public final class PlayerPrefs {

    private static final Prefs DEFAULT = new Prefs(true, true, true, true, false);

    private record Prefs(boolean mouseSteer, boolean reverseInvert, boolean actionbar,
                         boolean actionbarSpeed, boolean actionbarGrip) {
        private Prefs with(boolean value, Segment segment) {
            return switch (segment) {
                case MOUSE_STEER -> new Prefs(value, reverseInvert, actionbar, actionbarSpeed, actionbarGrip);
                case REVERSE_INVERT -> new Prefs(mouseSteer, value, actionbar, actionbarSpeed, actionbarGrip);
                case ACTIONBAR -> new Prefs(mouseSteer, reverseInvert, value, actionbarSpeed, actionbarGrip);
                case ACTIONBAR_SPEED -> new Prefs(mouseSteer, reverseInvert, actionbar, value, actionbarGrip);
                case ACTIONBAR_GRIP -> new Prefs(mouseSteer, reverseInvert, actionbar, actionbarSpeed, value);
            };
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Prefs> store = new HashMap<>();

    public PlayerPrefs(JavaPlugin plugin) {
        this(plugin, new File(plugin.getDataFolder(), "prefs.yml"));
    }

    /** Mit eigener Datei — der Selftest prueft die Migration so, ohne die echte prefs.yml
     *  eines laufenden Servers anzufassen. */
    PlayerPrefs(JavaPlugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
        load();
    }

    public boolean mouseSteer(UUID playerId) {
        return store.getOrDefault(playerId, DEFAULT).mouseSteer();
    }

    public boolean reverseInvert(UUID playerId) {
        return store.getOrDefault(playerId, DEFAULT).reverseInvert();
    }

    public boolean actionbar(UUID playerId) {
        return store.getOrDefault(playerId, DEFAULT).actionbar();
    }

    public boolean actionbarSpeed(UUID playerId) {
        return store.getOrDefault(playerId, DEFAULT).actionbarSpeed();
    }

    public boolean actionbarGrip(UUID playerId) {
        return store.getOrDefault(playerId, DEFAULT).actionbarGrip();
    }

    public void setMouseSteer(UUID playerId, boolean enabled) {
        put(playerId, copy(playerId).with(enabled, Segment.MOUSE_STEER));
    }

    public void setReverseInvert(UUID playerId, boolean enabled) {
        put(playerId, copy(playerId).with(enabled, Segment.REVERSE_INVERT));
    }

    public void setActionbar(UUID playerId, boolean enabled) {
        put(playerId, copy(playerId).with(enabled, Segment.ACTIONBAR));
    }

    public void setActionbarSpeed(UUID playerId, boolean enabled) {
        put(playerId, copy(playerId).with(enabled, Segment.ACTIONBAR_SPEED));
    }

    public void setActionbarGrip(UUID playerId, boolean enabled) {
        put(playerId, copy(playerId).with(enabled, Segment.ACTIONBAR_GRIP));
    }

    private enum Segment {
        MOUSE_STEER, REVERSE_INVERT, ACTIONBAR, ACTIONBAR_SPEED, ACTIONBAR_GRIP
    }

    private Prefs copy(UUID playerId) {
        return store.getOrDefault(playerId, DEFAULT);
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
                // Migration: früher hieß der Key reverse_invert_mouse; die Actionbar-Keys sind neu
                boolean invert = yml.isSet(key + ".reverse_invert")
                        ? yml.getBoolean(key + ".reverse_invert")
                        : yml.getBoolean(key + ".reverse_invert_mouse", true);
                store.put(id, new Prefs(
                        yml.getBoolean(key + ".mouse_steer", true),
                        invert,
                        yml.getBoolean(key + ".actionbar", true),
                        yml.getBoolean(key + ".actionbar_speed", true),
                        yml.getBoolean(key + ".actionbar_grip", false)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ungültiger Eintrag in prefs.yml: " + key);
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (var entry : store.entrySet()) {
            String base = entry.getKey().toString();
            Prefs p = entry.getValue();
            yml.set(base + ".mouse_steer", p.mouseSteer());
            yml.set(base + ".reverse_invert", p.reverseInvert());
            yml.set(base + ".actionbar", p.actionbar());
            yml.set(base + ".actionbar_speed", p.actionbarSpeed());
            yml.set(base + ".actionbar_grip", p.actionbarGrip());
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("prefs.yml konnte nicht gespeichert werden: " + e.getMessage());
        }
    }
}
