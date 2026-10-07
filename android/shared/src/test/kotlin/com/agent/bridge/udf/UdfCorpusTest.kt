package com.agent.bridge.udf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class UdfCorpusTest {

    @Test
    fun generatedCorpusFixturesRemainReadable() {
        val corpus = findCorpusDirectory()
        val files = Files.list(corpus).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".udf") }.sorted().toList()
        }

        assertEquals("İP-4 korpusundaki fixture sayısı değişti", 7, files.size)
        for (file in files) {
            Files.newInputStream(file).use { input ->
                val document = UdfParser.readUdf(input)
                assertTrue("Boş text: ${file.fileName}", document.text.isNotEmpty() || document.blocks.isNotEmpty())
                assertTrue("İmzasız fixture bekleniyordu: ${file.fileName}", !document.isSigned)
            }
        }

        Files.newInputStream(corpus.resolve("06-tablolu-belge.udf")).use { input ->
            val table = UdfParser.readUdf(input)
            assertEquals(1, table.blocks.size)
            assertTrue(table.blocks.single() is UdfTableBlock)
        }

        Files.newInputStream(corpus.resolve("04-linespacing-girinti.udf")).use { input ->
            val spacingFixture = UdfParser.readUdf(input)
            assertEquals(0.5f, spacingFixture.paragraphs.single().lineSpacing!!, 0.001f)
        }
    }

    private fun findCorpusDirectory(): Path {
        // Fixture'lar artık modülün test kaynaklarında yaşıyor. Gradle testi
        // modül dizininden çalıştırır; repo kökünden koşulma ihtimaline karşı
        // yukarı doğru da yürünür.
        val relative = Path.of("src", "test", "resources", "udf-korpus")
        var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            for (candidate in listOf(
                directory.resolve(relative),
                directory.resolve("android").resolve("shared").resolve(relative),
            )) {
                if (Files.isDirectory(candidate)) return candidate
            }
            directory = directory.parent ?: return@repeat
        }
        error("udf-korpus fixture klasörü bulunamadı")
    }
}
