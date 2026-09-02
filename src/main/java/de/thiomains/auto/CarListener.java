package de.thiomains.auto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Koppelt Spieler-Aktionen an das Plugin: Spawn per Rechtsklick mit dem Item,
 * Einsteigen per Rechtsklick aufs Auto, Abbau per Schlag. Rekonstruiert Car-Objekte,
 * wenn Chunks mit markierten Entities geladen werden.
 */
public final class CarListener implements Listener {

    private final CarManager carManager;
    private final CarConfig config;
    private final java.util.logging.Logger logger;

    public CarListener(CarManager carManager, CarConfig config, java.util.logging.Logger logger) {
        this.carManager = carManager;
        this.config = config;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawnCar(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!CarItem.isCarItem(item, carManager.getCarKey())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        var spawnLoc = clicked.getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        Car spawned = carManager.spawnCar(spawnLoc, player.getLocation().getYaw());
        if (config.debug) {
            logger.info("[Debug] Auto gespawnt: " + spawned.getBase().getUniqueId() + " bei " + spawnLoc);
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEnterCar(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!carManager.isCarPart(clicked)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (CarItem.isCarItem(inHand, carManager.getCarKey())) {
            // Klick mit Auto-Item aufs Auto gilt nicht als Einsteigen (vermeidt Doppel-Spawn-Verwirrung)
            return;
        }
        Car car = carManager.getCarByPart(clicked);
        if (car == null) {
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (car.getDriver() != null) {
            player.sendActionBar(Component.text("Dieses Auto ist bereits besetzt.", NamedTextColor.RED));
            return;
        }
        boolean mounted = car.getBase().addPassenger(player);
        boolean confirmed = player.getVehicle() == car.getBase();
        if (config.debug) {
            logger.info("[Debug] Einsteigen: spieler=" + player.getName()
                    + " clicked=" + clicked.getType() + " mounted=" + mounted + " confirmed=" + confirmed);
        }
        // Kein "Eingestiegen"-Hinweis: dass man sitzt, sieht man. Gemeldet wird nur der Fehlschlag.
        if (!mounted || !confirmed) {
            player.sendActionBar(Component.text("Einsteigen fehlgeschlagen.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBreakCar(EntityDamageByEntityEvent event) {
        Entity entity = event.getEntity();
        if (!carManager.isCarPart(entity)) {
            return;
        }
        event.setCancelled(true);
        Car car = carManager.getCarByPart(entity);
        if (car == null) {
            return;
        }
        // Nur abbauen, wenn gerade niemand eingestiegen ist — dass jemand drinsitzt, sieht man,
        // deshalb ohne Meldung
        if (car.getDriver() != null) {
            return;
        }
        // Im Kreativmodus wird das Item beim Platzieren nicht abgezogen — dann darf es beim
        // Abbauen auch nicht droppen, sonst vermehrt sich das Auto bei jedem Setzen und Schlagen.
        boolean creative = event.getDamager() instanceof Player p
                && p.getGameMode() == GameMode.CREATIVE;
        carManager.removeCar(car, !creative);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ArmorStand stand
                    && entity.getPersistentDataContainer().has(carManager.getCarKey())) {
                carManager.reRegister(stand);
            }
        }
    }
}
