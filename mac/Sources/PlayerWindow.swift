// Player do FramePlay para Mac, com o VLC (VLCKit): toca qualquer formato e áudio (MKV, HEVC, Dolby, DTS),
// liga só legendas em português (as que combinam com o filme), começa de onde parou e devolve o progresso ao site.
import Cocoa
import VLCKit

/** Vista principal do player: recebe teclado e mouse (clique = pausar/continuar, duplo clique = tela cheia) */
final class PlayerRootView: NSView {
    var onKey: ((NSEvent) -> Bool)?
    var onMouseMove: (() -> Void)?
    var onClick: (() -> Void)?
    var onDoubleClick: (() -> Void)?
    private var tracking: NSTrackingArea?

    override var acceptsFirstResponder: Bool { true }
    override func keyDown(with event: NSEvent) { if onKey?(event) != true { super.keyDown(with: event) } }
    override func mouseMoved(with event: NSEvent) { onMouseMove?() }
    override func mouseDown(with event: NSEvent) {
        onMouseMove?()
        NSObject.cancelPreviousPerformRequests(withTarget: self, selector: #selector(fireClick), object: nil)
        if event.clickCount >= 2 { onDoubleClick?() }
        else { perform(#selector(fireClick), with: nil, afterDelay: NSEvent.doubleClickInterval) } // espera para saber se é duplo clique
    }
    @objc private func fireClick() { onClick?() }
    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let t = tracking { removeTrackingArea(t) }
        let t = NSTrackingArea(rect: bounds, options: [.mouseMoved, .activeAlways, .inVisibleRect], owner: self, userInfo: nil)
        addTrackingArea(t)
        tracking = t
    }
}

/** fundo escurecido atrás dos painéis (sincronizar legenda, lista de falas): clicar fora fecha o painel */
final class PanelHost: NSView {
    var onOutsideClick: (() -> Void)?
    override var acceptsFirstResponder: Bool { false }
    override func mouseDown(with event: NSEvent) {
        let p = convert(event.locationInWindow, from: nil)
        if let panel = subviews.first, panel.frame.contains(p) { return }
        onOutsideClick?()
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

    // o tamanho das legendas só vale se for passado aqui (opção da biblioteca), ver SubStyle
    private let player = VLCMediaPlayer(options: SubStyle.libraryOptions)
    private let videoView = VLCVideoView()
    private let root = NSView()
    private let overlay = PlayerRootView()
    private let controls = NSVisualEffectView()
    private let topBar = PassThroughView()
    private var playButton: IconButton!
    private let timeline = TimelineView()
    private let timeLabel = GF.label("0:00", size: 12, weight: .semibold, mono: true)
    private let lengthLabel = GF.label("-0:00", size: 12, weight: .semibold, color: GF.dim, mono: true)
    private let titleLabel = GF.label("", size: 15, weight: .semibold)
    private var volumeButton: IconButton!
    private let volumeSlider = NSSlider(value: 100, minValue: 0, maxValue: 100, target: nil, action: nil)
    private var subsButton: IconButton!
    private var audioButton: IconButton!
    private var fsButton: IconButton!
    private var popover: NSPopover?
    private var panelHost: PanelHost?
    private var volumeLevel = 100
    private var volumeMuted = false
    private var volumeApplied = false
    private let displayTitle: String
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
    private var scaleButton: IconButton?
    private var lastScale = (-1, -1, -1)
    private var scaleForce = 0
    private let noticeLabel = NSTextField(wrappingLabelWithString: "")
    private let noticeBox = NSVisualEffectView()
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

    init(url: URL, subtitles: [URL], type: String, id: String, startMs: Int64, title: String = "") {
        self.displayTitle = title
        self.url = url
        self.subtitleURLs = subtitles
        self.metaType = type
        self.metaId = id
        self.startMs = startMs
        let screen = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let w = NSWindow(contentRect: screen, styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                         backing: .buffered, defer: false)
        w.title = title.isEmpty ? "FramePlay" : title
        w.titlebarAppearsTransparent = true
        w.titleVisibility = .hidden
        if #available(macOS 11.0, *) { w.titlebarSeparatorStyle = .none }
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
        if let saved = UserDefaults.standard.object(forKey: "fp-volume") as? Int { volumeLevel = max(0, min(100, saved)) }

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

        // aviso curto (volume, atraso da legenda, tela...) em vidro escuro, no alto
        noticeLabel.textColor = .white
        noticeLabel.font = .systemFont(ofSize: 14, weight: .semibold)
        noticeLabel.alignment = .center
        noticeLabel.maximumNumberOfLines = 2
        noticeLabel.preferredMaxLayoutWidth = 520
        noticeLabel.isHidden = true
        noticeLabel.translatesAutoresizingMaskIntoConstraints = false
        noticeBox.material = .hudWindow
        noticeBox.blendingMode = .withinWindow
        noticeBox.state = .active
        noticeBox.appearance = NSAppearance(named: .darkAqua)
        noticeBox.wantsLayer = true
        noticeBox.layer?.cornerRadius = 14
        noticeBox.isHidden = true
        noticeBox.translatesAutoresizingMaskIntoConstraints = false
        noticeBox.addSubview(noticeLabel)
        root.addSubview(noticeBox)

        // degradês escuros: o topo e o fundo do vídeo ficam legíveis em qualquer cena
        let bottomScrim = ScrimView(top: false, strength: 0.72)
        bottomScrim.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(bottomScrim)

        // barra de cima: voltar + título
        topBar.translatesAutoresizingMaskIntoConstraints = false
        let topScrim = ScrimView(top: true, strength: 0.7)
        topScrim.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(topScrim)
        let back = IconButton(symbol: "chevron.left", fallback: "←", tip: "Voltar (Esc)", size: 36, pointSize: 15)
        back.target = self
        back.action = #selector(closePlayer)
        titleLabel.stringValue = displayTitle
        titleLabel.lineBreakMode = .byTruncatingTail
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(back)
        topBar.addSubview(titleLabel)
        root.addSubview(topBar)

        // barra de baixo (vidro escuro): linha do tempo e, embaixo, volume | pausar/10 s | legendas, áudio, sincronizar, tela, tela cheia
        controls.material = .hudWindow
        controls.blendingMode = .withinWindow
        controls.state = .active
        controls.appearance = NSAppearance(named: .darkAqua)
        controls.wantsLayer = true
        controls.layer?.cornerRadius = 20
        controls.layer?.borderWidth = 1
        controls.layer?.borderColor = NSColor(white: 1, alpha: 0.08).cgColor
        controls.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(controls)

        timeline.timeText = { [weak self] f in
            guard let self = self else { return "" }
            let len = Int64(self.player.media?.length.intValue ?? 0)
            return self.format(Int64(f * Double(len)))
        }
        timeline.onScrub = { [weak self] f, done in self?.scrub(f, done: done) }
        timeline.translatesAutoresizingMaskIntoConstraints = false
        timeline.setContentHuggingPriority(.defaultLow, for: .horizontal)
        timeLabel.alignment = .right
        lengthLabel.alignment = .left
        for l in [timeLabel, lengthLabel] {
            l.translatesAutoresizingMaskIntoConstraints = false
            l.widthAnchor.constraint(equalToConstant: 60).isActive = true
        }
        let trow = NSStackView(views: [timeLabel, timeline, lengthLabel])
        trow.orientation = .horizontal
        trow.spacing = 8
        trow.alignment = .centerY
        trow.translatesAutoresizingMaskIntoConstraints = false
        controls.addSubview(trow)

        let play = IconButton(symbol: "pause.fill", fallback: "❚❚", tip: "Pausar (espaço)", size: 52, pointSize: 22, solid: true)
        play.target = self
        play.action = #selector(togglePlay)
        playButton = play
        let rw = IconButton(symbol: "gobackward.10", fallback: "−10", tip: "Voltar 10 s (←)", size: 42, pointSize: 21)
        rw.target = self
        rw.action = #selector(rewind)
        let ff = IconButton(symbol: "goforward.10", fallback: "+10", tip: "Avançar 10 s (→)", size: 42, pointSize: 21)
        ff.target = self
        ff.action = #selector(forward)
        let vol = IconButton(symbol: "speaker.wave.3.fill", fallback: "Vol", tip: "Silenciar (M)", size: 34, pointSize: 14)
        vol.target = self
        vol.action = #selector(toggleMute)
        volumeButton = vol
        volumeSlider.target = self
        volumeSlider.action = #selector(volumeChanged)
        volumeSlider.controlSize = .small
        volumeSlider.trackFillColor = .white
        volumeSlider.translatesAutoresizingMaskIntoConstraints = false
        volumeSlider.widthAnchor.constraint(equalToConstant: 84).isActive = true
        volumeSlider.refusesFirstResponder = true
        let subs = IconButton(symbol: "captions.bubble", fallback: "CC", tip: "Legendas (S)", size: 38, pointSize: 17)
        subs.target = self
        subs.action = #selector(chooseSubtitles(_:))
        subsButton = subs
        let audio = IconButton(symbol: "waveform", fallback: "Áudio", tip: "Áudio (A)", size: 38, pointSize: 17)
        audio.target = self
        audio.action = #selector(chooseAudio(_:))
        audioButton = audio
        let sync = IconButton(symbol: "clock.arrow.circlepath", fallback: "Sinc.", tip: "Sincronizar legenda (L)", size: 38, pointSize: 17)
        sync.target = self
        sync.action = #selector(showSyncPanel)
        let scale = IconButton(symbol: "aspectratio", fallback: "Tela", tip: "Tela: Ajustar (clique para trocar)", size: 38, pointSize: 17)
        scale.target = self
        scale.action = #selector(toggleScale(_:))
        scaleButton = scale
        let fs = IconButton(symbol: "arrow.up.left.and.arrow.down.right", fallback: "⤢", tip: "Tela cheia (F)", size: 38, pointSize: 16)
        fs.target = self
        fs.action = #selector(toggleFullScreen)
        fsButton = fs

        let left = NSStackView(views: [vol, volumeSlider])
        left.orientation = .horizontal
        left.spacing = 6
        left.alignment = .centerY
        let center = NSStackView(views: [rw, play, ff])
        center.orientation = .horizontal
        center.spacing = 18
        center.alignment = .centerY
        let right = NSStackView(views: [subs, audio, sync, scale, fs])
        right.orientation = .horizontal
        right.spacing = 4
        right.alignment = .centerY
        for g in [left, center, right] {
            g.translatesAutoresizingMaskIntoConstraints = false
            controls.addSubview(g)
        }

        let wide = controls.widthAnchor.constraint(equalTo: root.widthAnchor, constant: -48)
        wide.priority = .defaultHigh
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

            noticeBox.centerXAnchor.constraint(equalTo: root.centerXAnchor),
            noticeBox.topAnchor.constraint(equalTo: root.topAnchor, constant: 84),
            noticeBox.widthAnchor.constraint(lessThanOrEqualTo: root.widthAnchor, constant: -80),
            noticeLabel.leadingAnchor.constraint(equalTo: noticeBox.leadingAnchor, constant: 18),
            noticeLabel.trailingAnchor.constraint(equalTo: noticeBox.trailingAnchor, constant: -18),
            noticeLabel.topAnchor.constraint(equalTo: noticeBox.topAnchor, constant: 10),
            noticeLabel.bottomAnchor.constraint(equalTo: noticeBox.bottomAnchor, constant: -10),

            bottomScrim.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            bottomScrim.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            bottomScrim.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            bottomScrim.heightAnchor.constraint(equalToConstant: 230),

            topBar.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            topBar.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            topBar.topAnchor.constraint(equalTo: root.topAnchor),
            topBar.heightAnchor.constraint(equalToConstant: 100),
            topScrim.leadingAnchor.constraint(equalTo: topBar.leadingAnchor),
            topScrim.trailingAnchor.constraint(equalTo: topBar.trailingAnchor),
            topScrim.topAnchor.constraint(equalTo: topBar.topAnchor),
            topScrim.bottomAnchor.constraint(equalTo: topBar.bottomAnchor),
            back.leadingAnchor.constraint(equalTo: topBar.leadingAnchor, constant: 90),
            back.centerYAnchor.constraint(equalTo: topBar.topAnchor, constant: 27),
            titleLabel.leadingAnchor.constraint(equalTo: back.trailingAnchor, constant: 12),
            titleLabel.centerYAnchor.constraint(equalTo: back.centerYAnchor),
            titleLabel.trailingAnchor.constraint(lessThanOrEqualTo: topBar.trailingAnchor, constant: -90),

            controls.centerXAnchor.constraint(equalTo: root.centerXAnchor),
            controls.bottomAnchor.constraint(equalTo: root.bottomAnchor, constant: -24),
            controls.heightAnchor.constraint(equalToConstant: 108),
            controls.widthAnchor.constraint(lessThanOrEqualToConstant: 1000),
            controls.leadingAnchor.constraint(greaterThanOrEqualTo: root.leadingAnchor, constant: 24),
            wide,
            trow.leadingAnchor.constraint(equalTo: controls.leadingAnchor, constant: 16),
            trow.trailingAnchor.constraint(equalTo: controls.trailingAnchor, constant: -16),
            trow.topAnchor.constraint(equalTo: controls.topAnchor, constant: 8),
            trow.heightAnchor.constraint(equalToConstant: 40),
            left.leadingAnchor.constraint(equalTo: controls.leadingAnchor, constant: 16),
            left.centerYAnchor.constraint(equalTo: controls.bottomAnchor, constant: -36),
            center.centerXAnchor.constraint(equalTo: controls.centerXAnchor),
            center.centerYAnchor.constraint(equalTo: controls.bottomAnchor, constant: -36),
            right.trailingAnchor.constraint(equalTo: controls.trailingAnchor, constant: -16),
            right.centerYAnchor.constraint(equalTo: controls.bottomAnchor, constant: -36),
        ])

        overlay.onMouseMove = { [weak self] in self?.showControls() }
        overlay.onClick = { [weak self] in self?.togglePlay() }
        overlay.onDoubleClick = { [weak self] in self?.toggleFullScreen() }
        overlay.onKey = { [weak self] e in self?.handleKey(e) ?? false }
        w.makeFirstResponder(overlay)
        w.acceptsMouseMovedEvents = true
        updateVolumeUI()
        DispatchQueue.main.async { [weak self] in self?.placeTrafficLights() }
    }

    /** os 3 botões da janela alinhados com o botão Voltar da barra de cima */
    private func placeTrafficLights() {
        guard let w = window else { return }
        Chrome.positionTrafficLights(w, centerY: 27, leading: 20)
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
                applyVolume()
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
        // a faixa só fica ativa uns instantes depois de addPlaybackSlave (medido: logo depois do add o índice ainda é -1):
        // se a legenda ativa mudar por qualquer motivo, carrega o atraso guardado dela (sem efeito quando a chave é a mesma)
        if subsAttached && !finished { syncDelayToTrack(Int(player.currentVideoSubTitleIndex)) }
        reportProgress(force: false)
        if scaleForce > 0 { scaleForce -= 1; applyScale(force: true) } else { applyScale() }
        if !noticeLabel.isHidden && Date() > noticeUntil { noticeLabel.isHidden = true; noticeBox.isHidden = true }

        let playing = player.isPlaying
        let wantIcon = playing ? "pause.fill" : "play.fill"
        if playButton.symbol != wantIcon { playButton.symbol = wantIcon; playButton.toolTip = playing ? "Pausar (espaço)" : "Reproduzir (espaço)" }
        subsButton.accent = Int(player.currentVideoSubTitleIndex) != -1
        if !userSeeking && len > 0 {
            timeline.progress = Double(t) / Double(len)
            timeline.buffered = bufferedFraction(len: len)
            timeLabel.stringValue = format(t)
            lengthLabel.stringValue = "-" + format(max(0, len - t))
        } else if len <= 0 {
            timeLabel.stringValue = format(t)
        }

        // esconde os controles e o cursor depois de 2,5 s parado (só tocando, e nunca com o mouse em cima dos controles ou um painel aberto)
        if playing && Date() > hideAt && controls.alphaValue > 0 && !userSeeking && !uiBusy() {
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.25
                controls.animator().alphaValue = 0
                topBar.animator().alphaValue = 0
                if let w = window { Chrome.setTrafficLightsAlpha(w, 0, animated: true) }
            }
            NSCursor.setHiddenUntilMouseMoves(true)
        }
    }

