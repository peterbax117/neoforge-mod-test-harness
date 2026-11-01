# NeoForge Mod Test Harness

Automated tests for NeoForge 1.21.1 mods, runnable from the command line and GitHub Actions:

- **GameTests** (server side): fixtures for survival fake players, item use, block counting, and shared empty
  test structures. Run headless with `gradlew runGameTestServer`.
- **Client tests**: scripted tests inside a real Minecraft client. The harness creates a creative superflat
  world, runs every `@ClientTest`, writes a report and exits. Run with `gradlew clientTest`.

The harness is a small test-only mod (`modtestharness`). It is loaded only in test runs and never packaged into
your mod's jar.

Requirements: Minecraft 1.21.1, NeoForge 21.1.x, ModDevGradle 2.0.x, Java 21.

## Setup in a mod

Clone this repository next to your mod (for example `C:\code\neoforge-mod-test-harness` beside
`C:\code\YourMod`). Then:

`settings.gradle`:

```groovy
def harnessDir = file(System.getenv('MOD_TEST_HARNESS_DIR') ?: '../neoforge-mod-test-harness')
if(harnessDir.isDirectory()) {
    includeBuild(harnessDir)
}
```

End of `build.gradle`, after the `neoForge { mods { ... } }` block:

```groovy
def harnessDir = file(System.getenv('MOD_TEST_HARNESS_DIR') ?: '../neoforge-mod-test-harness')
if(harnessDir.isDirectory()) {
    apply from: new File(harnessDir, 'gradle/test-harness.gradle')
}
```

The script adds two source sets, `src/gametest` and `src/clienttest`, the harness dependency, the
`gameTestServer` and `clientTest` runs, and the `clientTest` verification task. Without the harness checkout
the mod still builds; only the test tasks are missing.

## GameTests

```java
@GameTestHolder(HarnessTemplates.NAMESPACE)   // use the harness's shared empty templates
@PrefixGameTestTemplate(false)
public class MyGameTests {
    @GameTest(template = HarnessTemplates.EMPTY_9X5X9)
    public static void placesBlock(GameTestHelper helper) {
        fill(helper, Blocks.STONE, 3, 5, 1, 3, 5);
        FakePlayer player = survivalPlayer(helper, new ItemStack(Items.COBBLESTONE, 2), new BlockPos(4, 2, 0), 0, 0);
        useItemOn(helper, player, new BlockPos(4, 1, 4), Direction.UP);
        helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(4, 2, 4));
        helper.succeed();
    }
}
```

`GameTestSupport` (static import) provides `survivalPlayer`, `useItemOn`, `useItemInAir`, `fill`,
`countInStructure`, `countInLayer`, `assertBox`, `countItem` and `giveStacks`. Templates: `EMPTY_9X5X9` and
`EMPTY_15X8X15`. Your own templates under `data/<modid>/structure/` keep working with `@GameTestHolder(<modid>)`.

NeoForge filters GameTests by template namespace; the Gradle script enables both your mod id and
`modtestharness`.

## Client tests

```java
@ClientTestHolder
public final class MyClientTests {
    @ClientTest(value = "use key places the held block", order = 1)
    public static void useKey(ClientTestContext ctx) {
        BlockPos target = new BlockPos(0, -59, -2);
        ctx.fill(target, target, Blocks.STONE.defaultBlockState());
        ctx.giveMainHand(() -> new ItemStack(Items.COBBLESTONE, 4));
        ctx.teleport(new BlockPos(0, -60, 0), 180, 0);
        ctx.look(180, 0);
        ctx.clickUse();
        ctx.await("block placed", () -> ctx.serverBlock(target.south()).is(Blocks.COBBLESTONE));
    }
}
```

Tests run on a background thread; `ClientTestContext` hops onto the client or integrated server thread for
each call, so tests read top to bottom.

| Area | Methods |
|---|---|
| Threads | `onClient`, `onServer`, `onServerDo` |
| Waiting | `await`, `awaitScreen`, `waitTicks`, `check` |
| Input | `sneak`, `pressKey`, `click`, `clickUse`, `clickAttack`, `scroll`, `look` |
| World | `giveMainHand`, `teleport`, `fill`, `serverBlock` |

Input goes through the game's own paths: key mappings, `KeyboardHandler.keyPress` (fires `InputEvent.Key`) and
NeoForge's scroll hook. Inputs are released and screens closed after every test.

The world is creative superflat; the surface is y = -61, so players stand at y = -60.

### Mods that poll the physical key state

Injected key events do not change what GLFW reports to `InputConstants.isKeyDown`. If your mod polls keys that
way, route the check through a seam and let tests include `SimulatedKeys`:

```java
// In the mod
public static IntPredicate keyStateSource = id -> InputConstants.isKeyDown(window(), id);

// In a @ClientTestHolder class
static {
    IntPredicate physical = MyEvents.keyStateSource;
    MyEvents.keyStateSource = key -> SimulatedKeys.isDown(key) || physical.test(key);
}
```

### Report

`build/clienttest/report.txt`, first line `PASS n passed, 0 failed` or `FAIL ...`, then one line per test. The
`clientTest` task fails unless the report starts with PASS. A watchdog halts the client after 10 minutes
(`-Dmodtestharness.clienttest.timeoutMinutes`).

## CI (GitHub Actions)

Check out the harness inside the workspace and point `MOD_TEST_HARNESS_DIR` at it. The client needs a display;
Xvfb with Mesa software OpenGL works on `ubuntu-latest`.

```yaml
env:
  MOD_TEST_HARNESS_DIR: harness
steps:
  - uses: actions/checkout@v7
  - uses: actions/checkout@v7
    with:
      repository: peterbax117/neoforge-mod-test-harness
      path: harness
  - uses: actions/setup-java@v6
    with: { distribution: temurin, java-version: 21 }
  - uses: gradle/actions/setup-gradle@v6
  - run: ./gradlew runGameTestServer
  - run: sudo apt-get update && sudo apt-get install -y xvfb libgl1-mesa-dri
  - run: xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew clientTest
```

Add `/harness/` to the mod's `.gitignore`.

## Writing reliable tests

- Prove each new test can fail: break the code once on purpose, confirm a clear failure message, revert.
- Wait for state that proves the new fixture arrived. `giveMainHand` waits for an empty hand, then the exact
  new item, so a previous test's identical item cannot satisfy the wait.
- `GameTestHelper.forEveryBlockInStructure` yields relative positions; read them with `helper.getBlockState`.
- When a new test fails, check the mod's documented behavior before changing either the test or the mod.

## What this cannot do

- Pixel-level rendering comparison. Check render inputs (computed preview positions, tint colors, model
  properties) instead.
- Protocol bots (Mineflayer and AI agents built on it) cannot join a NeoForge server with required payloads,
  and never run your mod's client code.

## Self-tests

The harness tests itself with vanilla content: `gradlew runGameTestServer` and `gradlew clientTest` in this
repository.

## License

MIT
