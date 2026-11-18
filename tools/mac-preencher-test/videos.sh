#!/bin/bash
# Vídeos sintéticos (1080p) e a legenda PT de 3 linhas compridas. $1 = pasta de saída
set -e
mkdir -p "$1"; cd "$1"
python3 - <<'PY'
def ts(ms):
    h, r = divmod(ms, 3600000); m, r = divmod(r, 60000); s, ms = divmod(r, 1000)
    return "%02d:%02d:%02d,%03d" % (h, m, s, ms)
l1 = "Você não sabe o que ela fez para nós, mas eu também estou aqui e isso é uma linha comprida de verdade"
l2 = "e esta é a segunda linha, ainda mais comprida do que a primeira, para ver se o recorte corta alguma letra"
l3 = "terceira linha: tudo isto tem de ficar inteiro dentro da janela, sem cortes nas pontas (%d)"
out = []
for i in range(40):
    a = 3000 + i * 6000
    out.append("%d\n%s --> %s\n%s\n%s\n%s\n" % (i + 1, ts(a), ts(a + 5200), l1, l2, l3 % (i + 1)))
open("leg.srt", "w", encoding="utf-8").write("\n".join(out))
PY
E="-c:v libx264 -preset veryfast -pix_fmt yuv420p -g 48 -c:a aac"
S="-f lavfi -i sine=frequency=440:sample_rate=48000"
# barra: 2,39:1 dentro de um quadro 16:9 (barras pretas gravadas)
ffmpeg -loglevel error -f lavfi -i testsrc2=size=1920x804:rate=24 $S -t 240 -vf "pad=1920:1080:0:138:black" $E barra.mkv
# wide: 2,39:1 de verdade
ffmpeg -loglevel error -f lavfi -i testsrc2=size=1920x800:rate=24 $S -t 240 $E wide.mkv
# hd: 16:9
ffmpeg -loglevel error -f lavfi -i testsrc2=size=1920x1080:rate=24 $S -t 240 $E hd.mkv
# pilar: 4:3 com barras laterais gravadas num quadro 16:9
ffmpeg -loglevel error -f lavfi -i testsrc2=size=1440x1080:rate=24 $S -t 240 -vf "pad=1920:1080:240:0:black" $E pilar.mkv
ls -la
