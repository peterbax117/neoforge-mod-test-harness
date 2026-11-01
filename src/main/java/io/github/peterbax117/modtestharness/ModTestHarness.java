package io.github.peterbax117.modtestharness;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Test-only helper mod. Load it in GameTest and client test runs only; it provides
 * {@link io.github.peterbax117.modtestharness.gametest.GameTestSupport} fixtures, shared empty test
 * structures, and the scripted client test runner.
 */
@Mod(ModTestHarness.MODID)
public final class ModTestHarness
{
    public static final String MODID = "modtestharness";
    public static final Logger LOGGER = LogUtils.getLogger();
}
