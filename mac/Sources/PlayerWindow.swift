// Player do FramePlay para Mac, com o VLC (VLCKit): toca qualquer formato e áudio (MKV, HEVC, Dolby, DTS),
// liga só legendas em português (as que combinam com o filme), começa de onde parou e devolve o progresso ao site.
import Cocoa
import VLCKit

/** Vista principal do player: recebe teclado e mouse */
final class PlayerRootView: NSView {
    var onKey: ((NSEvent) -> Bool)?
    var onMouseMove: (() -> Void)?
    var onDoubleClick: (() -> Void)?
    private var tracking: NSTrackingArea?

    override var acceptsFirstResponder: Bool { true }
    override func keyDown(with event: NSEvent) { if onKey?(event) != true { super.keyDown(with: event) } }
    override func mouseMoved(with event: NSEvent) { onMouseMove?() }
    override func mouseDown(with event: NSEvent) {
        onMouseMove?()
        if event.clickCount == 2 { onDoubleClick?() }
    }
    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let t = tracking { removeTrackingArea(t) }
        let t = NSTrackingArea(rect: bounds, options: [.mouseMoved, .activeAlways, .inVisibleRect], owner: self, userInfo: nil)
        addTrackingArea(t)
        tracking = t
    }
}

final class PlayerWindowController: NSWindowController, NSWindowDelegate {
    var onFinish: ((Int64, Int64, Bool) -> Void)?
    /** progresso periódico (tempo, duração), a cada ~5 s, como o reportSession do Android */
    var onProgress: ((Int64, Int64) -> Void)?

    private let url: URL
    private var subtitleURLs: [URL]
    private let metaType: String
    private let metaId: String
    private let startMs: Int64

    private let player = VLCMediaPlayer()
    private let videoView = VLCVideoView()
    private let root = NSView()
    private let overlay = PlayerRootView()
    private let controls = NSVisualEffectView()
    private let topBar = NSVisualEffectView()
    private let playButton = NSButton()
    private let slider = NSSlider()
    private let timeLabel = NSTextField(labelWithString: "0:00")
    private let lengthLabel = NSTextField(labelWithString: "0:00")
    private let spinner = NSProgressIndicator()
    private let message = NSTextField(labelWithString: "")

    private var timer: Timer?
    private var hideAt = Date.distantFuture
    private var startedPlaying = false
    private var resumeDone = false
    private var ended = false
    private var finished = false
    private var userSeeking = false
    private var lastTime: Int64 = 0
    private var lastLength: Int64 = 0
    private var lastReport = Date.distantPast
    private var playingSince = Date.distantFuture
    private var fillMode = false

    // legendas baixadas (arquivos já em UTF-8) e as que já vinham no vídeo
    private var cands: [SubCand] = []
    private var subsDownloaded = false
    private var subsAttached = false
    private var addedSubs = 0
    private var embeddedSubs = Set<Int>()
    private var embeddedKnown = false
    private var embeddedCheckDone = false
    private var subLabels: [String] = []
    /** ajuste manual da legenda (ms; positivo = a legenda aparece mais tarde), lembrado por título */
    private var subDelayMs = 0
    private var delayValueLabel: NSTextField?
    private var attached: [SubCand] = []
    private var userPickedSub = false
    private var embeddedPtId: Int? = nil
    /** diagnóstico mostrado no topo do menu de legendas */
    private var diagId = "Procurando legendas…"
    private var diagRef = ""

    init(url: URL, subtitles: [URL], type: String, id: String, startMs: Int64) {
        self.url = url
        self.subtitleURLs = subtitles
        self.metaType = type
        self.metaId = id
        self.startMs = startMs
        let screen = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let w = NSWindow(contentRect: screen, styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                         backing: .buffered, defer: false)
        w.title = "FramePlay"
        w.titlebarAppearsTransparent = true
        w.titleVisibility = .hidden
        w.backgroundColor = .black
        w.collectionBehavior = [.fullScreenPrimary]
        w.isReleasedWhenClosed = false
        super.init(window: w)
        w.delegate = self
        subDelayMs = Int(UserDefaults.standard.integer(forKey: delayKey()))
        buildUI()
        loadSubtitles()
        start()
    }

    required init?(coder: NSCoder) { fatalError("não usado") }

