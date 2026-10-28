package com.novotimo.mtmixins.util;

import com.novotimo.mtmixins.MedievalTimesMixins;
import com.novotimo.mtmixins.bridge.FrozenBridge;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.util.ClassInheritanceMultiMap;

/**
 * Register B41's rules: crowded farms are ticked less often, and stop breeding past a per-chunk limit.
 *
 * <p>"Animals" here are every {@link EntityAnimal} (cows, pigs, sheep, chickens, horses, llamas, Animania,
 * Better Animals Plus, ...) except tamed pets and the excluded IDs. They are counted per chunk.
 *
 * <ul>
 *   <li><b>Throttle.</b> In a chunk with more than {@code throttle-above} animals, each one gets a full
 *       update only every Nth tick, N = animals / above rounded up, at most {@code throttle-max-stride}. A
 *       packed chunk then costs about what {@code throttle-above} animals cost. On the ticks it skips, a baby
 *       still grows and a breeding cooldown still counts down, so only movement and reactions slow down.
 *       Animals that are hurt, burning, in water, airborne, ridden, riding, leashed, dying, in love, under a
 *       potion effect, frozen by Ice and Fire ({@link FrozenBridge}) or (by default) named always get every
 *       tick, because those all count down in the update a skipped tick does not run.</li>
 *   <li><b>Breeding cap.</b> In a chunk that already has {@code breeding-limit-per-chunk} animals, feeding
 *       an adult its breeding food is refused (the item is kept) with a message, and a pair that was already
 *       in love does not give birth (both go on the usual 5-minute cooldown, as when another mod cancels a
 *       birth).</li>
 * </ul>
 *
 * <p>Settings come from {@code config/mtmixins-farms.properties}, written with the defaults on first use,
 * the same way register B18 reads {@code mtmixins-collisions.properties} (mixins run before any Forge
 * config could load, and prod's JVM flags are fixed). Read once; changing it needs a restart. The whole
 * register is switched off with {@code enabled = false} there, or by blacklisting
 * {@code mixins.mtmixins.farms.json} in {@code config/mixinbooter.cfg}.
 */
public final class FarmLimiter {

    private static final String FILE_NAME = "mtmixins-farms.properties";

    /** Gap between action-bar messages to one player, in milliseconds. */
    private static final long MESSAGE_GAP_MS = 3000L;

    /** Gap between the "densest farm" log lines for one world, in ticks (20 minutes). */
    private static final long REPORT_GAP_TICKS = 24000L;

    private static volatile boolean loaded;
    private static volatile boolean broken;

    private static boolean enabled = true;
    private static int recountTicks = 40;
    private static String[] excluded = {"iceandfire:*"};
    private static boolean throttleEnabled = true;
    private static int throttleAbove = 16;
    private static int throttleMaxStride = 8;
    private static boolean throttleExemptNamed = true;
    private static boolean breedingCapEnabled = true;
    private static int breedingLimit = 24;
    private static String[] breedingExempt = {"animania:*"};

    /** Server thread only. */
    private static final Map<Class<?>, Boolean> EXCLUDED_CLASSES = new HashMap<>();
    private static final Map<Class<?>, Boolean> BREEDING_EXEMPT_CLASSES = new HashMap<>();
    private static final Map<UUID, Long> LAST_MESSAGE = new HashMap<>();
    private static final Map<Integer, Long> LAST_REPORT = new HashMap<>();

    private FarmLimiter() {
    }

    /** Implemented onto {@link EntityAnimal} by the farm mixin: the animal count of its chunk at the last recount. */
    public interface Crowded {

        int mtmixins$getFarmCrowd();

        void mtmixins$setFarmCrowd(int animals);
    }

    // ------------------------------------------------------------------------------------------ throttle

    public static int recountTicks() {
        load();
        return recountTicks;
    }

