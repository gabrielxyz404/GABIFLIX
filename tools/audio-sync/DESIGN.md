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
| VAD | **v1: DSP próprio** (22 atributos + MLP 22-16-1 treinado com as próprias legendas como rótulo fraco, ~1 kB de pesos, Java/Swift puros). **v2 opcional: Silero via ONNX** | de ponta a ponta o Silero acerta 10-20 pontos a mais que o DSP (seção 4.1) mas custa +17,6 MB (.so arm64) / +27,9 MB (AAR) e um runtime nativo nos dois apps; o DSP é melhor que o WebRTC GMM em recall e sem dependências. A sincronia é recusada (não errada) quando falha, então o DSP é um ponto de partida seguro |
| Alternativas medidas | WebRTC GMM (port Java bit-exato com o C: 0 quadros diferentes em 12000, modos 2 e 3) e Silero via ONNX | ver seção 4 |
| Captura | segunda instância do libVLC/VLCKit com **cadeia de saída `sout` (transcode → WAV)**: não toca, não segue o relógio | 50–200x o tempo real e corte exato (seção 5) |
| Aplicação | só se aceita pelo motor (`z ≥ 9` no ajuste global: 0 falsos aceites em 1823 negativos × 6 configurações); a legenda com atraso manual salvo nunca é mexida | seção 6 |
| Janelas | 3 janelas de 90 s (4,5 min de áudio), escolhidas onde a LEGENDA tem mais fala (`AudioSyncSession.planDense`) | seção 4.3 |
| Reverificação | só monitora por padrão; mudança em reprodução só com evidência acumulada e fora de falas; nunca com atraso manual | seção 7 |

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
6. **Decisão.** `accepted = z ≥ 9 ∧ concordância ≥ 55% ∧ NCC ≥ 0,10 ∧ margem ≥ 0,15` (`z ≥ 6` é o piso interno que ainda gera hipóteses e diagnóstico; `acceptMinZ = 9` é o limiar final; ver 4.4). Escala fora das razões de fps (`scaleKnown = false`) exige
   `z ≥ 12 ∧ NCC ≥ 0,35`: todos os falsos aceites medidos nos negativos tinham escala livre.
7. **Ajuste por partes (cortes).** Com o modelo global aceito (ou `z ≥ minZ` mas com "pico ambíguo", que costuma ser o outro trecho), cada bloco é rotulado pelo modelo que o explica
   (NCC ≥ 0,25 e ≥ 70% do maior pico do bloco). Blocos sem explicação (≥ 2) geram um novo deslocamento (mesma escala), testado com z ≥ 5 contra deslocamentos aleatórios.
   O corte entre dois trechos vai para o **maior intervalo sem falas** entre o último bloco de um e o primeiro do outro (em tempo de legenda: `Segment.fromSubMs`).
   Se o intervalo ainda tem > 50 s, o motor pede uma janela de 30 s no meio (`Result.more`).
8. **Sessão progressiva** (`AudioSyncSession`): o app captura o plano inicial, chama `solve()`; enquanto vier `more`, captura, `addWindow`, `solve()` (até 6 rodadas, ≤ 9 janelas).

Parâmetros (`AudioAligner.Params`): `frameMs 40`, `blockMs 30000`, `lagFrames 32000` (±1280 s), `maxOffsetMs 600000`, `maxAlpha 0.125`, `topK 12`, `tolFrames 9`, `smoothMs 60`,
`randomHyps 4000`, `minZ 6`, `minAgreeFrac 0.55`, `minNcc 0.10`, `minMargin 0.15`, `minZFree 12`, `minNccFree 0.35`, `snapAlpha 3e-3`, `snapResMs 120`, `knownMinFrac 0.4`,
`pieceFitNcc 0.25`, `pieceRelPeak 0.7`, `pieceMinZ 5`, `maxPieces 4`, `cutResolutionMs 50000`, `bisectLenMs 30000`, `maxWindows 9`, `acceptMinZ 9` (aplicado só ao ajuste global; ajustes por partes seguem a regra do passo 7).

Custo: ~0,1 s por `solve` em JVM de PC com 3×90 s (≈1 s no PC com sessão de cortes); memória: um bloco guarda 2561 floats de correlação por bloco de 30 s.

## 3. Como foi medido

- **Corpus:** 28 filmes de domínio público do archive.org tentados; 17 deram verdade de campo (93 legendas PT/EN com verdade; 10 filmes sem nenhuma verdade). Legendas reais do addon `opensubtitles-v3`
  (até 3 PT + EN por filme). Nada de mídia vai para o repositório: o filme vive no cache do runner.
