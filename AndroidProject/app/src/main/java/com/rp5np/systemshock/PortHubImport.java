package com.rp5np.systemshock;

import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * PortHub copies the game's res/data and res/sound to Internal storage/PortHub/SystemShock/res.
 * With "All files access" the launcher imports them from there by path, so no folder picker is needed.
 */
final class PortHubImport {
    private PortHubImport() {}

    static File resDir() {
        return new File(Environment.getExternalStorageDirectory(), "PortHub/SystemShock/res");
    }

    /** The folder is there; its files can only be listed once access is allowed. */
    static boolean folderPresent() {
        return resDir().isDirectory();
    }

    static boolean canRead() {
        return Environment.isExternalStorageManager();
    }

    /** Copies res/data and res/sound into [filesDir]/res, via a staging folder so a failed copy leaves nothing half-done. */
    static long importInto(File filesDir) throws IOException {
        File source = resDir();
        File data = child(source, "data");
        File sound = child(source, "sound");
        if (data == null || sound == null) {
            throw new IOException("PortHub/SystemShock/res has no data and sound folders.");
        }
        File staging = new File(filesDir, "res.importing");
        File destination = new File(filesDir, "res");
        deleteRecursively(staging);
        long[] bytes = {0};
        copy(data, new File(staging, "data"), bytes);
        copy(sound, new File(staging, "sound"), bytes);
        deleteRecursively(destination);
        if (!staging.renameTo(destination)) {
            deleteRecursively(staging);
            throw new IOException("Unable to activate the imported game data.");
        }
        return bytes[0];
    }

    /** Store copies keep their original names, which may not be lower case. */
    private static File child(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory() && f.getName().equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    private static void copy(File from, File to, long[] bytes) throws IOException {
        if (!to.mkdirs() && !to.isDirectory()) throw new IOException("Unable to create " + to);
        File[] files = from.listFiles();
        if (files == null) throw new IOException("Unable to read " + from);
        byte[] buffer = new byte[1 << 16];
        for (File f : files) {
            // Names are kept as they are, like the picker import does (the engine matches case-insensitively).
            File out = new File(to, f.getName());
            if (f.isDirectory()) {
                copy(f, out, bytes);
                continue;
            }
            try (InputStream in = new FileInputStream(f); OutputStream o = new FileOutputStream(out)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    o.write(buffer, 0, n);
                    bytes[0] += n;
                }
            }
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
