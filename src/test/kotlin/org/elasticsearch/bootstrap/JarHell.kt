package org.elasticsearch.bootstrap

import java.net.URL
import java.nio.file.Path
import java.util.*
import java.util.Map
import java.util.Set
import java.util.function.Consumer
import java.util.jar.Manifest


object JarHell {
    @Throws(Exception::class)
    fun checkJarHell() {
    }

    @Throws(Exception::class)
    fun checkJarHell(urls: Array<URL?>?) {
    }

    @Throws(Exception::class)
    fun checkJarHell(urls: Set<URL?>?, output: Consumer<String?>?) {
    }

    fun checkVersionFormat(targetVersion: String?) {}
    fun checkJavaVersion(resource: String?, targetVersion: String?) {}
    fun parseClassPath(): Set<URL> {
        val urlElements: Set<URL> = LinkedHashSet()
        return urlElements
    }

    private fun checkManifest(manifest: Manifest, jar: Path) {}
    private fun checkClass(clazzes: Map<String, Path>, clazz: String, jarpath: Path) {}
}

