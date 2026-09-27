# Medieval Times Mixins

Mixin patches for the **Medieval Times: Empires Rising** modpack (Minecraft 1.12.2, Forge).

Two jobs:

1. **Replace hand-edited mod jars.** Behaviour currently baked into renamed jars
   (`ancientwarfare-...-TownyRaidCompat-v2-FTBOnlyWORKING.jar` and friends) moves here, so
   third-party mods can go back to pristine and be updated by dropping in a new jar.
2. **Performance fixes** found by profiling prod.

BSD-3-Clause.

## Requirements

| | |
|---|---|
| JDK to *run* Gradle | 17 or newer — Gradle 9 will not start on Java 8 |
| JDK the mod compiles to | 8, provisioned automatically by the toolchain |
| MixinBooter on the server | **11.0 or newer** (prod was on 10.6 — see below) |

You do not need a Java 8 JDK installed. `settings.gradle` enables the Foojay toolchain
resolver, so Gradle downloads one.

## First run

There is no `gradlew` in the repo yet. Either:

```bash
gradle wrapper --gradle-version 9.7.0
```

or open the project in IntelliJ, which reads `gradle/wrapper/gradle-wrapper.properties`,
downloads Gradle 9.7 and generates the wrapper itself. Then:

```bash
./gradlew build
```

The first build downloads and deobfuscates Minecraft and will take several minutes. After
that, `./gradlew runServer` gives you a dev server with the mixins applied.

The output jar lands in `build/libs/` already reobfuscated — that is the one to deploy.

## The MixinBooter version matters

Prod is running MixinBooter **10.6**, which predates the `MixinConfigs` manifest attribute
being handled as a first-class path and still expects `IEarlyMixinLoader` /
`ILateMixinLoader`. This project targets **11.17**, where those are deprecated and the
manifest attribute is the supported route.

**Update MixinBooter on the server and in the pack to 11.17 before deploying this.** It is
a shared library coremod, so every mixin-using mod in the pack goes through it — test on
the local server first. 11.x also adds `config/mixinbooter.cfg` (blacklist a misbehaving
mixin config without repackaging) and a dedicated `logs/mixinbooter.log`, both of which you
will want the first time a mixin fails to apply after a mod update.

## Layout

```
src/main/java/com/novotimo/mtmixins/
    MedievalTimesMixins.java          @Mod container; registers nothing
    mixin/
        vanilla/                      Minecraft and Forge targets (need the refmap)
        armourers/                    per-mod packages for mod targets
src/main/resources/
    mcmod.info
    mixins.mtmixins.json              the mixin config
```

## Which list does a mixin go in?

`mixins.mtmixins.json` has three arrays, and the choice is not cosmetic:

- **`mixins`** — applied on both physical sides. **This is the default and what you almost
  always want.** Singleplayer runs an integrated server inside the client, so anything
  fixing logical-server behaviour belongs here or singleplayer silently misses it.
- **`client`** — physical client only.
- **`server`** — dedicated server only. Use it in exactly two cases: the target class does
  not exist client-side (ForgeEssentials ships as a server-only jar), or applying it
  client-side would be unsafe (dropping Forge's `EntityDataManager` read lock is fine on a
  dedicated server where entity ticking is single-threaded, but on a client the render
  thread reads while the netty thread writes).

## Two patterns, one of each in the repo

**Vanilla / Forge targets** — `mixin/vanilla/MixinEntityItem`. Write MCP names
(`onUpdate`, `motionX`). The annotation processor wired up by `modUtils.enableMixins`
generates `mixins.mtmixins.refmap.json`, which maps them to the SRG names present at
runtime. Without the refmap a mixin applies in dev and silently fails in production, which
is the classic way to lose an afternoon.

**Mod targets** — `mixin/armourers/MixinAddonCustomNPCS`. Use `@Pseudo` with a string
`targets =` and `remap = false`. The target class then **does not need to be on the compile
classpath at all**, and Mixin does nothing instead of crashing when the mod is absent. That
matters here because several mods you need to patch (BuycraftX, Holograms) are on no maven.

