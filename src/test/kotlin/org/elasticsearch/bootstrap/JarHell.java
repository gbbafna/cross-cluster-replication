package org.elasticsearch.bootstrap;

import org.elasticsearch.common.SuppressForbidden;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.Manifest;

public class JarHell {

    private JarHell() {
    }

    public static void checkJarHell(Consumer<String> output) throws IOException, URISyntaxException {
    }

    public static Set<URL> parseClassPath() {
        return Collections.emptySet();
    }

    @SuppressForbidden(reason = "resolves against CWD because that is how classpaths work")
    static Set<URL> parseClassPath(String classPath) {
        return Collections.emptySet();
    }

    @SuppressForbidden(reason = "needs JarFile for speed, just reading entries")
    public static void checkJarHell(Set<URL> urls, Consumer<String> output) throws URISyntaxException, IOException {
    }

    /**
     * inspect manifest for sure incompatibilities
     */
    private static void checkManifest(Manifest manifest, Path jar) {
    }

    public static void checkVersionFormat(String targetVersion) {
    }

    /**
     * Checks that the java specification version {@code targetVersion}
     * required by {@code resource} is compatible with the current installation.
     */
    public static void checkJavaVersion(String resource, String targetVersion) {
    }

    private static void checkClass(Map<String, Path> clazzes, String clazz, Path jarpath) {
    }
}
