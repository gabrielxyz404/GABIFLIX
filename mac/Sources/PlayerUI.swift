// Peças visuais do player do FramePlay para Mac: botões de ícone (SF Symbols), linha do tempo, degradês e a lista em "popover".
// Só desenho e interação; a lógica do player (e das legendas) fica em PlayerWindow.swift.
import Cocoa

enum GF {
    /** vermelho do FramePlay (o mesmo do site) */
    static let red = NSColor(srgbRed: 0.898, green: 0.035, blue: 0.078, alpha: 1)
    static let dim = NSColor(white: 1, alpha: 0.62)
    static let faint = NSColor(white: 1, alpha: 0.40)

    static func symbol(_ name: String, _ size: CGFloat, _ weight: NSFont.Weight = .semibold) -> NSImage? {
        guard let img = NSImage(systemSymbolName: name, accessibilityDescription: nil) else { return nil }
        return img.withSymbolConfiguration(NSImage.SymbolConfiguration(pointSize: size, weight: weight))
    }

    static func label(_ s: String, size: CGFloat, weight: NSFont.Weight = .regular, color: NSColor = .white, mono: Bool = false) -> NSTextField {
        let t = NSTextField(labelWithString: s)
        t.font = mono ? NSFont.monospacedDigitSystemFont(ofSize: size, weight: weight) : NSFont.systemFont(ofSize: size, weight: weight)
        t.textColor = color
        t.lineBreakMode = .byTruncatingTail
        return t
    }
}

/** degradê que não recebe cliques: escurece o topo ou o fundo do vídeo para os controles se lerem bem */
final class ScrimView: NSView {
    private let gradient = CAGradientLayer()

    init(top: Bool, strength: CGFloat) {
        super.init(frame: .zero)
        wantsLayer = true
        let dark = NSColor(white: 0, alpha: strength).cgColor
        let clear = NSColor(white: 0, alpha: 0).cgColor
        gradient.colors = top ? [dark, clear] : [clear, dark]
        gradient.startPoint = CGPoint(x: 0.5, y: 1) // topo
        gradient.endPoint = CGPoint(x: 0.5, y: 0)   // fundo
        layer?.addSublayer(gradient)
    }

    required init?(coder: NSCoder) { fatalError("não usado") }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        gradient.frame = bounds
        CATransaction.commit()
    }

    override func hitTest(_ point: NSPoint) -> NSView? { nil }
}

/** vista que deixa passar os cliques onde não há nada dentro (a barra de cima não bloqueia o vídeo) */
final class PassThroughView: NSView {
    override func hitTest(_ point: NSPoint) -> NSView? {
        let v = super.hitTest(point)
        return v === self ? nil : v
    }
}

/** botão redondo com ícone (SF Symbol); mostra um fundo claro ao passar o mouse; sem o símbolo usa o texto de reserva */
final class IconButton: NSButton {
    private var hovering = false { didSet { refresh() } }
    private var pressed = false { didSet { refresh() } }
    private var tracking: NSTrackingArea?
    private let pointSize: CGFloat
    private let fallback: String
    private let solid: Bool
    var symbol: String { didSet { applyImage() } }
    /** ligado (ex.: legenda ativa): fundo mais claro */
    var accent = false { didSet { refresh() } }

    init(symbol: String, fallback: String, tip: String, size: CGFloat = 38, pointSize: CGFloat = 16, solid: Bool = false) {
        self.symbol = symbol
        self.fallback = fallback
        self.pointSize = pointSize
        self.solid = solid
        super.init(frame: NSRect(x: 0, y: 0, width: size, height: size))
        isBordered = false
        setButtonType(.momentaryChange)
        wantsLayer = true
        layer?.cornerRadius = size / 2
        toolTip = tip
        setAccessibilityLabel(tip)
        refusesFirstResponder = true
        translatesAutoresizingMaskIntoConstraints = false
        widthAnchor.constraint(equalToConstant: size).isActive = true
        heightAnchor.constraint(equalToConstant: size).isActive = true
        contentTintColor = solid ? .black : .white
        applyImage()
        refresh()
    }

    required init?(coder: NSCoder) { fatalError("não usado") }

    private func applyImage() {
        if let img = GF.symbol(symbol, pointSize) {
            image = img
            title = ""
            imagePosition = .imageOnly
        } else {
            image = nil
            imagePosition = .noImage
            attributedTitle = NSAttributedString(string: fallback, attributes: [
                .foregroundColor: solid ? NSColor.black : NSColor.white, .font: NSFont.systemFont(ofSize: 13, weight: .semibold)])
        }
    }

    private func refresh() {
        let a: CGFloat
        if solid { a = pressed ? 0.7 : hovering ? 0.88 : 1 }
        else { a = pressed ? 0.28 : accent ? 0.26 : hovering ? 0.16 : 0 }
        layer?.backgroundColor = NSColor(white: 1, alpha: a).cgColor
    }

    override var mouseDownCanMoveWindow: Bool { false }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

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
        pressed = true
        super.mouseDown(with: event)
        pressed = false
    }
}

