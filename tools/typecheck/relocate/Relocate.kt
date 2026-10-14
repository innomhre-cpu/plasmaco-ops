import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.commons.ClassRemapper
import org.jetbrains.org.objectweb.asm.commons.Remapper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Rewrites references to the shaded IntelliJ packages of kotlin-compiler-embeddable back to the plain packages used
// by the CLI compiler, so the Compose compiler plugin can be loaded by kotlinc for frontend checks.
fun main(args: Array<String>) {
    val from = "org/jetbrains/kotlin/com/intellij/"
    val to = "com/intellij/"
    val remapper = object : Remapper() {
        override fun map(internalName: String): String = if (internalName.startsWith(from)) to + internalName.removePrefix(from) else internalName
    }
    ZipFile(args[0]).use { zin ->
        ZipOutputStream(File(args[1]).outputStream()).use { zout ->
            for (e in zin.entries()) {
                val bytes = zin.getInputStream(e).readBytes()
                zout.putNextEntry(ZipEntry(e.name))
                if (e.name.endsWith(".class")) {
                    val cw = ClassWriter(0)
                    ClassReader(bytes).accept(ClassRemapper(cw, remapper), 0)
                    zout.write(cw.toByteArray())
                } else zout.write(bytes)
                zout.closeEntry()
            }
        }
    }
}
