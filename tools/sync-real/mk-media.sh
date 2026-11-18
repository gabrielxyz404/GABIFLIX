#!/bin/bash
# Material "de verdade" para a prova da sincronizacao da legenda: a partir do trecho de 20 min (clip.mkv, com as legendas certa/+3/-2/outro)
# gera arquivos com o aspecto de um filme real: video a bitrate alto e constante (CBR com enchimento, como um WEB-DL/remux) e audio multicanal
# (AC3/EAC3/DTS/TrueHD 5.1) com varias faixas (original + dublagem em PT). Uso: mk-media.sh DIR
set -ex
D=${1:-/tmp/media}
cd "$D"
# 5.1 a partir do estereo: fala no centro (como num filme), um pouco nas laterais
# video preto (so as legendas aparecem na tela: da para medir quando cada fala some e volta) com o audio do trecho; e a legenda certa deslocada +36 s
ffmpeg -nostdin -loglevel error -y -f lavfi -i color=c=black:s=640x360:r=24 -i clip.mkv -map 0:v -map 1:a -c:v libx264 -preset veryfast -crf 40 -c:a aac -b:a 96k -ac 2 -t 1200 -shortest blk.mkv
python3 - <<'PY'
import re
t = open("certa.srt", encoding="utf-8").read()
def sh(m):
    def f(h, mi, s, ms):
        return (int(h) * 3600 + int(mi) * 60 + int(s)) * 1000 + int(ms)
    a, b = f(*m.group(1, 2, 3, 4)), f(*m.group(5, 6, 7, 8))
    a += 36000; b += 36000
    g = lambda x: "%02d:%02d:%02d,%03d" % (x // 3600000, x // 60000 % 60, x // 1000 % 60, x % 1000)
    return g(a) + " --> " + g(b)
open("mais36.srt", "w", encoding="utf-8").write(re.sub(r"(\d+):(\d+):(\d+),(\d+) --> (\d+):(\d+):(\d+),(\d+)", sh, t))
PY
if [ -f ONLY_D ]; then ls -la; exit 0; fi
PAN="pan=5.1|FL=0.45*FL|FR=0.45*FR|FC=0.7*FL+0.7*FR|LFE=0.0*FL|BL=0.2*FL|BR=0.2*FR"
vid() { # $1 = saida, $2 = bitrate, $3 = duracao (s)
  ffmpeg -nostdin -loglevel error -y -t "$3" -i clip.mkv -an -vf "scale=1280:720,format=yuv420p" -c:v libx264 -preset veryfast \
    -b:v "$2" -minrate "$2" -maxrate "$2" -bufsize "$2" -x264-params nal-hrd=cbr:force-cfr=1 -r 24 "$1"
}
[ -f ONLY_B ] || vid v20.mkv 20M 1200
vid v4.mkv 4M 600
if [ ! -f ONLY_B ]; then
# A) 20 Mbit/s, 20 min: AC3 5.1 original (padrao) + AAC estereo "por" (dublagem)
ffmpeg -nostdin -loglevel error -y -i v20.mkv -i clip.mkv -map 0:v -map 1:a -map 1:a -c:v copy \
  -filter:a:0 "$PAN" -c:a:0 ac3 -b:a:0 640k -c:a:1 aac -b:a:1 128k -ac:a:1 2 \
  -metadata:s:a:0 language=eng -metadata:s:a:0 title="Original 5.1" -metadata:s:a:1 language=por -metadata:s:a:1 title="Portugues" \
  -disposition:a:0 default -disposition:a:1 0 real-ac3.mkv
fi
# controle: o MESMO trecho de 10 min, audio estereo AAC e AC3 5.1
ffmpeg -nostdin -loglevel error -y -t 600 -i v4.mkv -i clip.mkv -map 0:v -map 1:a -c:v copy -t 600 -c:a aac -b:a 128k -ac 2 -metadata:s:a:0 language=eng real-aac.mkv
ffmpeg -nostdin -loglevel error -y -t 600 -i v4.mkv -i clip.mkv -map 0:v -map 1:a -c:v copy -t 600 -filter:a "$PAN" -c:a ac3 -b:a 640k -metadata:s:a:0 language=eng real-ac3b.mkv
# B) so o codec muda (10 min, 4 Mbit/s): EAC3, DTS, TrueHD, todos 5.1
for c in "eac3:eac3:640k:" "dts:dca:754k:-strict -2" "truehd:truehd:0:-strict -2"; do
  n=${c%%:*}; r=${c#*:}; enc=${r%%:*}; r=${r#*:}; br=${r%%:*}; ex=${r#*:}
  BR=(); [ "$br" != 0 ] && BR=(-b:a "$br")
  ffmpeg -nostdin -loglevel error -y -t 600 -i v4.mkv -i clip.mkv -map 0:v -map 1:a -c:v copy -t 600 -filter:a "$PAN" -c:a $enc "${BR[@]}" $ex \
    -metadata:s:a:0 language=eng real-$n.mkv || echo "FALHOU gerar $n"
done
rm -f v20.mkv v4.mkv
ls -la
for f in real-*.mkv; do ffprobe -v error -show_entries stream=index,codec_name,channels,bit_rate:format=duration,bit_rate -of compact "$f"; done
