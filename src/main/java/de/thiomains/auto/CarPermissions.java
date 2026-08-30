package de.thiomains.auto;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Permission-Nodes des Plugins. Die festen Nodes stehen in der paper-plugin.yml;
 * die Pro-Key-Nodes zum Setzen der Fahrwerte entstehen zur Laufzeit aus den Key-Listen
 * in {@link CarConfig} — ein neuer Config-Key bekommt seine Node damit automatisch.
 * Geprüft wird ausschließlich über hasPermission, nie über isOp: so kann jedes
 * Permissions-Plugin die Defaults überschreiben.
 */
public final class CarPermissions {

    /** /car und /car help — Default true. */
    public static final String USE = "car.use";
    /** /car prefs — Default true. */
    public static final String PREFS = "car.prefs";
    /** /car give — Default op. */
    public static final String GIVE = "car.give";
    /** Fahrwerte lesen — Default op. */
    public static final String CONFIG = "car.config";
    /** Sammel-Node über alle Key-Nodes — Default op. */
    public static final String CONFIG_ALL = "car.config.*";

    private static final String CONFIG_PREFIX = "car.config.";

    private CarPermissions() {
    }

    /** Node, die das Setzen genau dieses Config-Keys erlaubt. */
    public static String config(String key) {
        return CONFIG_PREFIX + key;
    }

    /** Alle Config-Keys in Anzeige-Reihenfolge (Zahlen zuerst, dann Schalter). */
    public static List<String> configKeys() {
        List<String> keys = new ArrayList<>(CarConfig.NUMBER_KEYS);
        keys.addAll(CarConfig.BOOL_KEYS);
        return keys;
    }

    /** Registriert die Pro-Key-Nodes und ihren Sammel-Elternknoten. */
    public static void register(PluginManager pluginManager) {
        Map<String, Boolean> children = new LinkedHashMap<>();
        for (String key : configKeys()) {
            String node = config(key);
            addIfAbsent(pluginManager, new Permission(node,
                    "Erlaubt /car config " + key + " <wert>", PermissionDefault.OP));
            children.put(node, true);
        }
        // Children vor dem Hinzufügen setzen: addPermission rechnet die Vererbung einmalig durch.
        addIfAbsent(pluginManager, new Permission(CONFIG_ALL,
                "Erlaubt das Ändern aller Fahrwerte", PermissionDefault.OP, children));
    }

    private static void addIfAbsent(PluginManager pluginManager, Permission permission) {
        if (pluginManager.getPermission(permission.getName()) == null) {
            pluginManager.addPermission(permission);
        }
    }
}
