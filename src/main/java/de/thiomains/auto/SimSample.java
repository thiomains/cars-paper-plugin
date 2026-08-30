package de.thiomains.auto;

/**
 * Ein Fahrzustand pro Tick, wie ihn die Simulation ausgibt. Ohne Beobachter landet
 * das als {@code [Sim]}-Zeile im Log; der {@link SelfTest} sammelt die Werte stattdessen
 * und prüft sie gegen die Erwartungen.
 */
public record SimSample(int ticksLeft, double speed, double vf, double slipDeg, boolean grounded,
                        double grip, boolean blocked, double x, double y, double z, float yaw) {

    /** Kompakte Zeile für Log und Fehlerausgabe. */
    public String describe() {
        return String.format("t=%d v=%.4f vf=%.4f slip=%.1f grounded=%s grip=%.2f blocked=%s pos=%.3f %.3f %.3f",
                ticksLeft, speed, vf, slipDeg, grounded, grip, blocked, x, y, z);
    }
}
