package de.thiomains.auto;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verwaltet alle Autos. Ein Auto besteht aus:
 * ArmorStand (fahrbarer Sitz, trägt den "auto"-Marker), ItemDisplay (Modell) und
 * Interaction (Klick-Hitbox) – Display und Interaction reiten dabei auf dem ArmorStand.
 * Beim Neuladen von Chunks werden markierte ArmorStands wieder als Car registriert.
 */
public final class CarManager {

    private static final float MODEL_SCALE = 1.5f;
    private static final float MODEL_Y_OFFSET = 0.6f;

    private final JavaPlugin plugin;
    private final NamespacedKey carKey;
    private final NamespacedKey carPartKey;
    private final Map<UUID, Car> cars = new HashMap<>();

    public CarManager(JavaPlugin plugin, NamespacedKey carKey, NamespacedKey carPartKey) {
        this.plugin = plugin;
        this.carKey = carKey;
        this.carPartKey = carPartKey;
    }

    public NamespacedKey getCarKey() {
        return carKey;
    }

    public NamespacedKey getCarPartKey() {
        return carPartKey;
    }

    public Car spawnCar(Location location, float yaw) {
        World world = location.getWorld();
        Location spawn = new Location(world, location.getX(), location.getY(), location.getZ(), yaw, 0f);

        ArmorStand base = world.spawn(spawn, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSmall(false);
            stand.setMarker(false);
            stand.setSilent(true);
            stand.setCollidable(false);
            var scale = stand.getAttribute(Attribute.SCALE);
            if (scale != null) {
                scale.setBaseValue(0.0625);
            }
            mark(stand, carKey);
        });

        ItemDisplay model = spawnModelDisplay(spawn, yaw);
        Interaction hitbox = spawnHitbox(spawn);

        base.addPassenger(model);
        base.addPassenger(hitbox);

        Car car = new Car(base, model, hitbox);
        car.setYaw(yaw);
        cars.put(base.getUniqueId(), car);
        return car;
    }

    /** Entfernt das Auto komplett und droppt das Item, wenn dropItem gesetzt ist. */
    public void removeCar(Car car, boolean dropItem) {
        cars.remove(car.getBase().getUniqueId());
        Location loc = car.getBase().getLocation();
        car.getModel().remove();
        car.getHitbox().remove();
        car.getBase().remove();
        if (dropItem) {
            loc.getWorld().dropItemNaturally(loc, CarItem.createCarItem(carKey));
        }
    }

    /** Erzeugt fehlende Display/Hitbox-Teile neu (z. B. nach Fremdeingriff durch andere Plugins). */
    public void ensureParts(Car car) {
        ArmorStand stand = car.getBase();
        Location loc = stand.getLocation();
        if (car.getModel() == null || !car.getModel().isValid()) {
            ItemDisplay model = spawnModelDisplay(loc, car.getYaw());
            stand.addPassenger(model);
            car.setModel(model);
        }
        if (car.getHitbox() == null || !car.getHitbox().isValid()) {
            Interaction hitbox = spawnHitbox(loc);
            stand.addPassenger(hitbox);
            car.setHitbox(hitbox);
        }
    }

    public Car getCarByBase(UUID baseId) {
        return cars.get(baseId);
    }

    /** Findet das Auto zu einem beliebigen Teil (Basis, Display oder Hitbox). */
    public Car getCarByPart(Entity entity) {
        Car direct = cars.get(entity.getUniqueId());
        if (direct != null) {
            return direct;
        }
        Entity vehicle = entity.getVehicle();
        while (vehicle != null) {
            Car found = cars.get(vehicle.getUniqueId());
            if (found != null) {
                return found;
            }
            vehicle = vehicle.getVehicle();
        }
        return null;
    }

    /** true, wenn die Entity ein Auto-Teil ist (Basis, Display oder Hitbox). */
    public boolean isCarPart(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.has(carKey, PersistentDataType.BYTE) || pdc.has(carPartKey, PersistentDataType.BYTE);
    }

    public List<Car> getCars() {
        return List.copyOf(cars.values());
    }

    public int size() {
        return cars.size();
    }

    /**
     * Registriert einen markierten ArmorStand nach Chunk-Load bzw. Restart wieder.
     * Fehlende Passagier-Entities (Display/Hitbox) werden neu erzeugt.
     */
    public void reRegister(ArmorStand stand) {
        if (cars.containsKey(stand.getUniqueId())) {
            return;
        }
        var scaleAttr = stand.getAttribute(Attribute.SCALE);
        if (scaleAttr != null && scaleAttr.getBaseValue() != 0.0625) {
            scaleAttr.setBaseValue(0.0625);
        }
        ItemDisplay model = null;
        Interaction hitbox = null;
        for (Entity passenger : stand.getPassengers()) {
            if (passenger instanceof ItemDisplay display && isCarPart(display)) {
                model = display;
            } else if (passenger instanceof Interaction interaction && isCarPart(interaction)) {
                hitbox = interaction;
            }
        }
        Location loc = stand.getLocation();
        float yaw = loc.getYaw();
        if (model == null) {
            model = spawnModelDisplay(loc, yaw);
            stand.addPassenger(model);
        }
        if (hitbox == null) {
            hitbox = spawnHitbox(loc);
            stand.addPassenger(hitbox);
        }
        Car car = new Car(stand, model, hitbox);
        car.setYaw(yaw);
        cars.put(stand.getUniqueId(), car);
        plugin.getLogger().info("Auto wiederhergestellt bei " + loc.getBlockX() + " "
                + loc.getBlockY() + " " + loc.getBlockZ() + " (Welt " + loc.getWorld().getName() + ").");
    }

    private ItemDisplay spawnModelDisplay(Location location, float yaw) {
        return location.getWorld().spawn(location, ItemDisplay.class, display -> {
            display.setItemStack(CarItem.createModelItem());
            display.setTeleportDuration(2);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
            display.setTransformation(new Transformation(
                    new Vector3f(0f, MODEL_Y_OFFSET, 0f),
                    new Quaternionf(),
                    new Vector3f(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE),
                    new Quaternionf()));
            display.setRotation(yaw, 0f);
            display.setSilent(true);
            mark(display, carPartKey);
        });
    }

    private Interaction spawnHitbox(Location location) {
        return location.getWorld().spawn(location, Interaction.class, interaction -> {
            interaction.setInteractionWidth(2.4f);
            interaction.setInteractionHeight(1.95f);
            interaction.setSilent(true);
            mark(interaction, carPartKey);
        });
    }

    private void mark(Entity entity, NamespacedKey key) {
        entity.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
    }
}
