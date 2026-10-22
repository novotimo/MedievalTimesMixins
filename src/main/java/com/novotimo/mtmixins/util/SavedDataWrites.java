package com.novotimo.mtmixins.util;

import com.novotimo.mtmixins.MedievalTimesMixins;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldSavedData;

/**
 * Register B42's save path for {@code world/data/*.dat} and each dimension's {@code data/} folder. See
 * {@code MixinMapStorageSaveData}.
 *
 * <p>Builds the same file vanilla builds, then:
 *
 * <ul>
 *   <li>if its uncompressed bytes are identical to what this file last held, does not write it at all;</li>
 *   <li>otherwise compresses at gzip's fastest level, not the default level 6, writes to a {@code .tmp} file
 *       next to it and renames that over the old one, so a crash mid-write leaves the old file intact rather
 *       than a truncated one (vanilla truncates first, then writes).</li>
 * </ul>
 *
 * <p>The file format is unchanged: gzip around the same NBT, which every reader inflates the same way at any
 * compression level. What "last held" means is an MD5 of the uncompressed bytes, kept per file for the
 * life of the server. The first save of a file after startup reads the existing file back to fill that in,
 * so an unchanged file is not rewritten even once. MD5 is used as a checksum here, not for security.
 */
public final class SavedDataWrites {

    /** Absolute path of a data file -> MD5 of the uncompressed NBT it holds. Server thread in practice. */
    private static final Map<String, byte[]> LAST_WRITTEN = new HashMap<>();

    /** Files already reported as skipped, so the log says it once per file per run. */
    private static final Set<String> REPORTED = new HashSet<>();

    private static volatile boolean broken;

    private SavedDataWrites() {
    }

    /**
     * Saves {@code data} the B42 way.
     *
     * @return true if the save was handled (written or deliberately skipped); false to let vanilla's own
     *         {@code saveData} run, which happens when there is nothing to save to or something went wrong.
     */
    public static boolean save(ISaveHandler saveHandler, WorldSavedData data) {
        if (saveHandler == null || broken) {
            return false;
        }

        File file = saveHandler.getMapFileFromName(data.mapName);
        if (file == null) {
            return false;
        }

        String key = file.getAbsolutePath();
        try {
            NBTTagCompound root = new NBTTagCompound();
            root.setTag("data", data.writeToNBT(new NBTTagCompound()));
            ByteArrayOutputStream raw = new ByteArrayOutputStream(64 * 1024);
            DataOutputStream out = new DataOutputStream(raw);
            CompressedStreamTools.write(root, out);
            out.flush();
            byte[] bytes = raw.toByteArray();
            byte[] digest = md5(bytes, bytes.length);

            synchronized (LAST_WRITTEN) {
                byte[] previous = LAST_WRITTEN.get(key);
                if (previous == null && file.isFile()) {
                    previous = digestOfFile(file);
                }

                if (previous != null && file.isFile() && Arrays.equals(previous, digest)) {
                    LAST_WRITTEN.put(key, digest);
                    if (REPORTED.add(key)) {
                        MedievalTimesMixins.LOG.info("{} unchanged since it was last written ({} KB of NBT), so it "
                                + "is not rewritten. Reported once per file. See register B42.", file.getName(),
                                bytes.length / 1024);
                    }

                    return true;
                }

                write(file, bytes);
                LAST_WRITTEN.put(key, digest);
            }

            return true;
        } catch (Exception problem) {
            synchronized (LAST_WRITTEN) {
                LAST_WRITTEN.remove(key);
            }

            if (!broken) {
                broken = true;
                MedievalTimesMixins.LOG.error("Register B42 could not save {}; vanilla saves it this time and every "
                        + "saved-data file from now until the next restart.", file.getName(), problem);
            }

            return false;
        }
    }

    private static void write(File file, byte[] bytes) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            GZIPOutputStream gzip = new GZIPOutputStream(out, 64 * 1024) {
                {
                    this.def.setLevel(Deflater.BEST_SPEED);
                }
            };
            gzip.write(bytes);
            gzip.finish();
            gzip.close();
        } finally {
            out.close();
        }

        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** MD5 of the file's inflated contents, or null if it cannot be read. */
    private static byte[] digestOfFile(File file) {
        InputStream in = null;
        try {
            in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(file)), 64 * 1024);
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                md5.update(buffer, 0, n);
            }

            return md5.digest();
        } catch (Exception unreadable) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // only read for a checksum
                }
            }
        }
    }

    private static byte[] md5(byte[] bytes, int length) throws NoSuchAlgorithmException {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        md5.update(bytes, 0, length);
        return md5.digest();
    }
}
