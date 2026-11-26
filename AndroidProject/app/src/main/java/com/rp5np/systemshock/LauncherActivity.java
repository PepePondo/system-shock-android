package com.rp5np.systemshock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class LauncherActivity extends Activity {
    private static final String TAG = "RP5NP_IMPORT";
    private static final int REQUEST_IMPORT_TREE = 0x5353;
    private static final int MAX_DIRECTORY_DEPTH = 64;

    private AlertDialog busyDialog;

    private static final class DocumentNode {
        final String documentId;
        final String mimeType;

        DocumentNode(String documentId, String mimeType) {
            this.documentId = documentId;
            this.mimeType = mimeType;
        }
    }

    private static final class CopyStats {
        long files;
        long bytes;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        if (hasGameData()) {
            launchGame();
        } else if (PortHubImport.folderPresent()) {
            portHubImport();
        } else {
            Log.i(TAG, "GAME_DATA_IMPORT_REQUIRED");
            showImportPrompt(null);
        }
    }

    /** Waiting for the player to allow "All files access" for the PortHub folder. */
    private boolean askedForAccess;

    @Override
    protected void onResume() {
        super.onResume();
        if (askedForAccess) {
            askedForAccess = false;
            if (PortHubImport.canRead()) {
                portHubImport();
            } else {
                showImportPrompt("All files access was not allowed.");
            }
        }
    }

    /** PortHub copied the game to Internal storage/PortHub/SystemShock/res: import it from there without a picker. */
    private void portHubImport() {
        if (!PortHubImport.canRead()) {
            new AlertDialog.Builder(this)
                    .setTitle("Allow access to the game files")
                    .setMessage("PortHub copied System Shock to Internal storage/PortHub/SystemShock. "
                            + "To import it from there, switch on \"All files access\" for this app on the next screen, then come back.")
                    .setPositiveButton("Open settings", (dialog, which) -> {
                        askedForAccess = true;
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    })
                    .setNegativeButton("Pick a folder instead", (dialog, which) -> showImportPrompt(null))
                    .setCancelable(false)
                    .show();
            return;
        }
        busyDialog = new AlertDialog.Builder(this)
                .setTitle("Importing game data")
                .setMessage("Copying files from PortHub/SystemShock. Do not close the app.")
                .setCancelable(false)
                .create();
        busyDialog.show();
        new Thread(() -> {
            try {
                long bytes = PortHubImport.importInto(getFilesDir());
                runOnUiThread(() -> {
                    dismissBusyDialog();
                    Log.i(TAG, "GAME_DATA_IMPORT=PASS source=porthub bytes=" + bytes);
                    if (hasGameData()) launchGame();
                    else showImportPrompt("The PortHub folder's data and sound folders are empty.");
                });
            } catch (Exception error) {
                Log.e(TAG, "GAME_DATA_IMPORT=FAIL source=porthub", error);
                runOnUiThread(() -> {
                    dismissBusyDialog();
                    showImportPrompt("Import from PortHub/SystemShock failed: " + safeErrorMessage(error));
                });
            }
        }, "SystemShockPortHubImport").start();
    }

    private boolean hasGameData() {
        File res = new File(getFilesDir(), "res");
        return hasChildren(new File(res, "data")) && hasChildren(new File(res, "sound"));
    }

    private static boolean hasChildren(File directory) {
        if (!directory.isDirectory()) {
            return false;
        }
        String[] children = directory.list();
        return children != null && children.length > 0;
    }

    private void showImportPrompt(String errorMessage) {
        if (isFinishing()) {
            return;
        }

        String message = "Select the System Shock res folder that contains data and sound. " +
                "The files will be copied into this app's private storage. " +
                "Game data is not included with this APK.";
        if (errorMessage != null && !errorMessage.isEmpty()) {
            message = errorMessage + "\n\n" + message;
        }

        new AlertDialog.Builder(this)
                .setTitle("System Shock game data required")
                .setMessage(message)
                .setPositiveButton("Select res folder", (dialog, which) -> openTreePicker())
                .setNegativeButton("Exit", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private void openTreePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMPORT_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_IMPORT_TREE) {
            return;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            showImportPrompt("No folder was selected.");
            return;
        }

        Uri treeUri = data.getData();
        int grantedFlags = data.getFlags() &
                (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(treeUri, grantedFlags);
        } catch (SecurityException ignored) {
            Log.w(TAG, "PERSISTABLE_URI_PERMISSION_NOT_AVAILABLE");
        }

        beginImport(treeUri);
    }

    private void beginImport(Uri treeUri) {
        busyDialog = new AlertDialog.Builder(this)
                .setTitle("Importing game data")
                .setMessage("Copying files. Do not close the app.")
                .setCancelable(false)
                .create();
        busyDialog.show();
        Log.i(TAG, "GAME_DATA_IMPORT_START");

        Thread worker = new Thread(() -> {
            try {
                CopyStats stats = importFromTree(treeUri);
                runOnUiThread(() -> {
                    dismissBusyDialog();
                    Log.i(TAG, "GAME_DATA_IMPORT=PASS files=" + stats.files + " bytes=" + stats.bytes);
                    launchGame();
                });
            } catch (Exception error) {
                Log.e(TAG, "GAME_DATA_IMPORT=FAIL", error);
                runOnUiThread(() -> {
                    dismissBusyDialog();
                    showImportPrompt("Import failed: " + safeErrorMessage(error));
                });
            }
        }, "SystemShockDataImport");
        worker.start();
    }

    private void dismissBusyDialog() {
        if (busyDialog != null) {
            busyDialog.dismiss();
            busyDialog = null;
        }
    }

    private CopyStats importFromTree(Uri treeUri) throws IOException {
        ContentResolver resolver = getContentResolver();
        String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);

        DocumentNode dataNode = findChild(resolver, treeUri, rootDocumentId, "data");
        DocumentNode soundNode = findChild(resolver, treeUri, rootDocumentId, "sound");
        if (dataNode == null || soundNode == null) {
            throw new IOException("The selected folder must contain data and sound directories.");
        }
        if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(dataNode.mimeType) ||
                !DocumentsContract.Document.MIME_TYPE_DIR.equals(soundNode.mimeType)) {
            throw new IOException("data and sound must both be directories.");
        }

        File filesDir = getFilesDir();
        File staging = new File(filesDir, "res.importing");
        File destination = new File(filesDir, "res");
        File backup = new File(filesDir, "res.backup");

        deleteRecursively(staging);
        deleteRecursively(backup);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException("Unable to create import staging directory.");
        }

        CopyStats stats = new CopyStats();
        boolean destinationBackedUp = false;
        boolean importActivated = false;

        try {
            copyDirectory(resolver, treeUri, dataNode.documentId,
                    new File(staging, "data"), 0, stats);
            copyDirectory(resolver, treeUri, soundNode.documentId,
                    new File(staging, "sound"), 0, stats);

            if (!hasChildren(new File(staging, "data")) ||
                    !hasChildren(new File(staging, "sound"))) {
                throw new IOException("The selected data or sound directory is empty.");
            }

            if (destination.exists()) {
                if (!destination.renameTo(backup)) {
                    throw new IOException("Unable to preserve the existing game-data directory.");
                }
                destinationBackedUp = true;
            }

            if (!staging.renameTo(destination)) {
                if (destinationBackedUp && !destination.exists()) {
                    backup.renameTo(destination);
                }
                throw new IOException("Unable to activate the imported game data.");
            }
            importActivated = true;

            if (destinationBackedUp) {
                tryDeleteRecursively(backup);
            }

            return stats;
        } catch (Exception error) {
            if (!importActivated) {
                tryDeleteRecursively(staging);
                if (destinationBackedUp && !destination.exists() && backup.exists()) {
                    if (!backup.renameTo(destination)) {
                        Log.e(TAG, "GAME_DATA_IMPORT_RESTORE=FAIL");
                    }
                }
            }

            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException("Unable to import the selected folder.", error);
        }
    }

    private static DocumentNode findChild(ContentResolver resolver, Uri treeUri,
                                          String parentDocumentId, String wantedName)
            throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, parentDocumentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                throw new IOException("Unable to read the selected folder.");
            }

            int idColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE);

            while (cursor.moveToNext()) {
                String name = cursor.getString(nameColumn);
                if (wantedName.equalsIgnoreCase(name)) {
                    return new DocumentNode(cursor.getString(idColumn), cursor.getString(mimeColumn));
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("Unable to inspect the selected folder.", error);
        }

        return null;
    }

    private static void copyDirectory(ContentResolver resolver, Uri treeUri,
                                      String sourceDocumentId, File targetDirectory,
                                      int depth, CopyStats stats) throws IOException {
        if (depth > MAX_DIRECTORY_DEPTH) {
            throw new IOException("Game-data directory nesting is too deep.");
        }
        if (!targetDirectory.mkdirs() && !targetDirectory.isDirectory()) {
            throw new IOException("Unable to create a destination directory.");
        }

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, sourceDocumentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                throw new IOException("Unable to enumerate a selected directory.");
            }

            int idColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE);

            while (cursor.moveToNext()) {
                String documentId = cursor.getString(idColumn);
                String displayName = cursor.getString(nameColumn);
                String mimeType = cursor.getString(mimeColumn);
                File target = safeChildFile(targetDirectory, displayName);

                if (target.exists()) {
                    throw new IOException("Duplicate item in selected game data: " + displayName);
                }

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    copyDirectory(resolver, treeUri, documentId, target, depth + 1, stats);
                } else {
                    copyFile(resolver, treeUri, documentId, target, stats);
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("Unable to copy a selected directory.", error);
        }
    }

    private static void copyFile(ContentResolver resolver, Uri treeUri,
                                 String documentId, File target, CopyStats stats)
            throws IOException {
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);

        try (InputStream input = resolver.openInputStream(documentUri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) {
                throw new IOException("Unable to open a selected game-data file.");
            }

            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                stats.bytes += read;
            }
            output.getFD().sync();
            stats.files++;
        }
    }

    private static File safeChildFile(File parent, String displayName) throws IOException {
        if (displayName == null || displayName.isEmpty() ||
                ".".equals(displayName) || "..".equals(displayName) ||
                displayName.indexOf('/') >= 0 || displayName.indexOf('\\') >= 0) {
            throw new IOException("Invalid item name in selected game data.");
        }

        File child = new File(parent, displayName);
        String parentPath = parent.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        if (!childPath.startsWith(parentPath + File.separator)) {
            throw new IOException("Invalid item path in selected game data.");
        }
        return child;
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("Unable to enumerate a temporary import directory.");
            }
            for (File child : children) {
                deleteRecursively(child);
            }
        }

        if (!file.delete() && file.exists()) {
            throw new IOException("Unable to remove a temporary import item.");
        }
    }

    private static void tryDeleteRecursively(File file) {
        try {
            deleteRecursively(file);
        } catch (IOException error) {
            Log.w(TAG, "GAME_DATA_IMPORT_CLEANUP=FAIL", error);
        }
    }

    private void launchGame() {
        Log.i(TAG, "GAME_LAUNCH");
        Intent game = new Intent(this, ShockolateActivity.class);
        startActivity(game);
        finish();
    }

    private static String safeErrorMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Unable to import the selected folder.";
        }
        return message.trim();
    }
}
