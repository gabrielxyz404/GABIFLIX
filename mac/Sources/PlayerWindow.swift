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

    // legendas baixadas (arquivos já em UTF-8) e as que já vinham no vídeo
    private var cands: [SubtitleSync.Cand] = []
    private var subsDownloaded = false
    private var subsAttached = false
    private var addedSubs = 0
    private var embeddedSubs = Set<Int>()
    private var embeddedKnown = false
    private var embeddedCheckDone = false
    private var subLabels: [String] = []
    /** ajuste manual da legenda (ms; positivo = a legenda aparece mais tarde), lembrado por título */
    private var subDelayMs = 0
    private var srcHash: String? = nil
    private var srcName: String? = nil
    private var refTrusted = false
    private var diagHash = "…"
    private var diagEmb = ""
    /** última combinação vídeo+fonte+legenda para a qual o atraso foi carregado; "\0" = ainda nenhuma */
    private var lastSyncKey = "\u{0}"
    private var announcedKey = ""
    private var scaleMode = 0
    private var scaleButton: NSButton?
    private var lastScale = (-1, -1, -1)
    private var scaleForce = 0
    private let noticeLabel = NSTextField(labelWithString: "")
    private var noticeUntil = Date.distantPast
    private var syncPanel: NSView?
    private var syncRows: [[SyncButton]] = []
    private var syncFocus = (0, 0)
    private var syncValue: NSTextField?
    private var enterStart = Date.distantPast
    private var enterLast = Date.distantPast
    private var speechPanel: NSView?
    private var speechTable: NSTableView?
    var speechCues: [SubtitleAlign.Cue] = []
    private var speechNow: Int64 = 0
    private var speechWasPlaying = false
    private var attached: [SubtitleSync.Cand] = []
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
        w.minSize = NSSize(width: 820, height: 460) // abaixo disso a linha de botões espremia a linha do tempo (29 px a 700 px de janela)
        w.isReleasedWhenClosed = false
        super.init(window: w)
        w.delegate = self
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

        noticeLabel.textColor = .white
        noticeLabel.font = .boldSystemFont(ofSize: 15)
        noticeLabel.alignment = .center
        noticeLabel.drawsBackground = true
        noticeLabel.backgroundColor = NSColor(white: 0, alpha: 0.7)
        noticeLabel.isHidden = true
        noticeLabel.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(noticeLabel)

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
        scaleButton = scale
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
            noticeLabel.centerXAnchor.constraint(equalTo: root.centerXAnchor),
            noticeLabel.topAnchor.constraint(equalTo: root.topAnchor, constant: 84),
            noticeLabel.widthAnchor.constraint(lessThanOrEqualTo: root.widthAnchor, constant: -80),

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
        media.addOption(":sub-text-scale=90") // como no Android (--sub-text-scale=90)
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
                scaleForce = 6
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
        if scaleForce > 0 { scaleForce -= 1; applyScale(force: true) } else { applyScale() }
        if !noticeLabel.isHidden && Date() > noticeUntil { noticeLabel.isHidden = true }

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
        if syncPanel != nil || speechPanel != nil { return panelKey(e) }
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
    /** baixa as legendas (porte do loadSubtitlesInBackground do Android): hash do arquivo (como o Stremio desktop), busca no OpenSubtitles
        do Stremio com hash/tamanho/nome (os resultados do hash vêm primeiro) mais as do site; alinha cada uma à referência de tempo
        (outro idioma com o mesmo hash, ou a PT do hash); cada uma vira UTF-8 e só fica se for legenda de verdade e em português */
    private func loadSubtitles() {
        let given = subtitleURLs.map { $0.absoluteString }
        let type = metaType, id = metaId
        let videoURL = url
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let vh = PlayerWindowController.computeHashTimeboxed(videoURL, seconds: 25)
            let fname = PlayerWindowController.fileName(of: videoURL, vh)
            DispatchQueue.main.async { self?.srcHash = vh.hash; self?.srcName = fname }
            if let h = vh.hash { self?.log("hash=\(h) tamanho=\(vh.size) arquivo=\(fname ?? "?")") }
            else { self?.log("hash não calculado: \(vh.error); arquivo=\(fname ?? "?")") }

            var sr: SubtitleSync.SearchResult? = nil
            if !type.isEmpty && !id.isEmpty {
                let extras = PlayerWindowController.makeExtras(vh, fname)
                if !extras.isEmpty { sr = PlayerWindowController.search(type, id, extras) }
                if sr == nil || (sr?.pt.isEmpty ?? true) {
                    let plain = PlayerWindowController.search(type, id, "")
                    if let cur = sr {
                        cur.pt.append(contentsOf: plain.pt)
                        if cur.refs.isEmpty { cur.refs.append(contentsOf: plain.refs); cur.refsAssumed = plain.refsAssumed }
                        if !cur.ok { cur.ok = plain.ok }
                    } else { sr = plain }
                }
            }
            var hashHits = 0
            if let s = sr {
                for f in s.pt where f.hashMatch { hashHits += 1 }
                for f in s.refs where f.hashMatch { hashHits += 1 }
            }
            let hashLine = hashHits > 0 ? "sim" : vh.hash != nil ? "calculado, sem casamento" : "não"
            let idLine: String
            if vh.hash == nil { idLine = "Vídeo não identificado (sem hash: \(PlayerWindowController.shortReason(vh.error)))" }
            else if sr == nil || sr?.ok != true { idLine = "Hash calculado, mas a busca de legendas falhou" }
            else if sr?.sawMatchField != true { idLine = "Hash calculado (o addon não confirma a correspondência)" }
            else if hashHits > 0 { idLine = "Vídeo identificado ✓" }
            else { idLine = "Hash calculado, sem correspondência no OpenSubtitles" }
            var addonLine = ""
            if let s = sr { addonLine = "; addon: \(s.total) itens, PT=\(s.pt.count), referências=\(s.refs.count)" + (s.refsAssumed ? " (palpite)" : "") + ", hash igual=\(hashHits)" }
            self?.log(idLine + addonLine)

            var merged: [SubtitleSync.Found] = []
            var seen = Set<String>()
            for f in (sr?.pt ?? []) where seen.insert(f.url).inserted { merged.append(f) }
            for u in given where u.hasPrefix("http") && seen.insert(u).inserted { merged.append(SubtitleSync.Found(u, false, "por")) }
            while merged.count > 14 { merged.removeLast() }
            self?.log("candidatas para baixar: \(merged.count) (do site: \(given.count))")

            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("frameplay-legendas", isDirectory: true)
            try? FileManager.default.removeItem(at: dir)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

            // referências de tempo (outro idioma, nunca mostradas), baixadas ao mesmo tempo que as legendas PT.
            // Referência "de palpite" (o addon não diz quem casa com o hash): outra versão do filme daria um tempo errado com cara de certo; na dúvida não usa
            var refList: [SubtitleSync.Found] = []
            if let s = sr, !s.refsAssumed { refList = s.refs }
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
            let ok = PlayerWindowController.fetchAll(merged, dir: dir)
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

            // alinha cada legenda PT à referência (escala x deslocamento)
            let lg = StrBuf().a("alinhamento com \(refStarts.count) referência(s):")
            let ar = SubtitleSync.alignAll(ok, refStarts, lg)
            let nAligned = ar.aligned
            self?.log(lg.s + "\n  alinhadas: \(nAligned)")
            let assumed = sr?.refsAssumed ?? false
            let hasRefs = !(sr?.refs.isEmpty ?? true)
            var refLine: String
            if ar.refKind == "outro idioma" { refLine = "outro idioma (\(PlayerWindowController.langShort(refLang)))" + (assumed ? ", palpite" : ", do hash") + (nAligned == 0 ? ", nenhuma legenda casou" : "") }
            else if ar.refKind == "PT do hash" { refLine = "PT do hash" }
            else if ar.refKind == "consenso" { refLine = "nenhuma de tempo; consenso entre \(ar.ptCount) PT (maior grupo: \(ar.consensusMax)) escolhe a legenda certa, não alinha" }
            else { refLine = (assumed && hasRefs) ? "nenhuma (o addon não confirma o hash: referência de palpite ignorada)" : hasRefs ? "nenhuma (a referência não baixou)" : "nenhuma" }
            if ar.pieced > 0 { refLine += "; \(ar.pieced) por partes (cortes)" }
            if ar.freeScaled > 0 { refLine += "; \(ar.freeScaled) com escala livre" }
            let trusted = (ar.refKind == "outro idioma" && !assumed) || ar.refKind == "PT do hash"

            let fId = idLine, fRef = refLine, fHash = hashLine
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.cands = ok
                self.diagId = fId
                self.diagRef = fRef
                self.diagHash = fHash
                self.refTrusted = trusted
                self.subsDownloaded = true
                self.attachReadySubs()
            }
        }
    }

    private static func fetchAll(_ items: [SubtitleSync.Found], dir: URL) -> [SubtitleSync.Cand] {
        var results = [SubtitleSync.Cand?](repeating: nil, count: items.count)
        let lock = NSLock()
        let group = DispatchGroup()
        for (i, f) in items.enumerated() {
            group.enter()
            DispatchQueue.global(qos: .userInitiated).async {
                let c = fetchSubtitle(f, dir: dir, rank: i)
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

    /** texto UTF-8 de uma legenda baixada (nil se não for legenda): gzip/zip, BOM, UTF-16, cp1252, ASS, fins de linha (ver SubtitleText) */
    private static func fetchText(_ u: String) -> String? {
        guard let url = URL(string: u), let d = get(url) else { return nil }
        return SubtitleText.decode([UInt8](d))
    }

    private static func fetchStarts(_ u: String) -> [Int64]? {
        guard let t = fetchText(u) else { return nil }
        return SubtitleAlign.alignStarts(t)
    }

    /** baixa uma legenda; devolve nil se não for uma legenda válida em português */
    private static func fetchSubtitle(_ fo: SubtitleSync.Found, dir: URL, rank: Int) -> SubtitleSync.Cand? {
        guard let text = fetchText(fo.url) else { return nil }
        if !SubtitleText.looksPortuguese(text) { return nil } // legenda de outro idioma com etiqueta errada
        let out = dir.appendingPathComponent("\(rank + 1)." + (text.hasPrefix("WEBVTT") ? "vtt" : "srt"))
        do { try Data(text.utf8).write(to: out) } catch { return nil } // sempre UTF-8: acentos certos no VLC
        let c = SubtitleSync.Cand()
        c.url = fo.url
        c.file = out
        c.text = text
        c.lastMs = SubtitleText.lastCueEnd(text)
        c.rank = rank
        c.hashMatch = fo.hashMatch
        c.rel = fo.rel
        return c
    }

    /** legenda PT/BR que veio dentro do arquivo (a primeira que não seja "forçada"); nil se não houver.
        allowForced=false: ignora a faixa "forçada" (só traduz falas estrangeiras; escolhê-la no lugar da legenda completa deixaria o filme sem diálogo legendado) */
    private func findEmbeddedPt(allowForced: Bool) -> Int? {
        var forced: Int? = nil
        for (id, name) in subTracks() where id != -1 && isPortuguese(name) {
            if embeddedKnown && !embeddedSubs.contains(id) { continue }
            let n = name.lowercased()
            if n.contains("forced") || n.contains("forçad") || n.contains("forcad") || n.contains("foreign") {
                if forced == nil { forced = id }
                continue
            }
            return id
        }
        return allowForced ? forced : nil
    }

    /** as faixas de legenda do VLC: (id, nome) */
    private func subTracks() -> [(Int, String)] {
        let names = (player.videoSubTitlesNames as? [String]) ?? []
        let ids = (player.videoSubTitlesIndexes as? [NSNumber])?.map { $0.intValue } ?? []
        var out: [(Int, String)] = []
        for (i, id) in ids.enumerated() where i < names.count { out.append((id, names[i])) }
        return out
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
        embeddedSubs = Set(subTracks().map { $0.0 })
        // a faixa embutida "forçada" só vale se não houver NENHUMA legenda baixada para mostrar no lugar dela
        embeddedPtId = findEmbeddedPt(allowForced: cands.isEmpty)
        log("legenda PT embutida: " + (embeddedPtId == nil ? "não há" : "faixa \(embeddedPtId ?? -1)"))
        diagEmb = embeddedPtId == nil ? "" : refTrusted ? "PT embutida mantida (há referência confiável do hash; o texto embutido não é lido)"
            : "PT embutida mantida, sem validação (sem referência de tempo confiável)"
        if cands.isEmpty { return }
        let lg = StrBuf().a("ranking das legendas:")
        let use = SubtitleSync.rank(cands, len, srcName, lg)
        log(lg.s)
        let embeddedFirst = embeddedPtId != nil
        for (i, c) in use.enumerated() where i < 6 {
            guard let f = c.file else { continue }
            _ = player.addPlaybackSlave(f, type: .subtitle, enforce: i == 0 && !embeddedFirst)
            addedSubs += 1
            attached.append(c)
            subLabels.append("Português \(subLabels.count + 1)" + (c.aligned ? (c.pieced ? " (sincronizada por partes)" : " (sincronizada)") : c.consensus ? " (consenso)" : c.hashMatch ? " (do hash)" : "") + (c.fixed ? " (corrigida)" : "") + (c.sparse ? " (poucas falas)" : ""))
        }
        if embeddedFirst && !userPickedSub, let id = embeddedPtId { player.currentVideoSubTitleIndex = Int32(id) }
        if let f = use.first {
            let pick: String = embeddedFirst ? "embutida (faixa \(embeddedPtId ?? -1))" : "candidata #\(f.rank)"
            log("escolhida: " + pick + "; melhor baixada: #\(f.rank) alinhada=\(f.aligned) nota=" + J.fmt(f.alignScore, 2) + " deslocamento=\(f.alignOffsetMs)ms hash=\(f.hashMatch) corrigida=\(f.fixed); \(addedSubs) no menu")
        }
        syncDelayToTrack(embeddedFirst && !userPickedSub ? (embeddedPtId ?? -1) : Int(player.currentVideoSubTitleIndex))
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            guard let self = self, !self.finished else { return }
            if embeddedFirst && !self.userPickedSub, let id = self.embeddedPtId { self.player.currentVideoSubTitleIndex = Int32(id) }
            self.syncDelayToTrack(Int(self.player.currentVideoSubTitleIndex))
        }
        // a legenda baixada escolhida por addPlaybackSlave pode demorar a ficar ativa: confere de novo
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
            guard let self = self, !self.finished else { return }
            self.syncDelayToTrack(Int(self.player.currentVideoSubTitleIndex))
        }
    }

    /** liga a PT/BR que vier dentro do arquivo (vem perfeitamente sincronizada); se não houver nada em português, desliga */
    private func selectEmbeddedPortuguese() {
        guard !embeddedCheckDone, !finished else { return }
        embeddedCheckDone = true
        if let id = findEmbeddedPt(allowForced: cands.isEmpty && !subsDownloaded) {
            embeddedPtId = id
            if !userPickedSub { player.currentVideoSubTitleIndex = Int32(id); syncDelayToTrack(id) }
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
        let tracks = subTracks()
        let current = Int(player.currentVideoSubTitleIndex)
        var rows: [(String, Int)] = []
        var chosen: String? = current == -1 ? "desativada" : nil
        var n = 0
        for (id, name) in tracks {
            if id == -1 { rows.append(("Desativado", id)); continue }
            if isDownloaded(id) {
                n += 1
                let title = n <= subLabels.count ? subLabels[n - 1] : "Português \(n)"
                rows.append((title, id))
                if id == current && n <= attached.count {
                    let c = attached[n - 1]
                    let adj = !c.alignNote.isEmpty ? c.alignNote : c.fixed ? "velocidade corrigida" : "sem ajuste auto"
                    chosen = "\(title) (\(adj))"
                }
            } else if isPortuguese(name) {
                rows.append(("Embutida: \(name)", id))
                if id == current { chosen = "Embutida: \(name)" }
            }
        }
        let menu = NSMenu()
        menu.addItem(greyItem(diagId + " · " + (!subsDownloaded ? "baixando legendas…" : cands.count == 1 ? "1 legenda PT" : "\(cands.count) legendas PT")))
        menu.addItem(greyItem("Hash: " + diagHash + " · Referência: " + (diagRef.isEmpty ? "…" : diagRef)))
        if !diagEmb.isEmpty { menu.addItem(greyItem(diagEmb)) }
        if let c = chosen { menu.addItem(greyItem("Escolhida: " + c)) }
        menu.addItem(greyItem("Atraso manual: " + (subDelayMs == 0 ? "0,0 s" : SubtitleSync.signedSecs(Int64(subDelayMs)))))
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
        }
        menu.addItem(NSMenuItem.separator())
        let sync = NSMenuItem(title: "Sincronizar legenda…", action: #selector(showSyncPanel), keyEquivalent: "")
        sync.target = self
        menu.addItem(sync)
        menu.popUp(positioning: nil, at: NSPoint(x: 0, y: sender.bounds.height + 4), in: sender)
        showControls()
    }
    @objc private func pickSubtitle(_ item: NSMenuItem) {
        userPickedSub = true
        player.currentVideoSubTitleIndex = Int32(item.tag)
        syncDelayToTrack(item.tag)
    }

    // ---------- ajuste manual do atraso da legenda ----------
    // VLCKit: currentVideoSubTitleDelay em microssegundos; positivo = a legenda aparece MAIS TARDE.
    // O atraso é guardado por VÍDEO + FONTE (hash/nome do arquivo) + LEGENDA (url da baixada ou "emb:" + nome da faixa), como no Android ("sub-delay-4"):
    // só volta a ser aplicado quando a mesma fonte E a mesma legenda estão ativas. O antigo "fp-subdelay-" (por título) não é mais lido.
    private static let delayPrefs = "sub-delay-4:"
    private static let maxDelayMs = 600000

    /** String.hashCode() do Java (sobre as unidades UTF-16) */
    private static func javaHash(_ s: String) -> Int32 {
        var h: Int32 = 0
        for u in s.utf16 { h = h &* 31 &+ Int32(u) }
        return h
    }

    private func sourceId() -> String {
        if let h = srcHash { return "h:" + h }
        if let n = srcName ?? PlayerWindowController.fileName(of: url, VideoHash()) { return "f:" + n }
        var u = url.absoluteString
        if let q = u.firstIndex(of: "?") { u = String(u[u.startIndex..<q]) }
        return "u:" + String(UInt32(bitPattern: PlayerWindowController.javaHash(u)), radix: 16)
    }

    /** legenda baixada que corresponde à faixa do VLC (as baixadas são as faixas novas, na ordem em que foram adicionadas); nil se for embutida/desativada */
    private func candForTrack(_ trackId: Int) -> SubtitleSync.Cand? {
        if trackId == -1 { return nil }
        var n = 0
        for (id, _) in subTracks() {
            if id == -1 || !isDownloaded(id) { continue }
            if id == trackId { return n < attached.count ? attached[n] : nil }
            n += 1
        }
        return nil
    }

    /** chave do atraso para a faixa; nil se não há legenda ativa */
    private func delayKeyFor(_ trackId: Int) -> String? {
        if trackId == -1 { return nil }
        var sub: String? = nil
        if let c = candForTrack(trackId) {
            // o atraso manual é medido em cima do arquivo JÁ reescrito pelo ajuste automático: se o ajuste for outro, o atraso guardado não vale
            sub = (c.url ?? "cand\(c.rank)") + "#" + c.sig
        } else {
            for (id, name) in subTracks() where id == trackId { sub = "emb:" + name }
        }
        guard let s = sub else { return nil }
        return "d3:" + (metaId.isEmpty ? "u" : metaId) + "|" + sourceId() + "|" + s
    }

    private func savedDelay(_ key: String?) -> Int {
        guard let k = key else { return 0 }
        return UserDefaults.standard.integer(forKey: PlayerWindowController.delayPrefs + k)
    }

    /** a legenda ativa mudou (ou ficou conhecida): carrega o atraso guardado dela (ou 0) e aplica */
    private func syncDelayToTrack(_ trackId: Int) {
        let k = delayKeyFor(trackId)
        let tag = k ?? ""
        if tag == lastSyncKey { return }
        lastSyncKey = tag
        let m = PlayerWindowController.maxDelayMs
        subDelayMs = max(-m, min(m, savedDelay(k)))
        applySubDelay()
        log("atraso da legenda ativa (\(k ?? "nenhuma")): \(subDelayMs) ms")
        if subDelayMs != 0 && tag != announcedKey {
            announcedKey = tag
            notice("Legenda: atraso guardado " + SubtitleSync.signedSecs(Int64(subDelayMs)))
        }
    }

    private func notice(_ s: String, seconds: Double = 3) {
        noticeLabel.stringValue = "  " + s + "  "
        noticeLabel.isHidden = false
        noticeUntil = Date().addingTimeInterval(seconds)
    }

    private func applySubDelay() { player.currentVideoSubTitleDelay = subDelayMs * 1000 }

    private func delayLabel() -> String {
        return "Atraso da legenda: " + (subDelayMs == 0 ? "0,0 s" : SubtitleSync.signedSecs(Int64(subDelayMs)))
    }

    /** define o atraso da legenda ativa (limite ±600 s), guarda por fonte+legenda, aplica e atualiza o texto */
    private func setSubDelay(_ ms: Int) {
        let m = PlayerWindowController.maxDelayMs
        subDelayMs = max(-m, min(m, ms))
        let k = delayKeyFor(Int(player.currentVideoSubTitleIndex))
        if let k = k {
            lastSyncKey = k
            UserDefaults.standard.set(subDelayMs, forKey: PlayerWindowController.delayPrefs + k)
        }
        applySubDelay()
        syncValue?.stringValue = delayLabel()
        log("atraso da legenda: \(subDelayMs) ms (\(k ?? "sem legenda ativa, não guardado"))")
    }

    private func changeSubDelay(_ delta: Int, reset: Bool) { setSubDelay(reset ? 0 : subDelayMs + delta) }

    // ---------- painel "Sincronizar legenda" (por cima do vídeo; teclado: setas/Enter/Esc; mouse: clique, segurar repete) ----------
    private func panelLabel(_ s: String, _ size: CGFloat, bold: Bool = false, color: NSColor = .white) -> NSTextField {
        let t = NSTextField(wrappingLabelWithString: s)
        t.font = bold ? .boldSystemFont(ofSize: size) : .systemFont(ofSize: size)
        t.textColor = color
        t.alignment = .center
        t.maximumNumberOfLines = 0
        t.preferredMaxLayoutWidth = 420
        return t
    }

    private func syncButton(_ title: String) -> SyncButton {
        let b = SyncButton(title: title, target: nil, action: nil)
        b.setButtonType(.momentaryPushIn)
        b.isBordered = false
        b.wantsLayer = true
        b.layer?.cornerRadius = 12
        b.layer?.backgroundColor = NSColor(white: 1, alpha: 0.16).cgColor
        b.layer?.borderWidth = 1
        b.layer?.borderColor = NSColor(white: 1, alpha: 0.18).cgColor
        b.attributedTitle = NSAttributedString(string: "  \(title)  ", attributes: [.foregroundColor: NSColor.white, .font: NSFont.boldSystemFont(ofSize: 15)])
        b.refusesFirstResponder = true
        return b
    }

    /** botão de passo: toque = um passo; segurar repete a cada ~150 ms e acelera (1×, 3×, 10×, 30×) */
    private func stepButton(_ title: String, _ delta: Int) -> SyncButton {
        let b = syncButton(title)
        b.onTap = { [weak self] in self?.changeSubDelay(delta, reset: false) }
        b.onHold = { [weak self] el in
            let mult = el < 1.5 ? 1 : el < 3 ? 3 : el < 5 ? 10 : 30
            self?.changeSubDelay(delta * mult, reset: false)
        }
        return b
    }

    private func makePanel() -> NSVisualEffectView {
        let p = NSVisualEffectView()
        p.material = .hudWindow
        p.blendingMode = .withinWindow
        p.state = .active
        p.appearance = NSAppearance(named: .darkAqua)
        p.wantsLayer = true
        p.layer?.cornerRadius = 18
        p.translatesAutoresizingMaskIntoConstraints = false
        return p
    }

    private func place(_ p: NSView, in stack: NSStackView, width: CGFloat) {
        stack.translatesAutoresizingMaskIntoConstraints = false
        p.addSubview(stack)
        root.addSubview(p)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: p.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: p.trailingAnchor, constant: -24),
            stack.topAnchor.constraint(equalTo: p.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(equalTo: p.bottomAnchor, constant: -20),
            p.widthAnchor.constraint(equalToConstant: width),
            p.centerXAnchor.constraint(equalTo: root.centerXAnchor),
            p.centerYAnchor.constraint(equalTo: root.centerYAnchor),
        ])
    }

    private func buttonRow(_ bs: [SyncButton]) -> NSStackView {
        let r = NSStackView(views: bs)
        r.orientation = .horizontal
        r.spacing = 12
        return r
    }

    private func setSyncFocus(_ r: Int, _ c: Int) {
        guard !syncRows.isEmpty else { return }
        let rr = max(0, min(syncRows.count - 1, r))
        let cc = max(0, min(syncRows[rr].count - 1, c))
        syncFocus = (rr, cc)
        for (i, row) in syncRows.enumerated() { for (j, b) in row.enumerated() { b.focused = (i == rr && j == cc) } }
    }

    @objc private func showSyncPanel() {
        closePanels()
        let active = candForTrack(Int(player.currentVideoSubTitleIndex))
        let value = panelLabel(delayLabel(), 20, bold: true)
        syncValue = value
        var views: [NSView] = [panelLabel("Sincronizar legenda", 22, bold: true)]
        var rows: [[SyncButton]] = []
        if let a = active, a.text != nil {
            // principal: acertar pela fala (só com legenda baixada ativa: a embutida não tem texto acessível)
            let sp = syncButton("Sincronizar pela fala")
            sp.onTap = { [weak self] in self?.showSpeechPicker(a) }
            rows.append([sp])
            views.append(buttonRow([sp]))
            views.append(panelLabel("Pause numa fala, escolha-a na lista e a legenda acerta sozinha.", 13, color: NSColor(white: 0.63, alpha: 1)))
        } else {
            views.append(panelLabel("Sincronizar pela fala: ative uma legenda baixada (a embutida não permite). Use os botões abaixo.", 13, color: NSColor(red: 1, green: 0.78, blue: 0.34, alpha: 1)))
        }
        views.append(value)
        let m30 = stepButton("−30 s", -30000), m5 = stepButton("−5 s", -5000), m1 = stepButton("−1 s", -1000)
        let p1 = stepButton("+1 s", 1000), p5 = stepButton("+5 s", 5000), p30 = stepButton("+30 s", 30000)
        let m05 = stepButton("−0,5 s", -500), p05 = stepButton("+0,5 s", 500)
        let reset = syncButton("Repor")
        reset.onTap = { [weak self] in self?.changeSubDelay(0, reset: true) }
        let close = syncButton("Fechar")
        close.onTap = { [weak self] in self?.closePanels() }
        views.append(panelLabel("Legenda DEPOIS da fala (adiantar):", 14))
        views.append(buttonRow([m30, m5, m1]))
        views.append(panelLabel("Legenda ANTES da fala (atrasar):", 14))
        views.append(buttonRow([p1, p5, p30]))
        views.append(buttonRow([m05, p05, reset]))
        views.append(panelLabel("Segure o botão para repetir (acelera). Setas escolhem, Enter aplica, Esc fecha.", 12, color: NSColor(white: 0.63, alpha: 1)))
        views.append(buttonRow([close]))
        let startRow = rows.count
        rows.append([m30, m5, m1])
        rows.append([p1, p5, p30])
        rows.append([m05, p05, reset])
        rows.append([close])
        let stack = NSStackView(views: views)
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 8
        let p = makePanel()
        place(p, in: stack, width: 480)
        syncPanel = p
        syncRows = rows
        setSyncFocus(startRow + 1, 0) // como no Android: o foco começa no +1 s
        window?.makeFirstResponder(overlay)
        showControls()
    }

    private func closePanels() {
        syncPanel?.removeFromSuperview(); syncPanel = nil
        speechPanel?.removeFromSuperview(); speechPanel = nil
        syncRows = []
        syncValue = nil
        speechTable = nil
        if speechWasPlaying { speechWasPlaying = false; player.play() }
        window?.makeFirstResponder(overlay)
    }

    /** teclas com um painel aberto (devolve true: a tecla foi usada) */
    private func panelKey(_ e: NSEvent) -> Bool {
        if speechPanel != nil {
            guard let t = speechTable else { return true }
            switch e.keyCode {
            case 53: closePanels()
            case 126: speechSelect(t.selectedRow - 1)
            case 125: speechSelect(t.selectedRow + 1)
            case 116: speechSelect(t.selectedRow - 8)
            case 121: speechSelect(t.selectedRow + 8)
            case 36, 76: speechChoose(t.selectedRow)
            default: break
            }
            return true
        }
        switch e.keyCode {
        case 53: closePanels()
        case 123: setSyncFocus(syncFocus.0, syncFocus.1 - 1)
        case 124: setSyncFocus(syncFocus.0, syncFocus.1 + 1)
        case 126: setSyncFocus(syncFocus.0 - 1, syncFocus.1)
        case 125: setSyncFocus(syncFocus.0 + 1, syncFocus.1)
        case 36, 76, 49:
            guard syncFocus.0 < syncRows.count, syncFocus.1 < syncRows[syncFocus.0].count else { return true }
            let b = syncRows[syncFocus.0][syncFocus.1]
            if !e.isARepeat { enterStart = Date(); enterLast = Date.distantPast; b.onTap?() }
            else if let h = b.onHold, Date().timeIntervalSince(enterLast) >= 0.15 {
                enterLast = Date()
                h(Date().timeIntervalSince(enterStart))
            }
        default: break
        }
        return true
    }

    static func clockString(_ ms: Int64) -> String {
        let s = max(0, ms) / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%02d:%02d", m, sec)
    }

    /** "Sincronizar pela fala": pausa, lista as falas da legenda ativa em volta do momento (±180 s no tempo da legenda) e,
        ao escolher a que estava sendo dita, o atraso passa a ser (tempo atual do vídeo − início dessa fala) */
    private func showSpeechPicker(_ active: SubtitleSync.Cand) {
        guard let text = active.text else { return }
        let wasPlaying = player.isPlaying
        closePanels()
        if wasPlaying { player.pause() }
        speechWasPlaying = wasPlaying
        let now = Int64(player.time.intValue)
        let center = now - Int64(subDelayMs) // onde estamos no tempo da legenda
        var list: [SubtitleAlign.Cue] = []
        var sel = 0
        var bestD = Int64.max
        for c in SubtitleAlign.cues(text) {
            let d = abs(c.startMs - center)
            if d > 180000 { continue }
            if d < bestD { bestD = d; sel = list.count }
            list.append(c)
        }
        if list.isEmpty {
            notice("Esta legenda não tem falas perto deste momento (±3 min). Use os botões de ajuste.", seconds: 5)
            closePanels()
            return
        }
        speechCues = list
        speechNow = now
        let table = NSTableView()
        let col = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("fala"))
        col.width = 560
        table.addTableColumn(col)
        table.headerView = nil
        table.backgroundColor = .clear
        table.rowHeight = 26
        table.dataSource = self
        table.delegate = self
        table.target = self
        table.action = #selector(speechClicked)
        table.selectionHighlightStyle = .regular
        let scroll = NSScrollView()
        scroll.documentView = table
        scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.heightAnchor.constraint(equalToConstant: 340).isActive = true
        scroll.widthAnchor.constraint(equalToConstant: 580).isActive = true
        let stack = NSStackView(views: [
            panelLabel("Qual fala estava sendo dita?", 20, bold: true),
            panelLabel("Pause numa fala, escolha-a na lista e a legenda acerta sozinha. Setas e Enter, ou clique; Esc cancela.", 13, color: NSColor(white: 0.63, alpha: 1)),
            scroll,
        ])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 10
        let p = makePanel()
        place(p, in: stack, width: 630)
        speechPanel = p
        speechTable = table
        table.reloadData()
        speechSelect(sel)
        window?.makeFirstResponder(overlay)
        showControls()
    }

    private func speechSelect(_ i: Int) {
        guard let t = speechTable, !speechCues.isEmpty else { return }
        let r = max(0, min(speechCues.count - 1, i))
        t.selectRowIndexes(IndexSet(integer: r), byExtendingSelection: false)
        t.scrollRowToVisible(r)
    }

    @objc private func speechClicked() {
        if let t = speechTable, t.clickedRow >= 0 { speechChoose(t.clickedRow) }
    }

    private func speechChoose(_ i: Int) {
        guard i >= 0 && i < speechCues.count else { return }
        let m = PlayerWindowController.maxDelayMs
        let nd = Int(max(Int64(-m), min(Int64(m), speechNow - speechCues[i].startMs)))
        setSubDelay(nd)
        notice("Atraso da legenda: " + SubtitleSync.signedSecs(Int64(nd)))
        closePanels() // retoma a reprodução
    }

    /** comandos do teste automático (só no GitHub) */
    func testCommand(_ line: String) {
        let w = line.split(separator: " ").map(String.init)
        guard let cmd = w.first else { return }
        let n = w.count > 1 ? Int(w[1]) ?? 0 : 0
        log("comando de teste: \(line)")
        switch cmd {
        case "scale": setScaleMode(n)
        case "sync": showSyncPanel()
        case "speech":
            showSyncPanel()
            if let c = candForTrack(Int(player.currentVideoSubTitleIndex)) { showSpeechPicker(c) }
        case "delay": setSubDelay(n)
        case "close": closePanels()
        case "key":
            let ev = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: window?.windowNumber ?? 0,
                                      context: nil, characters: "", charactersIgnoringModifiers: "", isARepeat: false, keyCode: UInt16(n))
            if let ev = ev { _ = handleKey(ev) }
        case "show": showControls()
        case "fs": toggleFullScreen()
        case "resize":
            let n2 = w.count > 2 ? Int(w[2]) ?? 0 : 0
            if n > 0 && n2 > 0 { window?.setContentSize(NSSize(width: n, height: n2)); window?.center() }
        case "layout":
            // mede se a barra de controles cabe: largura da linha de botões contra a largura da barra
            root.layoutSubtreeIfNeeded()
            var parts: [String] = []
            if let row = controls.subviews.first as? NSStackView {
                for v in row.arrangedSubviews { parts.append("\(NSStringFromClass(type(of: v)).split(separator: ".").last ?? "")@\(Int(v.frame.minX))-\(Int(v.frame.maxX))") }
                log("layout: janela=\(Int(root.bounds.width))x\(Int(root.bounds.height)) barra=\(Int(controls.frame.width)) linha=\(Int(row.frame.width)) fitting=\(Int(row.fittingSize.width)) slider=\(Int(slider.frame.width)) itens=\(parts.joined(separator: " "))")
            }
        case "hold":
            // simula segurar o botão da fila N,M do painel (mouse apertado por 1,3 s) e solta
            let col = w.count > 2 ? Int(w[2]) ?? 0 : 0
            guard n < syncRows.count, col < syncRows[n].count, let win = window else { log("hold: sem botão"); break }
            let b = syncRows[n][col]
            let before = subDelayMs
            let pt = b.convert(NSPoint(x: b.bounds.midX, y: b.bounds.midY), to: nil)
            if let d = NSEvent.mouseEvent(with: .leftMouseDown, location: pt, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: win.windowNumber, context: nil, eventNumber: 1, clickCount: 1, pressure: 1) {
                b.mouseDown(with: d)
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.3) { [weak self, weak b] in
                    if let u = NSEvent.mouseEvent(with: .leftMouseUp, location: pt, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: win.windowNumber, context: nil, eventNumber: 1, clickCount: 1, pressure: 0) { b?.mouseUp(with: u) }
                    self?.log("hold: atraso antes=\(before) depois=\(self?.subDelayMs ?? -1)")
                }
            }
        case "state":
            log("estado: faixa=\(player.currentVideoSubTitleIndex) atraso=\(subDelayMs) vlcDelay=\(player.currentVideoSubTitleDelay) legendas=\(subTracks()) escolhida=\(candForTrack(Int(player.currentVideoSubTitleIndex))?.rank ?? -1)")
        default: break
        }
    }

    // ---------- tela: Ajustar / Preencher / Esticar ----------
    // O VLC desenha as legendas DENTRO da imagem do vídeo. Por isso os 3 modos usam sempre a mesma vista (fillScreen = false, sem ampliar a vista):
    // Ajustar = como vem (barras pretas); Esticar = proporção do VLC igual à da vista (distorce até encher); Preencher = recorte
    // (videoCropGeometry com a proporção da vista: corta o excesso, sem distorcer). Assim a legenda fica inteira e dentro da tela nos 3 modos.
    private static let scaleNames = ["Ajustar", "Preencher", "Esticar"]

    private func applyScale(force: Bool = false) {
        let w = Int(videoView.bounds.width.rounded()), h = Int(videoView.bounds.height.rounded())
        if w <= 0 || h <= 0 { return }
        if !force && lastScale.0 == w && lastScale.1 == h && lastScale.2 == scaleMode { return }
        lastScale = (w, h, scaleMode)
        videoView.fillScreen = false
        let ratio = "\(w):\(h)"
        if scaleMode == 2 { ratio.withCString { player.videoAspectRatio = UnsafeMutablePointer(mutating: $0) } }
        else { player.videoAspectRatio = nil }
        if scaleMode == 1 { ratio.withCString { player.videoCropGeometry = UnsafeMutablePointer(mutating: $0) } }
        else { player.videoCropGeometry = nil }
        log("tela: \(PlayerWindowController.scaleNames[scaleMode]) \(ratio)")
    }

    @objc private func toggleScale(_ sender: NSButton) {
        setScaleMode((scaleMode + 1) % 3)
        showControls()
    }

    func setScaleMode(_ m: Int) {
        scaleMode = max(0, min(2, m))
        scaleButton?.attributedTitle = NSAttributedString(string: "  Tela: \(PlayerWindowController.scaleNames[scaleMode])  ", attributes: [
            .foregroundColor: NSColor.white, .font: NSFont.boldSystemFont(ofSize: 13)])
        applyScale(force: true)
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
        // como o Uri.encode do Android: só letras e dígitos ASCII e _-!.~'()* ficam como estão (mais `keep`)
        s.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-!.~'()*" + keep)) ?? s
    }

    /** extras no formato do Stremio (videoHash=...&videoSize=...&filename=...), só com o que temos */
    private static func makeExtras(_ vh: VideoHash, _ filename: String?) -> String {
        var parts: [String] = []
        if let h = vh.hash { parts.append("videoHash=\(h)"); parts.append("videoSize=\(vh.size)") }
        if let f = filename { parts.append("filename=\(encodeComponent(f))") }
        return parts.joined(separator: "&")
    }

    private static func search(_ type: String, _ id: String, _ extras: String) -> SubtitleSync.SearchResult {
        let path = "https://opensubtitles-v3.strem.io/subtitles/\(encodeComponent(type))/\(encodeComponent(id, keep: ":"))"
            + (extras.isEmpty ? "" : "/" + extras) + ".json"
        guard let api = URL(string: path) else { return SubtitleSync.SearchResult() }
        return SubtitleSync.parseSearch(get(api))
    }

    // ---------- fechar ----------
    @objc func closePlayer() { window?.performClose(nil) }

    func windowWillClose(_ notification: Notification) { finish() }
    func windowDidResize(_ notification: Notification) { applyScale() }

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


