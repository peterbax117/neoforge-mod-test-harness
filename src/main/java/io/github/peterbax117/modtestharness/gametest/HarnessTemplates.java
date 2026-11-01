package io.github.peterbax117.modtestharness.gametest;

import io.github.peterbax117.modtestharness.ModTestHarness;

/**
 * Empty structure templates shipped by the harness. Use them as
 * {@code @GameTest(templateNamespace = HarnessTemplates.NAMESPACE, template = HarnessTemplates.EMPTY_9X5X9)}.
 * NeoForge filters GameTests by template namespace, so the run's {@code neoforge.enabledGameTestNamespaces}
 * must include {@value #NAMESPACE} (the consumer Gradle script does this).
 */
public final class HarnessTemplates
{
    public static final String NAMESPACE = ModTestHarness.MODID;
    /** 9 wide, 5 high, 9 deep, all air. */
    public static final String EMPTY_9X5X9 = "empty_9x5x9";
    /** 15 wide, 8 high, 15 deep, all air. */
    public static final String EMPTY_15X8X15 = "empty_15x8x15";

    private HarnessTemplates() {
    }
}
