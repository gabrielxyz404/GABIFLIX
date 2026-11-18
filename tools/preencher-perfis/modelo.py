#!/usr/bin/env python3
"""Modelo do Preencher (mesma conta do PlayerActivity.zoomVideo / PlayerWindow.applyScale, sem número de aparelho nenhum):
só entram a proporção da tela, a proporção da imagem ativa (medida: sem as barras pretas gravadas no vídeo) e o limite de zoom.

  f      = max(L/aw, A/ah)            zoom que cobriria a tela inteira (imagem ativa ajustada em Normal: aw x ah; tela L x A)
  f      = min(f, 1,45 * L / aw)      (celular/TV) nunca mais de 1,45x a largura da tela
  f      = min(f, ZOOM_MAX)           o limite escolhido (1,10)
  barras = o que sobra da tela depois do zoom (por lado, em % da altura/largura da tela)
  corte  = o que sai da imagem ativa por lado (em % da largura/altura da própria imagem)

Uso: modelo.py [ZOOM_MAX] — escreve a tabela em texto (Markdown) no stdout."""
import sys

ZOOM = float(sys.argv[1]) if len(sys.argv) > 1 and __name__ == "__main__" else 1.10
COVER_MAX_WIDTH = 1.45

# perfis de tela (largura x altura em pixels, janela/tela de reprodução em paisagem; só a proporção conta)
PERFIS = [
    ("TV 55\" Fire TV Stick 4K Max (16:9, 1920x1080)", 1920, 1080, "do usuário"),
    ("Samsung Galaxy S26 Ultra (19,5:9, 3120x1440)", 3120, 1440, "do usuário"),
    ("MacBook 13\" janela 16:10 (1440x900)", 1440, 900, "do usuário"),
    ("Celular 20:9 (2400x1080)", 2400, 1080, "comum"),
    ("Tablet 16:10 (2560x1600)", 2560, 1600, "comum"),
    ("Tablet 4:3 (2048x1536)", 2048, 1536, "comum"),
    ("Mac janela 21:9 ultrawide (2520x1080)", 2520, 1080, "comum"),
]
# conteúdo: proporção da imagem ativa (sem barras gravadas)
CONTEUDOS = [("2,39:1", 2.39), ("2,00:1", 2.0), ("1,85:1", 1.85), ("16:9", 16 / 9), ("4:3", 4 / 3)]


def linha(L, A, asp, zoom_max):
    # Normal: imagem ativa ajustada dentro da tela
    if asp >= L / A:
        aw, ah = float(L), L / asp
    else:
        aw, ah = A * asp, float(A)
    bar_v0 = (A - ah) / 2 / A * 100   # barra de cima (= de baixo), % da altura da tela
    bar_h0 = (L - aw) / 2 / L * 100   # barra de cada lado, % da largura da tela
    f = max(L / aw, A / ah)
    f = min(f, COVER_MAX_WIDTH * L / aw)
    f = min(f, zoom_max)
    f = max(1.0, f)
    w2, h2 = aw * f, ah * f
    bar_v1 = max(0.0, (A - h2) / 2 / A * 100)
    bar_h1 = max(0.0, (L - w2) / 2 / L * 100)
    cut_h = max(0.0, (w2 - L) / 2 / w2 * 100)   # por lado, % da largura da imagem
    cut_v = max(0.0, (h2 - A) / 2 / h2 * 100)   # por lado, % da altura da imagem
    return f, bar_v0, bar_h0, bar_v1, bar_h1, cut_h, cut_v


def fmt(x):
    return ("%.1f" % x).replace(".", ",")


def main():
    print("Limite de zoom: %sx (conta igual no Android, TV e Mac; só usa a proporção da tela e as barras medidas)\n" % fmt(ZOOM).replace(",", ","))
    for nome, L, A, tipo in PERFIS:
        print("### %s [%s] proporção %s:1" % (nome, tipo, fmt(L / A)))
        print("| Conteúdo | Zoom | Barras antes (por lado) | Barras com limite (por lado) | Corte da imagem por lado |")
        print("|---|---|---|---|---|")
        for cn, asp in CONTEUDOS:
            f, v0, h0, v1, h1, ch, cv = linha(L, A, asp, ZOOM)
            antes = ("%s%% em cima/baixo" % fmt(v0)) if v0 > 0.05 else ("%s%% nas laterais" % fmt(h0)) if h0 > 0.05 else "sem barras"
            depois = ("%s%% em cima/baixo" % fmt(v1)) if v1 > 0.05 else ("%s%% nas laterais" % fmt(h1)) if h1 > 0.05 else "sem barras"
            corte = []
            if ch > 0.05: corte.append("%s%% nas laterais" % fmt(ch))
            if cv > 0.05: corte.append("%s%% em cima/baixo" % fmt(cv))
            print("| %s | %sx | %s | %s | %s |" % (cn, ("%.3f" % f).replace(".", ","), antes, depois, " e ".join(corte) if corte else "nenhum"))
        print()


if __name__ == "__main__":
    main()
