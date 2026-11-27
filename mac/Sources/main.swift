// FramePlay para Mac: abre o FramePlay (site) numa janela própria e toca os vídeos no player
// embutido com VLC (PlayerWindow.swift) — como o app Android.
import Cocoa
import WebKit
import VLCKit

// teste automático (só no GitHub): --gf-test-url <endereço> carrega o site da branch em vez do site publicado
let TEST_URL: URL? = { let a = CommandLine.arguments; if let i = a.firstIndex(of: "--gf-test-url"), i + 1 < a.count { return URL(string: a[i + 1]) }; return nil }()
let SITE_NOVO = URL(string: "https://gabrielxyz404.github.io/FramePlay/")!
let SITE_ANTIGO = URL(string: "https://gabrielxyz404.github.io/GABIFLIX/")!
let APP_URL = TEST_URL ?? SITE_ANTIGO
/// O site mora no GitHub Pages, que NÃO redireciona quando o repositório muda de nome: testa os dois endereços ao mesmo tempo
/// (o novo tem preferência). A origem é a mesma (gabrielxyz404.github.io), então o login e os dados guardados continuam.
func resolveSite(_ done: @escaping (URL) -> Void) {
    if let t = TEST_URL { done(t); return }
    // testa os dois ao mesmo tempo, mas NÃO espera pelo antigo quando o novo (preferido) já respondeu: um endereço lento
    // não segura mais a abertura do app (antes esperava sempre pelos dois, até 4 s)
    var state: [Bool?] = [nil, nil]
    var finished = false
    let lock = NSLock()
    func settle() { // chamado com o lock tomado
        if finished { return }
        let pick: URL?
        if state[0] == true { pick = SITE_NOVO }
        else if state[0] == false, state[1] == true { pick = SITE_ANTIGO }
        else if state[0] == false, state[1] == false { pick = lastSite ?? SITE_ANTIGO }
        else { pick = nil } // o novo ainda não respondeu: espera
        guard let url = pick else { return }
        finished = true
        DispatchQueue.main.async { done(url) }
    }
    for (i, base) in [SITE_NOVO, SITE_ANTIGO].enumerated() {
        var req = URLRequest(url: base.appendingPathComponent("manifest.json"), timeoutInterval: 4)
        req.httpMethod = "HEAD"
        req.cachePolicy = .reloadIgnoringLocalCacheData
        URLSession.shared.dataTask(with: req) { _, resp, _ in
            lock.lock()
            state[i] = (resp as? HTTPURLResponse)?.statusCode == 200
            settle()
            lock.unlock()
        }.resume()
    }
}
var lastSite: URL?
let APP_HOST = TEST_URL?.host ?? "gabrielxyz404.github.io"

// versão do app (a mostrada nas Configurações do site, no lugar da versão do Stremio)
let APP_VERSION: String = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? ""