    /** painel, lista ou o mouse em cima dos controles: os controles ficam */
    private func uiBusy() -> Bool {
        if panelHost != nil || popover?.isShown == true { return true }
        guard let w = window else { return false }
        let p = root.convert(w.mouseLocationOutsideOfEventStream, from: nil)
        return controls.frame.contains(p) || (topBar.frame.contains(p) && p.y > root.bounds.height - 64 && p.x < 400)
    }

    /** quanto do vídeo já foi carregado (0...1), se o VLC disser; senão 0 (a faixa "carregada" não aparece) */
    private func bufferedFraction(len: Int64) -> Double {
        return 0
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
                if let w = window { Chrome.setTrafficLightsAlpha(w, 1, animated: true) }
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
        notice((ms < 0 ? "−" : "+") + "\(abs(ms) / 1000) s", seconds: 0.8)
        showControls()
    }
    @objc private func rewind() { skip(-10000) }
    @objc private func forward() { skip(10000) }

    /** arrastar/clicar na linha do tempo: mostra a hora enquanto arrasta e salta ao soltar */
    private func scrub(_ f: Double, done: Bool) {
        let len = Int64(player.media?.length.intValue ?? 0)
        guard len > 0 else { userSeeking = false; return }
        let target = Int64(f * Double(len))
        userSeeking = !done
        timeLabel.stringValue = format(target)
        lengthLabel.stringValue = "-" + format(max(0, len - target))
        if done { player.time = VLCTime(int: Int32(truncatingIfNeeded: target)) }
        showControls()
    }

