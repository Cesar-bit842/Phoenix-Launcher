package com.hytale.mobile.phoenix;

import android.content.Context;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

final class AppStoragePaths {
    private AppStoragePaths() {
    }

    static String toWinePath(File file) throws IOException {
        String absolutePath = file.getCanonicalPath();
        if (!absolutePath.startsWith(File.separator)) {
            throw new IOException("Wine solo puede recibir rutas absolutas del almacenamiento Android.");
        }
        return "Z:" + absolutePath.replace('/', '\\');
    }

    static File resolveExecutable(
            Context appContext,
            String relativePath,
            boolean includeExternalFiles) throws IOException {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IllegalArgumentException("La ruta relativa del ejecutable no puede estar vacía.");
        }

        String normalizedPath = relativePath.replace('\\', '/');
        if (normalizedPath.startsWith("/") || normalizedPath.matches("^[A-Za-z]:.*")) {
            throw new IOException("La ruta del ejecutable debe ser relativa al almacenamiento privado de la app.");
        }

        Path relative;
        try {
            relative = Paths.get(normalizedPath).normalize();
        } catch (RuntimeException error) {
            throw new IOException("La ruta del ejecutable no es válida: " + relativePath, error);
        }
        if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isEmpty()) {
            throw new IOException("La ruta del ejecutable debe permanecer dentro del almacenamiento privado de la app.");
        }

        List<File> storageRoots = new ArrayList<>();
        storageRoots.add(appContext.getFilesDir());
        if (includeExternalFiles) {
            File externalFiles = appContext.getExternalFilesDir(null);
            if (externalFiles != null) {
                storageRoots.add(externalFiles);
            }
        }

        for (File storageRoot : storageRoots) {
            Path canonicalRoot = storageRoot.getCanonicalFile().toPath();
            File executable = new File(canonicalRoot.toFile(), relative.toString()).getCanonicalFile();
            if (!executable.toPath().startsWith(canonicalRoot)) {
                throw new IOException("La ruta del ejecutable sale del almacenamiento privado de la app.");
            }
            if (!executable.isFile()) {
                continue;
            }

            String name = executable.getName();
            if (name.length() < 4 || !name.regionMatches(true, name.length() - 4, ".exe", 0, 4)) {
                throw new IOException("El archivo indicado no tiene extensión .exe: " + executable.getAbsolutePath());
            }
            return executable;
        }

        throw new FileNotFoundException(
                "No se encontró el ejecutable en el almacenamiento privado de la app: "
                        + relativePath);
    }
}
