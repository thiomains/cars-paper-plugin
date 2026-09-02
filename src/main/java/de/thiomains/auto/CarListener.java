package de.thiomains.auto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
 * Einsteigen per Rechtsklick aufs Auto, Hupe per Rechtsklick aus dem Sitz, Abbau per
 * Schlag. Rekonstruiert Car-Objekte, wenn Chunks mit markierten Entities geladen werden.
 */
public final class CarListener implements Listener {

    /** Mindestabstand zweier Hupstoesse. Tick-basiert wie der Untersteuer-Sound, damit
     *  Server-Lag den Abstand nicht verschiebt. */
    private static final long HORN_COOLDOWN_TICKS = 10;

    private final CarManager carManager;
    private final CarConfig config;
    private final java.util.logging.Logger logger;

    public CarListener(CarManager carManager, CarConfig config, java.util.logging.Logger logger) {
        this.carManager = carManager;
        this.config = config;
        this.logger = logger;
    }

    /**
     * Hupe: Rechtsklick, waehrend man faehrt. Der Klick wird komplett geschluckt — aus dem
     * Auto heraus wird nichts platziert oder benutzt. Die Prioritaet LOW ist dabei kein
     * Detail: onSpawnCar laeuft auf NORMAL mit ignoreCancelled und faellt damit aus, sobald
     * hier gecancelt wurde. Sonst spawnt der Fahrer mit einem Auto-Item in der Hand aus dem
     * Sitz heraus ein zweites Auto.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onHorn(PlayerInteractEvent event) {
        // Ohne Hand-Filter feuert derselbe Klick fuer Haupt- UND Nebenhand, also doppelt.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Car car = drivenCar(event.getPlayer());
        if (car == null) {
            return;
        }
        event.setCancelled(true);
        honk(car, event.getPlayer());
    }

    /** Das Auto, das dieser Spieler gerade faehrt (nicht: in dem er nur sitzt), sonst null. */
    private Car drivenCar(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return null;
        }
        Car car = carManager.getCarByPart(vehicle);
        return car != null && car.getDriver() == player ? car : null;
    }

    /** Spielt die Hupe am Auto ab, gedeckelt durch den Cooldown. */
    private void honk(Car car, Player player) {
        long now = Bukkit.getCurrentTick();
        if (now - car.getLastHornTick() < HORN_COOLDOWN_TICKS) {
            return;
        }
        car.setLastHornTick(now);
        Location loc = car.getBase().getLocation();
        // Bukkits Hoerweite ist 16 Bloecke mal Lautstaerke; horn-range steht in Bloecken.
        float volume = (float) (config.hornRange / 16.0);
        loc.getWorld().playSound(loc, config.hornSound, volume, (float) config.hornPitch);
        if (config.debug) {
            logger.info("[Debug] Hupe: spieler=" + player.getName()
                    + " sound=" + CarConfig.soundName(config.hornSound)
                    + " pitch=" + config.hornPitch + " reichweite=" + config.hornRange);
        }
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
        Car car = carManager.getCarByPart(clicked);
        if (car == null) {
            return;
        }
        // Der Fahrer trifft aus dem Sitz heraus seine eigene Klick-Hitbox: das ist die Hupe.
        // Ohne diesen Zweig bekaeme ausgerechnet er die Meldung "bereits besetzt".
        if (car.getDriver() == player) {
            honk(car, player);
            return;
        }
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (CarItem.isCarItem(inHand, carManager.getCarKey())) {
            // Klick mit Auto-Item aufs Auto gilt nicht als Einsteigen (vermeidt Doppel-Spawn-Verwirrung)
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
