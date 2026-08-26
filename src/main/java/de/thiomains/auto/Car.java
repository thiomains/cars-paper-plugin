package de.thiomains.auto;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;

/**
 * Fahrzustand eines Autos: die drei Entities plus physikalische Größen.
 * Bewegung ist ein Geschwindigkeitsvektor (velX/velZ) in Blöcken pro Tick;
 * die Fahrtrichtungs-Komponente entlang des Yaw bestimmt über Vor-/Rückwärtsfahrt.
 */
public final class Car {

    private final ArmorStand base;
    private ItemDisplay model;
    private Interaction hitbox;

    private double velX;
    private double velZ;
    private float yaw;
    private double fallSpeed;
    private long lastUndersteerSoundMs;
    private int simTicks;
    private boolean simDrift;

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

    public double getVelX() {
        return velX;
    }

    public void setVelX(double velX) {
        this.velX = velX;
    }

    public double getVelZ() {
        return velZ;
    }

    public void setVelZ(double velZ) {
        this.velZ = velZ;
    }

    /** Komfortmethode für die Simulation: setzt Geschwindigkeit entlang +Z (Sim-Spawn-Yaw 0). */
    public void setSpeed(double speedAlongZ) {
        this.velX = 0;
        this.velZ = speedAlongZ;
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

    public boolean isSimDrift() {
        return simDrift;
    }

    public void setSimDrift(boolean simDrift) {
        this.simDrift = simDrift;
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
