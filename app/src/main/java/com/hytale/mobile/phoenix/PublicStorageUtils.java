package com.hytale.mobile.phoenix;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Utilidad para gestionar archivos accesibles en el almacenamiento del dispositivo
 * (fuera de android/data, tales como directorios públicos, descargas o almacenamiento externo).
 */
public class PublicStorageUtils {

    /**
     * Obtiene el directorio público de Descargas (Downloads).
     */
    public static File getPublicDownloadsDirectory() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    /**
     * Obtiene el directorio de archivos externos de la aplicación.
     */
    public static File getExternalFilesDirectory(Context context, String type) {
        return context.getExternalFilesDir(type);
    }

    /**
     * Verifica si un archivo existe en la ruta absoluta dada.
     */
    public static boolean exists(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) return false;
        File file = new File(absolutePath);
        return file.exists();
    }

    public static boolean exists(File file) {
        return file != null && file.exists();
    }

    /**
     * Crea un archivo en la ruta absoluta dada (creando directorios padres si faltan).
     */
    public static boolean createFile(String absolutePath) {
        try {
            File file = new File(absolutePath);
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
     * Escribe texto en un archivo en la ruta absoluta dada.
     */
    public static boolean writeFile(String absolutePath, String content, boolean append) {
        try {
            File file = new File(absolutePath);
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
     * Lee texto de un archivo en la ruta absoluta dada.
     */
    public static String readFile(String absolutePath) {
        File file = new File(absolutePath);
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
     * Elimina un archivo en la ruta absoluta dada.
     */
    public static boolean deleteFile(String absolutePath) {
        File file = new File(absolutePath);
        if (file.exists()) {
            return file.delete();
        }
        return false;
    }
}
