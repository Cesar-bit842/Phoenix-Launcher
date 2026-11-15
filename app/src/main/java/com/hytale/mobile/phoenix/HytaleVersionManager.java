package com.hytale.mobile.phoenix;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class HytaleVersionManager {
    private final Context appContext;

    HytaleVersionManager(Context context) {
        appContext = context.getApplicationContext();
    }

    File resolveVersionDirectory(String versionName) throws IOException {
        validateVersionName(versionName);
        File executable = AppStoragePaths.resolveExecutable(
                appContext,
                "Versions/" + versionName + "/Client/HytaleClient.exe",
                true);
        File clientDirectory = executable.getParentFile();
        File versionDirectory = clientDirectory == null ? null : clientDirectory.getParentFile();
        if (versionDirectory == null || !versionDirectory.isDirectory()) {
            throw new IOException("No se pudo determinar la carpeta raíz de la versión.");
        }
        return versionDirectory;
    }

    List<String> getVersionDirectoryNames() {
        Set<String> versionNames = new HashSet<>();
        addVersionDirectoryNames(new File(appContext.getFilesDir(), "Versions"), versionNames);

        File externalFiles = appContext.getExternalFilesDir(null);
        if (externalFiles != null) {
            addVersionDirectoryNames(new File(externalFiles, "Versions"), versionNames);
        }

        List<String> sortedNames = new ArrayList<>(versionNames);
        Collections.sort(sortedNames, String.CASE_INSENSITIVE_ORDER);
        return sortedNames;
    }

    static void validateVersionName(String versionName) {
        if (versionName == null
                || versionName.trim().isEmpty()
                || versionName.equals(".")
                || versionName.equals("..")
                || versionName.contains("/")
                || versionName.contains("\\")) {
            throw new IllegalArgumentException("El nombre de la versión no es válido.");
        }
    }

    private static void addVersionDirectoryNames(File versionsDirectory, Set<String> names) {
        if (!versionsDirectory.exists()) return;
        if (!versionsDirectory.isDirectory()) {
            throw new IllegalStateException(
                    "La ruta de versiones no es una carpeta: " + versionsDirectory.getAbsolutePath());
        }

        File[] entries = versionsDirectory.listFiles();
        if (entries == null) {
            throw new IllegalStateException(
                    "No se pudieron leer las carpetas de versiones: "
                            + versionsDirectory.getAbsolutePath());
        }
        for (File entry : entries) {
            if (entry.isDirectory()) names.add(entry.getName());
        }
    }
}
