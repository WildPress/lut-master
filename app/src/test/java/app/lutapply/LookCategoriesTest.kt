package app.lutapply

import org.junit.Assert.assertEquals
import org.junit.Test

class LookCategoriesTest {
    @Test
    fun oldLookPathsMoveToTheirNewFolders() {
        val m = LookCategories::migratePath
        assertEquals("luts/looks/moody/foggy-teal-woods.cube", m("luts/looks/moody-landscape/dark-forest.cube"))
        assertEquals("luts/looks/moody/dark-forest.cube", m("luts/looks/moody/dark-forest.cube"))
        assertEquals("luts/looks/moody/fog-grey.cube", m("luts/looks/moody-landscape/fog-grey.cube"))
        assertEquals("luts/looks/vivid/velvia-punch.cube", m("luts/looks/slide-film/velvia-punch.cube"))
        assertEquals("luts/looks/film/kodak-portra-400-style.cube", m("luts/looks/kodak-portra-400-style.cube"))
        assertEquals("luts/looks/tinted-blacks/blacks-teal.cube", m("luts/looks/blacks-teal.cube"))
        assertEquals("luts/looks/foggy-forest/golden-fog.cube", m("luts/looks/golden-fog.cube"))
        assertEquals("external:Mine", m("external:Mine"))
        assertEquals("Moody", LookCategories.migrateCategory("Moody Landscape"))
    }
}
