// SVG'yi macOS iconset PNG'lerine çevirir: swift ikon.swift <svg-dosyası> <iconset-dizini>
// rsvg-convert ya da ImageMagick olmadan, NSImage'ın SVG desteği ve AppKit çizimiyle çalışır.
import Cocoa

let girdiler = CommandLine.arguments

func dur(_ mesaj: String) -> Never {
    FileHandle.standardError.write(Data(("ikon: " + mesaj + "\n").utf8))
    exit(1)
}

if girdiler.count < 3 { dur("kullanım: swift ikon.swift <svg> <iconset-dizini>") }

let svgYolu = girdiler[1]
let iconset = girdiler[2]

if !FileManager.default.fileExists(atPath: svgYolu) { dur("svg bulunamadı: \(svgYolu)") }

if let kaynak = NSImage(contentsOfFile: svgYolu) {
    try? FileManager.default.createDirectory(atPath: iconset, withIntermediateDirectories: true)

    for taban in [16, 32, 128, 256, 512] {
        for olcek in [1, 2] {
            let px = taban * olcek
            if let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px,
                                          bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                          colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0) {
                rep.size = NSSize(width: taban, height: taban)  // çizim nokta ölçüsünde, piksel ölçüsü onun katı
                if let baglam = NSGraphicsContext(bitmapImageRep: rep) {
                    NSGraphicsContext.saveGraphicsState()
                    NSGraphicsContext.current = baglam
                    baglam.imageInterpolation = .high
                    // macOS simge ızgarası: gövde tuvalin ~%80'i (1024'te 824), kenarda boşluk kalır.
                    let govde = CGFloat(taban) * 824 / 1024, kenar = (CGFloat(taban) - govde) / 2
                    kaynak.draw(in: NSRect(x: kenar, y: kenar, width: govde, height: govde),
                                from: .zero, operation: .copy, fraction: 1)
                    baglam.flushGraphics()
                    NSGraphicsContext.restoreGraphicsState()
                }
                if let png = rep.representation(using: .png, properties: [:]) {
                    let ad = olcek == 1 ? "icon_\(taban)x\(taban).png" : "icon_\(taban)x\(taban)@2x.png"
                    let yol = (iconset as NSString).appendingPathComponent(ad)
                    do { try png.write(to: URL(fileURLWithPath: yol)) }
                    catch { dur("png yazılamadı: \(yol) (\(error.localizedDescription))") }
                } else { dur("png üretilemedi: \(taban)@\(olcek)x") }
            } else { dur("bitmap oluşturulamadı: \(taban)@\(olcek)x") }
        }
    }
    print("iconset hazır: \(iconset)")
} else {
    dur("svg okunamadı: \(svgYolu)")
}