- **Verdade de campo (oráculo):** três alinhadores independentes rodam sobre o áudio do filme: `ffsubsync` (VAD WebRTC), `ffsubsync` com `auditok` e `alass`. A verdade de uma legenda é o melhor par que concorda
  (mediana ≤ 250 ms, p75 ≤ 1000 ms). O oráculo tem viés de ~100-200 ms; por isso as distorções são pontuadas por **erro relativo**: a legenda distorcida corrigida pelo motor tem de cair onde o PRÓPRIO motor põe
  a legenda sem distorção (isso mede a recuperação da distorção). Cortes: p90 do erro por fala. Natural (legendas como baixadas): erro absoluto contra o oráculo.
- **Distorções:** deslocamentos ±0,5 … ±120 s (+ ±300 s e ±590 s), escalas de fps (25↔23,976, 25↔24, 24↔23,976 e inversas), combinação escala+deslocamento, 1 corte (`piece1`: ±4, ±15, ±40 s em 3 posições)
  e 2 cortes (`piece2`), e **negativos**: legendas de outros filmes (1823 pares por configuração).
- **Acerto** = aceita pelo motor E erro ≤ 250 ms. Recusar conta como "não acertou" nas colunas "certas"; o que importa para o produto é a coluna "aceitas erradas" (a legenda piora) e "falsos aceites".
- **Rodadas citadas** (workflows `audio-motor*.yml`, resultados em `audio-motor-out*`): filme inteiro `43938986565`; clipes de 20 min (`CLIP_S=1200`) com plano por densidade e reverificação `43949828352`;
  reverificação isolada `43967753643` (filme inteiro, janelas em qualquer ponto); captura Linux `43925904685`, Android `43932056794`, Mac `43943615160` (etapa 2) e `43939063968` (etapa 1).

## 4. Resultados

### 4.1 VAD e alinhamento (3 janelas de 60 s, filme inteiro, run `43938986565`; limiar de aceitação antigo `z ≥ 6`)

| VAD | offset+escala+combo certos (% de todos) | certos entre aceitas | falsos aceites (1823 negativos) | tempo/`solve` |
|---|---|---|---|---|
| **Silero (ONNX)** | **80,1%** | 94,0% | 8 | ~0,3 s |
| DSP próprio (v1) | 59,4% | 93,2% | 6 | ~1,1 s |
| WebRTC GMM modo 3 (Python `webrtcvad`) | 59,6% | 92,5% | 4 | ~0,4 s |
| WebRTC GMM modo 3 (port Java bit-exato) | 46,1% | 99,8% | 2 | ~0,45 s |
| WebRTC GMM modo 2 (port Java) | 41,9% | 99,4% | 1 | ~0,5 s |

Leitura honesta: o Silero é claramente o melhor VAD de ponta a ponta (+20 pontos de recall sobre o DSP com 3×60 s; +10 com 3×90 s), o WebRTC é o mais "seguro" (precisão 99%+) mas recusa mais da metade, e o DSP fica no meio
sem dependência nativa. O port Java do WebRTC é **bit-exato** com o C (0 quadros diferentes em 12 000, modos 2 e 3), o que o torna portável 1:1 para Swift. Custo do Silero: modelo 2,3 MB + `libonnxruntime.so` arm64 17,6 MB
(AAR 27,9 MB com ORT 1.20; 24,0 MB com 1.17.3); em Python rodou 230-290× tempo real com 1 thread. Por isso: **v1 = DSP** (zero dependência, mesmo código nos dois apps), **v2 = Silero** se o orçamento de APK/IPA permitir.

### 4.2 Acurácia por categoria (filme inteiro, `z ≥ 6` do run; 3×90 s = 4,5 min de áudio)

| categoria | DSP: aceitas / certas | DSP: aceitas erradas | Silero: aceitas / certas | Silero: aceitas erradas |
|---|---|---|---|---|
| offset (528 casos) | 431 (82%) / 424 (**80%**) | 7 | 478 (91%) / 460 (**87%**) | 18 |
| escala fps (198) | 150 (76%) / 142 (**72%**) | 8 | 175 (88%) / 169 (**85%**) | 6 |
| combo escala+offset (396) | 296 (75%) / 283 (**71%**) | 13 | 348 (88%) / 340 (**86%**) | 8 |
| 1 corte (198) | 102 (52%) / 53 (**27%**) | 49 | 149 (75%) / 88 (**44%**) | 61 |
| natural, legendas como baixadas (93) | 70 (75%) / 47 (**51%**) | 23 | 90 (97%) / 52 (**56%**) | 38 |
| negativos, outro filme (1823) | 2 aceitos | **2 falsos aceites** | 4 aceitos | **4 falsos aceites** |

- **A meta de ≥ 95% das distorções dentro de ±250 ms NÃO foi atingida**: o melhor número é 87% (Silero, offset, 4,5 min de áudio) e 80% (DSP). A parte alcançada é a **precisão**: entre os casos aceitos, 96,8% de offset+escala+combo
  estão certos (3×90 s, os dois VADs). O resto é recusa (a legenda fica como está), que não piora nada.
