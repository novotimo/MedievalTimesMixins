package com.novotimo.mtmixins.util;

import com.novotimo.mtmixins.MedievalTimesMixins;
import de.maxhenkel.corpse.Death;
import de.maxhenkel.corpse.net.MessageDeathInventory;
import de.maxhenkel.corpse.proxy.CommonProxy;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Register B29 &mdash; shared by the mixins in {@code mixin/corpse}. Finds Corpse's death records in
 * every deaths folder on the server rather than only the one belonging to the world a player is
 * standing in, and opens a record's item window on the player who asked for it.
 *
 * <p>Corpse files a death under {@code <world directory>/deaths/<player>/<death>.dat}, taking the
 * world directory from whichever world the player is in. Vanilla dimensions share the overworld's
 * directory, but every ForgeEssentials Multiworld world has its own under {@code FEMultiworld/<name>},
 * so a death in Spawn sits in {@code world/FEMultiworld/Spawn/deaths} and every other death in
 * {@code world/deaths}. The folders are searched in this order: the asking player's own world, every
 * loaded world, then every Multiworld folder on disk, so a Multiworld world that is not loaded at the
 * moment is still found.
 *
 * <p>Everything here runs on the server thread: the history command, and Corpse's network messages,
 * which {@code MessageToServer} schedules onto it.
 */
public final class CorpseDeaths {

    /**
     * Viewer's UUID &rarr; UUID of the player whose death history the server last sent them. The only
     * place a death's ID ever reaches a client is that history list, and the list is sent only to the
     * player themselves or to someone allowed to run {@code /deathhistory}, so this records exactly the
     * permission the server already granted, whatever decided it (op level or ForgeEssentials).
     */
    private static final Map<UUID, UUID> HISTORY_SENT = new HashMap<>();

    private CorpseDeaths() {
    }

    /** Called whenever Corpse sends {@code viewer} the death history of {@code owner}. */
    public static void historySent(EntityPlayerMP viewer, UUID owner) {
        HISTORY_SENT.put(viewer.getUniqueID(), owner);
    }

    /**
     * Opens the item window of death {@code deathId} of player {@code owner} for {@code viewer}, which is
     * what Corpse's {@code MessageShowCorpseInventory.execute} meant to do. Corpse itself opened it on
     * the owner and looked the record up under the owner while storing it under the viewer, so it only
     * ever worked for a player viewing their own deaths in the world they died in. Never throws.
     */
    public static void show(EntityPlayerMP viewer, @Nullable UUID owner, @Nullable UUID deathId) {
        try {
            if (owner == null || deathId == null) {
                return;
            }
            boolean own = owner.equals(viewer.getUniqueID());
            if (!own && !owner.equals(HISTORY_SENT.get(viewer.getUniqueID()))) {
                MedievalTimesMixins.LOG.warn(
                        "Register B29: refused {} the items of death {} of player {}: the server never sent "
                                + "them that player's death history.",
                        viewer.getName(), deathId, owner);
                return;
            }

            Death death = find(viewer, owner, deathId);
            if (death == null) {
                viewer.sendMessage(new TextComponentString("That death record could not be read.")
                        .setStyle(new Style().setColor(TextFormatting.RED)));
                MedievalTimesMixins.LOG.warn(
                        "Register B29: death {} of player {}, asked for by {}, is in none of {}.",
                        deathId, owner, viewer.getName(), deathFolders(viewer));
                return;
            }

            // Corpse's own three steps, all aimed at the viewer. The client builds its window from the
            // record MessageDeathInventory delivers; the server builds its container from setDeathToShow.
            CommonProxy.simpleNetworkWrapper.sendTo(new MessageDeathInventory(death), viewer);
            CommonProxy.setDeathToShow(viewer, death);
            viewer.openGui("corpse", 1, viewer.world, 0, 0, 0);
            if (!own) {
                MedievalTimesMixins.LOG.info(
                        "Register B29: {} opened the items of death {} of {} ({}), {} stacks, {}.",
                        viewer.getName(), deathId, death.getPlayerName(), owner, death.getItems().size(),
                        viewer.capabilities.isCreativeMode ? "editable (creative)" : "read only");
            }
        } catch (Throwable t) {
            MedievalTimesMixins.LOG.error("Register B29: could not show death {} of {} to {}",
                    deathId, owner, viewer.getName(), t);
        }
    }

