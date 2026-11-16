#!/bin/bash
# Gera os vídeos de teste (MKV com vídeo H.264 pesado + áudio em vários codecs) a partir de fala real (Sintel, filme aberto da Blender).
# Uso: mkmedia.sh DIR_SAIDA SINTEL_OGV [bitrate_video=6M] [duracao_s=1800]
set -e
OUT=$1; SRC=$2; VB=${3:-6M}; DUR=${4:-1800}
mkdir -p "$OUT"; cd "$OUT"
# áudio de fala real em loop até DUR segundos (48 kHz estéreo)
ffmpeg -nostdin -loglevel error -y -stream_loop 3 -i "$SRC" -vn -map 0:a:0 -t $DUR -ac 2 -ar 48000 -c:a pcm_s16le speech.wav
# vídeo: ruído para forçar taxa alta (parecido com um remux 1080p)
ffmpeg -nostdin -loglevel error -y -f lavfi -i "testsrc2=size=1280x720:rate=24" -vf "noise=alls=12:allf=t" -t $DUR -c:v libx264 -preset ultrafast -b:v $VB -maxrate $VB -bufsize 12M -g 48 -pix_fmt yuv420p video.mkv
ls -la
# 5.1 a partir do estéreo (como os filmes reais)
P51="pan=5.1|FL=FL|FR=FR|FC=0.5*FL+0.5*FR|LFE=0.1*FL+0.1*FR|BL=0.7*FL|BR=0.7*FR"
mux() { # nome codec_args
  local n=$1; shift
  ffmpeg -nostdin -loglevel error -y -i video.mkv -i speech.wav -map 0:v -map 1:a -c:v copy "$@" "$n.mkv" || echo "FALHOU $n"
}
mux ac3   -af "$P51" -c:a ac3 -b:a 640k
mux eac3  -af "$P51" -c:a eac3 -b:a 768k
mux aac   -c:a aac -b:a 192k
mux dts   -af "$P51" -c:a dca -strict -2 -b:a 1509k
mux truehd -af "$P51" -c:a truehd -strict -2
mux flac  -c:a flac
ls -la *.mkv