    @objc private func toggleFullScreen() { window?.toggleFullScreen(nil) }

    // ---------- volume (0...100 %, lembrado entre sessões) ----------
    /** o objeto de áudio do VLC (pelo nome, para não depender do tipo exato do cabeçalho do VLCKit) */
    private func vlcAudio() -> NSObject? {
        guard player.responds(to: NSSelectorFromString("audio")) else { return nil }
        return player.value(forKey: "audio") as? NSObject
    }

    private func applyVolume() {
        let v = volumeMuted ? 0 : volumeLevel
        if let a = vlcAudio(), a.responds(to: NSSelectorFromString("setVolume:")) {
            a.setValue(NSNumber(value: v), forKey: "volume")
            volumeApplied = true
        }
        log("volume: \(v)% (mudo=\(volumeMuted))")
        updateVolumeUI()
    }

    private func updateVolumeUI() {
        volumeSlider.doubleValue = volumeMuted ? 0 : Double(volumeLevel)
        volumeButton.symbol = volumeMuted || volumeLevel == 0 ? "speaker.slash.fill" : volumeLevel < 34 ? "speaker.wave.1.fill" : volumeLevel < 67 ? "speaker.wave.2.fill" : "speaker.wave.3.fill"
        volumeButton.toolTip = volumeMuted ? "Ligar o som (M)" : "Silenciar (M)"
    }

