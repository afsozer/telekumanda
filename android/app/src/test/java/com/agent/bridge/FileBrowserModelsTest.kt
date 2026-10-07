package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBrowserModelsTest {

    @Test
    fun sortDirEntriesByNameMtimeAndSize() {
        val entries = listOf(
            DirEntry(name = "b.txt", path = "/b.txt", type = "file", size = 30, mtime = 100),
            DirEntry(name = "A.txt", path = "/A.txt", type = "file", size = 10, mtime = 300),
            DirEntry(name = "c.txt", path = "/c.txt", type = "file", size = 20, mtime = 200),
        )
        // Ad: harf büyüklüğünden bağımsız A→Z; azalanda Z→A.
        assertEquals(listOf("A.txt", "b.txt", "c.txt"), sortDirEntries(entries, FileSortKey.NAME, true).map { it.name })
        assertEquals(listOf("c.txt", "b.txt", "A.txt"), sortDirEntries(entries, FileSortKey.NAME, false).map { it.name })
        // Tarih: azalan = en yeni üstte.
        assertEquals(listOf("A.txt", "c.txt", "b.txt"), sortDirEntries(entries, FileSortKey.MTIME, false).map { it.name })
        // Boyut: artan = en küçük üstte.
        assertEquals(listOf("A.txt", "c.txt", "b.txt"), sortDirEntries(entries, FileSortKey.SIZE, true).map { it.name })
    }

    @Test
    fun sortDirEntriesTiesBreakByNameEvenDescending() {
        // Eşit birincil anahtarda (aynı mtime) ad kırılımı azalan yönde de A→Z kalır.
        val entries = listOf(
            DirEntry(name = "zeta.txt", path = "/z.txt", type = "file", size = 5, mtime = 100),
            DirEntry(name = "alfa.txt", path = "/a.txt", type = "file", size = 5, mtime = 100),
        )
        assertEquals(listOf("alfa.txt", "zeta.txt"), sortDirEntries(entries, FileSortKey.MTIME, false).map { it.name })
        assertEquals(listOf("alfa.txt", "zeta.txt"), sortDirEntries(entries, FileSortKey.SIZE, true).map { it.name })
    }

    @Test
    fun markdownDetectionSupportsMdAndMarkdownOnly() {
        assertTrue(isMarkdownFile("C:\\tez\\README.md"))
        assertTrue(isMarkdownFile("notes.markdown#baslik"))
        assertFalse(isMarkdownFile("rapor.docx"))
        assertFalse(isMarkdownFile("tablo.xlsx"))
    }

    // Kod blokları ayrı çizilir ki her birinin altına kendi kopya tuşu gelsin.
    @Test
    fun segmentsSplitFencedCodeFromProse() {
        val segments = splitMarkdownSegments("Once metin\n\n```kotlin\nval x = 1\n```\n\nSonra metin")
        assertEquals(3, segments.size)
        assertEquals("Once metin", (segments[0] as MarkdownSegment.Prose).text)
        val code = segments[1] as MarkdownSegment.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
        assertEquals("Sonra metin", (segments[2] as MarkdownSegment.Prose).text)
    }

    // Akış sürerken kapanmamış blok: tuş anında görünsün, metin sonradan sıçramasın.
    @Test
    fun segmentsTreatUnterminatedFenceAsCode() {
        val segments = splitMarkdownSegments("Basliyor\n\n```sh\ngit status")
        assertEquals(2, segments.size)
        assertEquals("git status", (segments[1] as MarkdownSegment.Code).code)
    }

    // Kod bloğu içindeki daha kısa çit satırı bloğu erken kapatmamalı.
    @Test
    fun segmentsRequireMatchingFenceToClose() {
        val segments = splitMarkdownSegments("````\n```\nic icerik\n````")
        assertEquals(1, segments.size)
        assertEquals("```\nic icerik", (segments[0] as MarkdownSegment.Code).code)
    }

    @Test
    fun segmentsKeepPlainTextAsSingleProse() {
        val segments = splitMarkdownSegments("sadece duz metin")
        assertEquals(1, segments.size)
        assertEquals("sadece duz metin", (segments[0] as MarkdownSegment.Prose).text)
    }

    @Test
    fun imageDetectionSupportsCommonFormats() {
        assertTrue(isImageFile("C:\\Fotograflar\\ekran görüntüsü.PNG"))
        assertTrue(isImageFile("belge/foto.jpeg"))
        assertTrue(isImageFile("kamera.heic"))
        assertFalse(isImageFile("rapor.docx"))
        assertFalse(isImageFile("notlar.md"))
        assertFalse(isImageFile("arsiv.zip"))
    }

    // PDF artık uygulama içinde açılıyor; ayrım harici uygulamaya düşme kararını
    // veriyor (openFileEntry), yani yanlış eşleşme "dışarı çıkıyor" demek.
    @Test
    fun pdfDetectionIsCaseInsensitiveAndExact() {
        assertTrue(isPdfFile("C:\\Dosyalar\\tensip.PDF"))
        assertTrue(isPdfFile("evrak.pdf"))
        assertFalse(isPdfFile("rapor.docx"))
        // Uzantı gibi görünen ama olmayan adlar: ".pdf" adın ortasında.
        assertFalse(isPdfFile("dilekce.pdf.bak"))
        assertFalse(isPdfFile("pdf"))
    }

    // Tür ayrımı üç ayrı yerde tekrarlanıyordu ve PDF eklenirken biri atlandı
    // (sohbetten açılan PDF dışarı düşüyordu). Artık tek fonksiyon karar veriyor.
    @Test
    fun viewerKindRoutesEachTypeToItsOwnViewer() {
        assertEquals(ViewerKind.MARKDOWN, viewerKindFor("notlar.md"))
        assertEquals(ViewerKind.DOCX, viewerKindFor("dilekce.docx"))
        assertEquals(ViewerKind.IMAGE, viewerKindFor("C:\\foto\\tarama.PNG"))
        assertEquals(ViewerKind.PDF, viewerKindFor("tensip.pdf"))
        assertEquals(ViewerKind.VIDEO, viewerKindFor("durusma-kaydi.MP4"))
        assertEquals(ViewerKind.EXTERNAL, viewerKindFor("arsiv.zip"))
    }

    // UDF artik DISARI DUSMUYOR: DOCX ile ayni blok editorune gider (06.08.2026).
    // .udfx BILEREK disarida — kabi ayni ama icerigi cozulmus degil.
    @Test
    fun udfBlokEditorundeAcilir() {
        assertEquals(ViewerKind.DOCX, viewerKindFor("evrak.udf"))
        assertEquals(ViewerKind.DOCX, viewerKindFor("C:\\dava\\DILEKCE.UDF"))
        assertEquals(ViewerKind.EXTERNAL, viewerKindFor("imzali.udfx"))
        assertTrue(isBlockEditorFile("a.docx"))
        assertTrue(isBlockEditorFile("a.udf"))
        assertFalse(isBlockEditorFile("a.pdf"))
    }

    // Canli hata (20.08.2026): UDF acikken paylas tusuna basilinca paylasim
    // ekrani "dilekce.udf" yerine "belge.docx" gosteriyordu. Eleme isDocxFile
    // ile yapiliyordu, UDF elemeyi gecemiyor ve ad yedege dusuyordu.
    @Test
    fun paylasilanBelgeKendiAdiniKorur() {
        assertEquals("dilekce.udf", sharedDocumentName("dilekce.udf"))
        assertEquals("DILEKCE.UDF", sharedDocumentName("DILEKCE.UDF"))
        assertEquals("rapor.docx", sharedDocumentName("rapor.docx"))
        // Ad okunamiyorsa yedek: paylasim ekrani adsiz kalmasin.
        assertEquals("belge.docx", sharedDocumentName(""))
        assertEquals("belge.docx", sharedDocumentName("adsiz"))
    }

    // Duz metin turleri de ayni editore gider: kopru okuma/yazma uclari tur
    // bakmiyor ve editor zaten ham metinle calisiyor, disari yollamak icin
    // teknik bir sebep yoktu.
    @Test
    fun duzMetinTurleriEditordeAcilir() {
        assertEquals(ViewerKind.MARKDOWN, viewerKindFor("notlar.txt"))
        assertEquals(ViewerKind.MARKDOWN, viewerKindFor("C:\\logs\\bridge.LOG"))
        assertEquals(ViewerKind.MARKDOWN, viewerKindFor("config.json"))
        assertEquals(ViewerKind.MARKDOWN, viewerKindFor("liste.csv"))
        assertEquals(ViewerKind.MARKDOWN, viewerKindFor("ayar.yml"))
        assertEquals(ViewerKind.MARKDOWN, viewerKindFor("script.ps1"))
        assertEquals(ViewerKind.MARKDOWN, viewerKindFor("Main.kt"))
    }

    // Ikili dosyalar editore GIRMEMELI: metin sanip acmak bozuk icerik gosterir
    // ve kaydedilirse dosyayi bozar.
    @Test
    fun ikiliVeBilinmeyenTurlerEditoreGirmez() {
        assertEquals(ViewerKind.EXTERNAL, viewerKindFor("arsiv.zip"))
        assertEquals(ViewerKind.EXTERNAL, viewerKindFor("kurulum.exe"))
        assertEquals(ViewerKind.EXTERNAL, viewerKindFor("model.safetensors"))
        assertEquals(ViewerKind.EXTERNAL, viewerKindFor("ses.mp3"))
        // Uzantisiz dosya: metin olabilir ama ikili de olabilir - riske girme.
        assertEquals(ViewerKind.EXTERNAL, viewerKindFor("LICENSE"))
        assertEquals(ViewerKind.EXTERNAL, viewerKindFor("Makefile"))
    }

    // Hareketli webp/gif BİLEREK görsel sayılıyor: tek kare de olabiliyorlar ve
    // görsel görüntüleyicinin zoom/paylaş akışını kaybetmeleri istenmiyor.
    @Test
    fun animatedImageContainersStayInTheImageViewer() {
        assertEquals(ViewerKind.IMAGE, viewerKindFor("animasyon.webp"))
        assertEquals(ViewerKind.IMAGE, viewerKindFor("animasyon.gif"))
        assertTrue(isVideoFile("klip.webm"))
        assertFalse(isVideoFile("klip.webm.bak"))
        assertFalse(isVideoFile("mp4"))
    }

    @Test
    fun chatLinkerRecognizesOfficeFiles() {
        val linked = linkFileReferences("C:\\tez\\rapor.docx\nC:\\tez\\veri.xlsx")
        assertTrue(linked, linked.contains("agfile://"))
        assertTrue(linked, linked.contains("rapor.docx"))
        assertTrue(linked, linked.contains("veri.xlsx"))
    }
    // README'de görülen kozmetik hata: kod çipi içindeki dosya adı linklenince
    // Markwon ham "[notlar.md](agfile://notlar.md)" metnini kod olarak basıyordu.
    @Test
    fun chatLinkerSkipsInlineCodeSpans() {
        val linked = linkFileReferences("Notları `notlar.md` içinde tut.")
        assertEquals("Notları `notlar.md` içinde tut.", linked)
    }

    @Test
    fun chatLinkerStillLinksOutsideInlineCode() {
        val linked = linkFileReferences("`kod.md` degil ama rapor.docx linklensin")
        assertTrue(linked, linked.contains("[rapor.docx](agfile://rapor.docx)"))
        assertTrue(linked, linked.contains("`kod.md`"))
        assertFalse(linked, linked.contains("[kod.md]"))
    }

    // Regex boşluğa izin verdiği için cümledeki önceki kelimeleri de linke katıyordu.
    @Test
    fun chatLinkerDoesNotSwallowPrecedingWords() {
        val linked = linkFileReferences("bu rapor.docx dosyasi")
        assertEquals("bu [rapor.docx](agfile://rapor.docx) dosyasi", linked)
    }

    // Boşluklu yollar (Cowork alanlarında yaygın) linklenmeye devam etmeli.
    @Test
    fun chatLinkerStillHandlesPathsWithSpaces() {
        val linked = linkFileReferences("C:\\CoworkSpaces\\ortak proje\\not defteri.txt")
        assertTrue(linked, linked.contains("[C:\\CoworkSpaces\\ortak proje\\not defteri.txt]"))
    }

    // Okuyucu modunda çıplak URL tıklanamıyordu (Linkify yok → düz metin) ve
    // dosya-linkleyici URL kuyruğunu ("com/blog/yazi.html") dosya sanıp sahte
    // agfile linki üretiyordu. URL'ler önce ayıklanır, web linki olur.
    @Test
    fun chatLinkerTurnsBareUrlsIntoWebLinks() {
        val linked = linkFileReferences("bkz https://example.com/blog/yazi.html devam")
        assertEquals("bkz [https://example.com/blog/yazi.html](https://example.com/blog/yazi.html) devam", linked)
        assertFalse(linked, linked.contains("agfile://"))
    }

    @Test
    fun chatLinkerLinksWwwWithHttpsScheme() {
        val linked = linkFileReferences("adres www.example.com/dosya.pdf burada")
        assertEquals("adres [www.example.com/dosya.pdf](https://www.example.com/dosya.pdf) burada", linked)
    }

    @Test
    fun chatLinkerKeepsTrailingPunctuationOutOfUrl() {
        val linked = linkFileReferences("bkz https://ornek.com/karar.")
        assertEquals("bkz [https://ornek.com/karar](https://ornek.com/karar).", linked)
    }

    // Web linkleri tarayıcıya gider (null); "www." eğik çizgi yüzünden dosya
    // sanılıp uygulama içi viewer'a düşüyordu.
    @Test
    fun linkResolverTreatsWebLinksAsExternal() {
        assertNull(filePathFromLink("https://ornek.com/dosya.docx"))
        assertNull(filePathFromLink("www.ornek.com/dosya.docx"))
        assertEquals("C:\\tez\\rapor.docx", filePathFromLink("agfile://C%3A%5Ctez%5Crapor.docx"))
    }

    @Test fun parentPathHandlesWindowsAndUnixPaths() {
        assertEquals("C:/work", browserParentPath("C:\\work\\app"))
        assertEquals("/home", browserParentPath("/home/user"))
        assertEquals("/", browserParentPath("/home"))
        assertNull(browserParentPath(""))
        assertNull(browserParentPath("/"))
    }

    @Test fun parentOfFirstLevelIsDriveRootNotBareDriveLetter() {
        // "C:" ters bölüsüz Windows'ta "o sürücüdeki geçerli dizin" demek:
        // köprü onu kendi çalışma klasörüne çözüyordu, yani C:\Users'ta üst
        // klasöre basınca sürücü kökü yerine bridge klasörüne düşülüyordu.
        assertEquals("C:/", browserParentPath("C:\\Users"))
        assertEquals("D:/", browserParentPath("D:/yedek"))
        // Sürücü kökünün üstü yok — oradan çıkış sürücü şeridiyle olur.
        assertNull(browserParentPath("C:\\"))
        assertNull(browserParentPath("C:/"))
    }

    @Test fun driveMatchIsSeparatorAndCaseInsensitive() {
        assertTrue(isUnderDrive("C:\\Users\\kullanici", "C:\\"))
        assertTrue(isUnderDrive("c:/users", "C:\\"))
        assertTrue(isUnderDrive("D:\\", "D:\\"))
        assertFalse(isUnderDrive("D:\\yedek", "C:\\"))
        // "C:\\yedek" ile "C:\\ye" karışmasın: sınır ayraçta.
        assertFalse(isUnderDrive("CD:\\x", "C:\\"))
        assertFalse(isUnderDrive("", "C:\\"))
    }

    @Test fun confinedParentStopsAtRootLock() {
        val root = "C:\\Users\\x\\CoworkSpaces"
        // Kökte veya kök üstünde: yukarı çıkış yok.
        assertNull(confinedParentPath("C:\\Users\\x\\CoworkSpaces", root))
        assertNull(confinedParentPath("C:/Users/x/CoworkSpaces/", root))
        assertNull(confinedParentPath("C:\\Users\\x", root))
        assertNull(confinedParentPath("", root))
        // Kökün altında: normal üst klasör.
        assertEquals("C:/Users/x/CoworkSpaces", confinedParentPath("C:\\Users\\x\\CoworkSpaces\\ortak proje", root))
        assertEquals("C:/Users/x/CoworkSpaces/ortak proje", confinedParentPath("C:\\Users\\x\\CoworkSpaces\\ortak proje\\belgeler", root))
        // Case toleransı (Windows).
        assertNull(confinedParentPath("c:\\users\\x\\coworkspaces", root))
        assertEquals("c:/users/x/coworkspaces", confinedParentPath("c:\\users\\x\\coworkspaces\\alan", root))
        // rootLock yoksa eski davranış.
        assertEquals("C:/work", confinedParentPath("C:\\work\\app", null))
        assertEquals("C:/work", confinedParentPath("C:\\work\\app", ""))
    }

    @Test fun relativeToRootShowsPathInsideLock() {
        val root = "C:\\Users\\x\\CoworkSpaces"
        assertEquals("", relativeToRoot("C:\\Users\\x\\CoworkSpaces", root))
        assertEquals("ortak proje", relativeToRoot("C:\\Users\\x\\CoworkSpaces\\ortak proje", root))
        assertEquals("ortak proje/belgeler", relativeToRoot("C:/Users/x/CoworkSpaces/ortak proje/belgeler", root))
        // Kök dışı veya kilitsiz: olduğu gibi.
        assertEquals("D:\\baska", relativeToRoot("D:\\baska", root))
        assertEquals("C:\\x", relativeToRoot("C:\\x", null))
    }

    // Çalışma klasörü tuşunun "son klasör" hafızası alan bazında: gezilen yol
    // hangi alanın altındaysa o alana yazılır. Kök ve kök dışı yollar hafıza
    // üretmez (alan listesinde gezinmek "bir alanda kalmak" değil).
    @Test fun coworkWorkspaceForFindsFirstFolderUnderRoot() {
        val root = "C:\\Users\\x\\CoworkSpaces"
        assertEquals("C:/Users/x/CoworkSpaces/ortak proje", coworkWorkspaceFor(root, "C:\\Users\\x\\CoworkSpaces\\ortak proje"))
        assertEquals("C:/Users/x/CoworkSpaces/ortak proje", coworkWorkspaceFor(root, "C:/Users/x/CoworkSpaces/ortak proje/belgeler/ekler"))
        // Case toleransı (Windows).
        assertEquals("c:/users/x/coworkspaces/alan", coworkWorkspaceFor(root, "c:\\users\\x\\coworkspaces\\alan\\alt"))
        // Kökün kendisi, kök dışı ve boşlar: alan yok.
        assertEquals("", coworkWorkspaceFor(root, root))
        assertEquals("", coworkWorkspaceFor(root, "D:\\baska\\yer"))
        assertEquals("", coworkWorkspaceFor("", "C:\\x"))
        assertEquals("", coworkWorkspaceFor(root, ""))
    }

    @Test fun coworkResumeDirFallsBackToWorkspaceWhenSavedIsForeign() {
        val alan = "C:\\Users\\x\\CoworkSpaces\\ortak proje"
        // Kayıt alanın altında: aynen döner (köprüye verilecek ham yol).
        assertEquals("C:\\Users\\x\\CoworkSpaces\\ortak proje\\belgeler", coworkResumeDir(alan, "C:\\Users\\x\\CoworkSpaces\\ortak proje\\belgeler"))
        // Ayraç/harf farkı engel değil.
        assertEquals("c:/users/x/coworkspaces/ORTAK PROJE/belgeler", coworkResumeDir(alan, "c:/users/x/coworkspaces/ORTAK PROJE/belgeler"))
        // Alanın kendisi de geçerli bir "son yer".
        assertEquals(alan, coworkResumeDir(alan, alan))
        // Kayıt yok / başka alana ait / "ortak projeX" gibi ad-öneki tuzağı:
        // alanın kökü döner.
        assertEquals(alan, coworkResumeDir(alan, null))
        assertEquals(alan, coworkResumeDir(alan, ""))
        assertEquals(alan, coworkResumeDir(alan, "C:\\Users\\x\\CoworkSpaces\\baska alan\\belgeler"))
        assertEquals(alan, coworkResumeDir(alan, "C:\\Users\\x\\CoworkSpaces\\ortak projeX\\belgeler"))
    }

    @Test fun browserResumeDirWorksForAnyBackendProjectRoot() {
        val proje = "C:\\Users\\x\\agtest"
        assertEquals(
            "C:\\Users\\x\\agtest\\android\\app",
            browserResumeDir(proje, "C:\\Users\\x\\agtest\\android\\app"),
        )
        assertEquals(proje, browserResumeDir(proje, "C:\\Users\\x\\agtest-old\\android"))
        assertEquals(proje, browserResumeDir(proje, "D:\\baska-proje"))
        assertEquals(proje, browserResumeDir(proje, null))
    }

    @Test fun byteFormatterUsesReadableUnits() {
        assertEquals("999 B", formatByteCount(999))
        assertEquals("1.0 KB", formatByteCount(1024))
        assertEquals("1.0 MB", formatByteCount(1024L * 1024))
    }
}