    /**
     * Recounts the animals per chunk and writes each animal's chunk count onto it. One pass to count and one
     * to write, over the world's entity list; animals that stop counting (tamed, excluded) are set to zero.
     */
    public static void recount(World world) {
        load();
        if (!enabled || broken || !throttleEnabled) {
            return;
        }

        try {
            List<Entity> entities = world.loadedEntityList;
            Map<Long, int[]> perChunk = new HashMap<>();
            for (int i = 0; i < entities.size(); ++i) {
                Entity entity = entities.get(i);
                if (entity instanceof EntityAnimal && counts((EntityAnimal) entity)) {
                    int[] n = perChunk.get(chunkKey(entity));
                    if (n == null) {
                        perChunk.put(chunkKey(entity), new int[]{1});
                    } else {
                        ++n[0];
                    }
                }
            }

            for (int i = 0; i < entities.size(); ++i) {
                Entity entity = entities.get(i);
                if (entity instanceof EntityAnimal) {
                    int[] n = counts((EntityAnimal) entity) ? perChunk.get(chunkKey(entity)) : null;
                    int crowd = n == null ? 0 : n[0];
                    // Still counted for its neighbours, but never throttled itself while Ice and Fire has it
                    // frozen: the thaw counts down in an event a skipped tick does not fire.
                    if (crowd > throttleAbove && FrozenBridge.isFrozen(entity)) {
                        crowd = 0;
                    }

                    ((Crowded) entity).mtmixins$setFarmCrowd(crowd);
                }
            }

            report(world, perChunk);
        } catch (RuntimeException problem) {
            fail("recounting animals", problem);
        }
    }

    /** True when this tick's {@code onUpdate} should be skipped for {@code entity}. */
    public static boolean skipsUpdate(Entity entity) {
        if (!(entity instanceof EntityAnimal) || broken) {
            return false;
        }

        try {
            int crowd = ((Crowded) entity).mtmixins$getFarmCrowd();
            if (crowd <= throttleAbove || !enabled || !throttleEnabled || entity.world.isRemote) {
                return false;
            }

            int stride = Math.min(throttleMaxStride, (crowd + throttleAbove - 1) / throttleAbove);
            if (stride < 2 || (entity.world.getTotalWorldTime() + entity.getEntityId()) % stride == 0) {
                return false;
            }

            EntityAnimal animal = (EntityAnimal) entity;
            if (mustTick(animal)) {
                return false;
            }

            // What EntityAgeable.onLivingUpdate would have done this tick, short of the last step, which is
            // left to a real tick so onGrowingAdult still runs.
            int age = animal.getGrowingAge();
            if (age < -1) {
                animal.setGrowingAge(age + 1);
            } else if (age > 1) {
                animal.setGrowingAge(age - 1);
            }

            return true;
        } catch (RuntimeException problem) {
            fail("throttling an animal", problem);
            return false;
        }
    }

    private static boolean mustTick(EntityAnimal animal) {
        return animal.hurtTime > 0
                || animal.hurtResistantTime > 0
                || animal.deathTime > 0
                || animal.getHealth() <= 0.0F
                || !animal.onGround
                || animal.isBurning()
                || animal.isInWater()
                || animal.isRiding()
                || animal.isBeingRidden()
                || animal.getLeashed()
                || animal.isInLove()
                // Potion effects count down in the update a skipped tick does not run.
                || !animal.getActivePotionEffects().isEmpty()
                || (throttleExemptNamed && animal.hasCustomName());
    }

    // -------------------------------------------------------------------------------------- breeding cap

    /**
     * Called at the head of {@code EntityAnimal.processInteract}, server side. True means the feeding was
     * refused: the player has been told, and their inventory resent so the client shows the food still there.
     */
    public static boolean refusesFeeding(EntityAnimal animal, EntityPlayer player, EnumHand hand) {
        load();
        if (!enabled || broken || !breedingCapEnabled || animal.world.isRemote) {
            return false;
        }

        try {
            ItemStack stack = player.getHeldItem(hand);
            if (stack.isEmpty() || animal.getGrowingAge() != 0 || animal.isInLove() || !animal.isBreedingItem(stack)
                    || !counts(animal) || matches(animal.getClass(), breedingExempt, BREEDING_EXEMPT_CLASSES)) {
                return false;
            }

            int animals = animalsInChunk(animal);
            if (animals < breedingLimit) {
                return false;
            }

            tell(player, "This chunk already has " + animals + " animals (the limit for breeding is " + breedingLimit
                    + "). Animals here won't breed until there are fewer.");
            if (player instanceof EntityPlayerMP) {
                ((EntityPlayerMP) player).sendContainerToPlayer(player.inventoryContainer);
            }

            return true;
        } catch (RuntimeException problem) {
            fail("checking a feeding", problem);
            return false;
        }
    }