    @objc private func volumeChanged() {
        volumeLevel = Int(volumeSlider.doubleValue.rounded())
        volumeMuted = false
        UserDefaults.standard.set(volumeLevel, forKey: "fp-volume")
        applyVolume()
        showControls()
    }

    @objc private func toggleMute() {
        volumeMuted.toggle()
        applyVolume()
        notice(volumeMuted ? "Som desligado" : "Volume \(volumeLevel)%", seconds: 1.2)
        showControls()
    }

    private func nudgeVolume(_ d: Int) {
        volumeLevel = max(0, min(100, volumeLevel + d))
        volumeMuted = false
        UserDefaults.standard.set(volumeLevel, forKey: "fp-volume")
        applyVolume()
        notice("Volume \(volumeLevel)%", seconds: 1.2)
        showControls()
    }

    private func handleKey(_ e: NSEvent) -> Bool {
        if e.modifierFlags.contains(.command) { return false } // atalhos do menu (⌘W, ⌘M...) seguem o caminho normal
        if syncPanel != nil || speechPanel != nil { return panelKey(e) }
        if e.keyCode == 53, popover?.isShown == true { popover?.performClose(nil); return true }
        switch e.keyCode {
        case 49: togglePlay(); return true                 // espaço
        case 123: skip(e.modifierFlags.contains(.shift) ? -30000 : -10000); return true   // ←
        case 124: skip(e.modifierFlags.contains(.shift) ? 30000 : 10000); return true     // →
        case 126: nudgeVolume(5); return true              // ↑
        case 125: nudgeVolume(-5); return true             // ↓
        case 3: toggleFullScreen(); return true            // F
        case 46: toggleMute(); return true                 // M
        case 1: showControls(); showSubtitlePopover(); return true   // S
        case 0: showControls(); showAudioPopover(); return true      // A
        case 37: showControls(); showSyncPanel(); return true        // L
        case 53:                                           // Esc
            if window?.styleMask.contains(.fullScreen) == true { toggleFullScreen() } else { closePlayer() }
            return true
        default: return false
        }
    }

