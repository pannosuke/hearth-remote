package net.pcg.hearth.remote.i18n

import net.pcg.hearth.remote.protocol.obj
import net.pcg.hearth.remote.protocol.parseJson
import net.pcg.hearth.remote.protocol.textOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class I18nCatalogTest {

    @Test
    fun `english and japanese catalogs match the android resources`() {
        val root = repoRoot()
        val english = readXml(File(root, "app/src/main/res/values/strings.xml"))
        val japanese = readXml(File(root, "app/src/main/res/values-ja/strings.xml"))
        assertEquals(english.keys, japanese.keys)
        assertEquals(english, readJson(File(root, "shared/i18n/en.json")))
        assertEquals(japanese, readJson(File(root, "shared/i18n/ja.json")))
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 6) {
            if (File(dir, "shared/i18n/en.json").isFile && File(dir, "settings.gradle.kts").isFile) {
                return dir
            }
            dir = dir.parentFile
            hops++
        }
        error("repo root not found from ${File(".").absoluteFile}")
    }

    private fun readXml(file: File): Map<String, String> {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = builder.parse(file)
        val nodes = doc.getElementsByTagName("string")
        val out = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val name = node.attributes.getNamedItem("name").nodeValue
            out[name] = androidUnescape(node.textContent)
        }
        return out.toSortedMap()
    }

    private fun readJson(file: File): Map<String, String> {
        return parseJson(file.readText()).obj().mapValues { (_, value) ->
            value.textOrNull() ?: error("catalog value is not a string")
        }.toSortedMap()
    }

    private fun androidUnescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            if (value[i] == '\\' && i + 1 < value.length) {
                out.append(
                    when (val next = value[i + 1]) {
                        '\'' -> '\''
                        '"' -> '"'
                        'n' -> '\n'
                        't' -> '\t'
                        '\\' -> '\\'
                        '@' -> '@'
                        '?' -> '?'
                        else -> next
                    }
                )
                i += 2
            } else {
                out.append(value[i])
                i++
            }
        }
        return out.toString()
    }
}
