package com.hytale.mobile.phoenix;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class VersionImportUtils {
    private VersionImportUtils() {
    }

    static String copyVersion(Context context, Uri sourceTreeUri) throws IOException {
        DocumentFile source = DocumentFile.fromTreeUri(context, sourceTreeUri);
        if (source == null || !source.isDirectory()) {
            throw new IOException("No se pudo abrir la carpeta seleccionada.");
        }

        String versionName = sanitizeName(source.getName());
        File externalFiles = context.getExternalFilesDir(null);
        File versionsDirectory = new File(
                externalFiles == null ? context.getFilesDir() : externalFiles,
                "Versions");
        File destination = new File(versionsDirectory, versionName);
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("No se pudo crear la carpeta de versiones.");
        }

        copyDirectoryContents(context, source, destination);
        return destination.getAbsolutePath();
    }

    private static void copyDirectoryContents(
            Context context,
            DocumentFile sourceDirectory,
            File destinationDirectory) throws IOException {
        for (DocumentFile child : sourceDirectory.listFiles()) {
            String childName = sanitizeName(child.getName());
            File destination = new File(destinationDirectory, childName);
            if (child.isDirectory()) {
                if (!destination.isDirectory() && !destination.mkdirs()) {
                    throw new IOException("No se pudo crear una subcarpeta de la versión.");
                }
                copyDirectoryContents(context, child, destination);
            } else if (child.isFile()) {
                try (InputStream input = context.getContentResolver().openInputStream(child.getUri());
                     OutputStream output = new FileOutputStream(destination)) {
                    if (input == null) {
                        throw new IOException("No se pudo leer un archivo de la carpeta seleccionada.");
                    }
                    copyStream(input, output);
                }
            } else {
                throw new IOException("No se pudo leer un elemento de la carpeta seleccionada.");
            }
        }
    }

    private static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
    }

    private static String sanitizeName(String name) throws IOException {
        if (name == null) {
            throw new IOException("La carpeta seleccionada no tiene un nombre válido.");
        }
        String safeName = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safeName.isEmpty() || safeName.equals(".") || safeName.equals("..")) {
            throw new IOException("El nombre de la carpeta seleccionada no es válido.");
        }
        return safeName;
    }
}