/** linha do tempo: faixa já lida, faixa já carregada (se conhecida), bolinha e a hora sob o mouse; clicar ou arrastar muda o ponto */
final class TimelineView: NSView {
    var progress: Double = 0 { didSet { if oldValue != progress { needsDisplay = true } } }
    var buffered: Double = 0 { didSet { if oldValue != buffered { needsDisplay = true } } }
    /** fração (0...1) -> texto da hora, para a dica ao passar o mouse */
    var timeText: ((Double) -> String)?
    /** fração e se o mouse foi solto (true = confirma o salto) */
    var onScrub: ((Double, Bool) -> Void)?

    private var hoverX: CGFloat?
    private var dragging = false
    private var tracking: NSTrackingArea?
    private let inset: CGFloat = 8

    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { false }
    override var mouseDownCanMoveWindow: Bool { false }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override var intrinsicContentSize: NSSize { NSSize(width: NSView.noIntrinsicMetric, height: 40) }

    private func fraction(_ x: CGFloat) -> Double {
        let w = max(1, bounds.width - 2 * inset)
        return Double(min(max((x - inset) / w, 0), 1))
    }

    private func x(of f: Double) -> CGFloat { inset + CGFloat(f) * max(1, bounds.width - 2 * inset) }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let t = tracking { removeTrackingArea(t) }
        let t = NSTrackingArea(rect: bounds, options: [.mouseMoved, .mouseEnteredAndExited, .activeAlways, .inVisibleRect], owner: self, userInfo: nil)
        addTrackingArea(t)
        tracking = t
    }

    /** só para o teste automático: simula o mouse em cima da linha do tempo (f em 0...1; nil tira) */
    func testHover(_ f: Double?) { hoverX = f.map { x(of: $0) }; needsDisplay = true }

    override func mouseEntered(with event: NSEvent) { hoverX = convert(event.locationInWindow, from: nil).x; needsDisplay = true }
    override func mouseMoved(with event: NSEvent) { hoverX = convert(event.locationInWindow, from: nil).x; needsDisplay = true }
    override func mouseExited(with event: NSEvent) { if !dragging { hoverX = nil; needsDisplay = true } }

    override func mouseDown(with event: NSEvent) {
        dragging = true
        let px = convert(event.locationInWindow, from: nil).x
        hoverX = px
        let f = fraction(px)
        progress = f
        onScrub?(f, false)
    }

    override func mouseDragged(with event: NSEvent) {
        let px = convert(event.locationInWindow, from: nil).x
        hoverX = px
        let f = fraction(px)
        progress = f
        onScrub?(f, false)
    }

    override func mouseUp(with event: NSEvent) {
        let p = convert(event.locationInWindow, from: nil)
        dragging = false
        if !bounds.contains(p) { hoverX = nil }
        let f = fraction(p.x)
        progress = f
        onScrub?(f, true)
        needsDisplay = true
    }

    override func draw(_ dirtyRect: NSRect) {
        let active = hoverX != nil || dragging
        let h: CGFloat = active ? 6 : 4
        let cy = bounds.height - 12
        let left = inset, right = bounds.width - inset
        func bar(_ to: CGFloat, _ color: NSColor) {
            guard to > left else { return }
            let r = NSRect(x: left, y: cy - h / 2, width: min(to, right) - left, height: h)
            color.setFill()
            NSBezierPath(roundedRect: r, xRadius: h / 2, yRadius: h / 2).fill()
        }
        bar(right, NSColor(white: 1, alpha: 0.22))
        if buffered > progress { bar(x(of: min(1, buffered)), NSColor(white: 1, alpha: 0.38)) }
        let px = x(of: min(1, max(0, progress)))
        bar(px, GF.red)
        if active {
            let d: CGFloat = 14
            let k = NSRect(x: px - d / 2, y: cy - d / 2, width: d, height: d)
            NSColor.white.setFill()
            NSBezierPath(ovalIn: k).fill()
        }
        // hora sob o mouse
        if let hx = hoverX, let text = timeText?(fraction(hx)) {
            let attrs: [NSAttributedString.Key: Any] = [.font: NSFont.monospacedDigitSystemFont(ofSize: 12, weight: .semibold), .foregroundColor: NSColor.white]
            let sz = (text as NSString).size(withAttributes: attrs)
            let bw = sz.width + 16, bh: CGFloat = 22
            var bx = hx - bw / 2
            bx = min(max(bx, 0), bounds.width - bw)
            let r = NSRect(x: bx, y: 0, width: bw, height: bh)
            NSColor(white: 0, alpha: 0.78).setFill()
            NSBezierPath(roundedRect: r, xRadius: 7, yRadius: 7).fill()
            (text as NSString).draw(at: NSPoint(x: r.minX + 8, y: r.minY + (bh - sz.height) / 2), withAttributes: attrs)
        }
    }
}

/** uma linha da lista do popover (legendas, áudio): marca de escolhida, título e uma linha menor; muda de cor sob o mouse */
struct PopItem {
    var title: String
    var detail: String? = nil
    var checked = false
    var enabled = true
    var action: (() -> Void)? = nil
}

