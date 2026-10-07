"""PDF metin katmani cikarici — okuma modunun ham girdisi.

Bilerek APTAL tutulmustur: temizlik, baslik tespiti ve markdown kurulumu
Node tarafinda (pdf-read.mjs) yapilir. Sebebi test: koprunun test cercevesi
`node --test`, ve o mantigin birim testine ancak oradan girilebilir. Bu betik
yalniz PyMuPDF'in gordugunu JSON'a cevirir.

Kullanim:  python pdf-read.py <dosya>
Cikti:     stdout'a TEK satir JSON, her zaman {"ok": bool, ...}
           Hata durumunda da 0 ile cikar; cagiran `ok` alanina bakar. Boylece
           "python patladi" ile "PDF taranmis" birbirine karismaz.

TUZAK — stdout kirliligi: PyMuPDF, find_tables() ilk cagrildiginda
"Consider using the pymupdf_layout package..." satirini STDOUT'a basiyor
(stderr'e degil; 07.08.2026'da olculdu). Temizlenmezse JSON.parse patlar.
Bu yuzden butun cikarim boyunca stdout stderr'e yonlendirilir ve JSON en
sonda gercek stdout'a yazilir.
"""

import contextlib
import io
import json
import os
import sys
import time

# Girdi tavani BILEREK cok yuksek: taranmis ders kitaplari 250 MB'i rahat
# asiyor (Themis serisi) ve dusuk bir tavan onlari "cok buyuk" diye reddedip
# asil cevabi — "bu belge taranmis" — gizliyordu. PyMuPDF dosyayi tembel
# aciyor, boyut tek basina maliyet degil. Asil koruma sayfa tavani ve
# taranmis yoklamasi.
MAX_BYTES = 2 * 1024 * 1024 * 1024
# Sayfa tavani: 500+ sayfalik ders kitaplarinda JSON'un boyu kontrolden cikar.
# Asilirsa kirpilir ve `truncated` ile bildirilir — sessiz kesme YOK.
MAX_PAGES = 400
# Taranmis yoklamasi: belge boyunca bu kadar sayfa ornekle. Hepsi de bu
# esigin altinda karakter tasiyorsa metin katmani yok demektir; okuma modu
# uygulanamaz ve butun belgeyi taramaya gerek kalmadan doneriz.
PROBE_PAGES = 8
PROBE_MIN_CHARS = 30
# PyMuPDF span flag bit'i: 2^4 = kalin. Font adindaki "Bold" ile birlikte
# kullanilir, cunku bazi gomulu fontlar bayragi kurmadan kalin cizilir.
BOLD_FLAG = 1 << 4

# Tablo tespiti icin toplam zaman butcesi (saniye). find_tables, sayfasi
# dekoratif cerceveli ders kitaplarinda pahali: 194 sayfalik bir kitapta 34,5
# sn ve karsiliginda yalniz 32 tablo (07.08.2026 olcumu). Cizim sayisina gore
# on eleme DENENDI ve ise yaramadi — o kitapta 194 sayfanin 185'i "cizimli".
# Butce asilinca tablo tespiti durur, metin cikarimi devam eder; kesme
# `tablesTruncated`/`tablesUpToPage` ile BILDIRILIR, sessizce yutulmaz.
# Kisa resmi evrakta (13 sayfalik e-yoklama: 1,2 sn) butce hic devreye girmez.
TABLE_BUDGET_SEC = 10.0

# Tablo stratejisi. Varsayilan "lines" ders kitaplarinda sayfa basligindaki
# susleme kutusunu tablo saniyor (20 sayfada 20 sahte tablo, 07.08.2026
# olcumu). "lines_strict" bunlari eliyor ve gercek resmi form tablolarini
# (10x11, 15x6) aynen koruyor. Doluluk oranina gore elemek YANLIS olurdu:
# gercek ama bos birakilmis resmi form tablosu %13 dolu olabiliyor.
TABLE_STRATEGY = "lines_strict"


def emit(payload):
    """JSON'u GERCEK stdout'a yazar (redirect'in disinda cagrilmali).

    TUZAK — konsol kod sayfasi: Windows'ta stdout varsayilan olarak cp1254'tur
    ve Turkce icerikli JSON yazilirken UnicodeEncodeError firlatir; cikti bos
    kalir, cagiran "bos yanit" gorur. PYTHONIOENCODING'i cagirana birakmak
    yeterli degil — betik kendi ciktisini garanti eder.
    """
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.stdout.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    sys.stdout.flush()


def fail(reason):
    emit({"ok": False, "reason": reason})
    sys.exit(0)


def text_flags(fitz):
    """get_text("dict") icin gorsel cikarimini KAPATAN bayrak kumesi.

    Varsayilan TEXTFLAGS_DICT, gomulu gorselleri de ikili veriyle birlikte
    cozuyor. Bu modul gorsel bloklarini zaten atiyor, yani is tamamen bosa
    gidiyordu — ve bedeli buyuk: gorsel agirlikli bir ders kitabinda 5 sayfa
    15,63 sn suruyordu, bayrak kapaninca 0,06 sn (aynen ayni 20 metin satiri,
    07.08.2026 olcumu). 194 sayfalik kitapta 318 sn → 6 sn.
    """
    return fitz.TEXTFLAGS_DICT & ~fitz.TEXT_PRESERVE_IMAGES


