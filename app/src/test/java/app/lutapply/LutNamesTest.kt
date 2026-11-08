package app.lutapply

import org.junit.Assert.assertEquals
import org.junit.Test

class LutNamesTest {
    @Test
    fun kebabToTitleCase() {
        assertEquals("Teal Orange Heavy", LutNames.pretty("teal-orange-heavy.cube"))
        assertEquals("Kodak 2383", LutNames.pretty("kodak_2383.cube"))
    }

    @Test
    fun slugForFileNames() {
        assertEquals("teal-orange-heavy", LutNames.slug("Teal Orange Heavy"))
    }
}