- **Cortes são o ponto fraco**: só 27% (DSP) / 44% (Silero) dos casos com 1 corte saem com ≥ 90% das falas certas, e quase metade dos aceitos está errada. `piece2` (2 cortes) fica em 8-11% (3×60 s). O ajuste por partes
  **não deve ser aplicado automaticamente na v1**: use-o só para avisar ("esta legenda parece ter cortes") ou para oferecer um botão. O global (offset+escala) é o que tem precisão.
- **Por filme** (3×60, DSP): há filmes onde o motor acerta ~100% (detour, dementia_13, hitch_hiker, sherlock, house_on_haunted_hill) e filmes onde quase nada (my_man_godfrey, night_of_the_living_dead 0%): áudio
  muito ruidoso/com música, ou diálogos esparsos na posição das janelas. Escolher onde ouvir (4.3) e pedir mais janelas quando recusa são as duas alavancas.

### 4.3 Escolha das janelas: por densidade de falas da legenda

`AudioSyncSession.planDense(cues, durMs, n, lenMs)` põe as janelas onde a legenda tem mais fala (densidade suavizada ±2 min, janelas afastadas ≥ 6 min, fora dos 4% iniciais e finais). Comparado com o plano uniforme
(15%…85% do filme), em clipes de 20 min (run `43949828352`, offset+escala+combo, % certos de todos):

| VAD | janelas | uniforme | por densidade |
|---|---|---|---|
| DSP | 3×90 | 61,4% | **72,4%** |
| DSP | 3×60 | 47,9% | 49,8% |
| DSP | 2×60 | 38,5% | 47,6% |
| Silero | 3×90 | 77,4% | 78,6% |
| Silero | 2×60 | 49,2% | **72,5%** |
| Silero | 5×30 | 39,6% | 53,4% |

Os clipes de 20 min são mais difíceis que o filme inteiro (as janelas ficam todas dentro dos primeiros 20 min: créditos/música, e 14 min de abertura não restringem a escala), então os números absolutos do clipe são
inferiores aos da seção 4.2; use-os só para comparar planos.

### 4.4 Quanto áudio é preciso, e o limiar de aceitação

Filme inteiro (run `43938986565`, `z ≥ 6`), offset+escala+combo, % certos de todos / certos entre aceitas / falsos aceites em 1823 negativos:

| VAD | janelas | áudio | certos | entre aceitas | negativos aceitos |
|---|---|---|---|---|---|
| DSP | 3×20 s | 1,0 min | 0% | - | 0 |
| DSP | 3×30 s | 1,5 min | 19,5% | 88,0% | 2 |
| DSP | 2×60 s | 2,0 min | 56,2% | 90,7% | 3 |
| DSP | 3×60 s | 3,0 min | 59,4% | 93,2% | 6 |
| DSP | 5×60 s | 5,0 min | 68,4% | 95,4% | 2 |
| DSP | 3×90 s | 4,5 min | 75,7% | 96,8% | 2 |
| Silero | 3×30 s | 1,5 min | 34,2% | 97,0% | 3 |
| Silero | 2×60 s | 2,0 min | 58,3% | 88,0% | 8 |
| Silero | 3×60 s | 3,0 min | 80,1% | 94,0% | 8 |
| Silero | 5×60 s | 5,0 min | 81,2% | 95,7% | 5 |
| Silero | 3×90 s | 4,5 min | 86,4% | 96,8% | 4 |

Menos de ~2 min de áudio quase nunca basta; ~4,5 min (3×90 s) é o melhor custo-benefício; passar de 5 min quase não ajuda. Janelas longas valem mais que muitas curtas (3×90 s > 5×60 s > 5×30 s).

**Limiar.** Os falsos aceites em negativos tinham `z` entre 6,0 e 8,2 (todos com NCC médio ≤ 0,4 e quase sempre escala livre ou deslocamento > 300 s). Exigir `z ≥ 9` no ajuste global (`Params.acceptMinZ`)
resultou em **0 falsos aceites em 1823 negativos em todas as 6 configurações medidas** (clipes, run `43949828352`), ao custo de recall:

| VAD, janelas | `z ≥ 6` aceitas / certas / precisão | `z ≥ 9` aceitas / certas / precisão | negativos aceitos (`z≥6` → `z≥9`) |
|---|---|---|---|
| DSP 3×90 | 71% / 61% / 87% | 59% / 52% / 89% | 4 → 0 |
| DSP 3×90 densa | 81% / 72% / 89% | 62% / 55% / 89% | 4 → 0 |
| Silero 3×90 | 91% / 77% / 85% | 80% / 68% / 85% | 8 → 0 |
| Silero 5×60 | 83% / 76% / 92% | 76% / 70% / 92% | 3 → 0 |

