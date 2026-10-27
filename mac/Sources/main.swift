// FramePlay para Mac: abre o FramePlay (site) numa janela própria e toca os vídeos no player
// embutido com VLC (PlayerWindow.swift) — como o app Android.
import Cocoa
import WebKit
import VLCKit

let APP_URL = URL(string: "https://gabrielxyz404.github.io/GABIFLIX/")!
let APP_HOST = "gabrielxyz404.github.io"

// O site chama window.GabiFlixNative.playSession(...) (o mesmo nome usado no Android).
let BRIDGE_JS = """
(function () {
  if (window.GabiFlixNative) return;
  window.GabiFlixNative = {
    playSession: function (url, subs, type, id, start) {
      window.webkit.messageHandlers.gabiflix.postMessage({
        url: String(url || ''), subs: String(subs || '[]'), type: String(type || ''),
        id: String(id || ''), start: String(start || '0')
      });
    },
    version: function () { return 4; }
  };
})();
"""

final class AppDelegate: NSObject, NSApplicationDelegate, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate, NSWindowDelegate {
    var window: NSWindow!
    var web: WKWebView!
    var player: PlayerWindowController?
    var lastOpen = Date.distantPast
    var checkingUpdate = false
    var leftoverTried = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.appearance = NSAppearance(named: .darkAqua)
        buildMenu()

        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default() // guarda o login e as configurações
        config.applicationNameForUserAgent = "Version/17.0 Safari/605.1.15 GabiFlixApp/mac"
        config.mediaTypesRequiringUserActionForPlayback = []
        let content = WKUserContentController()
        content.addUserScript(WKUserScript(source: BRIDGE_JS, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        content.add(self, name: "gabiflix")
        config.userContentController = content
        config.preferences.setValue(true, forKey: "developerExtrasEnabled")

        web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = self
        web.uiDelegate = self
        web.allowsBackForwardNavigationGestures = true
        web.setValue(false, forKey: "drawsBackground")

        let screen = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let size = NSSize(width: min(1440, screen.width * 0.9), height: min(920, screen.height * 0.9))
        window = NSWindow(contentRect: NSRect(origin: .zero, size: size),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                          backing: .buffered, defer: false)
        window.title = "FramePlay"
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.backgroundColor = .black
        window.minSize = NSSize(width: 420, height: 600)
        window.contentView = web
        window.delegate = self
        window.setFrameAutosaveName("GabiFlixMain")
        if !window.setFrameUsingName("GabiFlixMain") { window.center() }
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)

        web.load(URLRequest(url: APP_URL))

        // procura versão nova alguns segundos depois de abrir (não no teste automático)
        if !CommandLine.arguments.contains("--gf-test-play") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in self?.checkForUpdates(silent: true) }
        }

        // teste automático (só no GitHub): --gf-test-play <link do vídeo> <código IMDb>
        let args = CommandLine.arguments
        if let i = args.firstIndex(of: "--gf-test-play"), i + 2 < args.count, let u = URL(string: args[i + 1]) {
            let id = args[i + 2]
            PlayerWindowController.testLog = true
            VLCLibrary.shared().debugLogging = true
            VLCLibrary.shared().debugLoggingLevel = 3
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) {
                let p = PlayerWindowController(url: u, subtitles: [], type: "movie", id: id, startMs: 0)
                self.player = p
                p.showWindow(nil)
            }
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func applicationWillTerminate(_ notification: Notification) {
        player?.close() // entrega o progresso antes de sair
    }

    // ---------- menu (Copiar/Colar no login, Atualizar, Tela cheia, Sair) ----------
    func buildMenu() {
        let main = NSMenu()

        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "Sobre o FramePlay", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        appMenu.addItem(withTitle: "Procurar atualizações…", action: #selector(checkUpdatesMenu), keyEquivalent: "")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Ocultar FramePlay", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Sair do FramePlay", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        main.addItem(appItem)

        let editItem = NSMenuItem()
        let edit = NSMenu(title: "Editar")
        edit.addItem(withTitle: "Desfazer", action: Selector(("undo:")), keyEquivalent: "z")
        edit.addItem(withTitle: "Refazer", action: Selector(("redo:")), keyEquivalent: "Z")
        edit.addItem(.separator())
        edit.addItem(withTitle: "Recortar", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "Copiar", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Colar", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "Selecionar Tudo", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = edit
        main.addItem(editItem)

        let viewItem = NSMenuItem()
        let view = NSMenu(title: "Visualizar")
        view.addItem(withTitle: "Atualizar", action: #selector(reloadSite), keyEquivalent: "r")
        view.addItem(withTitle: "Voltar", action: #selector(goBack), keyEquivalent: "[")
        view.addItem(.separator())
        let fs = NSMenuItem(title: "Tela Cheia", action: #selector(NSWindow.toggleFullScreen(_:)), keyEquivalent: "f")
        fs.keyEquivalentModifierMask = [.command, .control]
        view.addItem(fs)
        viewItem.submenu = view
        main.addItem(viewItem)

        let winItem = NSMenuItem()
        let win = NSMenu(title: "Janela")
        win.addItem(withTitle: "Minimizar", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        win.addItem(withTitle: "Fechar", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        winItem.submenu = win
        main.addItem(winItem)
        NSApp.windowsMenu = win

        NSApp.mainMenu = main
    }

    @objc func reloadSite() {
        // mesmo efeito do "Atualizar" do menu do site: busca a versão nova
        web.evaluateJavaScript("window.gfUpdateApp ? (window.gfUpdateApp(), true) : false") { result, _ in
            if (result as? Bool) != true { self.web.reloadFromOrigin() }
        }
    }

    @objc func goBack() { if web.canGoBack { web.goBack() } }

    // ---------- ponte do site ----------
    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "gabiflix", let body = message.body as? [String: Any],
              let link = body["url"] as? String, link.hasPrefix("http://") || link.hasPrefix("https://"),
              let url = URL(string: link) else { return }
        if Date().timeIntervalSince(lastOpen) < 3 { return } // cliques repetidos
        lastOpen = Date()
        let type = body["type"] as? String ?? ""
        let id = body["id"] as? String ?? ""
        let start = Int64(body["start"] as? String ?? "0") ?? 0
        var subs: [URL] = []
        if let s = body["subs"] as? String, let data = s.data(using: .utf8),
           let arr = try? JSONSerialization.jsonObject(with: data) as? [String] {
            subs = arr.compactMap { URL(string: $0) }.filter { $0.scheme == "http" || $0.scheme == "https" }
        }
        player?.close()
        let p = PlayerWindowController(url: url, subtitles: subs, type: type, id: id, startMs: start)
        p.onProgress = { time, length in AppDelegate.saveLast(time: time, length: length, ended: false, url: link) }
        p.onFinish = { [weak self] time, length, ended in self?.deliverProgress(time: time, length: length, ended: ended, url: link) }
        player = p
        p.showWindow(nil)
        p.window?.makeKeyAndOrderFront(nil)
    }

    /** devolve ao site onde o vídeo parou (o site salva no Continuar assistindo do Stremio) */
    func deliverProgress(time: Int64, length: Int64, ended: Bool, url: String) {
        player = nil
        guard time > 0 else { return }
        // guarda antes de entregar: se o app fechar antes do site receber, entrega na próxima abertura
        AppDelegate.saveLast(time: time, length: length, ended: ended, url: url)
        sendToSite(time: time, length: length, ended: ended, url: url)
        window.makeKeyAndOrderFront(nil)
    }

    private func sendToSite(time: Int64, length: Int64, ended: Bool, url: String) {
        let quoted = (try? String(data: JSONSerialization.data(withJSONObject: [url]), encoding: .utf8)) ?? "[\"\"]"
        let js = "(function(){var u=\(quoted)[0];function go(n){if(window.gfNativeProgress){window.gfNativeProgress(\(time),\(length),\(ended ? "true" : "false"),u);}else if(n<40){setTimeout(function(){go(n+1)},500);}}if(window.gfNativeProgress){go(0);return true;}go(0);return false;})()"
        web.evaluateJavaScript(js) { result, error in
            // entregue na hora: não precisa guardar mais
            if error == nil, (result as? Bool) == true { AppDelegate.clearLast() }
        }
    }

    // última sessão do player (como o "gf-last-session" do Android)
    static let lastKey = "gf-last-session"
    static func saveLast(time: Int64, length: Int64, ended: Bool, url: String) {
        guard time > 0 else { return }
        let d: [String: Any] = ["time": NSNumber(value: time), "length": NSNumber(value: length), "ended": ended,
                                "url": url, "at": Date().timeIntervalSince1970]
        UserDefaults.standard.set(d, forKey: lastKey)
    }
    static func clearLast() { UserDefaults.standard.removeObject(forKey: lastKey) }

    /** progresso que sobrou de uma sessão anterior (o app fechou antes de entregar) */
    func deliverLeftover() {
        guard player == nil, let d = UserDefaults.standard.dictionary(forKey: AppDelegate.lastKey),
              let t = (d["time"] as? NSNumber)?.int64Value, t > 0,
              let url = d["url"] as? String,
              let at = d["at"] as? Double, Date().timeIntervalSince1970 - at < 12 * 3600 else {
            AppDelegate.clearLast(); return
        }
        let len = (d["length"] as? NSNumber)?.int64Value ?? 0
        let ended = d["ended"] as? Bool ?? false
        sendToSite(time: t, length: len, ended: ended, url: url)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if !leftoverTried {
            leftoverTried = true
            deliverLeftover()
        }
    }

    // ---------- atualizações ----------
    static let releaseAPI = URL(string: "https://api.github.com/repos/gabrielxyz404/GABIFLIX/releases/tags/mac")!

    @objc func checkUpdatesMenu() { checkForUpdates(silent: false) }

    func checkForUpdates(silent: Bool) {
        if checkingUpdate { return }
        checkingUpdate = true
        var req = URLRequest(url: AppDelegate.releaseAPI, timeoutInterval: 15)
        req.setValue("FramePlay", forHTTPHeaderField: "User-Agent")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        URLSession.shared.dataTask(with: req) { [weak self] data, resp, _ in
            var latest = -1
            var zip: URL?
            if let h = resp as? HTTPURLResponse, h.statusCode < 400, let data = data,
               let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                let text = "\(json["name"] as? String ?? "") \(json["body"] as? String ?? "")"
                if let re = try? NSRegularExpression(pattern: "1\\.0\\.(\\d+)"),
                   let m = re.firstMatch(in: text, range: NSRange(location: 0, length: (text as NSString).length)),
                   m.numberOfRanges > 1 {
                    latest = Int((text as NSString).substring(with: m.range(at: 1))) ?? -1
                }
                if let assets = json["assets"] as? [[String: Any]] {
                    for a in assets {
                        if let n = a["name"] as? String, n.lowercased().hasSuffix(".zip"), (zip == nil || n.lowercased().contains("frameplay")),
                           let u = a["browser_download_url"] as? String, let url = URL(string: u) { zip = url }
                    }
                }
            }
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.checkingUpdate = false
                self.showUpdateResult(latest: latest, zip: zip, silent: silent)
            }
        }.resume()
    }

    private func showUpdateResult(latest: Int, zip: URL?, silent: Bool) {
        let mine = Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0
        if latest < 0 || zip == nil {
            if !silent {
                let a = NSAlert()
                a.messageText = "Não foi possível procurar atualizações agora"
                a.informativeText = "Verifique a internet e tente de novo."
                a.addButton(withTitle: "OK")
                a.runModal()
            }
            return
        }
        if latest > mine, let zip = zip {
            let a = NSAlert()
            a.messageText = "Nova versão do FramePlay"
            a.informativeText = "A versão 1.0.\(latest) está disponível (a sua é a 1.0.\(mine)). Baixe, abra o .zip e substitua o FramePlay na pasta Aplicativos."
            a.addButton(withTitle: "Baixar")
            a.addButton(withTitle: "Agora não")
            if a.runModal() == .alertFirstButtonReturn { NSWorkspace.shared.open(zip) }
        } else if !silent {
            let a = NSAlert()
            a.messageText = "Já tens a versão mais recente"
            a.informativeText = "O FramePlay está atualizado (versão 1.0.\(mine))."
            a.addButton(withTitle: "OK")
            a.runModal()
        }
    }

    // ---------- links ----------
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url, let scheme = url.scheme?.lowercased() else { decisionHandler(.allow); return }
        if scheme == "about" || scheme == "blob" || scheme == "data" { decisionHandler(.allow); return }
        if (scheme == "http" || scheme == "https") && (url.host == APP_HOST || action.targetFrame?.isMainFrame == false) {
            decisionHandler(.allow); return
        }
        // qualquer outro link (YouTube, sites) abre no navegador do Mac
        if action.targetFrame?.isMainFrame == true || action.targetFrame == nil {
            NSWorkspace.shared.open(url)
            decisionHandler(.cancel); return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url { NSWorkspace.shared.open(url) }
        return nil
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let a = NSAlert()
        a.messageText = message
        a.runModal()
        completionHandler()
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let a = NSAlert()
        a.messageText = message
        a.addButton(withTitle: "OK")
        a.addButton(withTitle: "Cancelar")
        completionHandler(a.runModal() == .alertFirstButtonReturn)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { webView.reload() }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