final class PopRow: NSView {
    private let item: PopItem
    private var hovering = false { didSet { layer?.backgroundColor = NSColor(white: 1, alpha: hovering && item.enabled && item.action != nil ? 0.12 : 0).cgColor } }
    private var tracking: NSTrackingArea?
    var onPick: (() -> Void)?

    init(item: PopItem, width: CGFloat) {
        self.item = item
        super.init(frame: .zero)
        wantsLayer = true
        layer?.cornerRadius = 8
        translatesAutoresizingMaskIntoConstraints = false

        let mark = NSImageView()
        mark.translatesAutoresizingMaskIntoConstraints = false
        if item.checked, let img = GF.symbol("checkmark", 12, .bold) {
            mark.image = img
            mark.contentTintColor = .white
        }
        let title = GF.label(item.title, size: 13, weight: item.checked ? .semibold : .regular, color: item.enabled ? .white : GF.faint)
        title.lineBreakMode = .byTruncatingTail
        var texts: [NSView] = [title]
        if let d = item.detail, !d.isEmpty {
            let dl = GF.label(d, size: 11, color: GF.dim)
            dl.lineBreakMode = .byTruncatingTail
            texts.append(dl)
        }
        let v = NSStackView(views: texts)
        v.orientation = .vertical
        v.alignment = .leading
        v.spacing = 1
        v.translatesAutoresizingMaskIntoConstraints = false
        addSubview(mark)
        addSubview(v)
        NSLayoutConstraint.activate([
            widthAnchor.constraint(equalToConstant: width),
            mark.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 8),
            mark.centerYAnchor.constraint(equalTo: centerYAnchor),
            mark.widthAnchor.constraint(equalToConstant: 16),
            v.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 30),
            v.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -10),
            v.topAnchor.constraint(equalTo: topAnchor, constant: 6),
            v.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -6),
            heightAnchor.constraint(greaterThanOrEqualToConstant: 30),
        ])
    }

    required init?(coder: NSCoder) { fatalError("não usado") }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let t = tracking { removeTrackingArea(t) }
        let t = NSTrackingArea(rect: bounds, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect], owner: self, userInfo: nil)
        addTrackingArea(t)
        tracking = t
    }

    override func mouseEntered(with event: NSEvent) { hovering = true }
    override func mouseExited(with event: NSEvent) { hovering = false }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override func mouseDown(with event: NSEvent) {
        guard item.enabled, item.action != nil else { return }
        onPick?()
    }
}

/** popover escuro com título, linhas escolhíveis e notas pequenas (usado nos botões de legendas e de áudio) */
enum PopList {
    static let width: CGFloat = 300

    static func make(title: String, items: [PopItem], notes: [String], footer: [PopItem] = [], close: @escaping () -> Void) -> NSPopover {
        let pad: CGFloat = 10
        let inner = width - 2 * pad
        var views: [NSView] = []
        let head = GF.label(title.uppercased(), size: 11, weight: .semibold, color: GF.faint)
        views.append(head)
        for it in items {
            let r = PopRow(item: it, width: inner)
            r.onPick = { close(); it.action?() }
            views.append(r)
        }
        if !notes.isEmpty {
            let sep = NSBox()
            sep.boxType = .separator
            sep.translatesAutoresizingMaskIntoConstraints = false
            sep.widthAnchor.constraint(equalToConstant: inner).isActive = true
            views.append(sep)
            for n in notes {
                let l = NSTextField(wrappingLabelWithString: n)
                l.font = .systemFont(ofSize: 11)
                l.textColor = GF.dim
                l.maximumNumberOfLines = 3
                l.preferredMaxLayoutWidth = inner - 8
                l.translatesAutoresizingMaskIntoConstraints = false
                l.widthAnchor.constraint(equalToConstant: inner - 8).isActive = true
                views.append(l)
            }
        }
        if !footer.isEmpty {
            let sep = NSBox()
            sep.boxType = .separator
            sep.translatesAutoresizingMaskIntoConstraints = false
            sep.widthAnchor.constraint(equalToConstant: inner).isActive = true
            views.append(sep)
            for it in footer {
                let r = PopRow(item: it, width: inner)
                r.onPick = { close(); it.action?() }
                views.append(r)
            }
        }
        let stack = NSStackView(views: views)
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 2
        stack.setCustomSpacing(6, after: head)
        stack.translatesAutoresizingMaskIntoConstraints = false

        let content = NSView()
        content.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: pad),
            stack.topAnchor.constraint(equalTo: content.topAnchor, constant: pad + 2),
        ])
        stack.layoutSubtreeIfNeeded()
        let fit = stack.fittingSize
        content.frame = NSRect(x: 0, y: 0, width: width, height: fit.height + 2 * pad + 4)

        let vc = NSViewController()
        vc.view = content
        let pop = NSPopover()
        pop.contentViewController = vc
        pop.behavior = .transient
        pop.animates = true
        pop.appearance = NSAppearance(named: .vibrantDark)
        return pop
    }
}
