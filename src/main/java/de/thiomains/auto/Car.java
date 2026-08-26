package de.thiomains.auto;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;

/**
 * Fahrzustand eines Autos: die drei Entities plus dynamische Größen.
 * speed ist vorzeichenbehaftet (negativ = Rückwärtsgang), Einheit Blöcke pro Tick.
 */
public final class Car {

    private final ArmorStand base;
    private ItemDisplay model;
    private Interaction hitbox;

    private double speed;
    private float yaw;
    private double fallSpeed;
    private long lastUndersteerSoundMs;
    private int simTicks;

    public Car(ArmorStand base, ItemDisplay model, Interaction hitbox) {
        this.base = base;
        this.model = model;
        this.hitbox = hitbox;
        this.yaw = base.getLocation().getYaw();
    }

    public ArmorStand getBase() {
        return base;
    }

    public ItemDisplay getModel() {
        return model;
    }

    public void setModel(ItemDisplay model) {
        this.model = model;
    }

    public Interaction getHitbox() {
        return hitbox;
    }

    public void setHitbox(Interaction hitbox) {
        this.hitbox = hitbox;
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public double getFallSpeed() {
        return fallSpeed;
    }

    public void setFallSpeed(double fallSpeed) {
        this.fallSpeed = fallSpeed;
    }

    public long getLastUndersteerSoundMs() {
        return lastUndersteerSoundMs;
    }

    public void setLastUndersteerSoundMs(long lastUndersteerSoundMs) {
        this.lastUndersteerSoundMs = lastUndersteerSoundMs;
    }

    public int getSimTicks() {
        return simTicks;
    }

    public void setSimTicks(int simTicks) {
        this.simTicks = simTicks;
    }

    /** Der fahrende Spieler (Passagier auf dem ArmorStand), oder null. */
    public Player getDriver() {
        for (Entity passenger : base.getPassengers()) {
            if (passenger instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}
