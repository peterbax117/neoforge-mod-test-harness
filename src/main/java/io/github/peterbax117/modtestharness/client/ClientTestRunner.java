package io.github.peterbax117.modtestharness.client;

import io.github.peterbax117.modtestharness.ModTestHarness;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.ElementType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs every {@link ClientTest} in a real client, modeled on NeoForge's own SelfTestClient. Inactive unless the
 * {@code modtestharness.clienttest.report} system property is set. It creates a creative superflat world, runs
 * the tests, writes the report (first line PASS or FAIL) and stops the client. A watchdog halts a hung client.
 */
@EventBusSubscriber(modid = ModTestHarness.MODID, value = Dist.CLIENT)
public final class ClientTestRunner
{
    public static final String REPORT_PROPERTY = "modtestharness.clienttest.report";
    private static final String TIMEOUT_PROPERTY = "modtestharness.clienttest.timeoutMinutes";
    private static final AtomicLong CLIENT_TICKS = new AtomicLong();

    private record TestMethod(String name, int order, Method method) {}

    private ClientTestRunner() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        String report = System.getProperty(REPORT_PROPERTY);
        if(report == null) return;

        Path reportFile = Path.of(report);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> CLIENT_TICKS.incrementAndGet());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre e) -> ClientTestContext.onClientTickPre());
        Thread runner = new Thread(() -> runAll(reportFile), "modtestharness-clienttest");
        runner.setDaemon(true);
        runner.start();
        startWatchdog(reportFile, Integer.getInteger(TIMEOUT_PROPERTY, 10));
    }

    private static void runAll(Path reportFile) {
        ClientTestContext ctx = new ClientTestContext(CLIENT_TICKS);
        List<String> lines = new ArrayList<>();
        int failures = 0;
        int passed = 0;
        try {
            List<TestMethod> tests = discoverTests();
            if(tests.isEmpty()) throw new IllegalStateException("No @ClientTest methods found in @ClientTestHolder classes");
            enterTestWorld(ctx);

            for(TestMethod test : tests) {
                try {
                    test.method().invoke(null, ctx);
                    passed++;
                    lines.add("  ok    " + test.name());
                } catch(Throwable t) {
                    Throwable cause = t instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                    failures++;
                    lines.add("  FAIL  " + test.name() + ": " + cause);
                    ModTestHarness.LOGGER.error("Client test failed: {}", test.name(), cause);
                } finally {
                    try {
                        ctx.releaseAllInput();
                        ctx.setGameMode(GameType.CREATIVE);
                        ctx.waitTicks(5);
                    } catch(Throwable ignored) {
                    }
                }
            }
        } catch(Throwable t) {
            failures++;
            lines.add("  FAIL  setup: " + stackTrace(t));
        }

        String header = (failures == 0 ? "PASS" : "FAIL") + " " + passed + " passed, " + failures + " failed";
        writeReport(reportFile, header + System.lineSeparator() + String.join(System.lineSeparator(), lines));
        ModTestHarness.LOGGER.info("Client tests finished: {}", header);
        leaveWorldAndStop(ctx);
    }

    private static List<TestMethod> discoverTests() throws ClassNotFoundException {
        List<TestMethod> tests = new ArrayList<>();
        List<String> holders = ModList.get().getAllScanData().stream()
                .flatMap(data -> data.getAnnotatedBy(ClientTestHolder.class, ElementType.TYPE))
                .map(annotation -> annotation.clazz().getClassName())
                .distinct().sorted().toList();

        for(String className : holders) {
            Class<?> holder = Class.forName(className, true, ClientTestRunner.class.getClassLoader());
            for(Method method : holder.getDeclaredMethods()) {
                ClientTest annotation = method.getAnnotation(ClientTest.class);
                if(annotation == null) continue;
                if(!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != ClientTestContext.class) {
                    throw new IllegalStateException("@ClientTest " + holder.getName() + "." + method.getName()
                            + " must be static and take one ClientTestContext");
                }
                method.setAccessible(true);
                String name = annotation.value().isEmpty() ? holder.getSimpleName() + "." + method.getName() : annotation.value();
                tests.add(new TestMethod(name, annotation.order(), method));
            }
        }
        tests.sort(Comparator.comparingInt(TestMethod::order)
                .thenComparing(t -> t.method().getDeclaringClass().getName())
                .thenComparing(t -> t.method().getName()));
        return tests;
    }

    private static void enterTestWorld(ClientTestContext ctx) {
        Minecraft mc = ctx.mc();
        ctx.await("game finished loading", 300, () -> mc.getOverlay() == null && mc.screen != null);
        ctx.onClient(() -> {
            // A window that loses focus would otherwise open the pause menu and block input.
            mc.options.pauseOnLostFocus = false;
            mc.options.onboardAccessibility = false;
            LevelSettings settings = new LevelSettings("clienttest-" + System.currentTimeMillis(), GameType.CREATIVE,
                    false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(settings.levelName(), settings, new WorldOptions(0L, false, false),
                    registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                    mc.screen);
        });
        ctx.await("player joined the test world", 300,
                () -> mc.player != null && mc.level != null && mc.screen == null && mc.getSingleplayerServer() != null);
        ctx.waitTicks(40);
    }

    private static void leaveWorldAndStop(ClientTestContext ctx) {
        try {
            ctx.onClient(() -> {
                Minecraft mc = ctx.mc();
                if(mc.level != null) {
                    mc.level.disconnect();
                    mc.disconnect(new GenericMessageScreen(Component.literal("Client tests finished")));
                }
                mc.stop();
            });
        } catch(Throwable t) {
            Runtime.getRuntime().halt(1);
        }
    }

    private static void startWatchdog(Path reportFile, int minutes) {
        Thread watchdog = new Thread(() -> {
            try {
                TimeUnit.MINUTES.sleep(minutes);
            } catch(InterruptedException e) {
                return;
            }
            if(!Files.exists(reportFile)) writeReport(reportFile, "FAIL client tests did not finish within " + minutes + " minutes");
            Runtime.getRuntime().halt(1);
        }, "modtestharness-clienttest-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void writeReport(Path reportFile, String content) {
        try {
            Files.createDirectories(reportFile.getParent());
            Files.writeString(reportFile, content + System.lineSeparator());
        } catch(IOException e) {
            ModTestHarness.LOGGER.error("Could not write client test report", e);
        }
    }

    private static String stackTrace(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
