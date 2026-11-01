package io.github.peterbax117.modtestharness.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keys held down by {@link ClientTestContext#pressKey}. Injected key events do not change the physical key
 * state GLFW reports, so a mod that polls {@code InputConstants.isKeyDown} directly needs a small seam that
 * also consults {@link #isDown(int)} during tests.
 */
public final class SimulatedKeys
{
    private static final Set<Integer> DOWN = ConcurrentHashMap.newKeySet();

    private SimulatedKeys() {
    }

    public static boolean isDown(int glfwKey) {
        return DOWN.contains(glfwKey);
    }

    static void set(int glfwKey, boolean down) {
        if(down) DOWN.add(glfwKey);
        else DOWN.remove(glfwKey);
    }

    static Set<Integer> held() {
        return Set.copyOf(DOWN);
    }
}
