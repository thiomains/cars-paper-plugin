package de.thiomains.auto;

import org.bukkit.Material;

/**
 * Bestimmt den Grip-Faktor anhand des Untergrunds. Jede Betonfarbe gilt als
 * optimaler Fahrbelag (Name endet auf {@code CONCRETE}), weiches Erdreich als rutschig,
 * alle Eisarten als spiegelglatt.
 */
public final class GripCalculator {

    private final CarConfig config;

    public GripCalculator(CarConfig config) {
        this.config = config;
    }

    public double gripFor(Material material) {
        if (material.name().endsWith("CONCRETE")) {
            return config.gripConcrete;
        }
        return switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM,
                 DIRT_PATH, FARMLAND, MUD, SNOW, SNOW_BLOCK -> config.gripGrass;
            case ICE, PACKED_ICE, BLUE_ICE, FROSTED_ICE -> config.gripIce;
            default -> config.gripDefault;
        };
    }
}
