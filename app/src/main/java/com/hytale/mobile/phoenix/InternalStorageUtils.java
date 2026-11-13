package com.hytale.mobile.phoenix;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
     * Crea una carpeta y sus carpetas padre dentro del almacenamiento interno.
     * No permite rutas que salgan de la carpeta privada de la aplicación.
     */
    public static boolean createDirectory(Context context, String relativePath) {
        try {
            File directory = getDirectory(context, relativePath);
            return directory.isDirectory() || directory.mkdirs();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Elimina una carpeta vacía del almacenamiento interno.
     */
    public static boolean deleteDirectory(Context context, String relativePath) {
        return deleteDirectory(context, relativePath, false);
    }

    /**
     * Elimina una carpeta del almacenamiento interno. Si {@code recursive} es false,
     * solo elimina carpetas vacías; si es true, también elimina su contenido.
     * No permite eliminar la raíz del almacenamiento interno ni salir de ella.
     */
    public static boolean deleteDirectory(Context context, String relativePath, boolean recursive) {
        try {
            File directory = getDirectory(context, relativePath);
            if (!directory.isDirectory()) {
                return false;
            }
            return recursive ? deleteRecursively(directory) : directory.delete();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
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

    private static File getDirectory(Context context, String relativePath) throws IOException {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IOException("Directory path must not be empty");
        }

        File root = context.getFilesDir().getCanonicalFile();
        File requestedDirectory = new File(root, relativePath).getAbsoluteFile();
        File canonicalDirectory = requestedDirectory.getCanonicalFile();
        String rootPath = root.getPath();
        if (canonicalDirectory.equals(root)
                || !canonicalDirectory.getPath().startsWith(rootPath + File.separator)) {
            throw new IOException("Directory path must stay inside app internal storage");
        }
        return requestedDirectory;
    }

    private static boolean deleteRecursively(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath()) || !file.isDirectory()) {
            return file.delete();
        }

        File[] children = file.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (!deleteRecursively(child)) {
                return false;
            }
        }
        return file.delete();
    }
}