(`z` sozinho não separa os positivos errados dos certos: a precisão entre aceitas fica em ~85-92% em qualquer limiar; o ganho do limiar é nos negativos. A regra completa fica em `AudioAligner.decide`; `corpus/calibrate.py`
refaz a calibração deixando um filme de fora, só sobre casos já aceitos pelo motor.)

### 4.5 Ranking de candidatas por áudio versus consenso (clipes, 3×60, filmes com ≥ 2 candidatas e ≥ 1 com verdade)

| legendas | método | certa | errada | sem consenso / recusou tudo | não verificável |
|---|---|---|---|---|---|
| PT (10 filmes) | consenso sem referência (app hoje) | 3 (30%) | 3 (30%) | 0 | 4 (40%) |
| PT | ranking por áudio DSP + correção | 2 (20%) | 5 (50%) | 0 | 3 (30%) |
| PT | ranking por áudio Silero + correção | 4 (40%) | 3 (30%) | 1 (10%) | 2 (20%) |
| PT | ranking por áudio WebRTC (Python) + correção | 5 (50%) | 3 (30%) | 1 (10%) | 1 (10%) |
| EN (14 filmes) | consenso | 3 (21%) | 6 (43%) | 1 (7%) | 4 (29%) |
| EN | ranking por áudio DSP + correção | 3 (21%) | 5 (36%) | 3 (21%) | 3 (21%) |

O teto (existe candidata com verdade que o motor acerta) é 60-70% em PT. **Conclusão: com 3×60 s e VAD DSP o ranking por áudio NÃO supera o consenso; com Silero/WebRTC supera por pouco (40-50% × 30%) numa amostra
pequena (10 filmes), então a diferença não é estatisticamente firme.** O consenso baseline de 56% do app vem de outra medição e não é comparável com este conjunto (poucos filmes com ≥ 2 candidatas e verdade). A melhor
integração é combinar: o consenso ordena, o áudio **verifica** a candidata escolhida (e as 2 seguintes) e corrige offset/escala; recusa do áudio não derruba a candidata (seção 6).

## 5. Captura de áudio por plataforma

### 5.1 Método: segunda instância do libVLC/VLCKit com cadeia de saída `sout` (transcode → WAV)

O player de reprodução não é tocado. Uma **segunda** instância cria um `MediaPlayer` sem vídeo/legenda e com
`:sout=#transcode{acodec=s16l,channels=1,samplerate=16000}:std{access=file,mux=wav,dst=ARQ.wav}` mais `:start-time` / `:stop-time`. Sem saída de áudio o VLC não segue o relógio: lê e decodifica o mais rápido que a rede e a
CPU deixam (**dezenas a centenas de vezes o tempo real**) e o corte é exato. Os métodos alternativos medidos foram piores: `afile` (aout que grava WAV) e callbacks de áudio (`libvlc_audio_set_callbacks`) seguem o relógio
(tempo real, 60 s de captura = 60 s de espera) e o `MediaExtractor` do Android devolve PCM bruto (48 kHz estéreo) e não abre AC3/DTS/TrueHD.

Custo de banda: o áudio vem intercalado com o vídeo, então os bytes lidos crescem com o bitrate do vídeo (≈ 86 MB por 60 s a 6 Mb/s de vídeo; 70-90 MB por janela de 60 s no teste Mac/Android). Isso é aceitável em rede local/NAS,
mas em streaming remoto reduza o número de janelas e use janelas de 60 s; adapte pelo bitrate (`bytes/s` do cabeçalho).

### 5.2 Prova no emulador Android (libVLC 3.6.0, x86, 2 vCPU; run `43932056794`)

Janelas de 90 s em 3 pontos do arquivo (120 s, 600 s, 1100 s), servidas por HTTP local:

| codec do áudio | 3×90 s: tempo de parede | CPU total (todas as threads) | erro de início contra ffmpeg | observação |
|---|---|---|---|---|
| AC3 | ≈ 4,3 s (1,3-1,6 s por janela) | ≈ 3,3 s | 0 ms | 57-68× tempo real |
| E-AC3 | 3,27 s | 3,31 s | 0 ms | |
| AAC | 4,26 s | 2,75 s | 20-30 ms | |
| FLAC | 4,14 s | 2,77 s | 0-100 ms | |
| DTS | 4,26 s | 4,66 s | 60-170 ms | |
| TrueHD | 12,2 s | 18,7 s | **140-530 ms** | decodificação pesada e carimbo de tempo impreciso |
| (`afile`, só para comparar) | 59,7 s para 60 s | 3,9 s | -4870 ms | tempo real e início errado |