// O site chama window.GabiFlixNative.playSession(...) (o mesmo nome usado no Android).
let BRIDGE_JS = """
(function () {
  window.__gfMacVersion = "\(APP_VERSION)";
  // o WebKit não tem a API do Chromecast: o aviso "window.cast api not available" é só ruído no console
  try {
    var oe = console.error;
    console.error = function () {
      try { if (/cast api not available/i.test(Array.prototype.join.call(arguments, ' '))) return; } catch (e) {}
      return oe.apply(console, arguments);
    };
  } catch (e) {}
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
    var overlay: LoadingOverlay?
    var keyMonitor: Any?

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
        content.add(self, name: "gfmac") // comandos do site (arrastar a janela pelo cabeçalho, tela cheia)
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
        if #available(macOS 11.0, *) { window.titlebarSeparatorStyle = .none }
        window.backgroundColor = .black // sem clarão branco enquanto o site abre
        window.collectionBehavior = [.fullScreenPrimary]
        window.minSize = NSSize(width: 860, height: 560) // abaixo disso as 9 barras dos serviços não cabem direito
        window.isReleasedWhenClosed = false

        // o site ocupa a janela toda; por cima fica a tela de abertura (logo + carregando) até o site desenhar
        let container = NSView()
        container.wantsLayer = true
        container.layer?.backgroundColor = NSColor.black.cgColor
        web.frame = container.bounds
        web.autoresizingMask = [.width, .height]
        container.addSubview(web)
        let ov = LoadingOverlay()
        ov.frame = container.bounds
        ov.autoresizingMask = [.width, .height]
        ov.onRetry = { [weak self] in self?.retryLoad() }
        container.addSubview(ov)
        overlay = ov
        window.contentView = container
        window.delegate = self
        window.setFrameAutosaveName("GabiFlixMain")
        if !window.setFrameUsingName("GabiFlixMain") { window.center() }
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        DispatchQueue.main.async { [weak self] in self?.placeTrafficLights() }

        loadSite()

        // Esc volta uma tela (como o botão Voltar do site); só na janela do site, nunca no player
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] e in
            guard let self = self, e.keyCode == 53, e.window === self.window, self.player == nil else { return e }
            self.goBackInApp(blurFirst: true)
            return nil
        }

        // procura versão nova alguns segundos depois de abrir (não no teste automático)
        if !CommandLine.arguments.contains("--gf-test-play") && !CommandLine.arguments.contains("--gf-test-web") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in self?.checkForUpdates(silent: true) }
        }

        // teste automático (só no GitHub): --gf-test-play <link do vídeo> <código IMDb>
        let args = CommandLine.arguments
        // teste automático da janela do site: --gf-test-web. O script escreve linhas em /tmp/gf-web: "resize L A" ou "js <código>" (resultado em /tmp/gf-web.out)
        if args.contains("--gf-test-web") {
            Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
                guard let self = self, let c = try? String(contentsOfFile: "/tmp/gf-web", encoding: .utf8) else { return }
                try? FileManager.default.removeItem(atPath: "/tmp/gf-web")
                for line in c.split(separator: "\n") {
                    let l = String(line)
                    if l.hasPrefix("resize ") {
                        let n = l.split(separator: " ").compactMap { Int($0) }
                        if n.count == 2 {
                            self.window.setContentSize(NSSize(width: n[0], height: n[1]))
                            self.window.center()
                        }
                    } else if l == "fs" {
                        self.window.toggleFullScreen(nil)
                    } else if l == "esc" {
                        self.goBackInApp(blurFirst: true)
                    } else if l.hasPrefix("hit ") {
                        // qual vista recebe o clique neste ponto (x, y a partir do topo da janela)? usado para conferir os botões do cabeçalho
                        let n = l.split(separator: " ").compactMap { Double($0) }
                        if n.count == 2, let frameView = self.window.contentView?.superview {
                            let p = NSPoint(x: n[0], y: Double(self.window.frame.height) - n[1])
                            let v = frameView.hitTest(frameView.convert(p, from: nil))
                            let out = "hit \(n[0]),\(n[1]) -> \(v.map { NSStringFromClass(type(of: $0)) } ?? "nil")\n"
                            if let h = try? FileHandle(forWritingTo: URL(fileURLWithPath: "/tmp/gf-web.out")) { h.seekToEndOfFile(); h.write(out.data(using: .utf8)!); h.closeFile() }
                            else { try? out.write(toFile: "/tmp/gf-web.out", atomically: true, encoding: .utf8) }
                        }
                    } else if l.hasPrefix("js ") {
                        self.web.evaluateJavaScript(String(l.dropFirst(3))) { r, e in
                            let out = "\(r ?? "nil") \(e?.localizedDescription ?? "")\n"
                            try? out.write(toFile: "/tmp/gf-web.out", atomically: true, encoding: .utf8)
                        }
                    }
                }
            }
        }
        if let i = args.firstIndex(of: "--gf-test-play"), i + 2 < args.count, let u = URL(string: args[i + 1]) {
            let id = args[i + 2]
            PlayerWindowController.testLog = true
            VLCLibrary.shared().debugLogging = true
            VLCLibrary.shared().debugLoggingLevel = 3
            // --gf-test-sub <link de uma legenda SRT>: legenda do "site" (sem busca no addon quando o código IMDb é "-")
            var subs: [URL] = []
            if let k = args.firstIndex(of: "--gf-test-sub"), k + 1 < args.count, let su = URL(string: args[k + 1]) { subs.append(su) }
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) {
                let p = id == "-" ? PlayerWindowController(url: u, subtitles: subs, type: "", id: "", startMs: 0, title: "Filme de teste  ·  T1 E3")
                                  : PlayerWindowController(url: u, subtitles: subs, type: "movie", id: id, startMs: 0)
                self.player = p
                p.showWindow(nil)
                // comandos de teste: o script escreve uma linha em /tmp/gf-cmd (scale N | sync | speech | delay MS | close)
                Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak p] _ in
                    guard let p = p, let c = try? String(contentsOfFile: "/tmp/gf-cmd", encoding: .utf8) else { return }
                    try? FileManager.default.removeItem(atPath: "/tmp/gf-cmd")
                    for line in c.split(separator: "\n") { p.testCommand(String(line)) }
                }
            }
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func applicationWillTerminate(_ notification: Notification) {
        player?.close() // entrega o progresso antes de sair
    }

    // ---------- menu (Copiar/Colar no login, Navegar, Atualizar, Tela cheia, Sair) ----------
    func buildMenu() {
        let main = NSMenu()

        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "Sobre o FramePlay", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        appMenu.addItem(withTitle: "Procurar atualizações…", action: #selector(checkUpdatesMenu), keyEquivalent: "")
        appMenu.addItem(.separator())
        appMenu.addItem(menuItem("Configurações…", #selector(goSettings), ","))
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Ocultar FramePlay", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        let hideOthers = NSMenuItem(title: "Ocultar Outros", action: #selector(NSApplication.hideOtherApplications(_:)), keyEquivalent: "h")
        hideOthers.keyEquivalentModifierMask = [.command, .option]
        appMenu.addItem(hideOthers)
        appMenu.addItem(withTitle: "Mostrar Tudo", action: #selector(NSApplication.unhideAllApplications(_:)), keyEquivalent: "")
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

        // Navegar: as telas do site e voltar/avançar
        let navItem = NSMenuItem()
        let nav = NSMenu(title: "Navegar")
        nav.addItem(menuItem("Início", #selector(goHome), "1"))
        nav.addItem(menuItem("Mais Tarde", #selector(goLibrary), "2"))
        nav.addItem(menuItem("Calendário", #selector(goCalendar), "3"))
        nav.addItem(menuItem("Addons", #selector(goAddons), "4"))
        nav.addItem(.separator())
        nav.addItem(menuItem("Pesquisar", #selector(goSearch), "f"))
        nav.addItem(.separator())
        nav.addItem(menuItem("Voltar", #selector(goBack), "["))
        nav.addItem(menuItem("Avançar", #selector(goForward), "]"))
        navItem.submenu = nav
        main.addItem(navItem)

        let viewItem = NSMenuItem()
        let view = NSMenu(title: "Visualizar")
        view.addItem(menuItem("Atualizar", #selector(reloadSite), "r"))
        view.addItem(.separator())
        let fs = NSMenuItem(title: "Tela Cheia", action: #selector(NSWindow.toggleFullScreen(_:)), keyEquivalent: "f")
        fs.keyEquivalentModifierMask = [.command, .control]
        view.addItem(fs)
        viewItem.submenu = view
        main.addItem(viewItem)

        let winItem = NSMenuItem()
        let win = NSMenu(title: "Janela")
        win.addItem(withTitle: "Minimizar", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        win.addItem(withTitle: "Ampliar", action: #selector(NSWindow.performZoom(_:)), keyEquivalent: "")
        win.addItem(withTitle: "Fechar", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        win.addItem(.separator())
        win.addItem(withTitle: "Trazer Tudo para a Frente", action: #selector(NSApplication.arrangeInFront(_:)), keyEquivalent: "")
        winItem.submenu = win
        main.addItem(winItem)
        NSApp.windowsMenu = win

        let helpItem = NSMenuItem()
        let help = NSMenu(title: "Ajuda")
        help.addItem(menuItem("Atalhos do teclado", #selector(showShortcuts), ""))
        helpItem.submenu = help
        main.addItem(helpItem)
        NSApp.helpMenu = help

        NSApp.mainMenu = main
    }

    private func menuItem(_ title: String, _ action: Selector, _ key: String) -> NSMenuItem {
        let it = NSMenuItem(title: title, action: action, keyEquivalent: key)
        it.target = self
        return it
    }

    /** vai para uma tela do site (o site usa o endereço depois do # como rota) */
    private func goRoute(_ hash: String) {
        if player != nil { return } // com o player aberto os atalhos do site não valem
        web.evaluateJavaScript("location.hash = '\(hash)'; true")
        window.makeKeyAndOrderFront(nil)
    }
    @objc func goHome() { goRoute("#/") }
    @objc func goLibrary() { goRoute("#/library") }
    @objc func goCalendar() { goRoute("#/calendar") }
    @objc func goAddons() { goRoute("#/addons") }
    @objc func goSettings() { goRoute("#/settings") }
    @objc func goSearch() {
        goRoute("#/search")
        // põe o cursor no campo de busca assim que a tela abre
        let js = "(function f(n){var i=document.querySelector('[class*=\"search-input\"] input, input[type=\"text\"]');if(i){i.focus();return true;}if(n<15)setTimeout(function(){f(n+1)},150);return false;})(0)"
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in self?.web.evaluateJavaScript(js) }
    }

    @objc func reloadSite() {
        // mesmo efeito do "Atualizar" do menu do site: busca a versão nova
        web.evaluateJavaScript("window.gfUpdateApp ? (window.gfUpdateApp(), true) : false") { result, _ in
            if (result as? Bool) != true { self.web.reloadFromOrigin() }
        }
    }

    /** volta uma tela como o botão Voltar do site (fecha janelas abertas, serviço -> início, título -> anterior) */
    func goBackInApp(blurFirst: Bool) {
        let blur = blurFirst ? "var a=document.activeElement;if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA')){a.blur();return true;}" : ""
        web.evaluateJavaScript("(function(){" + blur + "return window.gfBack ? window.gfBack() : false;})()")
    }

    @objc func goBack() { if player == nil { goBackInApp(blurFirst: false) } }
    @objc func goForward() { if player == nil && web.canGoForward { web.goForward() } }

    @objc func showShortcuts() {
        let a = NSAlert()
        a.messageText = "Atalhos do FramePlay"
        a.informativeText = """
        No site
        ⌘1  Início     ⌘2  Mais Tarde     ⌘3  Calendário     ⌘4  Addons
        ⌘F  Pesquisar     ⌘,  Configurações
        Esc ou ⌘[  Voltar     ⌘]  Avançar     ⌘R  Atualizar

        No player
        Espaço  Pausar/continuar     ←  →  10 s para trás/frente
        ↑  ↓  Volume     M  Silenciar     F  Tela cheia
        S  Legendas     A  Áudio     L  Sincronizar legenda
        Esc  Fechar o painel ou sair da tela cheia/do player
        """
        a.addButton(withTitle: "OK")
        a.runModal()
    }

    // ---------- ponte do site ----------
    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        if message.name == "gfmac" { handleMacCommand(message.body); return }
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
        // o título que está na tela do site (a página do filme/série) vai para o topo do player
        let js = "(function(){var t=document.querySelector('.gf-d-title');return t?String(t.textContent||'').trim():'';})()"
        web.evaluateJavaScript(js) { [weak self] result, _ in
            guard let self = self else { return }
            let title = AppDelegate.playerTitle(site: (result as? String) ?? "", id: id, link: url)
            let p = PlayerWindowController(url: url, subtitles: subs, type: type, id: id, startMs: start, title: title)
            p.onProgress = { time, length in AppDelegate.saveLast(time: time, length: length, ended: false, url: link) }
            p.onFinish = { [weak self] time, length, ended in self?.deliverProgress(time: time, length: length, ended: ended, url: link) }
            self.player = p
            p.showWindow(nil)
            p.window?.makeKeyAndOrderFront(nil)
        }
    }

    /** "Nome do filme" ou "Nome da série · T1 E3"; sem nome no site, usa o nome do arquivo do link */
    static func playerTitle(site: String, id: String, link: URL) -> String {
        var base = site
        if base.isEmpty {
            var f = (link.lastPathComponent.removingPercentEncoding ?? link.lastPathComponent)
            if let dot = f.lastIndex(of: "."), f.distance(from: dot, to: f.endIndex) <= 5 { f = String(f[f.startIndex..<dot]) }
            base = f.replacingOccurrences(of: ".", with: " ").replacingOccurrences(of: "_", with: " ")
        }
        let parts = id.split(separator: ":")
        if parts.count >= 3, let s = Int(parts[parts.count - 2]), let e = Int(parts[parts.count - 1]) { base += "  ·  T\(s) E\(e)" }
        return base
    }

    /** comandos que o site manda (gabiflix/mac.js): arrastar a janela pelo cabeçalho, zoom, tela cheia */
    private func handleMacCommand(_ body: Any) {
        guard let d = body as? [String: Any], let cmd = d["cmd"] as? String else { return }
        switch cmd {
        case "drag":
            // só enquanto o botão do mouse ainda está apertado (a mensagem chega um instante depois do clique)
            if NSEvent.pressedMouseButtons & 1 != 0, let e = NSApp.currentEvent,
               e.type == .leftMouseDown || e.type == .leftMouseDragged { window.performDrag(with: e) }
        case "zoom": window.performZoom(nil)
        case "fullscreen": window.toggleFullScreen(nil)
        default: break
        }
    }

    /** devolve ao site onde o vídeo parou (o site salva no Continuar assistindo do Stremio) */
    func deliverProgress(time: Int64, length: Int64, ended: Bool, url: String) {
        player = nil
        window.makeKeyAndOrderFront(nil) // também quando fechou sem tocar nada (tempo 0): a janela do site tem de voltar à frente
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

    // ---------- tela de abertura ----------
    /** tira a tela de abertura quando o site já desenhou algo (ou depois de uns 8 s, para nunca ficar presa) */
    private func waitForSiteThenReveal(_ attempt: Int = 0) {
        guard overlay != nil else { return }
        web.evaluateJavaScript("window.gfMacReady ? window.gfMacReady() : false") { [weak self] result, _ in
            guard let self = self else { return }
            if (result as? Bool) == true || attempt >= 40 { self.revealSite() }
            else { DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { self.waitForSiteThenReveal(attempt + 1) } }
        }
    }

    private func revealSite() {
        guard let ov = overlay else { return }
        overlay = nil
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.4
            ov.animator().alphaValue = 0
        }, completionHandler: { ov.removeFromSuperview() })
    }

    private func loadSite() {
        resolveSite { [weak self] url in lastSite = url; self?.web.load(URLRequest(url: url)) }
    }

    private func retryLoad() {
        overlay?.showLoading()
        loadSite()
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        overlay?.showError("Não consegui abrir o FramePlay.\nVerifique a ligação à internet.")
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        overlay?.showError("Não consegui abrir o FramePlay.\nVerifique a ligação à internet.")
    }

    // ---------- janela ----------
    /** os botões vermelho/amarelo/verde ficam no meio da cápsula de vidro do cabeçalho */
    func placeTrafficLights() {
        Chrome.positionTrafficLights(window, centerY: 44, leading: 24)
    }
    func windowDidResize(_ notification: Notification) {
        DispatchQueue.main.async { [weak self] in self?.placeTrafficLights() }
    }
    func windowDidBecomeKey(_ notification: Notification) {
        DispatchQueue.main.async { [weak self] in self?.placeTrafficLights() }
    }
    func windowWillEnterFullScreen(_ notification: Notification) {
        web.evaluateJavaScript("window.gfMacFullscreen && window.gfMacFullscreen(true)")
    }
    func windowDidExitFullScreen(_ notification: Notification) {
        web.evaluateJavaScript("window.gfMacFullscreen && window.gfMacFullscreen(false)")
        DispatchQueue.main.async { [weak self] in self?.placeTrafficLights() }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        waitForSiteThenReveal()
        if !leftoverTried {
            leftoverTried = true
            deliverLeftover()
        }
    }

    // ---------- atualizações ----------
    /// lê a release "mac" pelo nome novo do repositório e, se não responder, pelo antigo
    static let releaseAPIs = ["FramePlay", "GABIFLIX"].map { URL(string: "https://api.github.com/repos/gabrielxyz404/\($0)/releases/tags/mac")! }

    @objc func checkUpdatesMenu() { checkForUpdates(silent: false) }

    func checkForUpdates(silent: Bool) {
        if checkingUpdate { return }
        checkingUpdate = true
        fetchRelease(AppDelegate.releaseAPIs[...]) { [weak self] latest, zip in
            guard let self = self else { return }
            self.checkingUpdate = false
            self.showUpdateResult(latest: latest, zip: zip, silent: silent)
        }
    }

    private func fetchRelease(_ urls: ArraySlice<URL>, _ done: @escaping (Int, URL?) -> Void) {
        guard let first = urls.first else { done(-1, nil); return }
        var req = URLRequest(url: first, timeoutInterval: 15)
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
                if latest < 0 || zip == nil, urls.count > 1, let self = self { self.fetchRelease(urls.dropFirst(), done) }
                else { done(latest, zip) }
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
            a.informativeText = "A versão 1.0.\(latest) está disponível (a sua é a 1.0.\(mine)). O FramePlay baixa, instala e abre sozinho."
            a.addButton(withTitle: "Atualizar agora")
            a.addButton(withTitle: "Agora não")
            if a.runModal() == .alertFirstButtonReturn { installUpdate(zip: zip) }
        } else if !silent {
            let a = NSAlert()
            a.messageText = "Já tens a versão mais recente"
            a.informativeText = "O FramePlay está atualizado (versão 1.0.\(mine))."
            a.addButton(withTitle: "OK")
            a.runModal()
        }
    }

    // baixa o .zip pelo próprio app (sem a marca "vindo da internet", que o macOS usa para bloquear), troca o app e abre de novo
    private var updatePanel: NSPanel?

    private func updateFailed(_ text: String) {
        let a = NSAlert()
        a.messageText = "Não foi possível atualizar"
        a.informativeText = text
        a.addButton(withTitle: "OK")
        a.runModal()
    }

    private func installUpdate(zip: URL) {
        let appPath = Bundle.main.bundlePath
        let parent = (appPath as NSString).deletingLastPathComponent
        if !FileManager.default.isWritableFile(atPath: parent) {
            updateFailed("O FramePlay não tem permissão para se atualizar nesta pasta. Mova-o para a pasta Aplicativos e tente de novo.")
            return
        }
        let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 320, height: 90), styleMask: [.titled], backing: .buffered, defer: false)
        panel.title = "FramePlay"
        let label = NSTextField(labelWithString: "A baixar a atualização…")
        label.frame = NSRect(x: 20, y: 50, width: 280, height: 20)
        let bar = NSProgressIndicator(frame: NSRect(x: 20, y: 22, width: 280, height: 16))
        bar.style = .bar
        bar.isIndeterminate = true
        bar.startAnimation(nil)
        panel.contentView?.addSubview(label)
        panel.contentView?.addSubview(bar)
        panel.center()
        panel.makeKeyAndOrderFront(nil)
        updatePanel = panel
        // sessão própria com prazo: sem rede a meio do download a janela "A baixar…" não fica presa para sempre
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 30
        cfg.timeoutIntervalForResource = 600
        URLSession(configuration: cfg).downloadTask(with: zip) { [weak self] tmp, resp, err in
            var dest: URL?
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 200
            if let tmp = tmp, err == nil, code < 400 {
                let d = FileManager.default.temporaryDirectory.appendingPathComponent("FramePlay-update.zip")
                try? FileManager.default.removeItem(at: d)
                if (try? FileManager.default.moveItem(at: tmp, to: d)) != nil { dest = d }
            }
            DispatchQueue.main.async { self?.finishInstall(zipFile: dest) }
        }.resume()
    }

    private func finishInstall(zipFile: URL?) {
        updatePanel?.close()
        updatePanel = nil
        guard let zipFile = zipFile else {
            updateFailed("Não consegui baixar a nova versão. Verifique a internet e tente de novo.")
            return
        }
        let appPath = Bundle.main.bundlePath
        let pid = ProcessInfo.processInfo.processIdentifier
        let script = """
        #!/bin/bash
        while kill -0 \(pid) 2>/dev/null; do sleep 0.3; done
        T=$(mktemp -d /tmp/frameplay-upd.XXXXXX)
        if ditto -x -k "\(zipFile.path)" "$T" && [ -d "$T/FramePlay.app" ]; then
          rm -rf "\(appPath)" && mv "$T/FramePlay.app" "\(appPath)" && xattr -cr "\(appPath)"
        fi
        rm -rf "$T" "\(zipFile.path)"
        open "\(appPath)"
        """
        let sh = "/tmp/frameplay-update.sh"
        do { try script.write(toFile: sh, atomically: true, encoding: .utf8) } catch {
            updateFailed("Não consegui preparar a atualização.")
            return
        }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/bash")
        p.arguments = ["-c", "nohup /bin/bash \(sh) >/dev/null 2>&1 &"]
        do { try p.run() } catch {
            updateFailed("Não consegui iniciar a atualização.")
            return
        }
        NSApp.terminate(nil)
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

    /// o processo da página morreu (pouca memória, falha): recarrega, mas no máximo 3 vezes por minuto, para não entrar em ciclo
    private static var reloadTimes: [Date] = []
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        let now = Date()
        AppDelegate.reloadTimes = AppDelegate.reloadTimes.filter { now.timeIntervalSince($0) < 60 }
        if AppDelegate.reloadTimes.count >= 3 {
            overlay?.showError("A página parou várias vezes.\nFeche e abra o FramePlay de novo.")
            return
        }
        AppDelegate.reloadTimes.append(now)
        webView.reload()
    }
}

/** tela de abertura: o logo do app e "carregando"; se o site não abrir mostra o erro e um botão para tentar de novo */
final class LoadingOverlay: NSView {
    var onRetry: (() -> Void)?
    private let icon = NSImageView()
    private let spinner = NSProgressIndicator()
    private let label = NSTextField(labelWithString: "")
    private let retry = NSButton()

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        icon.image = NSApp.applicationIconImage
        icon.imageScaling = .scaleProportionallyUpOrDown
        icon.translatesAutoresizingMaskIntoConstraints = false
        icon.widthAnchor.constraint(equalToConstant: 112).isActive = true
        icon.heightAnchor.constraint(equalToConstant: 112).isActive = true
        spinner.style = .spinning
        spinner.controlSize = .small
        spinner.appearance = NSAppearance(named: .darkAqua)
        spinner.startAnimation(nil)
        label.textColor = NSColor(white: 1, alpha: 0.72)
        label.font = .systemFont(ofSize: 14)
        label.alignment = .center
        label.maximumNumberOfLines = 0
        label.isHidden = true
        retry.title = "Tentar de novo"
        retry.bezelStyle = .rounded
        retry.target = self
        retry.action = #selector(retryTapped)
        retry.isHidden = true
        let stack = NSStackView(views: [icon, spinner, label, retry])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 20
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: centerYAnchor, constant: -10),
        ])
    }

    required init?(coder: NSCoder) { fatalError("não usado") }

    override var mouseDownCanMoveWindow: Bool { true }

    func showError(_ text: String) {
        spinner.stopAnimation(nil)
        spinner.isHidden = true
        label.stringValue = text
        label.isHidden = false
        retry.isHidden = false
    }

    func showLoading() {
        label.isHidden = true
        retry.isHidden = true
        spinner.isHidden = false
        spinner.startAnimation(nil)
    }

    @objc private func retryTapped() { onRetry?() }
}

/** ajustes da janela (botões vermelho/amarelo/verde) usados pela janela do site e pelo player */
enum Chrome {
    /** põe os 3 botões da janela com o centro a `centerY` pontos do topo e o primeiro a `leading` pontos da esquerda.
        O contêiner da barra de título cresce junto (senão os botões ficam cortados e sem clique). */
    static func positionTrafficLights(_ w: NSWindow, centerY: CGFloat, leading: CGFloat) {
        if w.styleMask.contains(.fullScreen) { return }
        guard let close = w.standardWindowButton(.closeButton),
              let mini = w.standardWindowButton(.miniaturizeButton),
              let zoom = w.standardWindowButton(.zoomButton),
              let titlebar = close.superview, let container = titlebar.superview else { return }
        let h = max(centerY * 2, 28)
        var f = container.frame
        f.size.height = h
        f.origin.y = w.frame.height - h
        container.frame = f
        titlebar.frame = NSRect(x: 0, y: 0, width: container.frame.width, height: h)
        let step = max(mini.frame.origin.x - close.frame.origin.x, 18)
        for (i, b) in [close, mini, zoom].enumerated() {
            b.setFrameOrigin(NSPoint(x: leading + CGFloat(i) * step, y: (h - b.frame.height) / 2))
        }
    }

    /** esmaece/mostra os 3 botões junto com os controles do player */
    static func setTrafficLightsAlpha(_ w: NSWindow, _ a: CGFloat, animated: Bool) {
        for t in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] {
            guard let b = w.standardWindowButton(t) else { continue }
            if animated { b.animator().alphaValue = a } else { b.alphaValue = a }
        }
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