    // ---------- áudio ----------
    @objc private func chooseAudio(_ sender: Any?) { showControls(); showAudioPopover() }

    private func showAudioPopover() {
        let names = (player.audioTrackNames as? [String]) ?? []
        let ids = (player.audioTrackIndexes as? [NSNumber])?.map { $0.intValue } ?? []
        var items: [PopItem] = []
        for (i, id) in ids.enumerated() where i < names.count {
            items.append(PopItem(title: id == -1 ? "Desativado" : names[i], checked: Int(player.currentAudioTrackIndex) == id,
                                 action: { [weak self] in self?.player.currentAudioTrackIndex = Int32(id) }))
        }
        if items.isEmpty { items.append(PopItem(title: "Sem opções de áudio", enabled: false)) }
        present(PopList.make(title: "Áudio", items: items, notes: [], close: { [weak self] in self?.popover?.performClose(nil) }), from: audioButton)
    }

    /** mostra a lista (popover) por cima do botão; a anterior fecha */
    private func present(_ p: NSPopover, from anchor: NSView) {
        popover?.performClose(nil)
        popover = p
        p.show(relativeTo: anchor.bounds, of: anchor, preferredEdge: .maxY)
        showControls()
    }

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

    @objc private func chooseSubtitles(_ sender: Any?) { showControls(); showSubtitlePopover() }

    private func showSubtitlePopover() {
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
        var items: [PopItem] = []
        if rows.count <= 1 {
            items.append(PopItem(title: !subsDownloaded ? "Procurando legendas em português…" : "Não há legendas em português", enabled: false))
            if let off = rows.first { items.append(PopItem(title: off.0, checked: current == off.1, action: { [weak self] in self?.pickSubtitleId(off.1) })) }
        } else {
            for (title, id) in rows {
                items.append(PopItem(title: title, checked: current == id, action: { [weak self] in self?.pickSubtitleId(id) }))
            }
        }
        // diagnóstico (como estava no menu): o que o app achou e como escolheu
        var notes: [String] = []
        notes.append(diagId + " · " + (!subsDownloaded ? "baixando legendas…" : cands.count == 1 ? "1 legenda PT" : "\(cands.count) legendas PT"))
        notes.append("Hash: " + diagHash + " · Referência: " + (diagRef.isEmpty ? "…" : diagRef))
        if !diagEmb.isEmpty { notes.append(diagEmb) }
        if let c = chosen { notes.append("Escolhida: " + c) }
        notes.append("Atraso manual: " + (subDelayMs == 0 ? "0,0 s" : SubtitleSync.signedSecs(Int64(subDelayMs))))
        let footer = [PopItem(title: "Sincronizar legenda…", detail: "Atalho: L", action: { [weak self] in self?.showSyncPanel() })]
        present(PopList.make(title: "Legendas", items: items, notes: notes, footer: footer, close: { [weak self] in self?.popover?.performClose(nil) }), from: subsButton)
    }

    private func pickSubtitleId(_ id: Int) {
        userPickedSub = true
        player.currentVideoSubTitleIndex = Int32(id)
        syncDelayToTrack(id)
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
        noticeLabel.stringValue = s
        noticeLabel.isHidden = false
        noticeBox.isHidden = false
        noticeUntil = Date().addingTimeInterval(seconds)
    }

    private func applySubDelay() { player.currentVideoSubTitleDelay = subDelayMs * 1000 }