Ou seja, ≈ **1,0-1,2 s de CPU e ≈ 1-1,5 s de parede por janela de 90 s** (exceto TrueHD) no emulador x86; correlação do PCM capturado com o do ffmpeg 0,92-1,00 (0,48-0,88 no TrueHD). Um Fire Stick (Cortex-A53) é
tipicamente 3-5× mais lento por núcleo que o emulador num PC: estimativa **~4-6 s de CPU de um núcleo por janela de 90 s, ~12-18 s para o plano inicial de 3 janelas** (NÃO medido em aparelho real; é extrapolação).

### 5.3 Prova no Mac (VLCKit 3.7.3, runner macOS; run `43943615160`)

`sout` captura 60 s em **0,23-0,53 s de parede** (≈ 110-260× tempo real) e **0,30-0,69 s de CPU**, para AC3/E-AC3/AAC/DTS/FLAC; início exato a ±40 ms (um quadro de 40 ms; correlação 0,96-0,98 em AC3). O método de
callbacks entrega o PCM certo, mas em tempo real (60 s de captura = ~60 s de parede) e lendo MB crescentes conforme a posição (121 MB em 60 s no início; 1,0-1,2 GB em 1200 s: a leitura de um MKV com índice no fim).
**TrueHD não funciona no Mac nem por `sout` nem por callbacks** (sem saída em 180 s): trate faixa TrueHD/DTS-HD como "sem sincronia automática" ou escolha outra faixa do arquivo.

### 5.4 Código

**Android (Java, `capture/android/.../AudioCapture.java`)** — segunda instância, fora da thread principal:

```java
LibVLC vlc = new LibVLC(ctx.getApplicationContext(), Arrays.asList("--no-lua","--no-osd","--no-spu","--no-sub-autodetect-file"));
Media m = new Media(vlc, Uri.parse(url));
m.addOption(":no-video");
m.addOption(":network-caching=600");
m.addOption(":start-time=" + startMs / 1000.0);
m.addOption(":stop-time=" + (startMs + durMs) / 1000.0);
m.addOption(":sout=#transcode{acodec=s16l,channels=1,samplerate=16000}:std{access=file,mux=wav,dst=" + out + "}");
MediaPlayer mp = new MediaPlayer(vlc);
CountDownLatch done = new CountDownLatch(1);
mp.setEventListener(ev -> { if (ev.type == MediaPlayer.Event.EndReached || ev.type == MediaPlayer.Event.EncounteredError
                                || ev.type == MediaPlayer.Event.Stopped) done.countDown(); });
mp.setMedia(m); mp.play();
done.await(timeoutMs, TimeUnit.MILLISECONDS);
mp.stop(); mp.release(); m.release(); vlc.release();
short[] pcm = AudioCapture.readWav(out);          // mono s16 16 kHz; pula o cabeçalho procurando o bloco "data"
```

Rodar uma captura por vez, em `Thread` de prioridade baixa (`Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)`), com `timeout` = 5× a duração da janela; apagar o WAV depois de ler.

**macOS (Swift, VLCKit; `capture/mac/capprobe.swift`)** — o VLCKit termina sem "ended" quando a saída é um arquivo, então o fim é detectado pelo tamanho do WAV:

```swift
let player = VLCMediaPlayer(options: ["--no-video", "--no-spu", "--no-sub-autodetect-file", "--no-lua", "--no-osd",
                                      "--codec=avcodec", "--avcodec-hw=none", "--no-videotoolbox"])
let media = VLCMedia(url: URL(string: url)!)
for o in [":no-video", ":no-spu", ":network-caching=600", ":http-reconnect",
          ":start-time=\(startS)", ":stop-time=\(startS + durS)",
          ":sout=#transcode{acodec=s16l,channels=1,samplerate=16000}:std{access=file,mux=wav,dst=\(outPath)}"] { media.addOption(o) }
player.media = media
player.play()
let deadline = Date().addingTimeInterval(max(120, durS * 3))
while Date() < deadline {
    RunLoop.current.run(until: Date().addingTimeInterval(0.05))
    if let sz = (try? FileManager.default.attributesOfItem(atPath: outPath))?[.size] as? Int, sz >= Int(durS * 32000 * 0.985) { break }
}
player.stop()
```

(`32000` = bytes por segundo de PCM mono s16 16 kHz.) Usar uma `DispatchQueue` com `qos: .background`.

## 6. Integração no pipeline de legendas (fase seguinte)

