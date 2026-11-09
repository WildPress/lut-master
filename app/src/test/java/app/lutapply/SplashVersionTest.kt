package app.lutapply

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// The splash shows the version as drawn text, so it has to be regenerated on every release:
// python3 ../cat-daddy-brand/make_brand.py <version>, then copy android/lut-master/splash_branding.xml.
class SplashVersionTest {
    @Test
    fun splashShowsTheCurrentVersion() {
        val gradle = File("build.gradle.kts").readText()
        val version = Regex("""versionName = "([^"]+)"""").find(gradle)!!.groupValues[1]
        val branding = File("src/main/res/drawable/splash_branding.xml").readText()
        val shown = Regex("""<!-- version: ([^ ]+) -->""").find(branding)?.groupValues?.get(1)
        assertEquals("Regenerate splash_branding.xml for this version", version, shown)
    }
}
