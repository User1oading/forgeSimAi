
package forge.ai.simulation;

import java.util.Random;

public class SimRandom {
    private static final ThreadLocal<Random> threadRandom =
            ThreadLocal.withInitial(Random::new);

    public static Random get() {
        return threadRandom.get();
    }

    public static void setSeed(long seed) {
        threadRandom.get().setSeed(seed);
    }

    public static void setRandom(Random r) {
        threadRandom.set(r);
    }
}