    /**
     * Called at the head of {@code EntityAIMate.spawnBaby}. True means the birth was refused, and both parents
     * have been handled exactly as vanilla handles a birth another mod cancels.
     */
    public static boolean refusesBirth(EntityAnimal animal, EntityAnimal mate) {
        load();
        if (!enabled || broken || !breedingCapEnabled || animal == null || mate == null || animal.world.isRemote) {
            return false;
        }

        try {
            if (!counts(animal) || matches(animal.getClass(), breedingExempt, BREEDING_EXEMPT_CLASSES)) {
                return false;
            }

            int animals = animalsInChunk(animal);
            if (animals < breedingLimit) {
                return false;
            }

            EntityPlayerMP cause = animal.getLoveCause() != null ? animal.getLoveCause() : mate.getLoveCause();
            animal.setGrowingAge(6000);
            mate.setGrowingAge(6000);
            animal.resetInLove();
            mate.resetInLove();
            if (cause != null) {
                tell(cause, "Your animals didn't breed: this chunk already has " + animals + " animals (the limit is "
                        + breedingLimit + ").");
            }

            return true;
        } catch (RuntimeException problem) {
            fail("checking a birth", problem);
            return false;
        }
    }

    /** Live count of the animals in {@code animal}'s chunk, from the chunk's own entity lists. */
    private static int animalsInChunk(EntityAnimal animal) {
        Chunk chunk = animal.world.getChunk(MathHelper.floor(animal.posX) >> 4, MathHelper.floor(animal.posZ) >> 4);
        int n = 0;
        for (ClassInheritanceMultiMap<Entity> section : chunk.getEntityLists()) {
            for (EntityAnimal other : section.getByClass(EntityAnimal.class)) {
                if (counts(other)) {
                    ++n;
                }
            }
        }

        return n;
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static boolean counts(EntityAnimal animal) {
        return !animal.isDead
                && !(animal instanceof EntityTameable && ((EntityTameable) animal).isTamed())
                && !matches(animal.getClass(), excluded, EXCLUDED_CLASSES);
    }

    private static boolean matches(Class<?> type, String[] patterns, Map<Class<?>, Boolean> cache) {
        Boolean known = cache.get(type);
        if (known != null) {
            return known;
        }

        boolean hit = false;
        @SuppressWarnings("unchecked")
        ResourceLocation id = EntityList.getKey((Class<? extends Entity>) type);
        if (id != null) {
            for (String pattern : patterns) {
                if (pattern.endsWith(":*")
                        ? id.getNamespace().equals(pattern.substring(0, pattern.length() - 2))
                        : id.toString().equals(pattern)) {
                    hit = true;
                    break;
                }
            }
        }

        cache.put(type, hit);
        return hit;
    }

    private static long chunkKey(Entity entity) {
        long x = MathHelper.floor(entity.posX) >> 4;
        long z = MathHelper.floor(entity.posZ) >> 4;
        return (x << 32) | (z & 0xFFFFFFFFL);
    }

    private static void tell(EntityPlayer player, String text) {
        long now = System.currentTimeMillis();
        Long last = LAST_MESSAGE.get(player.getUniqueID());
        if (last != null && now - last < MESSAGE_GAP_MS) {
            return;
        }

        LAST_MESSAGE.put(player.getUniqueID(), now);
        player.sendStatusMessage(new TextComponentString(TextFormatting.YELLOW + text), true);
    }

    /** At most every 20 minutes per world: how many chunks are throttled and where the densest one is. */
    private static void report(World world, Map<Long, int[]> perChunk) {
        int dimension = world.provider.getDimension();
        long now = world.getTotalWorldTime();
        Long last = LAST_REPORT.get(dimension);
        if (last != null && now - last < REPORT_GAP_TICKS) {
            return;
        }

        int over = 0;
        long densest = 0L;
        int most = 0;
        for (Map.Entry<Long, int[]> entry : perChunk.entrySet()) {
            int n = entry.getValue()[0];
            if (n > throttleAbove) {
                ++over;
            }

            if (n > most) {
                most = n;
                densest = entry.getKey();
            }
        }

        if (over == 0) {
            return;
        }

        LAST_REPORT.put(dimension, now);
        int cx = (int) (densest >> 32);
        int cz = (int) densest;
        MedievalTimesMixins.LOG.info(
                "Farm limiter (B41): {} chunk(s) in dimension {} have more than {} animals and are throttled. "
                        + "The densest, chunk {},{} (blocks {},{}), has {}.",
                over, dimension, throttleAbove, cx, cz, cx << 4, cz << 4, most);
    }

    private static void fail(String what, RuntimeException problem) {
        if (!broken) {
            broken = true;
            MedievalTimesMixins.LOG.error("Farm limiter (B41) failed while {} and has switched itself off until the "
                    + "next restart. Animals are back to vanilla behaviour.", what, problem);
        }
    }

    // -------------------------------------------------------------------------------------------- config

    private static void load() {
        if (loaded) {
            return;
        }

        synchronized (FarmLimiter.class) {
            if (loaded) {
                return;
            }

            File file = new File(new File("config"), FILE_NAME);
            if (!file.isFile()) {
                writeDefaults(file);
            } else {
                read(file);
            }

            MedievalTimesMixins.LOG.info(
                    "Farm limiter (B41) {}: throttle {} above {} animals per chunk (longest gap {} ticks), breeding "
                            + "{} at {} animals per chunk. From {}.",
                    enabled ? "on" : "off", throttleEnabled ? "on" : "off", throttleAbove, throttleMaxStride,
                    breedingCapEnabled ? "capped" : "uncapped", breedingLimit, FILE_NAME);
            loaded = true;
        }
    }

    private static void read(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            Properties props = new Properties();
            props.load(in);
            enabled = bool(props, "enabled", enabled);
            recountTicks = Math.max(10, integer(props, "recount-ticks", recountTicks));
            excluded = list(props, "excluded-entities", excluded);
            throttleEnabled = bool(props, "throttle-enabled", throttleEnabled);
            throttleAbove = Math.max(1, integer(props, "throttle-above", throttleAbove));
            throttleMaxStride = Math.max(2, integer(props, "throttle-max-stride", throttleMaxStride));
            throttleExemptNamed = bool(props, "throttle-exempt-named", throttleExemptNamed);
            breedingCapEnabled = bool(props, "breeding-cap-enabled", breedingCapEnabled);
            breedingLimit = Math.max(1, integer(props, "breeding-limit-per-chunk", breedingLimit));
            breedingExempt = list(props, "breeding-exempt", breedingExempt);
        } catch (Exception problem) {
            MedievalTimesMixins.LOG.warn("Could not read {} ({}), so the farm limiter uses its defaults.", FILE_NAME,
                    problem);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // nothing useful to do about a failed close on a config read
                }
            }
        }
    }

    private static void writeDefaults(File file) {
        String text = "# Register B41 - farm limiter. Read at startup; restart after changing.\n"
                + "# Animals are counted per chunk: every animal (cows, pigs, sheep, chickens, horses, Animania, ...)\n"
                + "# except tamed pets and the excluded entities.\n"
                + "enabled = true\n"
                + "# How often, in ticks, each world's animals are recounted.\n"
                + "recount-ticks = 40\n"
                + "# Entity IDs never counted, throttled or capped, comma separated. modid:* matches a whole mod.\n"
                + "excluded-entities = iceandfire:*\n"
                + "\n"
                + "# Animals in a chunk with more than throttle-above animals are only updated every Nth tick,\n"
                + "# N = animals / throttle-above rounded up, at most throttle-max-stride. Babies still grow.\n"
                + "throttle-enabled = true\n"
                + "throttle-above = 16\n"
                + "throttle-max-stride = 8\n"
                + "# Animals with a name tag are never throttled (they still count).\n"
                + "throttle-exempt-named = true\n"
                + "\n"
                + "# No breeding in a chunk that already has this many animals.\n"
                + "breeding-cap-enabled = true\n"
                + "breeding-limit-per-chunk = 24\n"
                + "# Entity IDs whose feeding is never refused. Animania's food also feeds the animal.\n"
                + "breeding-exempt = animania:*\n";
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        } catch (Exception problem) {
            MedievalTimesMixins.LOG.warn("Could not write the default {} ({}); using the defaults anyway.", FILE_NAME,
                    problem);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                    // the defaults are in effect whether or not the file was written
                }
            }
        }
    }

    private static boolean bool(Properties props, String key, boolean fallback) {
        String raw = props.getProperty(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }

    private static int integer(Properties props, String key, int fallback) {
        String raw = props.getProperty(key);
        return raw == null ? fallback : Integer.parseInt(raw.trim());
    }

    private static String[] list(Properties props, String key, String[] fallback) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }

        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }

        return out.toArray(new String[0]);
    }
}