1. **Candidatas.** O app já baixa N legendas candidatas e tem `consensusGroups/pickGroup` (consenso sem referência). Mantenha-o como primeira ordenação.
2. **Verificar e corrigir.** Capture o plano inicial (3×90 s por `planDense` sobre a candidata) com o `sout`; rode `AudioSyncSession` (até 6 rodadas, ≤ 9 janelas) na candidata escolhida:
   - `accepted` → aplique `Result.apply(ms)` (escala + offset); registre `confidence`;
   - recusada → tente a 2ª e a 3ª do consenso (cada uma reaproveita as MESMAS janelas capturadas: só o `solve()` roda de novo; ~0,3-1 s cada em PC);
   - todas recusadas → deixe a legenda como estava e não avise nada.
3. **Regras duras.** O atraso manual salvo do usuário (por vídeo) **sempre vence**: se existir, não capture nem aplique (só a reverificação pode "olhar", nunca mudar). Só aplique quando `accepted`. Nunca aplique ajuste por partes sem o
   usuário pedir (a precisão medida é baixa, seção 4.2). Rejeite se `|scale-1| > 12%` fora das razões de fps conhecidas (`scaleKnown = false` já exige `z ≥ 12` e NCC ≥ 0,35).
4. **Cache.** Chave `hash do vídeo + id da legenda + versão do motor`; guarde `(scale, offsetMs, confidence, aceito/recusado)` para não recapturar a cada abertura. Zere a chave se o usuário trocar de legenda ou de faixa de áudio.
5. **Quando rodar.** Ao abrir um vídeo com legenda nova (ou em uma ação "Sincronizar pelo áudio"), em thread de baixa prioridade, depois de o vídeo começar a tocar (a sincronia é aplicada quando termina; a legenda atual não pisca antes).
6. **Faixa de áudio.** Capture a faixa que o usuário está ouvindo (idioma da dublagem pode não casar com a legenda: a legenda é a fala do original). Se for TrueHD (Mac) ou a faixa for comentário/áudio descritivo, não sincronize.

### Avisos em português (UX)

- Capturando/analisando: "Sincronizando legenda pelo áudio…" (some sozinho).
- Aplicou: "Legenda sincronizada pelo áudio (+2,4 s)" ou "(−1,1 s)"; com escala: "Legenda sincronizada pelo áudio (ajuste de velocidade 25 → 23,976 fps)"; com partes (só se o usuário pediu): "(2 trechos)".
- Recusou: nada na tela; no menu da legenda: "Não foi possível sincronizar pelo áudio" (com "Tentar de novo").
- Negativo claro (todas as candidatas recusadas por `z` baixo): "Esta legenda não parece ser deste filme" só no menu, nunca na tela de reprodução.
- Atraso manual existente: "Atraso manual mantido" no menu (o botão "Sincronizar pelo áudio" pede confirmação).
- Reverificação que muda algo: "Legenda reajustada pelo áudio (+14,9 s a partir daqui)" e botão "Desfazer" por 20 s.

## 7. Reverificação durante a reprodução

### 7.1 Regras

1. **Na abertura:** ouça os primeiros ~4,5 min, alinhe offset+escala e aplique só se confiante (seção 6).
2. **A cada ~10 min:** capture 1-2 min (`Recheck.Params`: janela de 90-120 s) na posição atual e chame `Recheck.check(...)`.
3. **Só muda com evidência forte** de salto: o modelo atual precisa ter deixado de explicar o áudio E a busca larga (±300 s, mesma escala) precisa achar o mesmo novo deslocamento com `z ≥ 7` e NCC ≥ 0,25, e (a) `z ≥ 11 ∧ NCC ≥ 0,40`,
   ou (b) a mesma proposta confirmada em duas checagens seguidas (`PROPOSED` → `CHANGE`).
4. **Aplicação no intervalo entre falas:** a mudança vale a partir da primeira fala que começa depois de uma pausa ≥ 500 ms posterior à posição atual + 1 s (`Out.applyFromSubMs`); nunca no meio de uma fala.
5. **Atraso manual vence sempre:** `manualLocked = true` → `KEEP` sem sequer olhar.
6. **Nunca piorar uma legenda já certa:** o caminho barato (modelo atual explica o áudio: NCC ≥ 0,20 e |deslocamento| ≤ 500 ms) devolve `KEEP` sem buscar nada.

### 7.2 API (Java, `Recheck.java`; porta 1:1 para Swift)

```java
Recheck.Out o = Recheck.check(cues, windowsFailingSoFar, currentResult, playMs, mediaDurationMs,
                              manualLocked, previousOut, alignerParams, new Recheck.Params());
switch (o.verdict) {
  case KEEP:     /* nada */ break;
  case PROPOSED: previousOut = o; break;                       // guarda; confirme na próxima checagem
  case CHANGE:   applyFrom(o.applyFromSubMs, o.newOffsetMs); // mesma escala; undo disponível
}
```

`windowsFailingSoFar` = a janela capturada agora + até 2 janelas das últimas checagens que o modelo atual NÃO explicava (zerar num `KEEP ok`).

