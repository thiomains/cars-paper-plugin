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
    private double yawVel;
    private double spinVel;
    private double lastPitchDeg;
    private double lastRollDeg;
    private boolean lastStepBlocked;
    private double fallSpeed;
    private long lastUndersteerSoundTick = -1000;
    private int simTicks;
    private boolean simDrift;
    private boolean simDrive;
    private java.util.function.Consumer<SimSample> simObserver;

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

    /** Geglaettete aktuelle Drehrate in Grad/Tick (Lenk-Schwung). */
    public double getYawVel() {
        return yawVel;
    }

    public void setYawVel(double yawVel) {
        this.yawVel = yawVel;
    }

    /** Crash-Drehrate in Grad/Tick (Hebel-Impuls aus Wandkontakt); getrennt von yawVel,
     *  damit der Lenk-Smoother den Crash-Spin nicht sofort wieder bekämpft. */
    public double getSpinVel() {
        return spinVel;
    }

    public void setSpinVel(double spinVel) {
        this.spinVel = spinVel;
    }

    /** Zuletzt ans Modell übertragene Pitch/Roll-Winkel (Grad), EMA-geglättet. */
    public double getLastPitchDeg() {
        return lastPitchDeg;
    }

    public void setLastPitchDeg(double lastPitchDeg) {
        this.lastPitchDeg = lastPitchDeg;
    }

    public double getLastRollDeg() {
        return lastRollDeg;
    }

    public void setLastRollDeg(double lastRollDeg) {
        this.lastRollDeg = lastRollDeg;
    }

    /** true, wenn der letzte Tick Blockkontakt hatte (erlaubt Rangieren im Stand). */
    public boolean wasStepBlocked() {
        return lastStepBlocked;
    }

    public void setStepBlocked(boolean stepBlocked) {
        this.lastStepBlocked = stepBlocked;
    }

    public double getFallSpeed() {
        return fallSpeed;
    }

    public void setFallSpeed(double fallSpeed) {
        this.fallSpeed = fallSpeed;
    }

    public long getLastUndersteerSoundTick() {
        return lastUndersteerSoundTick;
    }

    public void setLastUndersteerSoundTick(long lastUndersteerSoundTick) {
        this.lastUndersteerSoundTick = lastUndersteerSoundTick;
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

    public boolean isSimDrive() {
        return simDrive;
    }

    public void setSimDrive(boolean simDrive) {
        this.simDrive = simDrive;
    }

    /** Sammelt die Tick-Werte statt sie zu loggen (vom SelfTest gesetzt); null = normales [Sim]-Log. */
    public java.util.function.Consumer<SimSample> getSimObserver() {
        return simObserver;
    }

    public void setSimObserver(java.util.function.Consumer<SimSample> simObserver) {
        this.simObserver = simObserver;
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