When a mixin does need the real types, prefer CurseMaven over vendoring a jar:

```groovy
compileOnly 'curse.maven:oreveins-281216:4642373'
```

Otherwise drop the jar in `libs/` (gitignored) and use `compileOnly name: 'the-jar-name'`.

## The invoke-owner trap

This one already cost a boot failure, so it goes first.

For an `@At(value = "INVOKE")` target, the owner in the string must be the owner **in the
bytecode**, which is not necessarily the class that declares the method. javac emits the
*qualifying type* of the receiver. So for `this.pushOutOfBlocks(...)` inside `EntityItem`,
where `pushOutOfBlocks` is declared on `Entity`, the constant pool holds:

```
#174 = Methodref // net/minecraft/entity/item/EntityItem.func_145771_j:(DDD)Z
```

Targeting `Lnet/minecraft/entity/Entity;pushOutOfBlocks(DDD)Z` matches nothing, and the
failure mode is ugly: the mixin *applies*, then the injection check throws at boot with

```
failed injection check, (0/1) succeeded. Scanned 0 target(s).
```

which reads like a refmap problem and is not one. Before writing an INVOKE target, read the
real owner out of `build/rfg/srg_merged_minecraft.jar`:

```bash
unzip -o -j build/rfg/srg_merged_minecraft.jar net/minecraft/entity/item/EntityItem.class -d /tmp
javap -v -p /tmp/EntityItem.class | grep -A1 "Methodref.*func_145771_j"
```

That jar is SRG-named original Mojang bytecode, i.e. exactly what the production server
loads. `mcp_patched_minecraft-sources.jar` in the same folder is the readable source.

Note `javap -c` omits the owner when it equals the current class, so a bare
`invokevirtual // Method func_145771_j:(DDD)Z` means the owner **is** the current class.
Resolve the constant pool entry rather than trusting the disassembly line.

Once the owner is right, a second check bites: **the receiver parameter must be typed to
match the invoke owner.** With owner `EntityItem`, a handler declaring `Entity self` is
rejected at apply time:

```
Found unexpected argument type net.minecraft.entity.Entity at index 0,
expected net.minecraft.entity.item.EntityItem
```

Same underlying fact, second symptom. Type the receiver as the owner class, not the
declaring class, even when every field you read off it is inherited.

## A failed injection crashes the server

`mixins.mtmixins.json` sets `injectors.defaultRequire: 1`, so an injection that matches
nothing is fatal at boot &mdash; which is what happened the first time. That is the right
default: the alternative is a patch silently not applying and you believing it is live.

The escape hatch is MixinBooter 11's `config/mixinbooter.cfg`, which can blacklist a mixin
config without repackaging the jar. If a mod update moves a call site out from under a
mixin, blacklist that config to get the server up, then fix the target. Worth knowing
*before* you need it at 2am.

## The @Mod class needs a public no-arg constructor

FML constructs it reflectively with `Class.newInstance()` in `ILanguageAdapter$JavaAdapter`.
Giving it a private constructor because "nobody should instantiate this" fails mod loading
with `IllegalAccessException: ... can not access a member of ... with modifiers "private"`.
The class can still be `final`.

## Verifying an injection landed

`-Dmixin.debug.export=true` is already on for the dev runs, so every transformed class is
written to `run/.mixin.out/class/`. `javap -p -c` that and confirm your call is where you
meant. This is worth doing once per mixin — a `@WrapOperation` that matched nothing throws
at startup, but one that matched the *wrong* call site does not.

## Prefix your members

Anything you add to a mixin gets merged into the target class. Prefix additions with
`mtmixins$` and mark them `@Unique` so collisions with the target or another mod's mixin
fail at build time rather than at runtime.

## Naming

`base.archivesName` is the mod id, so builds come out as `mtmixins-0.1.0.jar`. Resist the
urge to encode what changed in the filename — that is what the git history is for, and the
previous approach to this codebase produced `qourtescivilizations-v1.4.1-lastworking.jar`.
