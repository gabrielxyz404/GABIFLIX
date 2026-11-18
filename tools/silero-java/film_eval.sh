#!/bin/bash
# Roda o motor de sincronia (PreCheck: 3 janelas de 90 s por densidade, o mesmo AudioAligner do app) sobre as 4 legendas do material de um filme
# (certa, +3 s, -2 s e a de outro filme), com 3 VADs: java (Silero em Java puro = o app agora), ort (Silero no ONNX Runtime = o app antes) e dsp (o ultimo recurso).
# Uso: film_eval.sh MEDIA_DIR SAIDA.txt     (precisa de PC_CP com as classes compiladas e do ONNX Runtime, MODEL com o caminho do silero_vad.onnx)
M=$1; OUT=$2
: > "$OUT"
ffmpeg -nostdin -loglevel error -y -i "$M/clip.mkv" -vn -ac 1 -ar 16000 -c:a pcm_s16le /tmp/a16.wav || exit 1
for f in certa mais3 menos2 outro; do
  [ -f "$M/$f.srt" ] || continue
  for e in java ort dsp; do
    l=$(VAD=$e java -cp "$PC_CP" PreCheck /tmp/a16.wav "$M/$f.srt" "$MODEL" 2>&1 | grep '^PRE' | sed 's/ janelas=.*//')
    [ -z "$l" ] && l="PRE ERRO"
    echo "$f $e $l" | tee -a "$OUT"
  done
done
