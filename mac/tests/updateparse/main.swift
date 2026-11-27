import Foundation

var fails = 0
func check<T: Equatable>(_ what: String, _ got: T, _ want: T) {
    if got == want { print("ok    \(what): \(got)") } else { print("FALHA \(what): obtido \(got), esperado \(want)"); fails += 1 }
}

// (a) release antiga
let oldName = "FramePlay para Mac"
let oldBody = "Versão 1.0.45 — Mac com chip Apple (M1, M2, M3, M4…), macOS 11 ou mais novo.\nInstalar: ..."
let a = UpdateParse.parse(name: oldName, body: oldBody)
check("antigo: build", a.build, 45)
check("antigo: marketing", a.marketing ?? "", "1.0.45")

// (b) release nova (o corpo que o mac.yml publica, com o run 46)
let newName = "FramePlay para Mac"
let newBody = "Versão 2.1 — Mac com chip Apple (M1, M2, M3, M4…), macOS 11 ou mais novo.\n<!-- build 1.0.46 -->\nInstalar: ..."
let b = UpdateParse.parse(name: newName, body: newBody)
check("novo: build", b.build, 46)
check("novo: marketing", b.marketing ?? "", "2.1")

// (c) o regex ANTIGO exacto (main.swift até 1.0.45) sobre name+" "+body da release nova
let text = "\(newName) \(newBody)"
let re = try! NSRegularExpression(pattern: "1\\.0\\.(\\d+)")
let m = re.firstMatch(in: text, range: NSRange(location: 0, length: (text as NSString).length))!
check("regex antigo sobre release nova", Int((text as NSString).substring(with: m.range(at: 1))) ?? -1, 46)

// (d) vários dígitos; sem marcador não há build
check("build 123", UpdateParse.parse(name: newName, body: "Versão 2.1 — x\n<!-- build 1.0.123 -->").build, 123)
check("sem marcador", UpdateParse.parse(name: newName, body: "Versão 2.1 — x").build, -1)

exit(fails == 0 ? 0 : 1)