    // ---------- interface ----------
    private func buildUI() {
        guard let w = window else { return }
        root.wantsLayer = true
        root.layer?.backgroundColor = NSColor.black.cgColor
        w.contentView = root

        videoView.translatesAutoresizingMaskIntoConstraints = false
        videoView.fillScreen = false
        root.addSubview(videoView)

        spinner.style = .spinning
        spinner.controlSize = .large
        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.appearance = NSAppearance(named: .darkAqua)
        spinner.startAnimation(nil)
        root.addSubview(spinner)

        message.textColor = .white
        message.font = .boldSystemFont(ofSize: 17)
        message.alignment = .center
        message.isHidden = true
        message.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(message)

        // camada transparente por cima do vídeo: recebe cliques, movimento do mouse e teclado
        overlay.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(overlay)

        // barra de cima: voltar
        topBar.material = .hudWindow
        topBar.blendingMode = .withinWindow
        topBar.state = .active
        topBar.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(topBar)
        let back = pill("←  Voltar", #selector(closePlayer))
        topBar.addSubview(back)

        // barra de baixo: play, -10/+10, tempo, linha do tempo, áudio, legendas, tela cheia
        controls.material = .hudWindow
        controls.blendingMode = .withinWindow
        controls.state = .active
        controls.wantsLayer = true
        controls.layer?.cornerRadius = 16
        controls.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(controls)

        playButton.title = "❚❚"
        styleButton(playButton, #selector(togglePlay))
        playButton.font = .boldSystemFont(ofSize: 18)
        let rw = button("−10", #selector(rewind))
        let ff = button("+10", #selector(forward))
        for l in [timeLabel, lengthLabel] {
            l.textColor = .white
            l.font = .monospacedDigitSystemFont(ofSize: 13, weight: .semibold)
        }
        slider.minValue = 0
        slider.maxValue = 1000
        slider.target = self
        slider.action = #selector(sliderMoved)
        slider.isContinuous = false
        let audio = button("Áudio", #selector(chooseAudio))
        let subs = button("Legendas", #selector(chooseSubtitles))
        let scale = button("Tela: Ajustar", #selector(toggleScale(_:)))
        let fs = button("Tela cheia", #selector(toggleFullScreen))

        let row = NSStackView(views: [playButton, rw, ff, timeLabel, slider, lengthLabel, audio, subs, scale, fs])
        row.orientation = .horizontal
        row.spacing = 12
        row.alignment = .centerY
        row.translatesAutoresizingMaskIntoConstraints = false
        slider.setContentHuggingPriority(.defaultLow, for: .horizontal)
        controls.addSubview(row)

        NSLayoutConstraint.activate([
            videoView.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            videoView.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            videoView.topAnchor.constraint(equalTo: root.topAnchor),
            videoView.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            overlay.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            overlay.topAnchor.constraint(equalTo: root.topAnchor),
            overlay.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            spinner.centerXAnchor.constraint(equalTo: root.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: root.centerYAnchor),
            message.centerXAnchor.constraint(equalTo: root.centerXAnchor),
            message.centerYAnchor.constraint(equalTo: root.centerYAnchor),
            message.widthAnchor.constraint(lessThanOrEqualTo: root.widthAnchor, constant: -80),

            topBar.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            topBar.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            topBar.topAnchor.constraint(equalTo: root.topAnchor),
            topBar.heightAnchor.constraint(equalToConstant: 64),
            back.leadingAnchor.constraint(equalTo: topBar.leadingAnchor, constant: 84),
            back.bottomAnchor.constraint(equalTo: topBar.bottomAnchor, constant: -12),

            controls.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 24),
            controls.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -24),
            controls.bottomAnchor.constraint(equalTo: root.bottomAnchor, constant: -20),
            controls.heightAnchor.constraint(equalToConstant: 56),
            row.leadingAnchor.constraint(equalTo: controls.leadingAnchor, constant: 16),
            row.trailingAnchor.constraint(equalTo: controls.trailingAnchor, constant: -16),
            row.centerYAnchor.constraint(equalTo: controls.centerYAnchor),
        ])

        overlay.onMouseMove = { [weak self] in self?.showControls() }
        overlay.onDoubleClick = { [weak self] in self?.toggleFullScreen() }
        overlay.onKey = { [weak self] e in self?.handleKey(e) ?? false }
        w.makeFirstResponder(overlay)
        w.acceptsMouseMovedEvents = true
    }

    private func button(_ title: String, _ action: Selector) -> NSButton {
        let b = NSButton(title: title, target: self, action: action)
        styleButton(b, action)
        return b
    }

    private func styleButton(_ b: NSButton, _ action: Selector) {
        b.target = self
        b.action = action
        b.bezelStyle = .rounded
        b.isBordered = false
        b.wantsLayer = true
        b.layer?.cornerRadius = 10
        b.layer?.backgroundColor = NSColor(white: 1, alpha: 0.12).cgColor
        b.contentTintColor = .white
        b.attributedTitle = NSAttributedString(string: "  \(b.title)  ", attributes: [
            .foregroundColor: NSColor.white, .font: b.font ?? NSFont.boldSystemFont(ofSize: 13)])
        b.setContentHuggingPriority(.required, for: .horizontal)
        b.refusesFirstResponder = true
    }

    private func setTitle(_ b: NSButton, _ t: String) {
        b.attributedTitle = NSAttributedString(string: "  \(t)  ", attributes: [
            .foregroundColor: NSColor.white, .font: NSFont.boldSystemFont(ofSize: 18)])
    }

    private func pill(_ title: String, _ action: Selector) -> NSButton {
        let b = button(title, action)
        b.font = .boldSystemFont(ofSize: 14)
        b.translatesAutoresizingMaskIntoConstraints = false
        return b
    }

    // ---------- reprodução ----------
    private func start() {
        player.drawable = videoView
        let media = VLCMedia(url: url)
        media.addOption(":network-caching=3000")
        media.addOption(":http-reconnect")
        media.addOption(":sub-language=pob,por,pt")
        player.media = media
        player.play()
        showControls()
        timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in self?.tick() }
    }

    static var testLog = false
    private func log(_ text: String) {
        guard PlayerWindowController.testLog else { return }
        let line = "\(Date().timeIntervalSince1970) \(text)\n"
        let url = URL(fileURLWithPath: "/tmp/frameplay-player.log")
        if let h = try? FileHandle(forWritingTo: url) { h.seekToEndOfFile(); h.write(line.data(using: .utf8)!); h.closeFile() }
        else { try? line.write(to: url, atomically: true, encoding: .utf8) }
    }

    private var prevTickTime: Int64 = -1
    private func tick() {
        var state = player.state
        let t = Int64(player.time.intValue)
        // o VLCKit às vezes continua a dizer "carregando" mesmo com o vídeo a andar: se o tempo avança, está tocando
        if state != .playing && state != .error && player.isPlaying && t > 0 && t != prevTickTime { state = .playing }
        prevTickTime = t
        let len = Int64(player.media?.length.intValue ?? 0)
        log("estado=\(state.rawValue) tempo=\(t) duração=\(len) tocando=\(player.isPlaying) legendas=\(addedSubs)/\(cands.count) faixasLeg=\((player.videoSubTitlesNames as? [String]) ?? []) audio=\((player.audioTrackNames as? [String]) ?? [])")
        if t > 0 { lastTime = t }
        if len > 0 { lastLength = len }

        switch state {
        case .playing:
            spinner.isHidden = true
            if !startedPlaying {
                startedPlaying = true
                playingSince = Date()
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in self?.selectEmbeddedPortuguese() }
            }
            if !resumeDone && len > 0 {
                resumeDone = true
                let saved = savedPosition()
                if saved > 30000 && saved < len - 60000 { player.time = VLCTime(int: Int32(truncatingIfNeeded: saved)) }
            }
        case .buffering, .opening:
            if !startedPlaying { spinner.isHidden = false }
        case .ended:
            if startedPlaying { endNow(); return }
        case .stopped:
            if startedPlaying && lastLength > 0 && lastTime > lastLength - 8000 { endNow(); return }
        case .error:
            spinner.isHidden = true
            message.stringValue = "Não foi possível reproduzir este vídeo.\nVolte e escolha outra fonte."
            message.isHidden = false
            showControls()
        default:
            break
        }

        attachReadySubs()
        reportProgress(force: false)

        setTitle(playButton, player.isPlaying ? "❚❚" : "▶")
        if !userSeeking && len > 0 { slider.doubleValue = Double(t) * 1000 / Double(len) }
        timeLabel.stringValue = format(t)
        lengthLabel.stringValue = format(len)

        // esconde os controles e o cursor depois de 2,5 s parado (só tocando)
        if player.isPlaying && Date() > hideAt && controls.alphaValue > 0 {
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.25
                controls.animator().alphaValue = 0
                topBar.animator().alphaValue = 0
            }
            NSCursor.setHiddenUntilMouseMoves(true)
        }
    }

    private func endNow() {
        ended = true
        lastTime = lastLength
        clearPosition()
        closePlayer()
    }

    // ---------- continuar de onde parou / avisar o site ----------
    private func positionKey() -> String {
        var h: UInt64 = 5381
        for b in url.absoluteString.utf8 { h = (h &* 33) &+ UInt64(b) }
        return "fp-pos-\(h)"
    }
    /** aberto pelo site: o ponto certo é o do Stremio; sem código do título, usa o último ponto guardado aqui */
    private func savedPosition() -> Int64 {
        if !metaId.isEmpty { return startMs }
        return Int64(UserDefaults.standard.double(forKey: positionKey()))
    }
    private func clearPosition() { if metaId.isEmpty { UserDefaults.standard.removeObject(forKey: positionKey()) } }

    private func reportProgress(force: Bool) {
        if ended || finished { return }
        let now = Date()
        if !force && now.timeIntervalSince(lastReport) < 5 { return }
        lastReport = now
        let t = Int64(player.time.intValue)
        let len = Int64(player.media?.length.intValue ?? 0)
        if t <= 0 { return }
        if metaId.isEmpty { UserDefaults.standard.set(Double(t), forKey: positionKey()) }
        else { onProgress?(t, len) }
    }

    private func showControls() {
        hideAt = Date().addingTimeInterval(2.5)
        if controls.alphaValue < 1 {
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.15
                controls.animator().alphaValue = 1
                topBar.animator().alphaValue = 1
            }
        }
    }

    @objc private func togglePlay() {
        if player.isPlaying { player.pause(); reportProgress(force: true) } else { player.play() }
        showControls()
    }

    @objc private func toggleScale(_ sender: NSButton) {
        fillMode = !fillMode
        videoView.fillScreen = fillMode
        sender.attributedTitle = NSAttributedString(string: "  Tela: \(fillMode ? "Preencher" : "Ajustar")  ", attributes: [
            .foregroundColor: NSColor.white, .font: NSFont.boldSystemFont(ofSize: 13)])
        showControls()
    }

    private func skip(_ ms: Int64) {
        let len = Int64(player.media?.length.intValue ?? 0)
        var t = max(0, Int64(player.time.intValue) + ms)
        if len > 0 { t = min(t, len - 1000) }
        player.time = VLCTime(int: Int32(truncatingIfNeeded: t))
        showControls()
    }
    @objc private func rewind() { skip(-10000) }
    @objc private func forward() { skip(10000) }

    @objc private func sliderMoved() {
        let len = Int64(player.media?.length.intValue ?? 0)
        guard len > 0 else { return }
        let target = Int64(slider.doubleValue / 1000 * Double(len))
        timeLabel.stringValue = format(target)
        player.time = VLCTime(int: Int32(truncatingIfNeeded: target))
        showControls()
    }

    @objc private func toggleFullScreen() { window?.toggleFullScreen(nil) }

    private func handleKey(_ e: NSEvent) -> Bool {
        switch e.keyCode {
        case 49: togglePlay(); return true                 // espaço
        case 123: skip(-10000); return true                // ←
        case 124: skip(10000); return true                 // →
        case 3: toggleFullScreen(); return true            // F
        case 53:                                           // Esc
            if window?.styleMask.contains(.fullScreen) == true { toggleFullScreen() } else { closePlayer() }
            return true
        default: return false
        }
    }

    // ---------- áudio ----------
    @objc private func chooseAudio(_ sender: NSButton) {
        let names = (player.audioTrackNames as? [String]) ?? []
        let ids = (player.audioTrackIndexes as? [NSNumber])?.map { $0.intValue } ?? []
        let menu = NSMenu()
        for (i, id) in ids.enumerated() where i < names.count {
            let item = NSMenuItem(title: id == -1 ? "Desativado" : names[i], action: #selector(pickAudio(_:)), keyEquivalent: "")
            item.target = self
            item.tag = id
            item.state = Int(player.currentAudioTrackIndex) == id ? .on : .off
            menu.addItem(item)
        }
        if menu.items.isEmpty { menu.addItem(withTitle: "Sem opções de áudio", action: nil, keyEquivalent: "") }
        menu.popUp(positioning: nil, at: NSPoint(x: 0, y: sender.bounds.height + 4), in: sender)
        showControls()
    }
    @objc private func pickAudio(_ item: NSMenuItem) { player.currentAudioTrackIndex = Int32(item.tag) }

    // ---------- legendas em português ----------
    private static let ptRegex = try? NSRegularExpression(pattern: "portug|brazil|brasil|\\bpt\\b|\\bpor\\b|\\bpob\\b|pt-br|pt_br", options: .caseInsensitive)
    private func isPortuguese(_ s: String) -> Bool {
        guard let r = PlayerWindowController.ptRegex else { return false }
        return r.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }
    private func isDownloaded(_ id: Int) -> Bool { addedSubs > 0 && embeddedKnown && id != -1 && !embeddedSubs.contains(id) }

    /** uma legenda baixada: arquivo local em UTF-8, fim da última fala, posição na lista do addon (hash primeiro) */
    struct SubCand {
        let url: URL
        var lastMs: Int64
        var text: String
        let ext: String
        let rank: Int
        let hashMatch: Bool
        var fixed = false
        var score = 0.0
        var aligned = false
        var alignScore = 0.0
        var alignScale = 1.0
        var alignOffsetMs: Int64 = 0
    }
    struct FoundSub { let url: URL; let hashMatch: Bool; let lang: String }
    /** resposta do addon: legendas PT + candidatas a referência de tempo (outros idiomas, nunca mostradas) */
    struct SearchResult {
        var pt: [FoundSub] = []
        var refs: [FoundSub] = []
        var sawMatchField = false
        var refsAssumed = false
        var total = 0
        var ok = false
    }
    struct VideoHash {
        var hash: String? = nil
        var size: Int64 = 0
        var error = ""
        var headerName: String? = nil
        var finalURL: URL? = nil
    }

    private static func shortReason(_ s: String) -> String { s.count > 48 ? String(s.prefix(48)) : s }
    private static func langShort(_ l: String) -> String {
        if l.isEmpty { return "?" }
        if l == "eng" || l == "en" { return "EN" }
        return l.uppercased()
    }
    private static func signedSecs(_ ms: Int64) -> String {
        let v = String(format: "%.1f", Double(abs(ms)) / 1000.0).replacingOccurrences(of: ".", with: ",")
        return (ms > 0 ? "+" : ms < 0 ? "−" : "") + v + " s"
    }

    /** baixa as legendas: hash do arquivo (como o Stremio desktop), busca no OpenSubtitles do Stremio com hash/tamanho/nome
        (os resultados do hash vêm primeiro) mais as do site; alinha cada uma à legenda de referência (outro idioma, mesmo hash);
        cada uma vira UTF-8 e só fica se for legenda de verdade e em português */
    private func loadSubtitles() {
        let given = subtitleURLs
        let type = metaType, id = metaId
        let videoURL = url
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let vh = PlayerWindowController.computeHashTimeboxed(videoURL, seconds: 25)
            let fname = PlayerWindowController.fileName(of: videoURL, vh)
            if let h = vh.hash { self?.log("hash=\(h) tamanho=\(vh.size) arquivo=\(fname ?? "?")") }
            else { self?.log("hash não calculado: \(vh.error); arquivo=\(fname ?? "?")") }

            var sr: SearchResult? = nil
            if !type.isEmpty && !id.isEmpty {
                let extras = PlayerWindowController.makeExtras(vh, fname)
                if !extras.isEmpty { sr = PlayerWindowController.searchOpenSubtitles(type: type, id: id, extras: extras) }
                if sr == nil || (sr?.pt.isEmpty ?? true) {
                    let plain = PlayerWindowController.searchOpenSubtitles(type: type, id: id, extras: "")
                    if var cur = sr {
                        cur.pt.append(contentsOf: plain.pt)
                        if cur.refs.isEmpty { cur.refs = plain.refs; cur.refsAssumed = plain.refsAssumed }
                        if !cur.ok { cur.ok = plain.ok }
                        sr = cur
                    } else { sr = plain }
                }
            }
            let hashHits = (sr?.pt.filter { $0.hashMatch }.count ?? 0) + (sr?.refs.filter { $0.hashMatch }.count ?? 0)
            let idLine: String
            if vh.hash == nil { idLine = "Vídeo não identificado (sem hash: \(PlayerWindowController.shortReason(vh.error)))" }
            else if sr == nil || sr?.ok != true { idLine = "Hash calculado, mas a busca de legendas falhou" }
            else if sr?.sawMatchField != true { idLine = "Hash calculado (o addon não confirma a correspondência)" }
            else if hashHits > 0 { idLine = "Vídeo identificado ✓" }
            else { idLine = "Hash calculado, sem correspondência no OpenSubtitles" }
            let guess: String = (sr?.refsAssumed ?? false) ? " (palpite)" : ""
            let nTotal = sr?.total ?? 0, nPt = sr?.pt.count ?? 0, nRefs = sr?.refs.count ?? 0
            self?.log(idLine + "; addon: \(nTotal) itens, PT=\(nPt), referências=\(nRefs)" + guess + ", hash igual=\(hashHits)")

            var merged: [FoundSub] = []
            var seen = Set<String>()
            for f in (sr?.pt ?? []) where seen.insert(f.url.absoluteString).inserted { merged.append(f) }
            for u in given where seen.insert(u.absoluteString).inserted { merged.append(FoundSub(url: u, hashMatch: false, lang: "por")) }
            if merged.count > 14 { merged = Array(merged.prefix(14)) }
            self?.log("candidatas para baixar: \(merged.count) (do site: \(given.count))")

            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("frameplay-legendas", isDirectory: true)
            try? FileManager.default.removeItem(at: dir)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

            // referências de tempo, baixadas ao mesmo tempo que as legendas PT
            let refList = sr?.refs ?? []
            var refResults = [[Int64]?](repeating: nil, count: refList.count)
            let lock = NSLock()
            let rgroup = DispatchGroup()
            for (i, r) in refList.enumerated() {
                rgroup.enter()
                DispatchQueue.global(qos: .userInitiated).async {
                    let st = PlayerWindowController.fetchStarts(r.url)
                    lock.lock()
                    refResults[i] = st
                    lock.unlock()
                    rgroup.leave()
                }
            }
            var ok = PlayerWindowController.fetchAll(merged, dir: dir)
            _ = rgroup.wait(timeout: .now() + 10)
            lock.lock()
            let gotRefs = refResults
            lock.unlock()
            var refStarts: [[Int64]] = []
            var refLang = ""
            for (i, st) in gotRefs.enumerated() {
                if let st = st, st.count >= 20 {
                    refStarts.append(st)
                    if refLang.isEmpty { refLang = refList[i].lang }
                }
            }
            self?.log("legendas PT válidas: \(ok.count) de \(merged.count); referências baixadas: \(refStarts.count)")

            var lg = "alinhamento com \(refStarts.count) referência(s):"
            let nAligned = PlayerWindowController.alignAll(&ok, refStarts, &lg)
            self?.log(lg + "\n  alinhadas: \(nAligned)")
            let refLine: String
            if refStarts.isEmpty { refLine = (sr?.refs.isEmpty ?? true) ? "sem referência" : "referência não baixou" }
            else { refLine = "referência \(PlayerWindowController.langShort(refLang)) ✓" + (nAligned == 0 ? " (nenhuma legenda casou)" : "") }

            let finalOk = ok
            let finalId = idLine
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.cands = finalOk
                self.diagId = finalId
                self.diagRef = refLine
                self.subsDownloaded = true
                self.attachReadySubs()
            }
        }
    }

    private static func fetchAll(_ items: [FoundSub], dir: URL) -> [SubCand] {
        var results = [SubCand?](repeating: nil, count: items.count)
        let lock = NSLock()
        let group = DispatchGroup()
        for (i, f) in items.enumerated() {
            group.enter()
            DispatchQueue.global(qos: .userInitiated).async {
                let c = fetchSubtitle(f, dir: dir, n: i + 1, rank: i)
                lock.lock()
                results[i] = c
                lock.unlock()
                group.leave()
            }
        }
        _ = group.wait(timeout: .now() + 20)
        lock.lock()
        let out = results.compactMap { $0 }
        lock.unlock()
        return out
    }

    /** início das falas (ms) de uma legenda de referência; nil se não for legenda */
    private static func fetchStarts(_ u: URL) -> [Int64]? {
        guard var data = get(u), data.count >= 20 else { return nil }
        if data.count > 2 && data[0] == 0x1F && data[1] == 0x8B {
            guard let d = gunzip(data) else { return nil }
            data = d
        }
        guard let text = decodeText(data), text.contains("-->") else { return nil }
        return SubtitleAlign.cueStarts(text.replacingOccurrences(of: "\u{FEFF}", with: ""))
    }

    /** alinha cada legenda PT à melhor das referências (reescrevendo o arquivo); devolve quantas ficaram alinhadas */
    private static func alignAll(_ all: inout [SubCand], _ refs: [[Int64]], _ lg: inout String) -> Int {
        var n = 0
        if refs.isEmpty { return 0 }
        for i in 0..<all.count {
            let starts = SubtitleAlign.cueStarts(all[i].text)
            var best: SubtitleAlign.Fit? = nil
            for r in refs {
                let f = SubtitleAlign.align(r, starts)
                if best == nil || f.score > (best?.score ?? 0) { best = f }
                if (best?.score ?? 0) >= 0.6 { break } // já está claro: não precisa das outras referências
            }
            guard let b = best else { continue }
            lg += "\n  #\(all[i].rank) alinhamento: nota=\(String(format: "%.2f", b.score)) escala=\(String(format: "%.4f", b.scale)) deslocamento=\(b.offsetMs)ms"
            if !b.ok { continue }
            var c = all[i]
            c.aligned = true
            c.alignScore = b.score
            c.alignScale = b.scale
            c.alignOffsetMs = b.offsetMs
            if b.scale != 1.0 || abs(b.offsetMs) >= 50 {
                let t = SubtitleAlign.transform(c.text, b.scale, b.offsetMs)
                do { try Data(t.utf8).write(to: c.url) } catch { continue }
                c.text = t
                let l = lastCueEnd(t, pattern: cueEndPattern)
                if l > 0 { c.lastMs = l }
            }
            all[i] = c
            n += 1
        }
        return n
    }

    /** baixa uma legenda; devolve nil se não for uma legenda válida em português */
    private static func fetchSubtitle(_ f: FoundSub, dir: URL, n: Int, rank: Int) -> SubCand? {
        guard var data = get(f.url), data.count >= 20 else { return nil }
        if data.count > 2 && data[0] == 0x1F && data[1] == 0x8B {
            guard let d = gunzip(data) else { return nil }
            data = d
        }
        guard var text = decodeText(data) else { return nil }
        text = text.replacingOccurrences(of: "\u{FEFF}", with: "")
        var ext = "srt"
        var last = lastCueEnd(text, pattern: cueEndPattern)
        if last > 0 {
            if text.hasPrefix("WEBVTT") { ext = "vtt" }
        } else {
            last = lastCueEnd(text, pattern: assEndPattern)
            if last > 0 && text.contains("Dialogue:") { ext = "ass" } else { return nil } // não é SRT/VTT/ASS (página de erro, arquivo vazio...)
        }
        if !looksPortuguese(text) { return nil } // legenda de outro idioma com etiqueta errada
        let out = dir.appendingPathComponent("\(n).\(ext)")
        do { try Data(text.utf8).write(to: out) } catch { return nil } // sempre UTF-8: acentos certos no VLC
        return SubCand(url: out, lastMs: last, text: text, ext: ext, rank: rank, hashMatch: f.hashMatch)
    }

    private static func gunzip(_ d: Data) -> Data? {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("fp-\(UUID().uuidString).gz")
        do { try d.write(to: tmp) } catch { return nil }
        defer { try? FileManager.default.removeItem(at: tmp) }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/gunzip")
        p.arguments = ["-c", tmp.path]
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = FileHandle.nullDevice
        do { try p.run() } catch { return nil }
        let out = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        if out.isEmpty { return nil }
        return out.count > 5_000_000 ? Data(out.prefix(5_000_000)) : out
    }

    /** texto da legenda: UTF-16 (com BOM), UTF-8, ou (se tiver bytes inválidos) Windows-1252, comum nas legendas antigas */
    private static func decodeText(_ d: Data) -> String? {
        if d.count >= 2 && d[0] == 0xFF && d[1] == 0xFE { return String(data: d.subdata(in: 2..<d.count), encoding: .utf16LittleEndian) }
        if d.count >= 2 && d[0] == 0xFE && d[1] == 0xFF { return String(data: d.subdata(in: 2..<d.count), encoding: .utf16BigEndian) }
        if let s = String(data: d, encoding: .utf8) { return s }
        if let s = String(data: d, encoding: .windowsCP1252) { return s }
        return String(data: d, encoding: .isoLatin1)
    }

    private static func wordSet(_ s: String) -> Set<String> { Set(s.components(separatedBy: " ")) }
    private static let ptWords = wordSet("não você vocês estou então isso são também muito porque pra obrigado tá nós meu minha ele dela já até bem tudo aqui foi vai tem sim uma")
    private static let enWords = wordSet("the you and that what this with have are not but your i'm it's don't they there just know")
    private static let esWords = wordSet("el los las pero usted esto estoy yo muy gracias señor tengo qué cómo hay soy eres nosotros")
    private static let wordRegex = try? NSRegularExpression(pattern: "[\\p{L}']+")

    /** confere se o texto é mesmo português (conta palavras típicas de português, inglês e espanhol) */
    private static func looksPortuguese(_ full: String) -> Bool {
        guard let re = wordRegex else { return false }
        let text = full.count > 300000 ? String(full.prefix(300000)) : full
        let ns = text as NSString
        var pt = 0, en = 0, es = 0
        for m in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            let w = ns.substring(with: m.range).lowercased()
            if ptWords.contains(w) { pt += 1 }
            if enWords.contains(w) { en += 1 }
            if esWords.contains(w) { es += 1 }
        }
        return pt >= 3 && pt > 2 * max(en, es)
    }

