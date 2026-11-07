// AgentBridge.app — köprü arayüzünü Brave --app sekmesi yerine kendi penceresinde açan yerli sarmalayıcı
// (AppKit + WKWebView). Adres (token dahil) derlemede Info.plist'e ABAdres olarak gömülür. Derleme: kur.sh
import Cocoa
import WebKit

let adres: URL = {
    if let s = Bundle.main.object(forInfoDictionaryKey: "ABAdres") as? String, let u = URL(string: s) { return u }
    return URL(string: "http://127.0.0.1:8787/ui/")!
}()

final class Uygulama: NSObject, NSApplicationDelegate, WKUIDelegate, WKNavigationDelegate, WKDownloadDelegate {
    var pencere: NSWindow!
    var web: WKWebView!
    var baslikGozcu: NSKeyValueObservation?

    // ---------- yaşam döngüsü ----------
    func applicationDidFinishLaunching(_ n: Notification) {
        menuKur()
        let ayar = WKWebViewConfiguration()
        ayar.preferences.setValue(true, forKey: "developerExtrasEnabled")  // sağ tık → Öğeyi İncele
        web = WKWebView(frame: .zero, configuration: ayar)
        web.uiDelegate = self
        web.navigationDelegate = self
        web.allowsMagnification = true
        web.allowsBackForwardNavigationGestures = false

        pencere = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1320, height: 900),
                           styleMask: [.titled, .closable, .miniaturizable, .resizable],
                           backing: .buffered, defer: false)
        pencere.title = "AgentBridge"
        pencere.minSize = NSSize(width: 420, height: 480)
        pencere.contentView = web
        pencere.center()
        pencere.setFrameAutosaveName("agentbridge-ana")
        pencere.isReleasedWhenClosed = false
        pencere.tabbingMode = .disallowed
        pencere.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)

        baslikGozcu = web.observe(\.title) { [weak self] w, _ in
            let t = w.title ?? ""
            self?.pencere.title = t.isEmpty ? "AgentBridge" : t
        }
        yukle()
    }

    func applicationShouldHandleReopen(_ s: NSApplication, hasVisibleWindows g: Bool) -> Bool {
        pencere.makeKeyAndOrderFront(nil)  // Dock ikonuna tıklayınca kapatılmış pencereyi geri getir
        return true
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ s: NSApplication) -> Bool { false }

    func yukle() { web.load(URLRequest(url: adres)) }

    // ---------- köprüye ulaşılamazsa ----------
    var yenidenDeneme: DispatchWorkItem?

    func baglantiYok(_ e: Error) {
        let hata = (e as NSError).localizedDescription
            .replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
        let sayfa = """
        <!doctype html><meta charset="utf-8"><style>
        :root{color-scheme:light dark}body{font:15px -apple-system,system-ui;display:grid;place-items:center;
        height:100vh;margin:0;background:Canvas;color:CanvasText}div{text-align:center;max-width:460px;opacity:.85}
        small{opacity:.6}button{margin-top:14px;font:inherit;padding:6px 16px}</style>
        <div><h3>Köprüye ulaşılamıyor</h3><p>\(adres.host ?? "")</p><small>\(hata)</small><br>
        <small>5 sn'de bir yeniden deneniyor…</small><br><button onclick="location.href='\(adres.absoluteString)'">Şimdi dene</button></div>
        """
        web.loadHTMLString(sayfa, baseURL: nil)
        yenidenDeneme?.cancel()
        let is_ = DispatchWorkItem { [weak self] in self?.yukle() }
        yenidenDeneme = is_
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: is_)
    }

    func webView(_ w: WKWebView, didFailProvisionalNavigation n: WKNavigation!, withError e: Error) {
        if (e as NSError).code == NSURLErrorCancelled { return }
        baglantiYok(e)
    }

    func webView(_ w: WKWebView, didCommit n: WKNavigation!) {
        if w.url?.host == adres.host { yenidenDeneme?.cancel() }
    }

    // Web içeriği süreci çökerse (uzun uykudan sonra olabiliyor) boş pencerede kalma
    func webViewWebContentProcessDidTerminate(_ w: WKWebView) { yukle() }

    // ---------- web görünümü ----------
    // <input type="file"> için dosya seçici
    func webView(_ w: WKWebView, runOpenPanelWith p: WKOpenPanelParameters, initiatedByFrame f: WKFrameInfo,
                 completionHandler bitti: @escaping ([URL]?) -> Void) {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = p.allowsMultipleSelection
        panel.canChooseDirectories = false
        panel.beginSheetModal(for: pencere) { bitti($0 == .OK ? panel.urls : nil) }
    }

    func webView(_ w: WKWebView, runJavaScriptAlertPanelWithMessage m: String, initiatedByFrame f: WKFrameInfo,
                 completionHandler bitti: @escaping () -> Void) {
        let u = NSAlert(); u.messageText = m; u.runModal(); bitti()
    }

    func webView(_ w: WKWebView, runJavaScriptConfirmPanelWithMessage m: String, initiatedByFrame f: WKFrameInfo,
                 completionHandler bitti: @escaping (Bool) -> Void) {
        let u = NSAlert(); u.messageText = m
        u.addButton(withTitle: "Evet"); u.addButton(withTitle: "Vazgeç")
        bitti(u.runModal() == .alertFirstButtonReturn)
    }

    // target="_blank" / window.open: yeni pencere açma, varsayılan tarayıcıya ver
    func webView(_ w: WKWebView, createWebViewWith c: WKWebViewConfiguration, for a: WKNavigationAction,
                 windowFeatures f: WKWindowFeatures) -> WKWebView? {
        if let u = a.request.url, u.scheme != "about" { NSWorkspace.shared.open(u) }
        return nil
    }

    // "indir" bağlantıları indirme olsun; köprü dışı bağlantılar varsayılan tarayıcıya gitsin
    func webView(_ w: WKWebView, decidePolicyFor a: WKNavigationAction, decisionHandler karar: @escaping (WKNavigationActionPolicy) -> Void) {
        if a.shouldPerformDownload { return karar(.download) }
        if let u = a.request.url, let h = u.host, h != adres.host, a.navigationType == .linkActivated {
            NSWorkspace.shared.open(u)
            return karar(.cancel)
        }
        karar(.allow)
    }

    func webView(_ w: WKWebView, decidePolicyFor r: WKNavigationResponse, decisionHandler karar: @escaping (WKNavigationResponsePolicy) -> Void) {
        karar(r.canShowMIMEType ? .allow : .download)
    }

    func webView(_ w: WKWebView, navigationAction a: WKNavigationAction, didBecome d: WKDownload) { d.delegate = self }
    func webView(_ w: WKWebView, navigationResponse r: WKNavigationResponse, didBecome d: WKDownload) { d.delegate = self }

    var sonIndirilen: URL?
    func download(_ d: WKDownload, decideDestinationUsing r: URLResponse, suggestedFilename ad: String,
                  completionHandler bitti: @escaping (URL?) -> Void) {
        let klasor = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        var hedef = klasor.appendingPathComponent(ad)
        var i = 2
        while FileManager.default.fileExists(atPath: hedef.path) {
            let u = (ad as NSString).pathExtension, g = (ad as NSString).deletingPathExtension
            hedef = klasor.appendingPathComponent("\(g) \(i)" + (u.isEmpty ? "" : ".\(u)"))
            i += 1
        }
        sonIndirilen = hedef
        bitti(hedef)
    }

    func downloadDidFinish(_ d: WKDownload) {
        if let u = sonIndirilen { NSWorkspace.shared.activateFileViewerSelecting([u]) }
    }

    func download(_ d: WKDownload, didFailWithError e: Error, resumeData: Data?) {
        let u = NSAlert(); u.alertStyle = .warning; u.messageText = "İndirilemedi"
        u.informativeText = e.localizedDescription; u.runModal()
    }

    // ---------- menü ----------
    @objc func yenile() { web.reload() }
    @objc func tarayicidaAc() { NSWorkspace.shared.open(adres) }
    @objc func buyut() { web.magnification = min(web.magnification + 0.1, 3) }
    @objc func kucult() { web.magnification = max(web.magnification - 0.1, 0.5) }
    @objc func gercekBoyut() { web.magnification = 1 }

    func menuKur() {
        let ana = NSMenu()
        func alt(_ baslik: String, _ ogeler: [NSMenuItem]) {
            let m = NSMenu(title: baslik); ogeler.forEach { m.addItem($0) }
            let kok = NSMenuItem(); kok.submenu = m; ana.addItem(kok)
        }
        func oge(_ ad: String, _ s: Selector?, _ tus: String = "", hedef: AnyObject? = nil) -> NSMenuItem {
            let o = NSMenuItem(title: ad, action: s, keyEquivalent: tus); o.target = hedef; return o
        }
        alt("AgentBridge", [
            oge("AgentBridge Hakkında", #selector(NSApplication.orderFrontStandardAboutPanel(_:))),
            .separator(),
            oge("AgentBridge'i Gizle", #selector(NSApplication.hide(_:)), "h"),
            .separator(),
            oge("AgentBridge'den Çık", #selector(NSApplication.terminate(_:)), "q")])
        alt("Dosya", [
            oge("Tarayıcıda Aç", #selector(tarayicidaAc), "", hedef: self),
            .separator(),
            oge("Pencereyi Kapat", #selector(NSWindow.performClose(_:)), "w")])
        alt("Düzen", [  // bu menü olmadan ⌘C/⌘V web görünümünde çalışmaz
            oge("Geri Al", Selector(("undo:")), "z"),
            oge("Yinele", Selector(("redo:")), "Z"),
            .separator(),
            oge("Kes", #selector(NSText.cut(_:)), "x"),
            oge("Kopyala", #selector(NSText.copy(_:)), "c"),
            oge("Yapıştır", #selector(NSText.paste(_:)), "v"),
            oge("Tümünü Seç", #selector(NSText.selectAll(_:)), "a")])
        alt("Görünüm", [
            oge("Yenile", #selector(yenile), "r", hedef: self),
            .separator(),
            oge("Yakınlaştır", #selector(buyut), "+", hedef: self),
            oge("Uzaklaştır", #selector(kucult), "-", hedef: self),
            oge("Gerçek Boyut", #selector(gercekBoyut), "0", hedef: self),
            .separator(),
            { let o = oge("Tam Ekran", #selector(NSWindow.toggleFullScreen(_:)), "f"); o.keyEquivalentModifierMask = [.command, .control]; return o }()])
        alt("Pencere", [
            oge("Küçült", #selector(NSWindow.performMiniaturize(_:)), "m"),
            oge("Büyüt/Küçült", #selector(NSWindow.performZoom(_:)))])
        NSApp.mainMenu = ana
    }
}

let uygulama = NSApplication.shared
let temsilci = Uygulama()
uygulama.delegate = temsilci
uygulama.setActivationPolicy(.regular)
uygulama.run()