/** botão do painel de sincronização: clique = onTap; segurar (mouse) repete onHold a cada ~150 ms, depois de 0,4 s; borda branca = foco do teclado */
final class SyncButton: NSButton {
    var onTap: (() -> Void)?
    /** segurando: segundos desde o começo do segurar */
    var onHold: ((TimeInterval) -> Void)?
    private var holdTimer: Timer?
    private var holdStart = Date()
    private var held = false
    var focused = false {
        didSet {
            layer?.borderWidth = focused ? 3 : 1
            layer?.borderColor = NSColor(white: 1, alpha: focused ? 1 : 0.18).cgColor
            layer?.backgroundColor = NSColor(white: 1, alpha: focused ? 0.28 : 0.16).cgColor
        }
    }

    override var acceptsFirstResponder: Bool { false }

    override func mouseDown(with event: NSEvent) {
        held = false
        holdStart = Date()
        holdTimer?.invalidate()
        if onHold != nil {
            holdTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self] _ in self?.startRepeat() }
        }
        layer?.backgroundColor = NSColor(white: 1, alpha: 0.34).cgColor
    }

    private func startRepeat() {
        held = true
        fire()
        holdTimer = Timer.scheduledTimer(withTimeInterval: 0.15, repeats: true) { [weak self] _ in self?.fire() }
    }

    private func fire() { onHold?(Date().timeIntervalSince(holdStart)) }

    override func mouseUp(with event: NSEvent) {
        holdTimer?.invalidate()
        holdTimer = nil
        layer?.backgroundColor = NSColor(white: 1, alpha: focused ? 0.28 : 0.16).cgColor
        if !held && bounds.contains(convert(event.locationInWindow, from: nil)) { onTap?() }
        held = false
    }
}

// lista de falas do "Sincronizar pela fala"
extension PlayerWindowController: NSTableViewDataSource, NSTableViewDelegate {
    func numberOfRows(in tableView: NSTableView) -> Int { return speechCues.count }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let c = speechCues[row]
        let one = c.text.replacingOccurrences(of: "\n", with: " ")
        let t = NSTextField(labelWithString: PlayerWindowController.clockString(c.startMs) + "   " + one)
        t.textColor = .white
        t.font = .monospacedDigitSystemFont(ofSize: 14, weight: .regular)
        t.lineBreakMode = .byTruncatingTail
        return t
    }
}
