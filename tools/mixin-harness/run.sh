#!/usr/bin/env bash
# Before/after check for the mixin fixes in BUGFIX-REPORT.md, without a Minecraft server.
#
# Builds the mixins twice - once from a baseline commit, once from the working tree - and applies
# each build with the real Mixin 0.8.7 transformer and MixinExtras to small stand-in classes that have
# the same method shapes the mixins target (stubs/). Each test then drives those classes and prints
# PASS/FAIL. On the baseline the bug shows as FAIL; on the working tree everything should PASS.
#
#   tools/mixin-harness/run.sh [baseline-rev]      # default baseline: 96cc13c
#
# Needs a JDK 9+ (javac --release 8) and network access to Maven Central for the first run.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
OLD_REV="${1:-96cc13c}"
WORK="$HERE/.work"
DEPS="$WORK/deps"
PKG=src/main/java/com/novotimo/mtmixins

MAVEN=https://repo1.maven.org/maven2
JARS=(
    "net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar"
    "io/github/llamalad7/mixinextras-common/0.5.5/mixinextras-common-0.5.5.jar"
    "org/ow2/asm/asm/9.7/asm-9.7.jar"
    "org/ow2/asm/asm-tree/9.7/asm-tree-9.7.jar"
    "org/ow2/asm/asm-commons/9.7/asm-commons-9.7.jar"
    "org/ow2/asm/asm-util/9.7/asm-util-9.7.jar"
    "org/ow2/asm/asm-analysis/9.7/asm-analysis-9.7.jar"
    "org/apache/logging/log4j/log4j-api/2.8.1/log4j-api-2.8.1.jar"
    "com/google/guava/guava/21.0/guava-21.0.jar"
    "com/google/code/gson/gson/2.8.0/gson-2.8.0.jar"
)

# Mod sources the tested mixins need, relative to $PKG. A file missing at a revision is skipped.
SOURCES=(
    MedievalTimesMixins.java INamedLootTable.java
    util/ChunkBiomeCache.java util/EntityCollisions.java
    mixin/oreveins/MixinWorldGenVeins.java
    mixin/vanilla/MixinEntityCollisionCount.java mixin/vanilla/MixinEntityLivingBaseCollisions.java
    mixin/vanilla/AccessorWorldUnloadQueue.java mixin/vanilla/MixinWorldServerEntityDupe.java
    mixin/lycanites/MixinBlockSpawnLocation.java mixin/lycanites/MixinMaterialSpawnLocation.java
    mixin/vanilla/MixinLootTableAttribution.java mixin/vanilla/MixinLootTableManagerName.java
    mixin/vanilla/MixinLootTableShuffle.java
)

mkdir -p "$DEPS"
for j in "${JARS[@]}"; do
    f="$DEPS/$(basename "$j")"
    [ -s "$f" ] || curl -sSf --retry 3 -o "$f" "$MAVEN/$j"
done
CP="$(printf '%s:' "$DEPS"/*.jar)"

jc() { javac -nowarn -g --release 8 -proc:none -Xlint:-options "$@"; }

rm -rf "$WORK/build" "$WORK/src-old"
mkdir -p "$WORK/build"/{harness,stubs,old,new,tests} "$WORK/src-old"

jc -d "$WORK/build/harness" -cp "$CP" "$HERE"/src/harness/*.java
cp -r "$HERE/src/META-INF" "$WORK/build/harness/"
jc -d "$WORK/build/stubs" -cp "$CP" $(find "$HERE/stubs" -name '*.java')

# Writes one Mixin config for $VARIANT, listing only the mixins that variant actually has.
config() { # file mixin...
    local out="$1" list="" m; shift
    for m in "$@"; do
        [ -f "$WORK/build/$VARIANT/com/novotimo/mtmixins/mixin/${m//.//}.class" ] && list+="\"$m\","
    done
    printf '{"required":true,"minVersion":"0.8","package":"com.novotimo.mtmixins.mixin","target":"@env(DEFAULT)",%s}\n' \
        "\"compatibilityLevel\":\"JAVA_8\",\"injectors\":{\"defaultRequire\":1},\"mixins\":[${list%,}]" \
        > "$WORK/build/$VARIANT-res/$out"
}

build_variant() { # name source-root
    local files=() f
    for f in "${SOURCES[@]}"; do [ -f "$2/$f" ] && files+=("$2/$f"); done
    jc -d "$WORK/build/$1" -cp "$CP$WORK/build/stubs" "${files[@]}"
    mkdir -p "$WORK/build/$1-res"
    VARIANT="$1"
    config collisions.json vanilla.MixinEntityCollisionCount vanilla.MixinEntityLivingBaseCollisions
    config dupe.json vanilla.AccessorWorldUnloadQueue vanilla.MixinWorldServerEntityDupe
    config oreveins.json oreveins.MixinWorldGenVeins
    config lycanites.json lycanites.MixinBlockSpawnLocation lycanites.MixinMaterialSpawnLocation
    config lootname.json vanilla.MixinLootTableAttribution vanilla.MixinLootTableManagerName
    config lootstats.json vanilla.MixinLootTableShuffle
}

for f in "${SOURCES[@]}"; do
    if git -C "$REPO" cat-file -e "$OLD_REV:$PKG/$f" 2>/dev/null; then
        mkdir -p "$WORK/src-old/$(dirname "$f")"
        git -C "$REPO" show "$OLD_REV:$PKG/$f" > "$WORK/src-old/$f"
    fi
done
build_variant old "$WORK/src-old"
build_variant new "$REPO/$PKG"
jc -d "$WORK/build/tests" -cp "$CP$WORK/build/stubs:$WORK/build/new" "$HERE"/tests/*.java

run() { # variant config test
    local dir; dir="$(mktemp -d "$WORK/run.XXXX")"
    ( cd "$dir" && java -Dorg.apache.logging.log4j.simplelog.level=INFO \
          -Dorg.apache.logging.log4j.simplelog.logFile="$dir/log.txt" \
          -cp "$WORK/build/harness:$CP" harness.Main "$2" "$3" \
          "$WORK/build/$1-res" "$WORK/build/$1" "$WORK/build/stubs" "$WORK/build/tests" 2>&1 \
      | grep -v -e '^Picked up JAVA_TOOL_OPTIONS' -e 'StatusLogger' ) || true
    grep -h 'Loot fill' "$dir/log.txt" 2>/dev/null | sed 's/^.*Loot fill/    log: Loot fill/' || true
    rm -rf "$dir"
}

for t in collisions:CollisionTest dupe:DupeTest oreveins:OreVeinsTest lycanites:LycanitesTest \
         lootname:LootNameTest lootstats:LootStatsTest; do
    echo "=== ${t#*:} - before ($OLD_REV)"
    run old "${t%%:*}.json" "tests.${t#*:}"
    echo "=== ${t#*:} - after (working tree)"
    run new "${t%%:*}.json" "tests.${t#*:}"
    echo
done
