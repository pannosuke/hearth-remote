package net.pcg.hearth.remote.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `language tags map to the three settings`() {
        assertEquals(AppLanguage.System, AppLanguage.fromLanguageTags(""))
        assertEquals(AppLanguage.English, AppLanguage.fromLanguageTags("en"))
        assertEquals(AppLanguage.English, AppLanguage.fromLanguageTags("en-US"))
        assertEquals(AppLanguage.Japanese, AppLanguage.fromLanguageTags("ja"))
        assertEquals(AppLanguage.Japanese, AppLanguage.fromLanguageTags("ja-JP"))
    }
}
