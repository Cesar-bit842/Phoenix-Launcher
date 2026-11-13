package com.hytale.mobile.phoenix;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Utilidad para gestionar archivos dentro de la carpeta interna del programa
 * (android/data/com.hytale.mobile.phoenix/files).
 */
public class InternalStorageUtils {

    /**
     * Obtiene un archivo dentro del almacenamiento interno de la app.
     */
    public static File getFile(Context context, String relativePath) {
        return new File(context.getFilesDir(), relativePath);
    }

    /**
     * Verifica si el archivo existe en el almacenamiento interno.
     */
    public static boolean exists(Context context, String relativePath) {
        File file = getFile(context, relativePath);
        return file.exists() && file.isFile();
    }

    /**
     * Crea un archivo (y sus directorios padres) en el almacenamiento interno.
     */
    public static boolean createFile(Context context, String relativePath) {
        try {
            File file = getFile(context, relativePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!file.exists()) {
                return file.createNewFile();
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Escribe texto en un archivo del almacenamiento interno.
     */
    public static boolean writeFile(Context context, String relativePath, String content, boolean append) {
        try {
            File file = getFile(context, relativePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(file, append)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                return true;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Lee el contenido de texto de un archivo del almacenamiento interno.
     */
    public static String readFile(Context context, String relativePath) {
        File file = getFile(context, relativePath);
        if (!file.exists() || !file.isFile()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int read = fis.read(buffer);
            if (read > 0) {
                return new String(buffer, 0, read, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Elimina un archivo del almacenamiento interno.
     */
    public static boolean deleteFile(Context context, String relativePath) {
        File file = getFile(context, relativePath);
        if (file.exists()) {
            return file.delete();
        }
        return false;
    }
}
