package de.thiomains.auto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;

/**
 * Erzeugt und erkennt das Auto-Item. Das Modell kommt aus dem Resourcepack
 * (Namespaced {@code thiomains:auto}); der Server hält nur Material + item_model-Komponente.
 */
public final class CarItem {

    public static final NamespacedKey MODEL_KEY =
            Objects.requireNonNull(NamespacedKey.fromString("thiomains:auto"));

    private CarItem() {
    }

    /** Das Item, das der Spieler im Inventar hält (mit PDC-Marker). */
    public static ItemStack createCarItem(NamespacedKey markerKey) {
        ItemStack stack = new ItemStack(Material.ARMADILLO_SCUTE);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(MODEL_KEY);
        meta.displayName(Component.text("Auto", NamedTextColor.GOLD));
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Der ItemStack, den der ItemDisplay als Fahrzeugmodell zeigt. */
    public static ItemStack createModelItem() {
        ItemStack stack = new ItemStack(Material.ARMADILLO_SCUTE);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(MODEL_KEY);
        stack.setItemMeta(meta);
        return stack;
    }

    public static boolean isCarItem(ItemStack stack, NamespacedKey markerKey) {
        if (stack == null || stack.getType() != Material.ARMADILLO_SCUTE) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }
}