    // os dois padrões têm 4 grupos: horas (opcional no SRT), minutos, segundos e fração
    private static let cueEndPattern = "-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})"
    private static let assEndPattern = "Dialogue:\\s*[^,\\n]*,\\s*\\d+:\\d\\d:\\d\\d[.]\\d+\\s*,\\s*(\\d+):(\\d\\d):(\\d\\d)[.](\\d\\d?)"

    /** fim da última fala da legenda (ms): serve para ver se ela combina com a duração do filme */
    private static func lastCueEnd(_ text: String, pattern: String) -> Int64 {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return 0 }
        let ns = text as NSString
        var best: Int64 = 0
        func num(_ m: NSTextCheckingResult, _ i: Int) -> Int64 {
            let r = m.range(at: i)
            if r.location == NSNotFound || r.length == 0 { return 0 }
            return Int64(ns.substring(with: r)) ?? 0
        }
        for m in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            if m.numberOfRanges < 5 { continue }
            var ms: Int64 = 0
            let fr = m.range(at: 4)
            if fr.location != NSNotFound {
                var f = ns.substring(with: fr)
                while f.count < 3 { f += "0" } // ASS usa centésimos
                ms = Int64(String(f.prefix(3))) ?? 0
            }
            let t = ((num(m, 1) * 60 + num(m, 2)) * 60 + num(m, 3)) * 1000 + ms
            if t > best { best = t }
        }
        return best
    }

    /** legenda PT/BR que veio dentro do arquivo (a primeira que não seja "forçada"); nil se não houver */
    private func findEmbeddedPt() -> Int? {
        let names = (player.videoSubTitlesNames as? [String]) ?? []
        let ids = (player.videoSubTitlesIndexes as? [NSNumber])?.map { $0.intValue } ?? []
        var forced: Int? = nil
        for (i, id) in ids.enumerated() where i < names.count && id != -1 && isPortuguese(names[i]) {
            if embeddedKnown && !embeddedSubs.contains(id) { continue }
            let n = names[i].lowercased()
            if n.contains("forced") || n.contains("forçad") || n.contains("forcad") {
                if forced == nil { forced = id }
                continue
            }
            return id
        }
        return forced
    }

    /** liga as legendas baixadas: as alinhadas à referência primeiro; a legenda PT embutida no arquivo (se houver) fica ativa */
    private func attachReadySubs() {
        guard startedPlaying, subsDownloaded, !subsAttached, !finished else { return }
        let len = Int64(player.media?.length.intValue ?? 0)
        // espera um pouco depois do play, e a duração aparecer (até ~13 s)
        let since = Date().timeIntervalSince(playingSince)
        if since < 1.5 { return }
        if len <= 0 && since < 13 { return }
        subsAttached = true
        embeddedKnown = true
        embeddedSubs = Set(((player.videoSubTitlesIndexes as? [NSNumber]) ?? []).map { $0.intValue })
        embeddedPtId = findEmbeddedPt()
        let embDesc: String = embeddedPtId != nil ? "faixa \(embeddedPtId ?? -1)" : "não há"
        log("legenda PT embutida: " + embDesc)
        if cands.isEmpty { return }
        var lg = "ranking das legendas:"
        let use = PlayerWindowController.rankAll(cands, len: len, lg: &lg)
        log(lg)
        let embeddedFirst = embeddedPtId != nil
        for (i, c) in use.enumerated() where i < 6 {
            _ = player.addPlaybackSlave(c.url, type: .subtitle, enforce: i == 0 && !embeddedFirst)
            addedSubs += 1
            attached.append(c)
            subLabels.append("Português \(subLabels.count + 1)" + (c.aligned ? " (sincronizada)" : c.hashMatch ? " (do hash)" : "") + (c.fixed ? " (corrigida)" : ""))
        }
        if embeddedFirst && !userPickedSub, let id = embeddedPtId { player.currentVideoSubTitleIndex = Int32(id) }
        if let f = use.first {
            let pick: String = embeddedFirst ? "embutida (faixa \(embeddedPtId ?? -1))" : "candidata #\(f.rank)"
            let sc = String(format: "%.2f", f.alignScore)
            log("escolhida: " + pick + "; melhor baixada: #\(f.rank) alinhada=\(f.aligned) nota=" + sc + " deslocamento=\(f.alignOffsetMs)ms hash=\(f.hashMatch) corrigida=\(f.fixed); \(addedSubs) no menu")
        }
        applySubDelay()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            guard let self = self else { return }
            self.applySubDelay()
            if embeddedFirst && !self.userPickedSub, let id = self.embeddedPtId { self.player.currentVideoSubTitleIndex = Int32(id) }
        }
    }

    /** ordem final: primeiro as alinhadas à referência (maior nota primeiro); depois as demais pela heurística de fim da legenda */
    private static func rankAll(_ all: [SubCand], len: Int64, lg: inout String) -> [SubCand] {
        let good = all.filter { $0.aligned }.sorted { (a: SubCand, b: SubCand) -> Bool in
            if a.alignScore != b.alignScore { return a.alignScore > b.alignScore }
            return a.rank < b.rank
        }
        let rest = all.filter { !$0.aligned }
        return good + rankCands(rest, len: len, lg: &lg)
    }

    // ---------- ranking e correção de taxa de quadros ----------
    /** razões entre taxas de quadros que dá para distinguir pelo fim da legenda (24/23,976 é pequena demais: 0,1%) */
    private static let fpsFactors: [Double] = [25.0 / 23.976, 23.976 / 25.0, 25.0 / 24.0, 24.0 / 25.0]

    private static func matchFactor(_ ratio: Double) -> Double {
        var best = 0.0, bestD = 0.012 // tolerância de 1,2%
        for f in fpsFactors {
            let d = abs(ratio - f)
            if d < bestD { bestD = d; best = f }
        }
        return best
    }

    private static func plausible(_ last: Int64, _ len: Int64) -> Bool {
        return last > 0 && Double(last) >= Double(len) * 0.6 && Double(last) <= Double(len) * 1.05
    }

    /** Heurística: a última fala costuma terminar alguns minutos antes do fim do vídeo (créditos), então não
        comparamos com a duração e sim com uma legenda de REFERÊNCIA:
        1) a primeira candidata que o addon marcou como casando com o hash do arquivo (e com duração plausível); ou
        2) se não houver, o maior grupo de candidatas cujo fim da última fala difere menos de 1,5% (mín. 2).
        Uma candidata (que não seja de hash) cujo fim é ~4,27% maior/menor que o da referência (25/23,976 ou 23,976/25;
        também 25/24 e 24/25) foi feita para outra taxa de quadros: reescalamos todos os tempos pelo fator inverso,
        conferimos que agora bate com a referência (1,5%) e marcamos como "corrigida".
        Nota = ajuste à referência (ou a 0,93 da duração, sem referência) + 0,01 por posição na lista do addon
        + 0,01 se corrigida - 0,02 se casa com o hash. Menor = melhor. Fim fora de 0,5x..1,12x da duração é descartado. */
    private static func rankCands(_ all: [SubCand], len: Int64, lg: inout String) -> [SubCand] {
        var sorted = all.sorted { $0.rank < $1.rank }
        if len <= 0 || sorted.isEmpty { return sorted }
        var ref: Int64 = 0
        var why = "sem referência"
        for c in sorted where c.hashMatch && plausible(c.lastMs, len) { ref = c.lastMs; why = "legenda do hash #\(c.rank)"; break }
        if ref == 0 {
            var bestN = 0
            for a in sorted where plausible(a.lastMs, len) {
                var n = 0
                for b in sorted where b.rank != a.rank && b.lastMs > 0 && abs(Double(b.lastMs) / Double(a.lastMs) - 1) <= 0.015 { n += 1 }
                if n > bestN { bestN = n; ref = a.lastMs }
            }
            if ref > 0 { why = "grupo de \(bestN + 1) legendas parecidas" }
        }
        lg += "referência de fim=\(ref)ms (\(why)), duração=\(len)ms"

        if ref > 0 {
            for i in 0..<sorted.count {
                let c = sorted[i]
                if c.hashMatch || c.lastMs <= 0 { continue }
                let f = matchFactor(Double(c.lastMs) / Double(ref))
                if f <= 0 { continue }
                let fixed = rescale(c.text, 1.0 / f)
                let nl = c.ext == "ass" ? lastCueEnd(fixed, pattern: assEndPattern) : lastCueEnd(fixed, pattern: cueEndPattern)
                if nl <= 0 || abs(Double(nl) / Double(ref) - 1) > 0.015 { continue }
                do { try Data(fixed.utf8).write(to: c.url) } catch { continue }
                lg += "\n  #\(c.rank) corrigida: fim \(c.lastMs)->\(nl)ms (fator \(String(format: "%.4f", f)))"
                sorted[i].text = fixed
                sorted[i].lastMs = nl
                sorted[i].fixed = true
            }
        }

        var use = sorted.filter { $0.lastMs <= 0 || (Double($0.lastMs) >= Double(len) * 0.5 && Double($0.lastMs) <= Double(len) * 1.12) }
        if use.isEmpty { use = sorted }
        for i in 0..<use.count {
            var fit = 0.3
            if use[i].lastMs > 0 {
                let d = ref > 0 ? abs(Double(use[i].lastMs) / Double(ref) - 1) : abs(Double(use[i].lastMs) / Double(len) - 0.93)
                fit = min(0.3, max(0, d - 0.005))
            }
            use[i].score = fit + 0.01 * Double(use[i].rank) + (use[i].fixed ? 0.01 : 0) - (use[i].hashMatch ? 0.02 : 0)
            lg += "\n  #\(use[i].rank) fim=\(use[i].lastMs)ms hash=\(use[i].hashMatch) corrigida=\(use[i].fixed) nota=\(String(format: "%.4f", use[i].score))"
        }
        use.sort { $0.score < $1.score }
        return use
    }

    /** multiplica por k todos os tempos das falas (SRT/VTT: linhas "a --> b"; ASS: linhas "Dialogue:") */
    private static func rescale(_ text: String, _ k: Double) -> String {
        let lines = text.components(separatedBy: "\n")
        var out: [String] = []
        out.reserveCapacity(lines.count)
        for ln in lines {
            if ln.contains("-->") { out.append(scaleStamps(ln, pattern: "(?:(\\d+):)?(\\d{1,2}):(\\d\\d)([,.])(\\d{3})", k: k, limit: 100, ass: false)) }
            else if ln.hasPrefix("Dialogue:") { out.append(scaleStamps(ln, pattern: "(\\d+):(\\d\\d):(\\d\\d)[.](\\d\\d?)", k: k, limit: 2, ass: true)) }
            else { out.append(ln) }
        }
        return out.joined(separator: "\n")
    }

    private static func scaleStamps(_ line: String, pattern: String, k: Double, limit: Int, ass: Bool) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return line }
        let ns = line as NSString
        let result = NSMutableString(string: line)
        let found = Array(re.matches(in: line, range: NSRange(location: 0, length: ns.length)).prefix(limit))
        for m in found.reversed() {
            func grp(_ i: Int) -> String {
                let r = m.range(at: i)
                return r.location == NSNotFound ? "" : ns.substring(with: r)
            }
            let h = Int64(grp(1)) ?? 0
            let mi = Int64(grp(2)) ?? 0
            let se = Int64(grp(3)) ?? 0
            let rep: String
            if ass {
                let frac = grp(4)
                let cs = (Int64(frac) ?? 0) * (frac.count == 1 ? 10 : 1)
                let total = (((h * 60 + mi) * 60 + se) * 100 + cs)
                let n = Int64((Double(total) * k).rounded())
                rep = String(format: "%lld:%02lld:%02lld.%02lld", n / 360000, (n / 6000) % 60, (n / 100) % 60, n % 100)
            } else {
                let ms = ((h * 60 + mi) * 60 + se) * 1000 + (Int64(grp(5)) ?? 0)
                let n = Int64((Double(ms) * k).rounded())
                rep = String(format: "%02lld:%02lld:%02lld%@%03lld", n / 3600000, (n / 60000) % 60, (n / 1000) % 60, grp(4), n % 1000)
            }
            result.replaceCharacters(in: m.range, with: rep)
        }
        return result as String
    }

    /** liga a PT/BR que vier dentro do arquivo (vem perfeitamente sincronizada); se não houver nada em português, desliga */
    private func selectEmbeddedPortuguese() {
        guard !embeddedCheckDone, !finished else { return }
        embeddedCheckDone = true
        if let id = findEmbeddedPt() {
            embeddedPtId = id
            if !userPickedSub { player.currentVideoSubTitleIndex = Int32(id) }
            return
        }
        if addedSubs > 0 { return }
        player.currentVideoSubTitleIndex = -1
    }

    private func greyItem(_ text: String) -> NSMenuItem {
        let it = NSMenuItem(title: text, action: nil, keyEquivalent: "")
        it.isEnabled = false
        it.attributedTitle = NSAttributedString(string: text, attributes: [
            .foregroundColor: NSColor.secondaryLabelColor, .font: NSFont.systemFont(ofSize: 11)])
        return it
    }

    @objc private func chooseSubtitles(_ sender: NSButton) {
        let names = (player.videoSubTitlesNames as? [String]) ?? []
        let ids = (player.videoSubTitlesIndexes as? [NSNumber])?.map { $0.intValue } ?? []
        let current = Int(player.currentVideoSubTitleIndex)
        var rows: [(String, Int)] = []
        var chosen: String? = current == -1 ? "desativada" : nil
        var n = 0
        for (i, id) in ids.enumerated() where i < names.count {
            if id == -1 { rows.append(("Desativado", id)); continue }
            if isDownloaded(id) {
                n += 1
                let title = n <= subLabels.count ? subLabels[n - 1] : "Português \(n)"
                rows.append((title, id))
                if id == current && n <= attached.count {
                    let c = attached[n - 1]
                    var adj = "sem ajuste"
                    if c.aligned {
                        adj = "ajuste \(PlayerWindowController.signedSecs(c.alignOffsetMs))"
                        if c.alignScale != 1.0 { adj += ", velocidade ×" + String(format: "%.4f", c.alignScale).replacingOccurrences(of: ".", with: ",") }
                    } else if c.fixed { adj = "velocidade corrigida" }
                    chosen = "\(title) (\(adj))"
                }
            } else if isPortuguese(names[i]) {
                rows.append(("Embutida: \(names[i])", id))
                if id == current { chosen = "Embutida: \(names[i])" }
            }
        }
        let menu = NSMenu()
        menu.addItem(greyItem(diagId))
        var second = !subsDownloaded ? "baixando legendas…" : (cands.count == 1 ? "1 legenda PT" : "\(cands.count) legendas PT")
        if !diagRef.isEmpty { second += " · " + diagRef }
        menu.addItem(greyItem(second))
        if let c = chosen { menu.addItem(greyItem("escolhida: " + c + (subDelayMs != 0 ? " · atraso " + PlayerWindowController.signedSecs(Int64(subDelayMs)) : ""))) }
        menu.addItem(NSMenuItem.separator())
        if rows.count <= 1 {
            menu.addItem(withTitle: !subsDownloaded ? "Procurando legendas em português…" : "Não há legendas em português", action: nil, keyEquivalent: "")
        } else {
            for (title, id) in rows {
                let item = NSMenuItem(title: title, action: #selector(pickSubtitle(_:)), keyEquivalent: "")
                item.target = self
                item.tag = id
                item.state = current == id ? .on : .off
                menu.addItem(item)
            }
            menu.addItem(NSMenuItem.separator())
            let sync = NSMenuItem(title: "Sincronizar legenda… (\(delayLabel()))", action: #selector(showSyncSheet), keyEquivalent: "")
            sync.target = self
            menu.addItem(sync)
        }
        menu.popUp(positioning: nil, at: NSPoint(x: 0, y: sender.bounds.height + 4), in: sender)
        showControls()
    }
    @objc private func pickSubtitle(_ item: NSMenuItem) {
        userPickedSub = true
        player.currentVideoSubTitleIndex = Int32(item.tag)
    }

    // ---------- ajuste manual do atraso da legenda ----------
    // VLCKit: currentVideoSubTitleDelay em microssegundos; positivo = a legenda aparece MAIS TARDE
    private func delayKey() -> String { "fp-subdelay-" + (metaId.isEmpty ? positionKey() : metaId) }

    private func applySubDelay() { player.currentVideoSubTitleDelay = subDelayMs * 1000 }

    private func delayLabel() -> String {
        return "Atraso da legenda: " + (subDelayMs == 0 ? "0,0 s" : PlayerWindowController.signedSecs(Int64(subDelayMs)))
    }

    private func changeSubDelay(_ delta: Int, reset: Bool) {
        subDelayMs = reset ? 0 : max(-60000, min(60000, subDelayMs + delta))
        UserDefaults.standard.set(subDelayMs, forKey: delayKey())
        applySubDelay()
        delayValueLabel?.stringValue = delayLabel()
        log("atraso da legenda: \(subDelayMs) ms")
    }
    /** o tag do botão é o passo em ms; 0 = repor */
    @objc private func syncStep(_ sender: NSButton) { changeSubDelay(sender.tag, reset: sender.tag == 0) }

    @objc private func showSyncSheet() {
        guard let w = window else { return }
        let value = NSTextField(labelWithString: delayLabel())
        value.font = .boldSystemFont(ofSize: 18)
        value.alignment = .center
        delayValueLabel = value
        func syncButton(_ title: String, _ step: Int) -> NSButton {
            let b = NSButton(title: title, target: self, action: #selector(syncStep(_:)))
            b.bezelStyle = .rounded
            b.tag = step
            return b
        }
        let cap1 = NSTextField(labelWithString: "A legenda aparece ANTES da fala:")
        let row1 = NSStackView(views: [syncButton("Atrasar +0,5 s", 500), syncButton("Atrasar +5 s", 5000)])
        let cap2 = NSTextField(labelWithString: "A legenda aparece DEPOIS da fala:")
        let row2 = NSStackView(views: [syncButton("Adiantar −0,5 s", -500), syncButton("Adiantar −5 s", -5000)])
        for r in [row1, row2] { r.orientation = .horizontal; r.spacing = 10 }
        let stack = NSStackView(views: [value, cap1, row1, cap2, row2, syncButton("Repor", 0)])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 8
        stack.setFrameSize(NSSize(width: 340, height: 190))
        let alert = NSAlert()
        alert.messageText = "Sincronizar legenda"
        alert.accessoryView = stack
        alert.addButton(withTitle: "Fechar")
        alert.beginSheetModal(for: w) { [weak self] _ in self?.delayValueLabel = nil }
        showControls()
    }

    private static func get(_ url: URL) -> Data? {
        var req = URLRequest(url: url, timeoutInterval: 12)
        req.setValue("FramePlay", forHTTPHeaderField: "User-Agent")
        let sem = DispatchSemaphore(value: 0)
        var out: Data?
        URLSession.shared.dataTask(with: req) { data, resp, _ in
            if let h = resp as? HTTPURLResponse, h.statusCode < 400, let d = data { out = d.count > 5_000_000 ? Data(d.prefix(5_000_000)) : d }
            sem.signal()
        }.resume()
        _ = sem.wait(timeout: .now() + 15)
        return out
    }

    // ---------- hash do OpenSubtitles ----------
    private static let chunkSize = 65536

    private struct RangeResult {
        var data: Data? = nil
        var total: Int64 = -1
        var finalURL: URL? = nil
        var disposition: String? = nil
        var error = ""
    }

    /** coleta no máximo `need` bytes de uma resposta com Range e cancela o resto (um servidor sem Range mandaria o arquivo inteiro);
        segue os redirecionamentos do debrid mantendo o Range */
    private final class RangeFetcher: NSObject, URLSessionDataDelegate {
        var data = Data()
        var status = 0
        var total: Int64 = -1
        var finalURL: URL?
        var disposition: String?
        var failure = ""
        var cancelledByUs = false
        let sem = DispatchSemaphore(value: 0)
        let need: Int
        let mustBePartial: Bool
        let range: String
        init(need: Int, mustBePartial: Bool, range: String) { self.need = need; self.mustBePartial = mustBePartial; self.range = range }

        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            var r = request
            r.setValue(range, forHTTPHeaderField: "Range")
            r.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            completionHandler(r)
        }
        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
            if let h = response as? HTTPURLResponse {
                status = h.statusCode
                finalURL = h.url
                disposition = h.value(forHTTPHeaderField: "Content-Disposition")
                if let cr = h.value(forHTTPHeaderField: "Content-Range"), let slash = cr.lastIndex(of: "/") {
                    total = Int64(cr[cr.index(after: slash)...].trimmingCharacters(in: .whitespaces)) ?? -1
                }
                if total < 0 && status == 200 { total = Int64(h.value(forHTTPHeaderField: "Content-Length") ?? "") ?? -1 }
                if !(status == 206 || (status == 200 && !mustBePartial)) {
                    failure = status == 200 ? "servidor sem suporte a Range" : "HTTP \(status)"
                    cancelledByUs = true
                    completionHandler(.cancel)
                    return
                }
            }
            completionHandler(.allow)
        }
        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            self.data.append(data)
            if self.data.count >= need { cancelledByUs = true; dataTask.cancel() }
        }
        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            if let e = error, !cancelledByUs { failure = e.localizedDescription }
            sem.signal()
        }
    }

    private static func getRange(_ url: URL, _ range: String, mustBePartial: Bool) -> RangeResult {
        var res = RangeResult()
        let fetcher = RangeFetcher(need: chunkSize, mustBePartial: mustBePartial, range: range)
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 12 // o debrid pode demorar para resolver o link
        let session = URLSession(configuration: cfg, delegate: fetcher, delegateQueue: nil)
        var req = URLRequest(url: url, timeoutInterval: 12)
        req.setValue(range, forHTTPHeaderField: "Range")
        req.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        req.setValue("FramePlay", forHTTPHeaderField: "User-Agent")
        let task = session.dataTask(with: req)
        task.resume()
        if fetcher.sem.wait(timeout: .now() + 15) == .timedOut {
            task.cancel()
            session.invalidateAndCancel()
            res.error = "tempo esgotado"
            return res
        }
        session.finishTasksAndInvalidate()
        if !(fetcher.status == 206 || (fetcher.status == 200 && !mustBePartial)) {
            res.error = fetcher.failure.isEmpty ? "HTTP \(fetcher.status)" : fetcher.failure
            return res
        }
        res.data = Data(fetcher.data.prefix(chunkSize))
        res.total = fetcher.total
        res.finalURL = fetcher.finalURL
        res.disposition = fetcher.disposition
        return res
    }

    private static func headSize(_ url: URL) -> Int64 {
        var req = URLRequest(url: url, timeoutInterval: 6)
        req.httpMethod = "HEAD"
        req.setValue("FramePlay", forHTTPHeaderField: "User-Agent")
        let sem = DispatchSemaphore(value: 0)
        var size: Int64 = -1
        URLSession.shared.dataTask(with: req) { _, resp, _ in
            if let h = resp as? HTTPURLResponse, h.statusCode < 400 { size = Int64(h.value(forHTTPHeaderField: "Content-Length") ?? "") ?? -1 }
            sem.signal()
        }.resume()
        _ = sem.wait(timeout: .now() + 7)
        return size
    }

    private static func sum64(_ d: Data) -> UInt64 {
        let b = [UInt8](d)
        var s: UInt64 = 0
        var i = 0
        while i + 8 <= b.count {
            var w: UInt64 = 0
            for k in 0..<8 { w |= UInt64(b[i + k]) << UInt64(8 * k) } // little-endian
            s = s &+ w
            i += 8
        }
        return s
    }

    /** nome do arquivo no Content-Disposition (filename*=UTF-8''... ou filename="...") */
    private static func dispositionName(_ cd: String?) -> String? {
        guard let cd = cd else { return nil }
        let ns = cd as NSString
        let full = NSRange(location: 0, length: ns.length)
        if let re = try? NSRegularExpression(pattern: "filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", options: .caseInsensitive),
           let m = re.firstMatch(in: cd, range: full), m.numberOfRanges > 1 {
            let raw = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces)
            return raw.removingPercentEncoding ?? raw
        }
        if let re = try? NSRegularExpression(pattern: "filename\\s*=\\s*\"?([^\";]+)\"?", options: .caseInsensitive),
           let m = re.firstMatch(in: cd, range: full), m.numberOfRanges > 1 {
            return ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces)
        }
        return nil
    }

    /** hash = tamanho + soma das palavras de 64 bits dos primeiros e dos últimos 64 KiB, módulo 2^64 */
    private static func computeHash(_ url: URL) -> VideoHash {
        var v = VideoHash()
        guard url.scheme == "http" || url.scheme == "https" else { v.error = "link não é http"; return v }
        let a = getRange(url, "bytes=0-\(chunkSize - 1)", mustBePartial: false)
        guard let ad = a.data else { v.error = "início do arquivo: \(a.error)"; return v }
        if ad.count != chunkSize { v.error = "início do arquivo curto (\(ad.count) bytes)"; return v }
        v.headerName = dispositionName(a.disposition)
        v.finalURL = a.finalURL
        var size = a.total
        if size < 0 { size = headSize(a.finalURL ?? url) }
        if size < Int64(chunkSize) { v.error = "tamanho do arquivo desconhecido"; return v }
        let range = "bytes=\(size - Int64(chunkSize))-\(size - 1)"
        // o link final já está resolvido pelo debrid: usa direto; se falhar, tenta o link original
        var b = getRange(a.finalURL ?? url, range, mustBePartial: true)
        if b.data == nil, let fu = a.finalURL, fu != url { b = getRange(url, range, mustBePartial: true) }
        guard let bd = b.data else { v.error = "fim do arquivo: \(b.error)"; return v }
        if bd.count != chunkSize { v.error = "fim do arquivo curto (\(bd.count) bytes)"; return v }
        let h = UInt64(size) &+ sum64(ad) &+ sum64(bd)
        v.hash = String(format: "%016llx", h)
        v.size = size
        return v
    }

    /** nunca devolve nil: `error` diz o que houve */
    private static func computeHashTimeboxed(_ url: URL, seconds: Double) -> VideoHash {
        let lock = NSLock()
        var result: VideoHash? = nil
        let sem = DispatchSemaphore(value: 0)
        DispatchQueue.global(qos: .userInitiated).async {
            let r = computeHash(url)
            lock.lock()
            result = r
            lock.unlock()
            sem.signal()
        }
        if sem.wait(timeout: .now() + seconds) == .timedOut {
            var t = VideoHash()
            t.error = "tempo esgotado (\(Int(seconds)) s)"
            return t
        }
        lock.lock()
        defer { lock.unlock() }
        return result ?? VideoHash()
    }

    private static let videoExts = ["mkv", "mp4", "avi", "m4v", "mov", "wmv", "ts", "webm", "mpg", "mpeg"]
    private static func videoLike(_ name: String?) -> String? {
        guard let n = name, !n.isEmpty else { return nil }
        let ext = (n as NSString).pathExtension.lowercased()
        return videoExts.contains(ext) && n.count > ext.count + 1 ? n : nil
    }

    /** nome do arquivo: último trecho do caminho do link ORIGINAL (decodificado; nos links do Torrentio é o nome real da release),
        senão o Content-Disposition, senão o último trecho do link final; nil se nenhum parecer um vídeo */
    private static func fileName(of url: URL, _ vh: VideoHash) -> String? {
        if let n = videoLike(url.lastPathComponent) { return n }
        if let n = videoLike(vh.headerName) { return n }
        if let fu = vh.finalURL, let n = videoLike(fu.lastPathComponent) { return n }
        return nil
    }

    private static func encodeComponent(_ s: String, keep: String = "") -> String {
        s.addingPercentEncoding(withAllowedCharacters: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~" + keep))) ?? s
    }

    /** extras no formato do Stremio (videoHash=...&videoSize=...&filename=...), só com o que temos */
    private static func makeExtras(_ vh: VideoHash, _ filename: String?) -> String {
        var parts: [String] = []
        if let h = vh.hash { parts.append("videoHash=\(h)"); parts.append("videoSize=\(vh.size)") }
        if let f = filename { parts.append("filename=\(encodeComponent(f))") }
        return parts.joined(separator: "&")
    }

    private static let ptLangs: Set<String> = ["pob", "pt-br", "ptb", "ptbr", "por", "pt", "pt-pt"]

    /** legendas do OpenSubtitles do Stremio: português (na ordem do addon, hash primeiro) e candidatas a referência (outro idioma) */
    private static func searchOpenSubtitles(type: String, id: String, extras: String) -> SearchResult {
        var res = SearchResult()
        let path = "https://opensubtitles-v3.strem.io/subtitles/\(encodeComponent(type))/\(encodeComponent(id, keep: ":"))"
            + (extras.isEmpty ? "" : "/" + extras) + ".json"
        guard let api = URL(string: path),
              let data = get(api),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let list = json["subtitles"] as? [[String: Any]] else { return res }
        res.ok = true
        res.total = list.count
        var others: [FoundSub] = []
        for s in list {
            let lang = (s["lang"] as? String ?? "").lowercased()
            guard let u = (s["url"] as? String).flatMap(URL.init(string:)) else { continue }
            if s["m"] != nil { res.sawMatchField = true }
            // o addon marca com m="f" as que casam com o hash do arquivo (m="i" = só o título)
            let hash = (s["m"] as? String) == "f"
            if ptLangs.contains(lang) { if res.pt.count < 10 { res.pt.append(FoundSub(url: u, hashMatch: hash, lang: lang)) } }
            else { others.append(FoundSub(url: u, hashMatch: hash, lang: lang)) }
        }
        // referência: de preferência inglês (eng) com hash; até 3
        for pass in 0..<2 {
            for f in others where res.refs.count < 3 {
                let eng = f.lang == "eng" || f.lang == "en"
                if f.hashMatch && ((pass == 0) == eng) { res.refs.append(f) }
            }
        }
        if res.refs.isEmpty && !res.sawMatchField {
            // sem o campo "m" não dá para saber quem casa com o hash: usa as primeiras (inglês antes) e o alinhamento confere
            res.refsAssumed = true
            for f in others where (f.lang == "eng" || f.lang == "en") && res.refs.count < 2 { res.refs.append(f) }
            for f in others where res.refs.count < 2 && !res.refs.contains(where: { $0.url == f.url }) { res.refs.append(f) }
        }
        return res
    }

    // ---------- fechar ----------
    @objc func closePlayer() { window?.performClose(nil) }

    func windowWillClose(_ notification: Notification) { finish() }

    override func close() {
        finish()
        super.close()
    }

    private func finish() {
        if finished { return }
        finished = true
        timer?.invalidate()
        timer = nil
        let t = Int64(player.time.intValue)
        if t > 0 { lastTime = t }
        if ended { lastTime = lastLength }
        if !ended && lastTime > 0 && metaId.isEmpty { UserDefaults.standard.set(Double(lastTime), forKey: positionKey()) }
        player.stop()
        player.drawable = nil
        onFinish?(lastTime, lastLength, ended)
        onFinish = nil
    }

    private func format(_ ms: Int64) -> String {
        let s = max(0, ms) / 1000
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }
}


