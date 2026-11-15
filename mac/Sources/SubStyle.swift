import Foundation

/** Estilo das legendas no player do Mac.
    O VLCKit só aplica estas opções quando elas vão na criação do VLCMediaPlayer (biblioteca): como opção da mídia
    (":sub-text-scale=...") o libvlc ignora, e as legendas saíam no tamanho padrão do VLC (linha ≈ 8,4 % da altura do vídeo,
    texto quebrado em 4 linhas). Medido no CI (workflow mac-legendas-medir) com o mesmo VLCKit do app. */
enum SubStyle {
    /** escala do texto da legenda em % do tamanho padrão do VLC no Mac; com 55 a linha fica ≈ 4,6 % da altura do vídeo (estilo Netflix) */
    static let textScale = 55
    static var libraryOptions: [String] { ["--sub-text-scale=\(textScale)"] }
}