### 7.3 Medido (filmes de verdade, 15 filmes com verdade e ajuste global aceito; run `43967753643`; checagem a cada 10 min)

| teste | resultado |
|---|---|
| **Taxa de mudança falsa** em legendas já certas (janela de 90 s, 1 janela) | **0 `CHANGE` em 236 checagens (0%)**; 5 `PROPOSED` (2,1%), nenhum aplicado |
| idem, com janelas de 120 s e até 3 janelas acumuladas | **0 `CHANGE` em 234 checagens (0%)**; 2 `PROPOSED` (0,9%) |
| 54 cortes injetados (−40 s e +15 s), 1 janela de 90 s por checagem | **0 detectados** (ver abaixo) |
| 54 cortes injetados, até 3 janelas de 120 s acumuladas | detectados 17/54 (31%); latência mediana **22 min** (~2 checagens de 10 min); erro mediano do novo deslocamento 120 ms; **3 dos 17 `CHANGE` com erro > 500 ms**; 0 `CHANGE` e 1 `PROPOSED` antes do corte |
| custo da análise (JVM de PC, 1 núcleo) | `KEEP ok`: mediana 6-7 ms; busca larga: p95 47 ms (1 janela) a 154 ms (3 janelas), máx 205 ms |

Ou seja: **a regra "nunca piorar" é cumprida (0%)**, mas **a detecção de cortes por checagens curtas é fraca** em áudio real (a busca em ±300 s com uma só janela de 90 s tem `z` de 5-9, igual ao de janelas sem salto). Recomendação:
na v1 use a reverificação como **monitor** (`KEEP`/`PROPOSED`, métrica e "Legenda parece ter saído de sincronia: Sincronizar de novo?") e deixe o `CHANGE` automático desligado; ligue-o só depois de validar em campo. Para um corte de verdade,
o caminho robusto é escalar para uma sessão completa (3×90 s ao redor da posição + `AudioSyncSession`), que é a mesma rotina da abertura.

### 7.4 Regras de execução (não travar o player)

- **Captura e análise em thread de baixa prioridade** (`THREAD_PRIORITY_BACKGROUND` no Android, `qos: .background` no Mac), **uma por vez**, nunca na thread de reprodução nem na principal; o decode é feito pela **segunda
  instância** do libVLC/VLCKit (processo/threads próprios do VLC), nunca pelo player.
- **Memória limitada:** uma janela de 90-120 s = 2,9-3,8 MB de PCM s16 16 kHz (descartado após o VAD) e 9-12 mil floats de probabilidade; o `solve` guarda ~2561 floats por bloco de 30 s (≈ 30 KB por janela). Teto da sessão:
  ≤ 3 janelas retidas ≈ < 1 MB; o WAV temporário é apagado ao ler.
- **CPU limitada:** só no máximo 1 captura por 10 min; pular se a bateria/temperatura estiver crítica, se o vídeo estiver em buffering, ou se o player perdeu quadros nos últimos 30 s; pausar a checagem se o usuário fez seek.
- **Custo por checagem** (captura 90 s + VAD + `check`), perfil de aparelho modesto: emulador x86 2 vCPU medido ≈ 1,0-1,2 s de CPU de captura + 0,03-0,2 s de análise por janela de 90 s; estimado para Fire Stick
  (Cortex-A53, ~3-5× mais lento por núcleo; NÃO medido em aparelho): **≈ 4-6 s de CPU de um núcleo por checagem, uma vez a cada 10 min (< 1% de um núcleo em média)**, em thread de fundo.
  O pior caso medido é a análise de 205 ms de JVM de PC (≈ 1 s num Fire Stick).

## 8. Plano de teste de paridade para o Swift

`parity/vectors.json` (12 casos, gerado por `harness/Vectors.java`) traz cues, janelas (probabilidade a 100 Hz) e o `Result` esperado do motor Java. Um teste XCTest do Mac:

1. carrega `vectors.json`, roda `AudioAligner.solve` do Swift em cada caso e compara: `accepted` igual; `offsetMs` ±1 ms; `scale` ±1e-6; `z` ±0,05; `segments[i].fromSubMs` ±1 s;
2. WebRTC GMM: o port Swift tem de ser **bit-exato** (mesmos quadros: 0 diferença em 12 000 quadros, modos 2 e 3) contra os vetores de `Parity.java`;
3. DSP VAD: atributos com tolerância 1e-4, probabilidade final ±1e-3 (use `Double` e a mesma ordem de operações; `exp/log/cos/sin` podem diferir no último bit entre Java e Swift, por isso as tolerâncias; se precisar de bit-exato, troque-as por tabelas geradas uma vez);
4. FFT: o mesmo tamanho (65536) e o mesmo radix-2 iterativo; compare `lag` dos 12 picos de cada bloco (índices iguais);
5. CI: roda no workflow `-mac` já existente; falha o build se qualquer vetor divergir. Antes de qualquer mudança de parâmetro, regenere `vectors.json` com `java Vectors` e revise o diff.

