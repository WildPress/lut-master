package app.lutapply

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledLutsTest {
    @Test
    fun everyBundledLutParses() {
        val root = File("src/main/assets/luts")
        val files = root.walkTopDown().filter { it.extension == "cube" }.toList()
        assertTrue("no bundled LUTs found under ${root.absolutePath}", files.isNotEmpty())
        for (f in files) {
            try {
                CubeParser.parseFloat(f.readText())
            } catch (e: Exception) {
                throw AssertionError("${f.name}: ${e.message}")
            }
        }
    }
}
