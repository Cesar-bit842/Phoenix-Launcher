package com.hytale.mobile.phoenix;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class JavaRuntimeInstaller {
    private static final String TAG = "JavaRuntimeInstaller";
    private static final String JAVA_RUNTIME_METADATA_URL =
            "https://launcher.hytale.com/version/release/jre.json";
    private static final String JAVA_RUNTIME_DIRECTORY = "hytale-jre-windows-amd64";
    private static final String JAVA_RUNTIME_MARKER = ".phoenix-hytale-jre-version";
    private static final String JAVA_EXECUTABLE_RELATIVE_PATH = "bin/java.exe";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Object JAVA_RUNTIME_LOCK = new Object();

    private final Context appContext;
    private final File javaRuntimeDirectory;

    JavaRuntimeInstaller(Context context) {
        appContext = context.getApplicationContext();
        javaRuntimeDirectory = new File(
                new File(appContext.getFilesDir(), "runtimes"), JAVA_RUNTIME_DIRECTORY);
    }

    interface ProgressListener {
        void onProgress(String status);
    }

    String getJavaExecutableWinePath() throws IOException {
        File javaExecutable = new File(javaRuntimeDirectory, JAVA_EXECUTABLE_RELATIVE_PATH);
        return AppStoragePaths.toWinePath(javaExecutable);
    }

    String ensureJavaRuntimeInstalled(ProgressListener progressListener) throws IOException {
        synchronized (JAVA_RUNTIME_LOCK) {
            try {
                return ensureJavaRuntimeInstalledLocked(progressListener);
            } catch (IOException error) {
                throw new IOException(
                        "No se pudo preparar Java para Wine. Si aún no está instalado, "
                                + "la primera instalación requiere Internet. Detalle: "
                                + error.getMessage(),
                        error);
            }
        }
    }

    private String ensureJavaRuntimeInstalledLocked(ProgressListener progressListener)
            throws IOException {
        File javaExecutable = new File(javaRuntimeDirectory, JAVA_EXECUTABLE_RELATIVE_PATH);
        File versionMarker = new File(javaRuntimeDirectory, JAVA_RUNTIME_MARKER);
        if (javaExecutable.isFile() && versionMarker.isFile()) {
            String installedVersion = RuntimeFileUtils.readText(versionMarker);
            if (!installedVersion.isEmpty()) {
                return AppStoragePaths.toWinePath(javaExecutable);
            }
        }

        File runtimesDirectory = javaRuntimeDirectory.getParentFile();
        if (runtimesDirectory == null
                || (!runtimesDirectory.isDirectory() && !runtimesDirectory.mkdirs())) {
            throw new IOException("No se pudo crear el directorio privado de runtimes de Phoenix.");
        }

        reportProgress(progressListener, "Consultando el runtime Java oficial de Hytale...");
        JSONObject metadata = fetchJavaRuntimeMetadata();
        String version = requiredJsonString(metadata, "version");
        JSONObject windowsAmd64;
        try {
            windowsAmd64 = metadata.getJSONObject("download_url")
                    .getJSONObject("windows")
                    .getJSONObject("amd64");
        } catch (JSONException error) {
            throw new IOException(
                    "El manifiesto oficial no ofrece Java para Windows x64.", error);
        }
        String downloadUrl = requiredJsonString(windowsAmd64, "url");
        String expectedSha256 = requiredJsonString(windowsAmd64, "sha256");
        if (!expectedSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("El manifiesto oficial de Java contiene un SHA-256 inválido.");
        }

        File archive = new File(appContext.getCacheDir(), "hytale-jre-windows-amd64.zip.part");
        File stagingDirectory = new File(runtimesDirectory, ".hytale-jre-staging");
        File readyDirectory = new File(runtimesDirectory, ".hytale-jre-ready");
        File backupDirectory = new File(runtimesDirectory, ".hytale-jre-backup");
        try {
            downloadJavaRuntime(downloadUrl, expectedSha256, archive, progressListener);
            if (stagingDirectory.exists() && !RuntimeFileUtils.deleteRecursively(stagingDirectory)) {
                throw new IOException("No se pudo limpiar una instalación Java incompleta.");
            }
            if (readyDirectory.exists() && !RuntimeFileUtils.deleteRecursively(readyDirectory)) {
                throw new IOException("No se pudo limpiar una instalación Java temporal.");
            }
            if (backupDirectory.exists() && !RuntimeFileUtils.deleteRecursively(backupDirectory)) {
                throw new IOException("No se pudo limpiar una copia anterior del runtime Java.");
            }
            if (!stagingDirectory.mkdirs()) {
                throw new IOException("No se pudo preparar la instalación temporal de Java.");
            }

            reportProgress(progressListener, "Instalando el runtime Java...");
            extractJavaRuntime(archive, stagingDirectory);
            File stagedJavaExecutable = findJavaExecutable(stagingDirectory);
            if (stagedJavaExecutable == null) {
                throw new IOException("El paquete Java oficial no contiene bin/java.exe.");
            }
            File stagedJavaHome = stagedJavaExecutable.getParentFile().getParentFile();
            if (stagedJavaHome == null) {
                throw new IOException("El paquete Java oficial tiene una estructura inesperada.");
            }
            moveDirectoryContents(stagedJavaHome, readyDirectory);
            RuntimeFileUtils.writeText(new File(readyDirectory, JAVA_RUNTIME_MARKER), version);
            if (!new File(readyDirectory, JAVA_EXECUTABLE_RELATIVE_PATH).isFile()) {
                throw new IOException("El runtime Java no contiene bin/java.exe.");
            }

            if (javaRuntimeDirectory.exists()
                    && !javaRuntimeDirectory.renameTo(backupDirectory)) {
                throw new IOException("No se pudo respaldar la instalación Java anterior.");
            }
            if (!readyDirectory.renameTo(javaRuntimeDirectory)) {
                if (backupDirectory.exists()) backupDirectory.renameTo(javaRuntimeDirectory);
                throw new IOException("No se pudo activar el runtime Java instalado.");
            }
            if (backupDirectory.exists() && !RuntimeFileUtils.deleteRecursively(backupDirectory)) {
                Log.w(TAG, "No se pudo eliminar la copia temporal del runtime Java anterior.");
            }

            reportProgress(progressListener, "Runtime Java listo.");
            return AppStoragePaths.toWinePath(
                    new File(javaRuntimeDirectory, JAVA_EXECUTABLE_RELATIVE_PATH));
        } finally {
            if (archive.exists() && !archive.delete()) {
                Log.w(TAG, "No se pudo eliminar el archivo temporal del runtime Java.");
            }
            if (stagingDirectory.exists()
                    && !RuntimeFileUtils.deleteRecursively(stagingDirectory)) {
                Log.w(TAG, "No se pudo eliminar la extracción temporal del runtime Java.");
            }
            if (readyDirectory.exists()
                    && !RuntimeFileUtils.deleteRecursively(readyDirectory)) {
                Log.w(TAG, "No se pudo eliminar la instalación temporal del runtime Java.");
            }
        }
    }

    private JSONObject fetchJavaRuntimeMetadata() throws IOException {
        HttpURLConnection connection = openHttpsConnection(
                new URL(JAVA_RUNTIME_METADATA_URL));
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException(
                        "El servidor oficial de Java respondió HTTP " + responseCode + ".");
            }
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IOException("El manifiesto de Java no se mantuvo en HTTPS.");
            }
            if (connection.getContentLengthLong() > 1024 * 1024) {
                throw new IOException("El manifiesto oficial de Java es demasiado grande.");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    if (output.size() + bytesRead > 1024 * 1024) {
                        throw new IOException("El manifiesto oficial de Java es demasiado grande.");
                    }
                    output.write(buffer, 0, bytesRead);
                }
                return new JSONObject(output.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
            } catch (JSONException error) {
                throw new IOException("El manifiesto oficial de Java no tiene un formato válido.", error);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String requiredJsonString(JSONObject object, String key) throws IOException {
        try {
            String value = object.getString(key).trim();
            if (value.isEmpty() || "null".equals(value)) {
                throw new IOException("El manifiesto oficial de Java no contiene '" + key + "'.");
            }
            return value;
        } catch (JSONException error) {
            throw new IOException("El manifiesto oficial de Java no contiene '" + key + "'.", error);
        }
    }

    private void downloadJavaRuntime(
            String downloadUrl,
            String expectedSha256,
            File archive,
            ProgressListener progressListener) throws IOException {
        URL url;
        try {
            url = new URL(downloadUrl);
        } catch (java.net.MalformedURLException error) {
            throw new IOException("La URL del runtime Java oficial no es válida.", error);
        }
        HttpURLConnection connection = openHttpsConnection(url);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("Android no dispone de SHA-256 para verificar Java.", error);
        }

        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException(
                        "La descarga oficial de Java respondió HTTP " + responseCode + ".");
            }
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IOException("La descarga del runtime Java no se mantuvo en HTTPS.");
            }
            long totalBytes = connection.getContentLengthLong();
            reportProgress(progressListener, "Descargando Java (0%)...");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(archive)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long totalRead = 0;
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    digest.update(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    if (totalBytes > 0) {
                        int percent = (int) Math.min(99, totalRead * 100 / totalBytes);
                        reportProgress(progressListener, "Descargando Java (" + percent + "%)...");
                    }
                }
                output.getFD().sync();
            }
        } finally {
            connection.disconnect();
        }

        if (!toHexString(digest.digest()).equalsIgnoreCase(expectedSha256)) {
            if (!archive.delete()) {
                Log.w(TAG, "No se pudo eliminar una descarga Java que no pasó SHA-256.");
            }
            throw new IOException("La descarga de Java no pasó la verificación SHA-256.");
        }
    }

    private static HttpURLConnection openHttpsConnection(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("El runtime Java solo se puede descargar mediante HTTPS.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(20));
        connection.setReadTimeout((int) TimeUnit.SECONDS.toMillis(60));
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Phoenix-Launcher");
        connection.setRequestProperty("Accept", "*/*");
        return connection;
    }

    private void extractJavaRuntime(File archive, File destination) throws IOException {
        Path canonicalRoot = destination.getCanonicalFile().toPath();
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                Path relative;
                try {
                    relative = Paths.get(entryName).normalize();
                } catch (RuntimeException error) {
                    throw new IOException("El archivo Java contiene una ruta no válida.", error);
                }
                if (relative.isAbsolute() || relative.startsWith("..")) {
                    throw new IOException("El archivo Java contiene una ruta fuera del runtime.");
                }
                if (relative.toString().isEmpty() && !entry.isDirectory()) {
                    throw new IOException("El archivo Java contiene una entrada sin nombre.");
                }

                File target = new File(destination, relative.toString()).getCanonicalFile();
                if (!target.toPath().startsWith(canonicalRoot)) {
                    throw new IOException("El archivo Java contiene una ruta fuera del runtime.");
                }
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new IOException("No se pudo extraer una carpeta del runtime Java.");
                    }
                } else {
                    RuntimeFileUtils.ensureParentExists(target);
                    try (BufferedOutputStream output = new BufferedOutputStream(
                            new FileOutputStream(target))) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int bytesRead;
                        while ((bytesRead = zip.read(buffer)) != -1) {
                            output.write(buffer, 0, bytesRead);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private File findJavaExecutable(File directory) throws IOException {
        File[] entries = directory.listFiles();
        if (entries == null) {
            throw new IOException("No se pudo inspeccionar el runtime Java extraído.");
        }
        File found = null;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                File nested = findJavaExecutable(entry);
                if (nested != null) {
                    if (found != null) {
                        throw new IOException("El paquete Java contiene varios ejecutables java.exe.");
                    }
                    found = nested;
                }
            } else if (entry.isFile()
                    && "java.exe".equalsIgnoreCase(entry.getName())
                    && entry.getParentFile() != null
                    && "bin".equalsIgnoreCase(entry.getParentFile().getName())) {
                if (found != null) {
                    throw new IOException("El paquete Java contiene varios ejecutables java.exe.");
                }
                found = entry;
            }
        }
        return found;
    }

    private void moveDirectoryContents(File source, File destination) throws IOException {
        if (!destination.mkdirs() && !destination.isDirectory()) {
            throw new IOException("No se pudo preparar la carpeta final del runtime Java.");
        }
        File[] entries = source.listFiles();
        if (entries == null || entries.length == 0) {
            throw new IOException("El paquete Java está vacío o incompleto.");
        }
        for (File entry : entries) {
            File target = new File(destination, entry.getName());
            try {
                Files.move(entry.toPath(), target.toPath());
            } catch (IOException error) {
                throw new IOException("No se pudo instalar un archivo del runtime Java.", error);
            }
        }
    }

    private static String toHexString(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void reportProgress(ProgressListener listener, String status) {
        if (listener != null) listener.onProgress(status);
    }
}