def looks_scanned(doc, fitz):
    """Belge boyunca ornekleyip metin katmani olup olmadigina bakar.

    Tek tek sayfalar bos olabilir (ara kapak, tam sayfa sema); karar
    ORNEKLEMIN TAMAMI bos oldugunda verilir.
    """
    total = doc.page_count
    if total == 0:
        return True
    step = max(1, total // PROBE_PAGES)
    for i in range(0, total, step):
        try:
            if len(doc[i].get_text("text").strip()) >= PROBE_MIN_CHARS:
                return False
        except Exception:
            continue
    return True


def page_payload(page, flags, detect_tables):
    """Tek sayfayi {lines, tables} sozlugune cevirir.

    Satirlar okuma sirasinda gelir (PyMuPDF blok sirasi). Tablo hucreleri
    ayrica satir olarak da gorunur; Node tarafi bunlari tablo kutusuna gore
    ayiklar — burada ayiklamak, tablo tespiti yanlissa metni yok etmek olurdu.
    """
    lines = []
    data = page.get_text("dict", flags=flags)
    for block in data.get("blocks", []):
        if block.get("type") != 0:  # 0 = metin, 1 = gorsel
            continue
        for line in block.get("lines", []):
            spans = line.get("spans", [])
            text = "".join(s.get("text", "") for s in spans)
            if not text.strip():
                continue
            # Satirin boyutu/kalinligi: en uzun span belirler. Satir basindaki
            # tek harflik sus karakteri butun satiri "baslik" yapmasin diye.
            lead = max(spans, key=lambda s: len(s.get("text", "")), default=None) or {}
            font = str(lead.get("font", ""))
            bold = bool(int(lead.get("flags", 0)) & BOLD_FLAG) or "bold" in font.lower()
            x0, y0, x1, y1 = line.get("bbox", (0, 0, 0, 0))
            lines.append({
                "t": text,
                "b": bold,
                "s": round(float(lead.get("size", 0.0)), 1),
                "x0": round(x0, 1), "y0": round(y0, 1),
                "x1": round(x1, 1), "y1": round(y1, 1),
            })

    tables = []
    if not detect_tables:
        found = []
    else:
        try:
            found = page.find_tables(strategy=TABLE_STRATEGY).tables
        except Exception:
            found = []
    for tab in found:
        try:
            rows = [[("" if cell is None else str(cell)) for cell in row] for row in tab.extract()]
        except Exception:
            continue
        if not rows:
            continue
        x0, y0, x1, y1 = tab.bbox
        tables.append({
            "rows": rows,
            "x0": round(x0, 1), "y0": round(y0, 1),
            "x1": round(x1, 1), "y1": round(y1, 1),
        })

    rect = page.rect
    return {
        "w": round(rect.width, 1),
        "h": round(rect.height, 1),
        "lines": lines,
        "tables": tables,
    }


def main():
    args = sys.argv[1:]
    if not args:
        fail("dosya verilmedi")
    path = args[0]
    if not os.path.isfile(path):
        fail("dosya yok")
    try:
        size = os.path.getsize(path)
    except OSError as exc:
        fail("dosya okunamadi: %s" % exc)
    if size > MAX_BYTES:
        fail("dosya cok buyuk")

    try:
        import fitz  # PyMuPDF
    except Exception:
        fail("pymupdf yok")

    # Cikarim boyunca stdout kilitli — bkz. modul basindaki TUZAK notu.
    real_stdout = sys.stdout
    sink = io.StringIO()
    try:
        with contextlib.redirect_stdout(sink):
            try:
                doc = fitz.open(path)
            except Exception as exc:
                sys.stdout = real_stdout
                fail("acilamadi: %s" % exc)
            if doc.needs_pass:
                sys.stdout = real_stdout
                fail("parola korumali")
            if looks_scanned(doc, fitz):
                sys.stdout = real_stdout
                fail("taranmis")
            total = doc.page_count
            limit = min(total, MAX_PAGES)
            flags = text_flags(fitz)
            pages = []
            table_spent = 0.0
            tables_up_to = limit
            for i in range(limit):
                detect = table_spent < TABLE_BUDGET_SEC
                started = time.monotonic()
                pages.append(page_payload(doc[i], flags, detect))
                if detect:
                    table_spent += time.monotonic() - started
                    if table_spent >= TABLE_BUDGET_SEC:
                        tables_up_to = i + 1
            doc.close()
    finally:
        sys.stdout = real_stdout

    emit({
        "ok": True,
        "pageCount": total,
        "truncated": total > limit,
        "tablesTruncated": tables_up_to < limit,
        "tablesUpToPage": tables_up_to,
        "pages": pages,
    })


if __name__ == "__main__":
    main()
