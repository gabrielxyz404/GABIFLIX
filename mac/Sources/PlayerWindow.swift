// Player do GabiFlix para Mac, com o VLC (VLCKit): toca qualquer formato e áudio (MKV, HEVC, Dolby, DTS),
// liga legendas em português, começa de onde parou e devolve o progresso ao site ao fechar.
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

    private let url: URL
    private var subtitleURLs: [URL]
    private let metaType: String
    private let metaId: String
    private let startMs: Int64

    private let player = VLCMediaPlayer()
    private let videoView = VLCVideoView()
    private let root = PlayerRootView()
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

    // legendas baixadas (arquivos .srt) e as que já vinham no vídeo
    private var readySubs: [URL] = []
    private var addedSubs = 0
    private var embeddedSubs = Set<Int>()
    private var embeddedKnown = false
    private var embeddedCheckDone = false

    init(url: URL, subtitles: [URL], type: String, id: String, startMs: Int64) {
        self.url = url
        self.subtitleURLs = subtitles
        self.metaType = type
        self.metaId = id
        self.startMs = startMs
        let screen = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let w = NSWindow(contentRect: screen, styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                         backing: .buffered, defer: false)
        w.title = "GabiFlix"
        w.titlebarAppearsTransparent = true
        w.titleVisibility = .hidden
        w.backgroundColor = .black
        w.collectionBehavior = [.fullScreenPrimary]
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
        slider.isContinuous = true
        let audio = button("Áudio", #selector(chooseAudio))
        let subs = button("Legendas", #selector(chooseSubtitles))
        let fs = button("Tela cheia", #selector(toggleFullScreen))

        let row = NSStackView(views: [playButton, rw, ff, timeLabel, slider, lengthLabel, audio, subs, fs])
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

        root.onMouseMove = { [weak self] in self?.showControls() }
        root.onDoubleClick = { [weak self] in self?.toggleFullScreen() }
        root.onKey = { [weak self] e in self?.handleKey(e) ?? false }
        w.makeFirstResponder(root)
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
        player.media = media
        player.play()
        showControls()
        timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in self?.tick() }
    }

    private func tick() {
        let state = player.state
        let t = Int64(player.time.intValue)
        let len = Int64(player.media?.length.intValue ?? 0)
        if t > 0 { lastTime = t }
        if len > 0 { lastLength = len }

        switch state {
        case .playing:
            spinner.isHidden = true
            if !startedPlaying {
                startedPlaying = true
                attachReadySubs()
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in self?.selectEmbeddedPortuguese() }
            }
            if !resumeDone && len > 0 {
                resumeDone = true
                if startMs > 30000 && startMs < len - 60000 { player.time = VLCTime(int: Int32(truncatingIfNeeded: startMs)) }
            }
        case .buffering, .opening:
            if !startedPlaying { spinner.isHidden = false }
        case .ended:
            ended = true
            lastTime = lastLength
            closePlayer()
            return
        case .error:
            spinner.isHidden = true
            message.stringValue = "Não foi possível reproduzir este vídeo.\nVolte e escolha outra fonte."
            message.isHidden = false
            showControls()
        default:
            break
        }

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
        if player.isPlaying { player.pause() } else { player.play() }
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
        let event = NSApp.currentEvent
        let releasing = event?.type == .leftMouseUp
        userSeeking = !releasing
        if releasing || event == nil { player.time = VLCTime(int: Int32(truncatingIfNeeded: target)) }
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

    /** baixa as legendas PT/BR (as que vieram do site, ou do OpenSubtitles do Stremio) para arquivos .srt */
    private func loadSubtitles() {
        let given = subtitleURLs
        let type = metaType, id = metaId
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var urls = given
            if urls.isEmpty && !type.isEmpty && !id.isEmpty { urls = PlayerWindowController.searchOpenSubtitles(type: type, id: id) }
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("gabiflix-legendas", isDirectory: true)
            try? FileManager.default.removeItem(at: dir)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var n = 0
            for u in urls {
                if n >= 5 { break }
                guard let data = PlayerWindowController.get(u), data.count > 20 else { continue }
                n += 1
                let head = String(data: data.prefix(16), encoding: .utf8) ?? ""
                let file = dir.appendingPathComponent("\(n)" + (head.replacingOccurrences(of: "\u{FEFF}", with: "").hasPrefix("WEBVTT") ? ".vtt" : ".srt"))
                do { try data.write(to: file) } catch { continue }
                DispatchQueue.main.async { self?.readySubs.append(file); self?.attachReadySubs() }
            }
        }
    }

    private func attachReadySubs() {
        guard startedPlaying, !finished else { return }
        if !embeddedKnown && addedSubs < readySubs.count {
            embeddedKnown = true
            let ids = (player.videoSubTitlesIndexes as? [NSNumber])?.map { $0.intValue } ?? []
            embeddedSubs = Set(ids)
        }
        while addedSubs < readySubs.count {
            _ = player.addPlaybackSlave(readySubs[addedSubs], type: .subtitle, enforce: addedSubs == 0)
            addedSubs += 1
        }
    }

    private func selectEmbeddedPortuguese() {
        guard !embeddedCheckDone, addedSubs == 0, !finished else { return }
        embeddedCheckDone = true
        let names = (player.videoSubTitlesNames as? [String]) ?? []
        let ids = (player.videoSubTitlesIndexes as? [NSNumber])?.map { $0.intValue } ?? []
        for (i, id) in ids.enumerated() where i < names.count && id != -1 && isPortuguese(names[i]) {
            player.currentVideoSubTitleIndex = Int32(id)
            break
        }
    }

    @objc private func chooseSubtitles(_ sender: NSButton) {
        let names = (player.videoSubTitlesNames as? [String]) ?? []
        let ids = (player.videoSubTitlesIndexes as? [NSNumber])?.map { $0.intValue } ?? []
        let menu = NSMenu()
        var n = 0
        for (i, id) in ids.enumerated() where i < names.count {
            let title: String
            if id == -1 { title = "Desativado" }
            else if isDownloaded(id) { n += 1; title = "Português \(n)" }
            else if isPortuguese(names[i]) { title = names[i] }
            else { continue }
            let item = NSMenuItem(title: title, action: #selector(pickSubtitle(_:)), keyEquivalent: "")
            item.target = self
            item.tag = id
            item.state = Int(player.currentVideoSubTitleIndex) == id ? .on : .off
            menu.addItem(item)
        }
        if menu.items.count <= 1 {
            menu.removeAllItems()
            menu.addItem(withTitle: readySubs.isEmpty && addedSubs == 0 ? "Procurando legendas em português…" : "Não há legendas em português", action: nil, keyEquivalent: "")
        }
        menu.popUp(positioning: nil, at: NSPoint(x: 0, y: sender.bounds.height + 4), in: sender)
        showControls()
    }
    @objc private func pickSubtitle(_ item: NSMenuItem) { player.currentVideoSubTitleIndex = Int32(item.tag) }

    private static func get(_ url: URL) -> Data? {
        var req = URLRequest(url: url, timeoutInterval: 12)
        req.setValue("GabiFlix", forHTTPHeaderField: "User-Agent")
        let sem = DispatchSemaphore(value: 0)
        var out: Data?
        URLSession.shared.dataTask(with: req) { data, resp, _ in
            if let h = resp as? HTTPURLResponse, h.statusCode < 400 { out = data }
            sem.signal()
        }.resume()
        _ = sem.wait(timeout: .now() + 15)
        return out
    }

    /** legendas PT/BR do OpenSubtitles do Stremio (Brasil primeiro) */
    private static func searchOpenSubtitles(type: String, id: String) -> [URL] {
        let enc: (String) -> String = { $0.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~"))) ?? $0 }
        guard let api = URL(string: "https://opensubtitles-v3.strem.io/subtitles/\(enc(type))/\(enc(id)).json"),
              let data = get(api),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let list = json["subtitles"] as? [[String: Any]] else { return [] }
        var br: [URL] = [], pt: [URL] = []
        for s in list {
            let lang = (s["lang"] as? String ?? "").lowercased()
            guard let u = (s["url"] as? String).flatMap(URL.init(string:)) else { continue }
            if ["pob", "pt-br", "ptb"].contains(lang) { br.append(u) }
            else if ["por", "pt", "pt-pt"].contains(lang) { pt.append(u) }
        }
        return br + pt
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