    private func delayLabel() -> String {
        return "Atraso da legenda: " + delayValueText()
    }
    private func delayValueText() -> String { subDelayMs == 0 ? "0,0 s" : SubtitleSync.signedSecs(Int64(subDelayMs)) }

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
        syncValue?.stringValue = delayValueText()
        log("atraso da legenda: \(subDelayMs) ms (\(k ?? "sem legenda ativa, não guardado"))")
    }

    private func changeSubDelay(_ delta: Int, reset: Bool) { setSubDelay(reset ? 0 : subDelayMs + delta) }

    // ---------- painel "Sincronizar legenda" (por cima do vídeo; teclado: setas/Enter/Esc; mouse: clique, segurar repete) ----------
    private func panelLabel(_ s: String, _ size: CGFloat, bold: Bool = false, color: NSColor = .white, align: NSTextAlignment = .center, width: CGFloat = 392) -> NSTextField {
        let t = NSTextField(wrappingLabelWithString: s)
        t.font = bold ? .systemFont(ofSize: size, weight: .bold) : .systemFont(ofSize: size)
        t.textColor = color
        t.alignment = align
        t.maximumNumberOfLines = 0
        t.preferredMaxLayoutWidth = width
        return t
    }

    private func syncButton(_ title: String, primary: Bool = false, width: CGFloat = 96) -> SyncButton {
        let b = SyncButton(title: title, target: nil, action: nil)
        b.setButtonType(.momentaryPushIn)
        b.isBordered = false
        b.wantsLayer = true
        b.primary = primary
        let para = NSMutableParagraphStyle()
        para.alignment = .center
        b.attributedTitle = NSAttributedString(string: title, attributes: [
            .foregroundColor: primary ? NSColor.black : NSColor.white, .font: NSFont.systemFont(ofSize: 14, weight: .semibold), .paragraphStyle: para])
        b.refusesFirstResponder = true
        b.translatesAutoresizingMaskIntoConstraints = false
        b.heightAnchor.constraint(equalToConstant: 36).isActive = true
        b.widthAnchor.constraint(greaterThanOrEqualToConstant: width).isActive = true
        b.restyle()
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
        p.layer?.cornerRadius = 22
        p.layer?.borderWidth = 1
        p.layer?.borderColor = NSColor(white: 1, alpha: 0.10).cgColor
        p.translatesAutoresizingMaskIntoConstraints = false
        return p
    }

    /** o painel no centro, sobre um fundo escurecido (clicar fora fecha) */
    private func place(_ p: NSView, in stack: NSStackView, width: CGFloat) {
        let host = PanelHost()
        host.wantsLayer = true
        host.layer?.backgroundColor = NSColor(white: 0, alpha: 0.42).cgColor
        host.onOutsideClick = { [weak self] in self?.closePanels() }
        host.translatesAutoresizingMaskIntoConstraints = false
        stack.translatesAutoresizingMaskIntoConstraints = false
        p.addSubview(stack)
        host.addSubview(p)
        root.addSubview(host)
        panelHost = host
        NSLayoutConstraint.activate([
            host.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            host.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            host.topAnchor.constraint(equalTo: root.topAnchor),
            host.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: p.leadingAnchor, constant: 28),
            stack.trailingAnchor.constraint(equalTo: p.trailingAnchor, constant: -28),
            stack.topAnchor.constraint(equalTo: p.topAnchor, constant: 24),
            stack.bottomAnchor.constraint(equalTo: p.bottomAnchor, constant: -24),
            p.widthAnchor.constraint(equalToConstant: width),
            p.centerXAnchor.constraint(equalTo: host.centerXAnchor),
            p.centerYAnchor.constraint(equalTo: host.centerYAnchor),
            p.topAnchor.constraint(greaterThanOrEqualTo: host.topAnchor, constant: 70),
        ])
    }

    private func buttonRow(_ bs: [SyncButton]) -> NSStackView {
        let r = NSStackView(views: bs)
        r.orientation = .horizontal
        r.spacing = 10
        r.distribution = .fillEqually
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
        popover?.performClose(nil)
        closePanels()
        let active = candForTrack(Int(player.currentVideoSubTitleIndex))
        let value = NSTextField(labelWithString: delayValueText())
        value.font = NSFont.monospacedDigitSystemFont(ofSize: 38, weight: .bold)
        value.textColor = .white
        value.alignment = .center
        syncValue = value
        let dimColor = NSColor(white: 1, alpha: 0.58)
        var views: [NSView] = [panelLabel("Sincronizar legenda", 20, bold: true)]
        var rows: [[SyncButton]] = []
        if let a = active, a.text != nil {
            // principal: acertar pela fala (só com legenda baixada ativa: a embutida não tem texto acessível)
            let sp = syncButton("Sincronizar pela fala", primary: true, width: 300)
            sp.onTap = { [weak self] in self?.showSpeechPicker(a) }
            rows.append([sp])
            views.append(buttonRow([sp]))
            views.append(panelLabel("Pause numa fala, escolha-a na lista e a legenda acerta sozinha.", 12, color: dimColor))
        } else {
            views.append(panelLabel("Sincronizar pela fala: ative uma legenda baixada (a embutida não permite). Use os botões abaixo.", 12, color: NSColor(red: 1, green: 0.78, blue: 0.34, alpha: 1)))
        }
        views.append(panelLabel("Atraso da legenda", 12, color: dimColor))
        views.append(value)
        let m30 = stepButton("−30 s", -30000), m5 = stepButton("−5 s", -5000), m1 = stepButton("−1 s", -1000)
        let p1 = stepButton("+1 s", 1000), p5 = stepButton("+5 s", 5000), p30 = stepButton("+30 s", 30000)
        let m05 = stepButton("−0,5 s", -500), p05 = stepButton("+0,5 s", 500)
        let reset = syncButton("Repor")
        reset.onTap = { [weak self] in self?.changeSubDelay(0, reset: true) }
        let close = syncButton("Fechar")
        close.onTap = { [weak self] in self?.closePanels() }
        views.append(panelLabel("Legenda DEPOIS da fala (adiantar)", 12, color: dimColor))
        views.append(buttonRow([m30, m5, m1]))
        views.append(panelLabel("Legenda ANTES da fala (atrasar)", 12, color: dimColor))
        views.append(buttonRow([p1, p5, p30]))
        views.append(buttonRow([m05, p05, reset]))
        views.append(panelLabel("Segure o botão para repetir (acelera). Setas escolhem, Enter aplica, Esc fecha.", 11, color: NSColor(white: 1, alpha: 0.45)))
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
        if let i = views.firstIndex(where: { $0 === value }) { stack.setCustomSpacing(14, after: views[i]) }
        for v in views where v is NSStackView { v.widthAnchor.constraint(equalTo: stack.widthAnchor).isActive = true }
        let p = makePanel()
        place(p, in: stack, width: 448)
        syncPanel = p
        syncRows = rows
        setSyncFocus(startRow + 1, 0) // como no Android: o foco começa no +1 s
        window?.makeFirstResponder(overlay)
        showControls()
    }

    private func closePanels() {
        panelHost?.removeFromSuperview(); panelHost = nil
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
        col.width = 556
        table.addTableColumn(col)
        table.headerView = nil
        table.backgroundColor = .clear
        table.rowHeight = 34
        table.intercellSpacing = NSSize(width: 0, height: 2)
        table.dataSource = self
        table.delegate = self
        table.target = self
        table.action = #selector(speechClicked)
        table.selectionHighlightStyle = .regular
        if #available(macOS 11.0, *) { table.style = .plain }
        let scroll = NSScrollView()
        scroll.documentView = table
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.scrollerStyle = .overlay
        scroll.drawsBackground = false
        scroll.borderType = .noBorder
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.heightAnchor.constraint(equalToConstant: 340).isActive = true
        scroll.widthAnchor.constraint(equalToConstant: 560).isActive = true
        let stack = NSStackView(views: [
            panelLabel("Qual fala estava sendo dita?", 20, bold: true, width: 560),
            panelLabel("Pause numa fala, escolha-a na lista e a legenda acerta sozinha. Setas e Enter, ou clique; Esc cancela.", 12, color: NSColor(white: 1, alpha: 0.58), width: 560),
            scroll,
        ])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 12
        let p = makePanel()
        place(p, in: stack, width: 616)
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

    /** o que o VLC diz sobre a leitura do arquivo (só para o teste: ver se dá para mostrar a faixa "carregada" na linha do tempo) */
    private func statsProbe() -> String {
        guard let m = player.media else { return "sem mídia" }
        var out: [String] = []
        for name in ["stats", "statistics"] where m.responds(to: NSSelectorFromString(name)) {
            out.append("\(name)=\(String(describing: m.value(forKey: name)))")
        }
        out.append("position=\(player.position)")
        return out.joined(separator: " ")
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
        case "subs": showSubtitlePopover()
        case "audio": showAudioPopover()
        case "popclose": popover?.performClose(nil)
        case "hover": timeline.testHover(n == 0 ? nil : Double(n) / 100)
        case "vol": volumeLevel = max(0, min(100, n)); volumeMuted = false; applyVolume()
        case "mute": toggleMute()
        case "stats": log("stats: " + statsProbe())
        case "showforce": controls.alphaValue = 1; topBar.alphaValue = 1; hideAt = Date().addingTimeInterval(30)
        case "dump":
            log("dump: controls alpha=\(controls.alphaValue) hidden=\(controls.isHidden) frame=\(controls.frame) topBar alpha=\(topBar.alphaValue) tocando=\(player.isPlaying) hideEm=\(hideAt.timeIntervalSinceNow) ordem=\(root.subviews.map { NSStringFromClass(type(of: $0)).split(separator: ".").last.map(String.init) ?? "" }) videoLayer=\(String(describing: videoView.layer))")
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
                log("layout: janela=\(Int(root.bounds.width))x\(Int(root.bounds.height)) barra=\(Int(controls.frame.width)) linha=\(Int(row.frame.width)) fitting=\(Int(row.fittingSize.width)) timeline=\(Int(timeline.frame.width)) itens=\(parts.joined(separator: " "))")
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

    @objc private func toggleScale(_ sender: Any?) {
        setScaleMode((scaleMode + 1) % 3)
        notice("Tela: \(PlayerWindowController.scaleNames[scaleMode])", seconds: 1.5)
        showControls()
    }

    func setScaleMode(_ m: Int) {
        scaleMode = max(0, min(2, m))
        scaleButton?.toolTip = "Tela: \(PlayerWindowController.scaleNames[scaleMode]) (clique para trocar)"
        scaleButton?.accent = scaleMode != 0
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
    func windowDidResize(_ notification: Notification) {
        applyScale()
        DispatchQueue.main.async { [weak self] in self?.placeTrafficLights() }
    }
    func windowDidEnterFullScreen(_ notification: Notification) { fsButton.symbol = "arrow.down.right.and.arrow.up.left"; fsButton.toolTip = "Sair da tela cheia (F)" }
    func windowDidExitFullScreen(_ notification: Notification) {
        fsButton.symbol = "arrow.up.left.and.arrow.down.right"
        fsButton.toolTip = "Tela cheia (F)"
        DispatchQueue.main.async { [weak self] in self?.placeTrafficLights() }
    }

    override func close() {
        finish()
        super.close()
    }

    private func finish() {
        if finished { return }
        finished = true
        popover?.performClose(nil)
        popover = nil
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


/** botão do painel de sincronização: clique = onTap; segurar (mouse) repete onHold a cada ~150 ms, depois de 0,4 s; borda de destaque = foco do teclado */
final class SyncButton: NSButton {
    var onTap: (() -> Void)?
    /** segurando: segundos desde o começo do segurar */
    var onHold: ((TimeInterval) -> Void)?
    private var holdTimer: Timer?
    private var holdStart = Date()
    private var held = false
    private var hovering = false { didSet { restyle() } }
    private var pressedLook = false { didSet { restyle() } }
    private var tracking: NSTrackingArea?
    /** botão principal: branco com letra preta */
    var primary = false
    var focused = false { didSet { restyle() } }

    override var acceptsFirstResponder: Bool { false }
    override var mouseDownCanMoveWindow: Bool { false }

    func restyle() {
        wantsLayer = true
        layer?.cornerRadius = 11
        if primary {
            layer?.backgroundColor = NSColor(white: 1, alpha: pressedLook ? 0.7 : hovering ? 0.88 : 0.96).cgColor
            layer?.borderWidth = focused ? 3 : 0
            layer?.borderColor = GF.red.cgColor
        } else {
            let a: CGFloat = pressedLook ? 0.30 : focused ? 0.24 : hovering ? 0.19 : 0.12
            layer?.backgroundColor = NSColor(white: 1, alpha: a).cgColor
            layer?.borderWidth = focused ? 2 : 0
            layer?.borderColor = NSColor(white: 1, alpha: 0.95).cgColor
        }
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let t = tracking { removeTrackingArea(t) }
        let t = NSTrackingArea(rect: bounds, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect], owner: self, userInfo: nil)
        addTrackingArea(t)
        tracking = t
    }
    override func mouseEntered(with event: NSEvent) { hovering = true }
    override func mouseExited(with event: NSEvent) { hovering = false }

    override func mouseDown(with event: NSEvent) {
        held = false
        holdStart = Date()
        holdTimer?.invalidate()
        if onHold != nil {
            holdTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self] _ in self?.startRepeat() }
        }
        pressedLook = true
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
        pressedLook = false
        if !held && bounds.contains(convert(event.locationInWindow, from: nil)) { onTap?() }
        held = false
    }
}

/** linha escolhida da lista de falas: retângulo claro arredondado (no lugar do azul do sistema) */
final class SpeechRowView: NSTableRowView {
    override func drawSelection(in dirtyRect: NSRect) {
        let r = bounds.insetBy(dx: 2, dy: 0)
        NSColor(white: 1, alpha: 0.18).setFill()
        NSBezierPath(roundedRect: r, xRadius: 9, yRadius: 9).fill()
    }
    override var isEmphasized: Bool { get { false } set {} }
}

// lista de falas do "Sincronizar pela fala"
extension PlayerWindowController: NSTableViewDataSource, NSTableViewDelegate {
    func numberOfRows(in tableView: NSTableView) -> Int { return speechCues.count }

    func tableView(_ tableView: NSTableView, rowViewForRow row: Int) -> NSTableRowView? { SpeechRowView() }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let c = speechCues[row]
        let one = c.text.replacingOccurrences(of: "\n", with: " ")
        let time = NSTextField(labelWithString: PlayerWindowController.clockString(c.startMs))
        time.font = .monospacedDigitSystemFont(ofSize: 13, weight: .medium)
        time.textColor = NSColor(white: 1, alpha: 0.55)
        time.translatesAutoresizingMaskIntoConstraints = false
        let text = NSTextField(labelWithString: one)
        text.font = .systemFont(ofSize: 14)
        text.textColor = .white
        text.lineBreakMode = .byTruncatingTail
        text.translatesAutoresizingMaskIntoConstraints = false
        let cell = NSView()
        cell.addSubview(time)
        cell.addSubview(text)
        NSLayoutConstraint.activate([
            time.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 12),
            time.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
            time.widthAnchor.constraint(equalToConstant: 52),
            text.leadingAnchor.constraint(equalTo: time.trailingAnchor, constant: 10),
            text.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -12),
            text.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
        ])
        return cell
    }
}