/** Alinhamento de legendas por referência: procura t' = t * escala + deslocamento que põe os INÍCIOS das falas da
    candidata em cima dos da legenda de referência (outro idioma, mesmo hash). Não usa nome, id nem duração do título. */
enum SubtitleAlign {
    static let scales: [Double] = [1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0]
    static let coarseRange: Int64 = 120000
    static let coarseBin: Int64 = 250
    static let tolerance: Int64 = 300
    static let minScore = 0.35
    static let scaleMargin = 0.05

    struct Fit {
        var scale = 1.0
        var offsetMs: Int64 = 0
        var score = 0.0
        var ok: Bool { return score >= SubtitleAlign.minScore }
    }

    private static let startRegex = try? NSRegularExpression(
        pattern: "^[ \\t]*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})[ \\t]*-->", options: [.anchorsMatchLines])

    /** início de cada fala (ms), em ordem crescente (SRT ou VTT); no máximo 6000 */
    static func cueStarts(_ text: String) -> [Int64] {
        guard let re = startRegex else { return [] }
        let ns = text as NSString
        var out: [Int64] = []
        for m in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            if out.count >= 6000 { break }
            func g(_ i: Int) -> Int64 {
                let r = m.range(at: i)
                if r.location == NSNotFound || r.length == 0 { return 0 }
                return Int64(ns.substring(with: r)) ?? 0
            }
            out.append(((g(1) * 60 + g(2)) * 60 + g(3)) * 1000 + g(4))
        }
        out.sort()
        return out
    }

    private static let tsRegex = try? NSRegularExpression(pattern: "(?:(\\d+):)?(\\d{1,2}):(\\d\\d)([,.])(\\d{3})")

    /** novo tempo = tempo * k + offsetMs em todas as linhas "a --> b" (SRT/VTT); o resto do texto não muda */
    static func transform(_ text: String, _ k: Double, _ offsetMs: Int64) -> String {
        let lines = text.components(separatedBy: "\n")
        var out: [String] = []
        out.reserveCapacity(lines.count)
        for ln in lines { out.append(ln.contains("-->") ? transformLine(ln, k, offsetMs) : ln) }
        return out.joined(separator: "\n")
    }

    private static func transformLine(_ line: String, _ k: Double, _ offsetMs: Int64) -> String {
        guard let re = tsRegex else { return line }
        let ns = line as NSString
        let result = NSMutableString(string: line)
        let found = re.matches(in: line, range: NSRange(location: 0, length: ns.length))
        for m in found.reversed() {
            func grp(_ i: Int) -> String {
                let r = m.range(at: i)
                return r.location == NSNotFound ? "" : ns.substring(with: r)
            }
            let h = Int64(grp(1)) ?? 0
            let mi = Int64(grp(2)) ?? 0
            let se = Int64(grp(3)) ?? 0
            let ms = ((h * 60 + mi) * 60 + se) * 1000 + (Int64(grp(5)) ?? 0)
            let n = max(0, Int64((Double(ms) * k).rounded()) + offsetMs)
            let rep = String(format: "%02lld:%02lld:%02lld%@%03lld", n / 3600000, (n / 60000) % 60, (n / 1000) % 60, grp(4), n % 1000)
            result.replaceCharacters(in: m.range, with: rep)
        }
        return result as String
    }

    /** índice do primeiro elemento >= v */
    private static func lowerBound(_ a: [Int64], _ v: Int64) -> Int {
        var lo = 0, hi = a.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if a[mid] < v { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }

    private static func countMatches(_ ref: [Int64], _ cand: [Int64], _ scale: Double, _ off: Int64) -> Int {
        var hits = 0
        for c in cand {
            let t = Int64((Double(c) * scale).rounded()) + off
            let i = lowerBound(ref, t)
            var hit = false
            if i < ref.count && ref[i] - t <= tolerance { hit = true }
            if !hit && i > 0 && t - ref[i - 1] <= tolerance { hit = true }
            if hit { hits += 1 }
        }
        return hits
    }

    /** melhor deslocamento para uma escala: histograma grosso (-120..+120 s) e depois refino de ±1 s em passos de 50 ms */
    private static func alignWithScale(_ ref: [Int64], _ cand: [Int64], _ scale: Double) -> Fit {
        let bins = Int(2 * coarseRange / coarseBin) + 3
        var hist = [Int](repeating: 0, count: bins)
        for c in cand {
            let t = Int64((Double(c) * scale).rounded())
            var i = lowerBound(ref, t - coarseRange)
            while i < ref.count && ref[i] <= t + coarseRange {
                let off = ref[i] - t
                let b = Int((off + coarseRange) / coarseBin) + 1
                if b >= 0 && b < bins { hist[b] += 1 }
                i += 1
            }
        }
        var bestBin = 1, bestVal = -1
        for b in 1..<(bins - 1) {
            let v = hist[b - 1] + hist[b] + hist[b + 1] // 750 ms de largura
            if v > bestVal { bestVal = v; bestBin = b }
        }
        let center = Int64(bestBin - 1) * coarseBin - coarseRange + coarseBin / 2
        var bestCount = -1
        var bestOffs: [Int64] = []
        var off = center - 1000
        while off <= center + 1000 {
            let cnt = countMatches(ref, cand, scale, off)
            if cnt > bestCount { bestCount = cnt; bestOffs = [off] }
            else if cnt == bestCount { bestOffs.append(off) }
            off += 50
        }
        var f = Fit()
        f.scale = scale
        f.offsetMs = bestOffs.isEmpty ? 0 : bestOffs[bestOffs.count / 2]
        let denom = max(1, min(cand.count, ref.count))
        f.score = Double(max(0, bestCount)) / Double(denom)
        return f
    }

    /** melhor transformação (escala x deslocamento) que leva a candidata para cima da referência; score < minScore = não confiável */
    static func align(_ ref: [Int64], _ cand: [Int64]) -> Fit {
        if ref.count < 20 || cand.count < 20 { return Fit() }
        var best = alignWithScale(ref, cand, scales[0])
        var bestOther: Fit? = nil
        for i in 1..<scales.count {
            let f = alignWithScale(ref, cand, scales[i])
            if bestOther == nil || f.score > (bestOther?.score ?? 0) { bestOther = f }
        }
        if let o = bestOther, o.score > best.score + scaleMargin { best = o }
        return best
    }
}
