package de.thiomains.auto;

import org.bukkit.Input;

/**
 * Fahrer-Eingabe ohne Fahrer. {@link org.bukkit.Input} ist eine reine Schnittstelle aus
 * sieben Boolean-Gettern — damit laufen Gas, Bremse, Handbremse und Lenkung im Selftest
 * durch DIESELBE Stelle wie beim echten Spieler ({@code DriveTask.applyInput}) statt durch
 * eine nachgebaute Kopie der Formel, die stillschweigend auseinanderlaufen kann.
 *
 * @param forward  W — Gas bzw. Bremse aus der Rückwärtsfahrt
 * @param backward S — Bremse bzw. Rückwärtsgang
 * @param left     A — Lenkung nach links
 * @param right    D — Lenkung nach rechts
 * @param jump     Sprungtaste — Handbremse
 */
public record SimInput(boolean forward, boolean backward, boolean left, boolean right,
                       boolean jump) implements Input {

    public static final SimInput GAS = new SimInput(true, false, false, false, false);
    public static final SimInput BREMSE = new SimInput(false, true, false, false, false);
    public static final SimInput HANDBREMSE = new SimInput(false, false, false, false, true);
    public static final SimInput RECHTS = new SimInput(false, false, false, true, false);
    public static final SimInput GAS_RECHTS = new SimInput(true, false, false, true, false);

    @Override
    public boolean isForward() {
        return forward;
    }

    @Override
    public boolean isBackward() {
        return backward;
    }

    @Override
    public boolean isLeft() {
        return left;
    }

    @Override
    public boolean isRight() {
        return right;
    }

    @Override
    public boolean isJump() {
        return jump;
    }

    /** Schleichen und Sprinten haben im Fahrmodell keine Bedeutung. */
    @Override
    public boolean isSneak() {
        return false;
    }

    @Override
    public boolean isSprint() {
        return false;
    }
}
