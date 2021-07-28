package org.elasticsearch.bootstrap;

import java.net.URL;

public class JarHell {
    private JarHell() {}
    public static void checkJarHell() throws Exception {}
    public static void checkJarHell(URL urls[]) throws Exception {}
    public static void checkJarHell(Set<URL> urls, Consumer<String> output) throws Exception {}

    public static void checkVersionFormat(String targetVersion) {}
    public static void checkJavaVersion(String resource, String targetVersion) {}
    public static Set<URL> parseClassPath() {return ImmutableSet.of();}
    private static void checkManifest(Manifest manifest, Path jar){}
    private static void checkClass(Map<String, Path> clazzes, String clazz, Path jarpath) {}
}

