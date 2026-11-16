# Sincronização automática de legendas pelo áudio (FramePlay)

Motor em Java puro (portável 1:1 para Swift) que alinha uma legenda ao ÁUDIO do vídeo: acha o deslocamento, a escala (fps) e, se preciso, cortes,
e diz com que confiança. Esta fase entrega só o motor, a avaliação e a prova de captura; a integração nos apps é da fase seguinte.

Tudo aqui foi medido em CI (GitHub Actions) com filmes de domínio público do archive.org e legendas reais do addon `opensubtitles-v3`
(nada de mídia no repositório). Os números abaixo vêm dos runs citados e podem ser refeitos com os workflows `audio-motor*.yml`
(disparam por push nos branches `run-audio`, `run-audio-cap`, `run-audio-capmac`, `run-audio-capand`).

## 1. Decisões (resumo)

| Tema | Decisão | Por quê |
|---|---|---|
| Alinhamento | correlação cruzada normalizada (FFT) entre a probabilidade de fala do VAD e a máscara de falas da legenda, em 2 passes (40 ms e 10 ms), com busca de escala | 100 ms por resolução em Java, sem rede, sem modelo grande |
| VAD | **DSP próprio** (22 atributos + MLP 22-16-1 treinado com as próprias legendas como rótulo fraco, ~1 kB de pesos) | mais exato que WebRTC e no nível do Silero; sem ONNX (+27 MB de APK) |
| Alternativas medidas | WebRTC GMM (port Java bit-exato com o C: 0 quadros diferentes em 12000, modos 2 e 3) e Silero via ONNX | ver seção 4 |
| Captura | segunda instância do libVLC/VLCKit com **cadeia de saída `sout` (transcode → WAV)**: não toca, não segue o relógio | 50–200x o tempo real e corte exato (seção 5) |
| Aplicação | só se aceita pelo motor; a legenda com atraso manual salvo nunca é mexida | seção 6 |

## 2. Algoritmo

Modelo: `tempoCorrigido = scale · tempoLegenda + offsetMs` (igual a `SubtitleAlign.transform(text, k, offsetMs)` do app: `ms*k + offsetMs`).
Defasagem entre as duas linhas do tempo `L(u) = u − s = α·u + β` com `α = 1 − 1/scale`, `β = offset/scale`.

Entradas: janelas de PCM mono 16 kHz já passadas pelo VAD (`Window{startMs, p[] a 100 Hz}`), lista de falas (`Cue`), duração do vídeo.
Saída (`Result`): `accepted`, `scale`, `offsetMs`, `segments` (por partes), `confidence`, evidências (`z`, `margin`, `nccMean`, `blocksAgree/Valid`, `f1`),
`more` (janelas extras que o motor quer) e `reason` (texto curto do motivo da recusa).

1. **Máscara da legenda.** Cada fala vira um intervalo de fala `[início, início + min(duração, max(700, 500 + 62·caracteres))]` (a fala real é mais curta que o tempo
   em tela); marcações de não-fala (`[música]`, `(risos)`, `♪`) são descartadas (`Cue.speech`).
2. **Passo grosso (40 ms).** Cada janela é cortada em blocos de 30 s; cada bloco é correlacionado (FFT 65536) com a máscara em ±1280 s
   (`lagFrames = 32000`) com NCC normalizada localmente (somas prefixadas, piso de variância `0,03·n`). Guarda os 12 melhores picos de cada bloco.
3. **Hipóteses.** Retas `L(u) = α·u + β` geradas (a) por pares de picos de blocos de janelas diferentes e (b) por cada pico × cada razão de fps
   conhecida (24/23,976, 25/23,976, 25/24, 30/29,97, 25/29,97, 30/25, 30/24 e inversas). Pontuação = soma, sobre os blocos, do melhor NCC a ±9 quadros da defasagem prevista.
   **Preferência por escala de fps:** se a melhor reta com escala "de fps" tem `z ≥ minZ` e explica ≥ 40% dos blocos, ela vence a reta livre (retas livres por 2–3 janelas
   geravam falsos aceites e escalas espúrias).
4. **Confiança.** `z` = (melhor − média)/desvio de 4000 retas aleatórias; `margin` = (melhor − 2ª "diferente")/melhor; `blocksAgree/Valid`; NCC fina; F1 de cobertura.
5. **Passo fino (10 ms).** Por janela, NCC exata com a máscara deformada pela hipótese; pico com interpolação parabólica; ajuste de mínimos quadrados ponderado de `(scale, offset)` com descarte de
   janelas com resíduo > 250 ms; **encaixe** na escala de fps mais próxima só se os resíduos continuarem ≤ 120 ms; 3 iterações.
6. **Decisão.** `accepted = z ≥ 6 ∧ concordância ≥ 55% ∧ NCC ≥ 0,10 ∧ margem ≥ 0,15` (valores calibrados na seção 4). Escala fora das razões de fps (`scaleKnown = false`) exige
   `z ≥ 12 ∧ NCC ≥ 0,35`: todos os falsos aceites medidos nos negativos tinham escala livre.
7. **Ajuste por partes (cortes).** Com o modelo global aceito (ou `z ≥ minZ` mas com "pico ambíguo", que costuma ser o outro trecho), cada bloco é rotulado pelo modelo que o explica
   (NCC ≥ 0,25 e ≥ 70% do maior pico do bloco). Blocos sem explicação (≥ 2) geram um novo deslocamento (mesma escala), testado com z ≥ 5 contra deslocamentos aleatórios.
   O corte entre dois trechos vai para o **maior intervalo sem falas** entre o último bloco de um e o primeiro do outro (em tempo de legenda: `Segment.fromSubMs`).
   Se o intervalo ainda tem > 50 s, o motor pede uma janela de 30 s no meio (`Result.more`).
8. **Sessão progressiva** (`AudioSyncSession`): o app captura o plano inicial, chama `solve()`; enquanto vier `more`, captura, `addWindow`, `solve()` (até 6 rodadas, ≤ 9 janelas).

Parâmetros (`AudioAligner.Params`): `frameMs 40`, `blockMs 30000`, `lagFrames 32000` (±1280 s), `maxOffsetMs 600000`, `maxAlpha 0.125`, `topK 12`, `tolFrames 9`, `smoothMs 60`,
`randomHyps 4000`, `minZ 6`, `minAgreeFrac 0.55`, `minNcc 0.10`, `minMargin 0.15`, `minZFree 12`, `minNccFree 0.35`, `snapAlpha 3e-3`, `snapResMs 120`, `knownMinFrac 0.4`,
`pieceFitNcc 0.25`, `pieceRelPeak 0.7`, `pieceMinZ 5`, `maxPieces 4`, `cutResolutionMs 50000`, `bisectLenMs 30000`, `maxWindows 9`.

Custo: ~0,1 s por `solve` em JVM de PC com 3×90 s (≈1 s no PC com sessão de cortes); memória: um bloco guarda 2561 floats de correlação por bloco de 30 s.

{{RESULTS}}
