package dev.gpuaccel.gpu;

import org.lwjgl.system.Configuration;
import org.lwjgl.system.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Extracts the bundled LWJGL <em>core</em> native (lwjgl.dll / liblwjgl.so / liblwjgl.dylib)
 * to a temporary directory and points {@link Configuration#LIBRARY_PATH} at it.
 *
 * <p>LWJGL's OpenCL binding is JNI-free: it loads the system OpenCL ICD loader
 * (OpenCL.dll / libOpenCL.so) shipped with the GPU driver. But loading any LWJGL binding
 * first requires the LWJGL core native to be present. Minecraft ships it, but a dedicated
 * server may not have it on {@code java.library.path}, so we bundle it as a fallback.</p>
 */
public final class LwjglNatives {
    private LwjglNatives() {}

    public static void prepare() {
        String resource = nativeResource();
        if (resource == null) return;
        try {
            Path dir;
            try {
                dir = Files.createTempDirectory("gpuaccel-natives");
            } catch (IOException e) {
                // Temp dir may be locked down (e.g. sandboxed servers); fall back to the working directory.
                dir = Paths.get("gpuaccel-natives").toAbsolutePath();
                Files.createDirectories(dir);
            }
            Path file = dir.resolve(nativeFileName());
            try (InputStream in = LwjglNatives.class.getResourceAsStream(resource)) {
                if (in == null) return;
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
            String abs = dir.toAbsolutePath().toString();
            Configuration.LIBRARY_PATH.set(abs);
            System.setProperty("org.lwjgl.librarypath", abs);
        } catch (IOException ignored) {
            // Extraction failed; rely on the host's own LWJGL native setup instead.
        }
    }

    private static String nativeResource() {
        switch (Platform.get()) {
            case WINDOWS: return "/windows/x64/org/lwjgl/lwjgl.dll";
            case LINUX:   return "/linux/x64/org/lwjgl/liblwjgl.so";
            case MACOSX:  return "/macos/x64/org/lwjgl/liblwjgl.dylib";
            default:      return null;
        }
    }

    private static String nativeFileName() {
        switch (Platform.get()) {
            case WINDOWS: return "lwjgl.dll";
            case LINUX:   return "liblwjgl.so";
            case MACOSX:  return "liblwjgl.dylib";
            default:      return "lwjgl";
        }
    }
}
