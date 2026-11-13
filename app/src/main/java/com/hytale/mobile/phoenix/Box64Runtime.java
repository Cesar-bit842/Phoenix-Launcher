package com.hytale.mobile.phoenix;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.github.luben.zstd.ZstdInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Box64Runtime {
    private static final String TAG = "Box64Runtime";
    private static final String ROOTFS_DIRECTORY = "rootfs";
    private static final String INSTALL_MARKER = ".phoenix-box64-runtime";
    private static final String RUNTIME_VERSION = "winlator-box64-0.4.4";
    private static final String BOX64_LIBRARY = "libbox64.so";
    private static final String LOADER_LIBRARY = "libbox64_loader.so";
    private static final String ROOTFS_LOADER_PATH = "lib/ld-linux-aarch64.so.1";
    private static final String BOX64_CONFIG_PATH = "etc/config.box64rc";
    private static final String ZSTD_LIBRARY_NAME = "zstd-jni-1.5.2-3";
    private static final int EXECUTABLE_MODE = 0x1ED;
    private static final int CONFIG_MODE = 0x1A4;
    private static final int PERMISSION_MASK = 0x1FF;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROCESS_TIMEOUT_SECONDS = 15L;

    private final Context appContext;
    private final File rootFs;
    private final File nativeLibraryDir;
    private final File box64;
    private final File loader;
    private final File rootFsLoader;
    private final File installMarker;

    public Box64Runtime(Context context) {
        this.appContext = context.getApplicationContext();
        this.rootFs = new File(appContext.getFilesDir(), ROOTFS_DIRECTORY);
        this.nativeLibraryDir = new File(appContext.getApplicationInfo().nativeLibraryDir);
        this.box64 = new File(nativeLibraryDir, BOX64_LIBRARY);
        this.loader = new File(nativeLibraryDir, LOADER_LIBRARY);
        this.rootFsLoader = new File(rootFs, ROOTFS_LOADER_PATH);
        this.installMarker = new File(appContext.getFilesDir(), INSTALL_MARKER);
    }

    public void install() throws IOException {
        requireSupportedArchitecture();
        if (isInstalled()) return;
        if (!box64.isFile() || !loader.isFile()) {
            throw new IOException(
                    "Faltan Box64 o su cargador en el directorio nativo instalado: " +
                            nativeLibraryDir.getAbsolutePath()
            );
        }

        if (installMarker.exists() && !installMarker.delete()) {
            throw new IOException("No se pudo invalidar el marcador de instalación anterior.");
        }
        if (rootFs.exists() && !deleteRecursively(rootFs)) {
            throw new IOException("No se pudo limpiar " + rootFs.getAbsolutePath());
        }
        if (!rootFs.mkdirs() && !rootFs.isDirectory()) {
            throw new IOException("No se pudo crear " + rootFs.getAbsolutePath());
        }

        try {
            loadZstdLibrary();
            extractArchive("runtime/rootfs.tzst");
            extractArchive("runtime/rootfs_patches.tzst");
            installBox64Config();

            if (!rootFsLoader.isFile()) {
                throw new IOException("El RootFS no contiene el cargador glibc.");
            }
            chmod(rootFsLoader, EXECUTABLE_MODE);
            writeText(installMarker, RUNTIME_VERSION);
        } catch (ErrnoException error) {
            throw new IOException("Android no pudo preparar los permisos del runtime: " + error.getMessage(), error);
        } catch (SecurityException error) {
            throw new IOException("Android denegó el acceso al almacenamiento privado: " + error.getMessage(), error);
        }
    }

    public String runVersion() throws IOException {
        requireSupportedArchitecture();
        if (!isInstalled()) {
            throw new IllegalStateException("Instala primero el runtime de Box64.");
        }

        String pathSeparator = File.pathSeparator;
        String libraryPath = String.join(pathSeparator,
                new File(rootFs, "lib").getAbsolutePath(),
                new File(rootFs, "usr/lib").getAbsolutePath(),
                new File(rootFs, "lib/aarch64-linux-gnu").getAbsolutePath(),
                new File(rootFs, "usr/lib/aarch64-linux-gnu").getAbsolutePath()
        );

        List<String> command = Arrays.asList(
                loader.getAbsolutePath(),
                "--library-path",
                libraryPath,
                box64.getAbsolutePath(),
                "-v"
        );

        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(rootFs);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("HOME", new File(rootFs, "home/xuser").getAbsolutePath());
            env.put("USER", "xuser");
            env.put("TMPDIR", new File(rootFs, "tmp").getAbsolutePath());
            env.put("PATH", String.join(pathSeparator,
                    new File(rootFs, "opt/wine/bin").getAbsolutePath(),
                    new File(rootFs, "usr/local/bin").getAbsolutePath(),
                    new File(rootFs, "usr/bin").getAbsolutePath()
            ));
            env.put("LD_LIBRARY_PATH", libraryPath);
            env.put("BOX64_RCFILE", new File(rootFs, BOX64_CONFIG_PATH).getAbsolutePath());
            env.put("BOX64_LD_LIBRARY_PATH", new File(rootFs, "lib/x86_64-linux-gnu").getAbsolutePath());
            env.put("BOX64_NOBANNER", "1");
            env.put("BOX64_DYNAREC", "1");
            env.put("BOX64_UNITYPLAYER", "0");
            env.put("BOX64_DYNACACHE", "0");

            process = pb.start();
        } catch (IOException error) {
            String details = "No se pudo iniciar Box64. command=" + String.join(" ", command) + "; " +
                    "cwd=" + rootFs.getAbsolutePath() + "; " +
                    "rootfs(isDirectory=" + rootFs.isDirectory() + ", canExecute=" + rootFs.canExecute() + "); " +
                    "nativeLibDir=" + nativeLibraryDir.getAbsolutePath() + "; " +
                    "loader(isFile=" + loader.isFile() + ", canExecute=" + loader.canExecute() + "); " +
                    "box64(isFile=" + box64.isFile() + ", canExecute=" + box64.canExecute() + "); " +
                    "causa=" + error.getMessage();
            Log.e(TAG, details, error);
            throw new IOException(details, error);
        }

        boolean finished;
        try {
            finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            Log.e(TAG, "Se interrumpió la prueba de Box64.", error);
            throw new IOException("Se interrumpió la prueba de Box64.", error);
        }

        if (!finished) {
            process.destroyForcibly();
            String details = "Box64 no respondió en " + PROCESS_TIMEOUT_SECONDS + "s. " +
                    "command=" + String.join(" ", command) + "; cwd=" + rootFs.getAbsolutePath();
            Log.e(TAG, details);
            throw new IOException(details);
        }

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            output = sb.toString().trim();
        }

        if (process.exitValue() != 0) {
            String details = "Box64 terminó con código " + process.exitValue() + ": " + output;
            Log.e(TAG, details);
            throw new IOException(details);
        }
        return output.isEmpty() ? "Box64 terminó sin salida." : output;
    }

    public boolean isInstalled() {
        return installMarker.isFile() &&
                readText(installMarker).equals(RUNTIME_VERSION) &&
                rootFsLoader.isFile() &&
                box64.isFile() &&
                loader.isFile() &&
                loader.canExecute() &&
                box64.canExecute();
    }

    private void requireSupportedArchitecture() {
        boolean supportsArm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                supportsArm64 = true;
                break;
            }
        }
        if (!supportsArm64) {
            throw new UnsupportedOperationException("Box64 requiere Android ARM64 (arm64-v8a).");
        }
    }

    private void loadZstdLibrary() throws IOException {
        try {
            System.loadLibrary(ZSTD_LIBRARY_NAME);
        } catch (UnsatisfiedLinkError error) {
            throw new IOException("No se pudo cargar el decodificador Zstandard para Android ARM64.", error);
        }
    }

    private void extractArchive(String assetPath) throws IOException, ErrnoException {
        try (InputStream asset = appContext.getAssets().open(assetPath);
             ZstdInputStream decompressed = new ZstdInputStream(new BufferedInputStream(asset));
             TarArchiveInputStream tar = new TarArchiveInputStream(decompressed)) {
            
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry) tar.getNextEntry()) != null) {
                File target = resolveTarget(rootFs, entry.getName());
                if (entry.isDirectory()) {
                    if (Files.isSymbolicLink(target.toPath())) {
                        removeExistingTarget(target);
                    }
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new IOException("No se pudo crear " + target.getAbsolutePath());
                    }
                    chmod(target, entry.getMode() & PERMISSION_MASK);
                } else if (entry.isSymbolicLink()) {
                    ensureParentExists(target);
                    removeExistingTarget(target);
                    Os.symlink(entry.getLinkName(), target.getAbsolutePath());
                } else if (entry.isLink()) {
                    ensureParentExists(target);
                    removeExistingTarget(target);
                    File linkTarget = resolveTarget(rootFs, entry.getLinkName().replaceAll("^/+", ""));
                    Os.link(linkTarget.getAbsolutePath(), target.getAbsolutePath());
                    chmod(target, entry.getMode() & PERMISSION_MASK);
                } else if (entry.isFile()) {
                    ensureParentExists(target);
                    removeExistingTarget(target);
                    try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int len;
                        while ((len = tar.read(buffer)) != -1) {
                            output.write(buffer, 0, len);
                        }
                    }
                    chmod(target, entry.getMode() & PERMISSION_MASK);
                } else {
                    throw new IOException("Tipo de entrada TAR no soportado: " + entry.getName());
                }
            }
        }
    }

    private void installBox64Config() throws IOException, ErrnoException {
        File config = new File(rootFs, BOX64_CONFIG_PATH);
        ensureParentExists(config);
        try (InputStream input = appContext.getAssets().open("runtime/box64/default.box64rc");
             FileOutputStream output = new FileOutputStream(config)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = input.read(buffer)) != -1) {
                output.write(buffer, 0, len);
            }
        }
        chmod(config, CONFIG_MODE);
    }

    private File resolveTarget(File root, String archivePath) throws IOException {
        Path normalized;
        try {
            normalized = Paths.get(archivePath.replace('\\', '/')).normalize();
        } catch (RuntimeException error) {
            throw new IOException("Ruta inválida dentro del runtime: " + archivePath, error);
        }
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IOException("Ruta fuera del runtime: " + archivePath);
        }
        if (normalized.toString().isEmpty() || normalized.toString().equals(".")) return root;

        File target = new File(root, normalized.toString());
        Path canonicalRoot = root.getCanonicalFile().toPath();
        File parent = target.getParentFile();
        if (parent == null) {
            throw new IOException("Ruta inválida dentro del runtime: " + archivePath);
        }
        Path canonicalParent = parent.getCanonicalFile().toPath();
        if (!canonicalParent.startsWith(canonicalRoot)) {
            throw new IOException("Ruta fuera del runtime: " + archivePath);
        }
        return target;
    }

    private void ensureParentExists(File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("No se pudo crear " + parent.getAbsolutePath());
        }
    }

    private void removeExistingTarget(File target) throws IOException {
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) && !target.delete()) {
            throw new IOException("No se pudo reemplazar " + target.getAbsolutePath());
        }
    }

    private void chmod(File file, int mode) throws ErrnoException {
        Os.chmod(file.getAbsolutePath(), mode);
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return false;
            }
            for (File child : children) {
                if (!deleteRecursively(child)) {
                    return false;
                }
            }
        }
        return file.delete();
    }

    private void writeText(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readText(File file) {
        try {
            byte[] encoded = Files.readAllBytes(file.toPath());
            return new String(encoded, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }
}
