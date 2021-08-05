package org.elasticsearch.bootstrap

import java.net.URL
import java.nio.file.Path
import java.util.function.Consumer
import java.util.jar.Manifest


object JarHellKotlin {
    @Throws(Exception::class)
    fun checkJarHell() {
    }

    @Throws(Exception::class)
    fun checkJarHell(urls: Array<URL?>?) {
    }

    @Throws(Exception::class)
    fun checkJarHell(output: Consumer<String?>?) {
    }

    @Throws(Exception::class)
    fun checkJarHell(urls: Set<URL?>?, output: Consumer<String?>?) {
    }

    fun checkVersionFormat(targetVersion: String?) {}
    fun checkJavaVersion(resource: String?, targetVersion: String?) {}
    fun parseClassPath(): Set<URL> {
        return setOf<URL>()
    }

    private fun checkManifest(manifest: Manifest, jar: Path) {}
    private fun checkClass(clazzes: Map<String, Path>, clazz: String, jarpath: Path) {}

}