    /**
     * The record {@code <folder>/<owner>/<deathId>.dat} from the first deaths folder that has it, or
     * null. A file that exists and cannot be read is logged and the next folder tried.
     */
    @Nullable
    public static Death find(EntityPlayerMP context, UUID owner, UUID deathId) {
        // Both names are UUID.toString() output, so neither can step outside the folder.
        String ownerDir = owner.toString();
        String fileName = deathId + ".dat";
        for (File folder : deathFolders(context)) {
            File file = new File(new File(folder, ownerDir), fileName);
            if (!file.isFile()) {
                continue;
            }
            try {
                return Death.fromNBT(CompressedStreamTools.read(file));
            } catch (Exception e) {
                MedievalTimesMixins.LOG.warn("Register B29: could not read death record {}", file, e);
            }
        }
        return null;
    }

    /**
     * Every death of {@code owner} in every deaths folder, newest first: Corpse's
     * {@code DeathManager.getDeaths(EntityPlayerMP, UUID)}, which reads only the folder of the world the
     * asking player is in. Returns null if something unexpected fails, so the caller can fall back to
     * Corpse's own listing.
     */
    @Nullable
    public static List<Death> list(EntityPlayerMP context, UUID owner) {
        try {
            Map<UUID, Death> byId = new LinkedHashMap<>();
            for (File folder : deathFolders(context)) {
                File[] files = new File(folder, owner.toString()).listFiles(CorpseDeaths::isDeathFile);
                if (files == null) {
                    continue;
                }
                for (File file : files) {
                    try {
                        Death death = Death.fromNBT(CompressedStreamTools.read(file));
                        byId.putIfAbsent(death.getId(), death);
                    } catch (Exception e) {
                        MedievalTimesMixins.LOG.warn("Register B29: could not read death record {}", file, e);
                    }
                }
            }
            List<Death> deaths = new ArrayList<>(byId.values());
            deaths.sort(Comparator.comparingLong(Death::getTimestamp).reversed());
            return deaths;
        } catch (Throwable t) {
            MedievalTimesMixins.LOG.error("Register B29: could not list the deaths of {}; using Corpse's own list",
                    owner, t);
            return null;
        }
    }

    /** Corpse's own filter: {@code <uuid>.dat} and nothing else. */
    private static boolean isDeathFile(File dir, String name) {
        String[] split = name.split("\\.");
        if (split.length != 2 || !split[1].equals("dat")) {
            return false;
        }
        try {
            UUID.fromString(split[0]);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Every distinct {@code deaths} folder, the context player's own world's first. */
    static List<File> deathFolders(EntityPlayerMP context) {
        Set<File> worldDirs = new LinkedHashSet<>();
        addCanonical(worldDirs, context.getServerWorld().getSaveHandler().getWorldDirectory());
        for (WorldServer world : DimensionManager.getWorlds()) {
            addCanonical(worldDirs, world.getSaveHandler().getWorldDirectory());
        }
        File root = DimensionManager.getCurrentSaveRootDirectory();
        if (root != null) {
            File[] multiworlds = new File(root, "FEMultiworld").listFiles(File::isDirectory);
            if (multiworlds != null) {
                for (File dir : multiworlds) {
                    addCanonical(worldDirs, dir);
                }
            }
        }
        List<File> folders = new ArrayList<>(worldDirs.size());
        for (File dir : worldDirs) {
            folders.add(new File(dir, "deaths"));
        }
        return folders;
    }

    private static void addCanonical(Set<File> dirs, File dir) {
        try {
            dirs.add(dir.getCanonicalFile());
        } catch (IOException e) {
            dirs.add(dir.getAbsoluteFile());
        }
    }
}