## 9. Riscos e limitações

- **Acurácia abaixo da meta:** 80-87% (offset) e 72-86% (escala/combo) de todos os casos com 4,5 min de áudio; precisão entre aceitas ≈ 85-97%. Cortes (`piece1/2`) < 45%: não aplicar automaticamente.
- **Oráculo:** a verdade tem viés de ~100-200 ms e há 10 filmes sem verdade (17 filmes, 93 legendas); o Silero como oráculo falhou (torch/hub) e ficou de fora. Os números de "natural" (legendas como baixadas) são os menos confiáveis.
- **Falsos aceites:** 0/1823 só com `z ≥ 9` (medido em 6 configurações, em clipes); 2-8 com `z ≥ 6`. O limiar `z ≥ 9` custa 10-20 pontos de recall. Uma legenda de outro filme com muito ruído de fundo pode ainda passar: por isso
  `Esta legenda não parece ser deste filme` não deve ser definitivo.
- **Áudio:** TrueHD (erro de 140-530 ms no carimbo de tempo e CPU 6× maior no Android; sem saída no Mac), DTS (60-170 ms), AAC (20-30 ms). Filmes com música/ruído contínuo ou diálogos esparsos nas janelas falham.
- **Banda:** a captura lê MB proporcionais ao bitrate do vídeo (70-90 MB por janela de 60 s no teste, 1,0-1,2 GB lidos no MKV com índice no fim para uma janela a 1200 s na captura por callbacks; o `sout` ficou em ~75-90 MB).
  Em streaming remoto mede-se antes (cabeçalho `Content-Length`/bitrate) e reduz-se o plano.
- **Fire Stick:** a CPU foi medida só no emulador x86; o custo no aparelho é extrapolado (3-5×).
- **Reverificação:** detecção de corte fraca (seção 7.3). `CHANGE` automático deve ficar desligado até validação em campo.
- **Silero/ONNX:** +17,6 MB (.so arm64) ou +27,9 MB (AAR), mais um segundo runtime nativo nos dois apps; o ganho de recall é real (+10-20 pontos).

## 10. Arquivos

Código do motor (Java puro, `tools/audio-sync/java/io/github/gabrielxyz404/gabiflix/audiosync/`): `AudioAligner.java`, `AudioSyncSession.java`, `Recheck.java`, `Result.java`, `Segment.java`, `Window.java`, `Cue.java`,
`DspVad.java`, `WebRtcVad.java`, `Vad.java`, `Fft.java`.
Harness: `tools/audio-sync/harness/{Eval,Film,Rank,SelfTest,Vectors,Parity,FeatDump,TrainVad}.java`, `harness/gabiflix/Baseline.java`.
Corpus/relatórios: `tools/audio-sync/corpus/{corpus.py,report.py,rank_report.py,calibrate.py,final_tables.py,films.json}`.
Captura: `tools/audio-sync/capture/{server.py, linux/, mac/capprobe.swift, mac/matrix.py, android/...}` (`AudioCapture.java`, `ExtractorCapture.java`, `CaptureBenchTest.java`).
Paridade: `tools/audio-sync/parity/vectors.json`. Workflows: `.github/workflows/audio-motor.yml`, `audio-motor-rck.yml`, `audio-motor-capture-{linux,mac,android}.yml`, `audio-motor-refs.yml`.

## 9. Rodada final v4 (clipes de 20 min, 93 legendas naturais, 1122 distorcidas, 1823 negativos)

Regra do motor v4: z >= 9, so escalas de fps conhecidas (sem escala livre), escala ~1 vira 1,0, banda morta 600 ms, cortes so como aviso (nada aplicado).
Varredura offline de politicas (`corpus/policy_sweep.py`), Silero 3x90 s (300 s de audio) x DSP 3x90 s (390 s):

| config | corretas +-250 | aceitas erradas (distorcidas) | safe mexidas >150 ms (de 93) | natural piorou / melhorou (de 93) | negativos aceitos |
|---|---|---|---|---|---|
| Silero 3x90, z>=9, banda 600 (v4 como esta) | 62,4% | 13,6% | 22 | 2 / 65 | 0/1823 |
| Silero 3x90, z>=9, banda 1100 ms, sem escalas 0,999/1,001 | 48,0% | 10,5% | 3 | 0 / 54 | 0/1823 |
| DSP 3x90, z>=9, banda 1100 ms, sem escalas 0,999/1,001 | 39,7% | 12,5% | 3 | 0 / 42 | 0/1823 |
| 4x90 nao melhora em nenhum VAD (menos corretas) | | | | | |
