# Resultados do motor de áudio (limiar 250 ms)

casos: 107392, filmes: 27

Acerto = aceita E erro <= limiar. Erro das distorções = relativo ao resultado do próprio motor com a legenda sem distorção (o oráculo tem viés de ~100-200 ms); cortes (piece) = p90; natural = absoluto contra o oráculo. Recusa conta como não-acerto.


## VAD `dsp`, janelas 2x60

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 276 (52.3%) | 254 (48.1%) | 22 | 252 | 0 | 329 | 713 |
| scale | 198 | 96 (48.5%) | 67 (33.8%) | 29 | 102 | 36 | 334 | 832 |
| combo | 396 | 171 (43.2%) | 111 (28.0%) | 60 | 225 | 38 | 370 | 867 |
| piece1 | 198 | 81 (40.9%) | 17 (8.6%) | 64 | 117 | 696 | 602 | 1037 |
| natural | 93 | 37 (39.8%) | 18 (19.4%) | 19 | 56 | 252 | 252 | 646 |
| natural_nt | 155 | 32 (20.6%) | - | 0 | 123 | - | - | 551 |
| neg | 1823 | 7 (0.4%) | - | 7 | 1816 | - | - | 736 |

**offset+scale+combo: 432/1122 = 38.5% certos (79.6% entre as aceitas); falsos aceites em negativos: 7/1823**


## VAD `dsp`, janelas 2x60d

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 312 (59.1%) | 280 (53.0%) | 32 | 216 | 0 | 215 | 477 |
| scale | 198 | 107 (54.0%) | 88 (44.4%) | 19 | 91 | 15 | 256 | 548 |
| combo | 396 | 201 (50.8%) | 166 (41.9%) | 35 | 195 | 16 | 245 | 594 |
| piece1 | 198 | 78 (39.4%) | 14 (7.1%) | 64 | 120 | 714 | 668 | 898 |
| piece2 | 99 | 18 (18.2%) | 1 (1.0%) | 17 | 81 | 1948 | 1542 | 1144 |
| natural | 93 | 58 (62.4%) | 28 (30.1%) | 30 | 35 | 270 | 270 | 709 |
| natural_nt | 155 | 53 (34.2%) | - | 0 | 102 | - | - | 914 |
| neg | 1823 | 13 (0.7%) | - | 13 | 1810 | - | - | 761 |

**offset+scale+combo: 534/1122 = 47.6% certos (86.1% entre as aceitas); falsos aceites em negativos: 13/1823**


## VAD `dsp`, janelas 3x20

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 80 (15.2%) | 67 (12.7%) | 13 | 448 | 0 | 1305 | 289 |
| scale | 198 | 20 (10.1%) | 13 (6.6%) | 7 | 178 | 26 | 298 | 276 |
| combo | 396 | 39 (9.8%) | 26 (6.6%) | 13 | 357 | 26 | 298 | 280 |
| piece1 | 198 | 20 (10.1%) | 0 (0.0%) | 20 | 178 | 716 | 716 | 275 |
| natural | 93 | 14 (15.1%) | 3 (3.2%) | 11 | 79 | 1154 | 1154 | 223 |
| natural_nt | 155 | 8 (5.2%) | - | 0 | 147 | - | - | 181 |
| neg | 1823 | 4 (0.2%) | - | 4 | 1819 | - | - | 228 |

**offset+scale+combo: 106/1122 = 9.4% certos (76.3% entre as aceitas); falsos aceites em negativos: 4/1823**


## VAD `dsp`, janelas 3x30

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 175 (33.1%) | 159 (30.1%) | 16 | 353 | 0 | 185 | 386 |
| scale | 198 | 46 (23.2%) | 35 (17.7%) | 11 | 152 | 20 | 123 | 424 |
| combo | 396 | 93 (23.5%) | 63 (15.9%) | 30 | 303 | 24 | 134 | 427 |
| piece1 | 198 | 52 (26.3%) | 6 (3.0%) | 46 | 146 | 902 | 930 | 485 |
| natural | 93 | 36 (38.7%) | 18 (19.4%) | 18 | 57 | 247 | 247 | 347 |
| natural_nt | 155 | 18 (11.6%) | - | 0 | 137 | - | - | 289 |
| neg | 1823 | 2 (0.1%) | - | 2 | 1821 | - | - | 397 |

**offset+scale+combo: 257/1122 = 22.9% certos (81.8% entre as aceitas); falsos aceites em negativos: 2/1823**


## VAD `dsp`, janelas 3x30d

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 178 (33.7%) | 165 (31.2%) | 13 | 350 | 0 | 265 | 315 |
| scale | 198 | 44 (22.2%) | 36 (18.2%) | 8 | 154 | 20 | 218 | 330 |
| combo | 396 | 82 (20.7%) | 69 (17.4%) | 13 | 314 | 26 | 200 | 335 |
| piece1 | 198 | 40 (20.2%) | 1 (0.5%) | 39 | 158 | 1270 | 1476 | 426 |
| piece2 | 99 | 4 (4.0%) | 0 (0.0%) | 4 | 95 | 1300 | 1308 | 412 |
| natural | 93 | 26 (28.0%) | 15 (16.1%) | 11 | 67 | 206 | 206 | 593 |
| natural_nt | 155 | 30 (19.4%) | - | 0 | 125 | - | - | 467 |
| neg | 1823 | 1 (0.1%) | - | 1 | 1822 | - | - | 357 |

**offset+scale+combo: 270/1122 = 24.1% certos (88.8% entre as aceitas); falsos aceites em negativos: 1/1823**


## VAD `dsp`, janelas 3x45

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 122 (23.1%) | 110 (20.8%) | 12 | 406 | 0 | 326 | 500 |
| scale | 198 | 40 (20.2%) | 29 (14.6%) | 11 | 158 | 24 | 315 | 545 |
| combo | 396 | 77 (19.4%) | 56 (14.1%) | 21 | 319 | 25 | 310 | 542 |
| piece1 | 198 | 59 (29.8%) | 12 (6.1%) | 47 | 139 | 570 | 570 | 610 |
| natural | 93 | 21 (22.6%) | 7 (7.5%) | 14 | 72 | 374 | 374 | 552 |
| natural_nt | 155 | 31 (20.0%) | - | 0 | 124 | - | - | 488 |
| neg | 1823 | 7 (0.4%) | - | 7 | 1816 | - | - | 587 |

**offset+scale+combo: 195/1122 = 17.4% certos (81.6% entre as aceitas); falsos aceites em negativos: 7/1823**


## VAD `dsp`, janelas 3x45d

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 133 (25.2%) | 111 (21.0%) | 22 | 395 | 0 | 293 | 640 |
| scale | 198 | 44 (22.2%) | 33 (16.7%) | 11 | 154 | 24 | 295 | 620 |
| combo | 396 | 84 (21.2%) | 60 (15.2%) | 24 | 312 | 26 | 296 | 637 |
| piece1 | 198 | 50 (25.3%) | 15 (7.6%) | 35 | 148 | 406 | 442 | 721 |
| piece2 | 99 | 10 (10.1%) | 1 (1.0%) | 9 | 89 | 1926 | 2092 | 741 |
| natural | 93 | 21 (22.6%) | 9 (9.7%) | 12 | 72 | 304 | 304 | 1113 |
| natural_nt | 155 | 39 (25.2%) | - | 0 | 116 | - | - | 829 |
| neg | 1823 | 4 (0.2%) | - | 4 | 1819 | - | - | 501 |

**offset+scale+combo: 204/1122 = 18.2% certos (78.2% entre as aceitas); falsos aceites em negativos: 4/1823**


## VAD `dsp`, janelas 3x60

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 346 (65.5%) | 307 (58.1%) | 39 | 182 | 0 | 434 | 1010 |
| scale | 198 | 116 (58.6%) | 82 (41.4%) | 34 | 82 | 18 | 339 | 1111 |
| combo | 396 | 212 (53.5%) | 148 (37.4%) | 64 | 184 | 22 | 339 | 1149 |
| piece1 | 198 | 120 (60.6%) | 32 (16.2%) | 88 | 78 | 772 | 956 | 1218 |
| piece2 | 99 | 22 (22.2%) | 6 (6.1%) | 16 | 77 | 3226 | 2803 | 1562 |
| natural | 93 | 54 (58.1%) | 18 (19.4%) | 36 | 39 | 329 | 329 | 1065 |
| natural_nt | 155 | 66 (42.6%) | - | 0 | 89 | - | - | 1085 |
| neg | 1823 | 8 (0.4%) | - | 8 | 1815 | - | - | 1182 |

**offset+scale+combo: 537/1122 = 47.9% certos (79.7% entre as aceitas); falsos aceites em negativos: 8/1823**


## VAD `dsp`, janelas 3x60d

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 336 (63.6%) | 301 (57.0%) | 35 | 192 | 0 | 329 | 979 |
| scale | 198 | 120 (60.6%) | 94 (47.5%) | 26 | 78 | 26 | 288 | 1009 |
| combo | 396 | 223 (56.3%) | 164 (41.4%) | 59 | 173 | 28 | 299 | 1037 |
| piece1 | 198 | 108 (54.5%) | 21 (10.6%) | 87 | 90 | 862 | 807 | 1082 |
| piece2 | 99 | 24 (24.2%) | 5 (5.1%) | 19 | 75 | 3861 | 3544 | 1343 |
| natural | 93 | 59 (63.4%) | 21 (22.6%) | 38 | 34 | 329 | 329 | 1587 |
| natural_nt | 155 | 76 (49.0%) | - | 0 | 79 | - | - | 1432 |
| neg | 1823 | 10 (0.5%) | - | 10 | 1813 | - | - | 1005 |

**offset+scale+combo: 559/1122 = 49.8% certos (82.3% entre as aceitas); falsos aceites em negativos: 10/1823**


## VAD `dsp`, janelas 3x90

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 393 (74.4%) | 357 (67.6%) | 36 | 135 | 0 | 346 | 707 |
| scale | 198 | 140 (70.7%) | 115 (58.1%) | 25 | 58 | 15 | 282 | 739 |
| combo | 396 | 262 (66.2%) | 217 (54.8%) | 45 | 134 | 17 | 294 | 773 |
| piece1 | 198 | 139 (70.2%) | 45 (22.7%) | 94 | 59 | 516 | 1020 | 956 |
| natural | 93 | 69 (74.2%) | 30 (32.3%) | 39 | 24 | 292 | 292 | 792 |
| natural_nt | 155 | 81 (52.3%) | - | 0 | 74 | - | - | 871 |
| neg | 1823 | 4 (0.2%) | - | 4 | 1819 | - | - | 1056 |

**offset+scale+combo: 689/1122 = 61.4% certos (86.7% entre as aceitas); falsos aceites em negativos: 4/1823**


## VAD `dsp`, janelas 3x90d

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 444 (84.1%) | 427 (80.9%) | 17 | 84 | 0 | 346 | 639 |
| scale | 198 | 165 (83.3%) | 138 (69.7%) | 27 | 33 | 19 | 320 | 722 |
| combo | 396 | 302 (76.3%) | 247 (62.4%) | 55 | 94 | 20 | 322 | 731 |
| piece1 | 198 | 131 (66.2%) | 55 (27.8%) | 76 | 67 | 257 | 533 | 1021 |
| piece2 | 99 | 28 (28.3%) | 5 (5.1%) | 23 | 71 | 924 | 1455 | 1443 |
| natural | 93 | 76 (81.7%) | 32 (34.4%) | 44 | 17 | 352 | 352 | 1603 |
| natural_nt | 155 | 77 (49.7%) | - | 0 | 78 | - | - | 1628 |
| neg | 1823 | 4 (0.2%) | - | 4 | 1819 | - | - | 948 |

**offset+scale+combo: 812/1122 = 72.4% certos (89.1% entre as aceitas); falsos aceites em negativos: 4/1823**


## VAD `dsp`, janelas 5x30

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 218 (41.3%) | 200 (37.9%) | 18 | 310 | 0 | 250 | 324 |
| scale | 198 | 56 (28.3%) | 45 (22.7%) | 11 | 142 | 20 | 357 | 369 |
| combo | 396 | 102 (25.8%) | 87 (22.0%) | 15 | 294 | 24 | 189 | 379 |
| piece1 | 198 | 63 (31.8%) | 12 (6.1%) | 51 | 135 | 397 | 926 | 454 |
| natural | 93 | 44 (47.3%) | 24 (25.8%) | 20 | 49 | 228 | 228 | 299 |
| natural_nt | 155 | 44 (28.4%) | - | 0 | 111 | - | - | 343 |
| neg | 1823 | 2 (0.1%) | - | 2 | 1821 | - | - | 408 |

**offset+scale+combo: 332/1122 = 29.6% certos (88.3% entre as aceitas); falsos aceites em negativos: 2/1823**


## VAD `dsp`, janelas 5x30d

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 191 (36.2%) | 171 (32.4%) | 20 | 337 | 0 | 270 | 370 |
| scale | 198 | 63 (31.8%) | 48 (24.2%) | 15 | 135 | 21 | 314 | 379 |
| combo | 396 | 111 (28.0%) | 90 (22.7%) | 21 | 285 | 22 | 272 | 394 |
| piece1 | 198 | 64 (32.3%) | 14 (7.1%) | 50 | 134 | 608 | 1045 | 508 |
| piece2 | 99 | 9 (9.1%) | 1 (1.0%) | 8 | 90 | 1640 | 2123 | 479 |
| natural | 93 | 37 (39.8%) | 17 (18.3%) | 20 | 56 | 270 | 270 | 818 |
| natural_nt | 155 | 45 (29.0%) | - | 0 | 110 | - | - | 763 |
| neg | 1823 | 2 (0.1%) | - | 2 | 1821 | - | - | 430 |

**offset+scale+combo: 309/1122 = 27.5% certos (84.7% entre as aceitas); falsos aceites em negativos: 2/1823**


## VAD `dsp`, janelas 5x60

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 379 (71.8%) | 347 (65.7%) | 32 | 149 | 0 | 574 | 664 |
| scale | 198 | 136 (68.7%) | 100 (50.5%) | 36 | 62 | 22 | 573 | 712 |
| combo | 396 | 244 (61.6%) | 174 (43.9%) | 70 | 152 | 29 | 556 | 746 |
| piece1 | 198 | 114 (57.6%) | 28 (14.1%) | 86 | 84 | 626 | 1072 | 786 |
| natural | 93 | 67 (72.0%) | 20 (21.5%) | 47 | 26 | 369 | 369 | 696 |
| natural_nt | 155 | 64 (41.3%) | - | 0 | 91 | - | - | 674 |
| neg | 1823 | 3 (0.2%) | - | 3 | 1820 | - | - | 706 |

**offset+scale+combo: 621/1122 = 55.3% certos (81.8% entre as aceitas); falsos aceites em negativos: 3/1823**


## VAD `dsp`, janelas rck90

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|

## VAD `pywebrtc3`, janelas 3x60

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 281 (53.2%) | 248 (47.0%) | 33 | 247 | 0 | 147 | 322 |
| scale | 198 | 96 (48.5%) | 81 (40.9%) | 15 | 102 | 8 | 95 | 337 |
| combo | 396 | 167 (42.2%) | 144 (36.4%) | 23 | 229 | 8 | 95 | 339 |
| piece1 | 198 | 82 (41.4%) | 24 (12.1%) | 58 | 116 | 456 | 852 | 388 |
| piece2 | 99 | 8 (8.1%) | 3 (3.0%) | 5 | 91 | 734 | 905 | 432 |
| natural | 93 | 56 (60.2%) | 34 (36.6%) | 22 | 37 | 98 | 98 | 305 |
| natural_nt | 155 | 30 (19.4%) | - | 0 | 125 | - | - | 344 |
| neg | 1823 | 6 (0.3%) | - | 6 | 1817 | - | - | 326 |

**offset+scale+combo: 473/1122 = 42.2% certos (86.9% entre as aceitas); falsos aceites em negativos: 6/1823**


## VAD `silero`, janelas 2x60

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 290 (54.9%) | 283 (53.6%) | 7 | 238 | 0 | 119 | 154 |
| scale | 198 | 113 (57.1%) | 99 (50.0%) | 14 | 85 | 16 | 118 | 165 |
| combo | 396 | 197 (49.7%) | 170 (42.9%) | 27 | 199 | 16 | 116 | 181 |
| piece1 | 198 | 105 (53.0%) | 43 (21.7%) | 62 | 93 | 399 | 512 | 300 |
| natural | 93 | 41 (44.1%) | 31 (33.3%) | 10 | 52 | 133 | 133 | 135 |
| natural_nt | 155 | 43 (27.7%) | - | 0 | 112 | - | - | 120 |
| neg | 1823 | 7 (0.4%) | - | 7 | 1816 | - | - | 178 |

**offset+scale+combo: 552/1122 = 49.2% certos (92.0% entre as aceitas); falsos aceites em negativos: 7/1823**


## VAD `silero`, janelas 2x60d

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 475 (90.0%) | 442 (83.7%) | 33 | 53 | 0 | 186 | 158 |
| scale | 198 | 164 (82.8%) | 130 (65.7%) | 34 | 34 | 10 | 184 | 207 |
| combo | 396 | 312 (78.8%) | 241 (60.9%) | 71 | 84 | 11 | 186 | 231 |
| piece1 | 198 | 137 (69.2%) | 65 (32.8%) | 72 | 61 | 96 | 604 | 394 |
| piece2 | 99 | 24 (24.2%) | 8 (8.1%) | 16 | 75 | 1386 | 2728 | 469 |
| natural | 93 | 78 (83.9%) | 50 (53.8%) | 28 | 15 | 180 | 180 | 127 |
| natural_nt | 155 | 57 (36.8%) | - | 0 | 98 | - | - | 154 |
| neg | 1823 | 11 (0.6%) | - | 11 | 1812 | - | - | 179 |

**offset+scale+combo: 813/1122 = 72.5% certos (85.5% entre as aceitas); falsos aceites em negativos: 11/1823**


## VAD `silero`, janelas 3x20

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 184 (34.8%) | 180 (34.1%) | 4 | 344 | 0 | 122 | 96 |
| scale | 198 | 45 (22.7%) | 38 (19.2%) | 7 | 153 | 6 | 49 | 102 |
| combo | 396 | 83 (21.0%) | 72 (18.2%) | 11 | 313 | 5 | 50 | 107 |
| piece1 | 198 | 36 (18.2%) | 14 (7.1%) | 22 | 162 | 714 | 478 | 135 |
| natural | 93 | 25 (26.9%) | 13 (14.0%) | 12 | 68 | 225 | 225 | 108 |
| natural_nt | 155 | 14 (9.0%) | - | 0 | 141 | - | - | 55 |
| neg | 1823 | 3 (0.2%) | - | 3 | 1820 | - | - | 95 |

**offset+scale+combo: 290/1122 = 25.8% certos (92.9% entre as aceitas); falsos aceites em negativos: 3/1823**


## VAD `silero`, janelas 3x30

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 249 (47.2%) | 194 (36.7%) | 55 | 279 | 0 | 224 | 128 |
| scale | 198 | 71 (35.9%) | 59 (29.8%) | 12 | 127 | 13 | 176 | 138 |
| combo | 396 | 122 (30.8%) | 93 (23.5%) | 29 | 274 | 17 | 162 | 144 |
| piece1 | 198 | 73 (36.9%) | 16 (8.1%) | 57 | 125 | 629 | 576 | 189 |
| natural | 93 | 38 (40.9%) | 27 (29.0%) | 11 | 55 | 212 | 212 | 114 |
| natural_nt | 155 | 33 (21.3%) | - | 0 | 122 | - | - | 88 |
| neg | 1823 | 5 (0.3%) | - | 5 | 1818 | - | - | 119 |

**offset+scale+combo: 346/1122 = 30.8% certos (78.3% entre as aceitas); falsos aceites em negativos: 5/1823**


## VAD `silero`, janelas 3x30d

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 195 (36.9%) | 173 (32.8%) | 22 | 333 | 0 | 185 | 125 |
| scale | 198 | 64 (32.3%) | 57 (28.8%) | 7 | 134 | 14 | 153 | 130 |
| combo | 396 | 116 (29.3%) | 103 (26.0%) | 13 | 280 | 18 | 137 | 134 |
| piece1 | 198 | 65 (32.8%) | 8 (4.0%) | 57 | 133 | 498 | 544 | 187 |
| piece2 | 99 | 12 (12.1%) | 0 (0.0%) | 12 | 87 | 2040 | 2061 | 172 |
| natural | 93 | 36 (38.7%) | 25 (26.9%) | 11 | 57 | 188 | 188 | 102 |
| natural_nt | 155 | 36 (23.2%) | - | 0 | 119 | - | - | 90 |
| neg | 1823 | 4 (0.2%) | - | 4 | 1819 | - | - | 104 |

**offset+scale+combo: 333/1122 = 29.7% certos (88.8% entre as aceitas); falsos aceites em negativos: 4/1823**


## VAD `silero`, janelas 3x45

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 178 (33.7%) | 175 (33.1%) | 3 | 350 | 0 | 142 | 86 |
| scale | 198 | 42 (21.2%) | 42 (21.2%) | 0 | 156 | 3 | 142 | 109 |
| combo | 396 | 81 (20.5%) | 79 (19.9%) | 2 | 315 | 5 | 142 | 109 |
| piece1 | 198 | 54 (27.3%) | 17 (8.6%) | 37 | 144 | 564 | 770 | 144 |
| natural | 93 | 20 (21.5%) | 14 (15.1%) | 6 | 73 | 142 | 142 | 88 |
| natural_nt | 155 | 21 (13.5%) | - | 0 | 134 | - | - | 81 |
| neg | 1823 | 3 (0.2%) | - | 3 | 1820 | - | - | 109 |

**offset+scale+combo: 296/1122 = 26.4% certos (98.3% entre as aceitas); falsos aceites em negativos: 3/1823**


## VAD `silero`, janelas 3x45d

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 232 (43.9%) | 226 (42.8%) | 6 | 296 | 0 | 177 | 103 |
| scale | 198 | 59 (29.8%) | 58 (29.3%) | 1 | 139 | 1 | 150 | 125 |
| combo | 396 | 112 (28.3%) | 105 (26.5%) | 7 | 284 | 5 | 154 | 128 |
| piece1 | 198 | 78 (39.4%) | 25 (12.6%) | 53 | 120 | 314 | 494 | 170 |
| piece2 | 99 | 10 (10.1%) | 3 (3.0%) | 7 | 89 | 1752 | 2186 | 169 |
| natural | 93 | 31 (33.3%) | 18 (19.4%) | 13 | 62 | 167 | 167 | 121 |
| natural_nt | 155 | 30 (19.4%) | - | 0 | 125 | - | - | 88 |
| neg | 1823 | 3 (0.2%) | - | 3 | 1820 | - | - | 102 |

**offset+scale+combo: 389/1122 = 34.7% certos (96.5% entre as aceitas); falsos aceites em negativos: 3/1823**


## VAD `silero`, janelas 3x60

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 405 (76.7%) | 373 (70.6%) | 32 | 123 | 0 | 135 | 197 |
| scale | 198 | 136 (68.7%) | 108 (54.5%) | 28 | 62 | 11 | 148 | 225 |
| combo | 396 | 253 (63.9%) | 200 (50.5%) | 53 | 143 | 12 | 147 | 230 |
| piece1 | 198 | 125 (63.1%) | 51 (25.8%) | 74 | 73 | 376 | 562 | 349 |
| piece2 | 99 | 28 (28.3%) | 5 (5.1%) | 23 | 71 | 1520 | 1800 | 407 |
| natural | 93 | 62 (66.7%) | 40 (43.0%) | 22 | 31 | 130 | 130 | 201 |
| natural_nt | 155 | 64 (41.3%) | - | 0 | 91 | - | - | 209 |
| neg | 1823 | 7 (0.4%) | - | 7 | 1816 | - | - | 261 |

**offset+scale+combo: 681/1122 = 60.7% certos (85.8% entre as aceitas); falsos aceites em negativos: 7/1823**


## VAD `silero`, janelas 3x60d

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 421 (79.7%) | 394 (74.6%) | 27 | 107 | 0 | 183 | 198 |
| scale | 198 | 143 (72.2%) | 125 (63.1%) | 18 | 55 | 7 | 157 | 241 |
| combo | 396 | 271 (68.4%) | 232 (58.6%) | 39 | 125 | 10 | 157 | 252 |
| piece1 | 198 | 141 (71.2%) | 52 (26.3%) | 89 | 57 | 345 | 579 | 373 |
| piece2 | 99 | 32 (32.3%) | 6 (6.1%) | 26 | 67 | 1352 | 1031 | 435 |
| natural | 93 | 76 (81.7%) | 45 (48.4%) | 31 | 17 | 160 | 160 | 214 |
| natural_nt | 155 | 74 (47.7%) | - | 0 | 81 | - | - | 202 |
| neg | 1823 | 10 (0.5%) | - | 10 | 1813 | - | - | 252 |

**offset+scale+combo: 751/1122 = 66.9% certos (89.9% entre as aceitas); falsos aceites em negativos: 10/1823**


## VAD `silero`, janelas 3x90

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 496 (93.9%) | 473 (89.6%) | 23 | 32 | 0 | 347 | 188 |
| scale | 198 | 181 (91.4%) | 141 (71.2%) | 40 | 17 | 13 | 269 | 248 |
| combo | 396 | 344 (86.9%) | 254 (64.1%) | 90 | 52 | 15 | 209 | 266 |
| piece1 | 198 | 168 (84.8%) | 70 (35.4%) | 98 | 30 | 297 | 840 | 401 |
| natural | 93 | 87 (93.5%) | 41 (44.1%) | 46 | 6 | 317 | 317 | 198 |
| natural_nt | 155 | 91 (58.7%) | - | 0 | 64 | - | - | 246 |
| neg | 1823 | 8 (0.4%) | - | 8 | 1815 | - | - | 330 |

**offset+scale+combo: 868/1122 = 77.4% certos (85.0% entre as aceitas); falsos aceites em negativos: 8/1823**


## VAD `silero`, janelas 3x90d

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 479 (90.7%) | 461 (87.3%) | 18 | 49 | 0 | 199 | 174 |
| scale | 198 | 179 (90.4%) | 147 (74.2%) | 32 | 19 | 9 | 161 | 210 |
| combo | 396 | 338 (85.4%) | 274 (69.2%) | 64 | 58 | 9 | 158 | 226 |
| piece1 | 198 | 159 (80.3%) | 61 (30.8%) | 98 | 39 | 320 | 715 | 446 |
| piece2 | 99 | 44 (44.4%) | 12 (12.1%) | 32 | 55 | 1038 | 1856 | 571 |
| natural | 93 | 90 (96.8%) | 50 (53.8%) | 40 | 3 | 207 | 207 | 201 |
| natural_nt | 155 | 92 (59.4%) | - | 0 | 63 | - | - | 222 |
| neg | 1823 | 9 (0.5%) | - | 9 | 1814 | - | - | 309 |

**offset+scale+combo: 882/1122 = 78.6% certos (88.6% entre as aceitas); falsos aceites em negativos: 9/1823**


## VAD `silero`, janelas 5x30

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 298 (56.4%) | 253 (47.9%) | 45 | 230 | 0 | 234 | 161 |
| scale | 198 | 83 (41.9%) | 68 (34.3%) | 15 | 115 | 8 | 180 | 162 |
| combo | 396 | 151 (38.1%) | 123 (31.1%) | 28 | 245 | 10 | 180 | 166 |
| piece1 | 198 | 100 (50.5%) | 21 (10.6%) | 79 | 98 | 884 | 866 | 212 |
| natural | 93 | 53 (57.0%) | 24 (25.8%) | 29 | 40 | 357 | 357 | 164 |
| natural_nt | 155 | 39 (25.2%) | - | 0 | 116 | - | - | 99 |
| neg | 1823 | 8 (0.4%) | - | 8 | 1815 | - | - | 132 |

**offset+scale+combo: 444/1122 = 39.6% certos (83.5% entre as aceitas); falsos aceites em negativos: 8/1823**


## VAD `silero`, janelas 5x30d

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 366 (69.3%) | 337 (63.8%) | 29 | 162 | 0 | 224 | 124 |
| scale | 198 | 107 (54.0%) | 93 (47.0%) | 14 | 91 | 8 | 168 | 138 |
| combo | 396 | 202 (51.0%) | 169 (42.7%) | 33 | 194 | 10 | 166 | 138 |
| piece1 | 198 | 109 (55.1%) | 27 (13.6%) | 82 | 89 | 1011 | 942 | 215 |
| piece2 | 99 | 14 (14.1%) | 1 (1.0%) | 13 | 85 | 1189 | 1906 | 201 |
| natural | 93 | 64 (68.8%) | 35 (37.6%) | 29 | 29 | 222 | 222 | 134 |
| natural_nt | 155 | 43 (27.7%) | - | 0 | 112 | - | - | 98 |
| neg | 1823 | 10 (0.5%) | - | 10 | 1813 | - | - | 133 |

**offset+scale+combo: 599/1122 = 53.4% certos (88.7% entre as aceitas); falsos aceites em negativos: 10/1823**


## VAD `silero`, janelas 5x60

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 463 (87.7%) | 437 (82.8%) | 26 | 65 | 0 | 296 | 211 |
| scale | 198 | 164 (82.8%) | 150 (75.8%) | 14 | 34 | 12 | 176 | 230 |
| combo | 396 | 303 (76.5%) | 269 (67.9%) | 34 | 93 | 12 | 181 | 241 |
| piece1 | 198 | 147 (74.2%) | 67 (33.8%) | 80 | 51 | 192 | 649 | 336 |
| natural | 93 | 82 (88.2%) | 43 (46.2%) | 39 | 11 | 213 | 213 | 219 |
| natural_nt | 155 | 82 (52.9%) | - | 0 | 73 | - | - | 213 |
| neg | 1823 | 3 (0.2%) | - | 3 | 1820 | - | - | 244 |

**offset+scale+combo: 856/1122 = 76.3% certos (92.0% entre as aceitas); falsos aceites em negativos: 3/1823**


## VAD `webrtc2`, janelas 3x60

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 232 (43.9%) | 214 (40.5%) | 18 | 296 | 0 | 513 | 352 |
| scale | 198 | 67 (33.8%) | 54 (27.3%) | 13 | 131 | 20 | 403 | 366 |
| combo | 396 | 122 (30.8%) | 101 (25.5%) | 21 | 274 | 19 | 403 | 368 |
| piece1 | 198 | 36 (18.2%) | 13 (6.6%) | 23 | 162 | 206 | 3280 | 434 |
| piece2 | 99 | 8 (8.1%) | 1 (1.0%) | 7 | 91 | 4953 | 5204 | 436 |
| natural | 93 | 35 (37.6%) | 12 (12.9%) | 23 | 58 | 495 | 495 | 414 |
| natural_nt | 155 | 15 (9.7%) | - | 0 | 140 | - | - | 357 |
| neg | 1823 | 3 (0.2%) | - | 3 | 1820 | - | - | 323 |

**offset+scale+combo: 369/1122 = 32.9% certos (87.6% entre as aceitas); falsos aceites em negativos: 3/1823**


## VAD `webrtc3`, janelas 3x60

| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |
|---|---|---|---|---|---|---|---|---|
| offset | 528 | 236 (44.7%) | 223 (42.2%) | 13 | 292 | 0 | 108 | 372 |
| scale | 198 | 65 (32.8%) | 58 (29.3%) | 7 | 133 | 16 | 86 | 398 |
| combo | 396 | 121 (30.6%) | 106 (26.8%) | 15 | 275 | 19 | 87 | 397 |
| piece1 | 198 | 56 (28.3%) | 20 (10.1%) | 36 | 142 | 478 | 1058 | 429 |
| piece2 | 99 | 5 (5.1%) | 0 (0.0%) | 5 | 94 | 2352 | 2293 | 466 |
| natural | 93 | 49 (52.7%) | 24 (25.8%) | 25 | 44 | 486 | 486 | 385 |
| natural_nt | 155 | 27 (17.4%) | - | 0 | 128 | - | - | 333 |
| neg | 1823 | 6 (0.3%) | - | 6 | 1817 | - | - | 330 |

**offset+scale+combo: 387/1122 = 34.5% certos (91.7% entre as aceitas); falsos aceites em negativos: 6/1823**


## Quanto áudio (offset+scale+combo)

| VAD | janelas | áudio (s) | n | acertos | % certos | % certos entre aceitas | negativos aceitos |
|---|---|---|---|---|---|---|---|
| dsp | 3x20 | 121 | 1122 | 106 | 9.4% | 76.3% | 4/1823 |
| dsp | 3x30d | 170 | 1122 | 270 | 24.1% | 88.8% | 1/1823 |
| dsp | 3x30 | 185 | 1122 | 257 | 22.9% | 81.8% | 2/1823 |
| dsp | 5x30 | 225 | 1122 | 332 | 29.6% | 88.3% | 2/1823 |
| dsp | 5x30d | 232 | 1122 | 309 | 27.5% | 84.7% | 2/1823 |
| dsp | 2x60d | 245 | 1122 | 534 | 47.6% | 86.1% | 13/1823 |
| dsp | 3x45 | 275 | 1122 | 195 | 17.4% | 81.6% | 7/1823 |
| dsp | 3x45d | 300 | 1122 | 204 | 18.2% | 78.2% | 4/1823 |
| dsp | 2x60 | 315 | 1122 | 432 | 38.5% | 79.6% | 7/1823 |
| dsp | 3x90d | 405 | 1122 | 812 | 72.4% | 89.1% | 4/1823 |
| dsp | 3x60 | 408 | 1122 | 537 | 47.9% | 79.7% | 8/1823 |
| dsp | 3x60d | 410 | 1122 | 559 | 49.8% | 82.3% | 10/1823 |
| dsp | 3x90 | 419 | 1122 | 689 | 61.4% | 86.7% | 4/1823 |
| dsp | 5x60 | 442 | 1122 | 621 | 55.3% | 81.8% | 3/1823 |
| pywebrtc3 | 3x60 | 383 | 1122 | 473 | 42.2% | 86.9% | 6/1823 |
| silero | 3x20 | 107 | 1122 | 290 | 25.8% | 92.9% | 3/1823 |
| silero | 3x30d | 182 | 1122 | 333 | 29.7% | 88.8% | 4/1823 |
| silero | 3x30 | 182 | 1122 | 346 | 30.8% | 78.3% | 5/1823 |
| silero | 3x45 | 205 | 1122 | 296 | 26.4% | 98.3% | 3/1823 |
| silero | 5x30d | 219 | 1122 | 599 | 53.4% | 88.7% | 10/1823 |
| silero | 3x45d | 231 | 1122 | 389 | 34.7% | 96.5% | 3/1823 |
| silero | 5x30 | 233 | 1122 | 444 | 39.6% | 83.5% | 8/1823 |
| silero | 2x60 | 239 | 1122 | 552 | 49.2% | 92.0% | 7/1823 |
| silero | 2x60d | 263 | 1122 | 813 | 72.5% | 85.5% | 11/1823 |
| silero | 3x60 | 305 | 1122 | 681 | 60.7% | 85.8% | 7/1823 |
| silero | 3x60d | 320 | 1122 | 751 | 66.9% | 89.9% | 10/1823 |
| silero | 3x90d | 352 | 1122 | 882 | 78.6% | 88.6% | 9/1823 |
| silero | 3x90 | 364 | 1122 | 868 | 77.4% | 85.0% | 8/1823 |
| silero | 5x60 | 391 | 1122 | 856 | 76.3% | 92.0% | 3/1823 |
| webrtc2 | 3x60 | 402 | 1122 | 369 | 32.9% | 87.6% | 3/1823 |
| webrtc3 | 3x60 | 405 | 1122 | 387 | 34.5% | 91.7% | 6/1823 |

## Por filme (VAD dsp, offset+scale+combo, todas as janelas somadas)

| filme | n | % certos | aceitas erradas |
|---|---|---|---|
| charade | 102 | 47% | 2 |
| dementia_13 | 34 | 68% | 11 |
| detour | 34 | 85% | 0 |
| freaks | 102 | 5% | 10 |
| his_girl_friday | 102 | 7% | 1 |
| hitch_hiker | 34 | 97% | 0 |
| house_on_haunted_hill | 34 | 97% | 0 |
| little_shop | 34 | 68% | 3 |
| manos | 68 | 60% | 18 |
| meet_john_doe | 102 | 31% | 12 |
| my_man_godfrey | 34 | 0% | 0 |
| night_of_the_living_dead | 34 | 0% | 6 |
| reefer_madness | 34 | 29% | 13 |
| scarlet_street | 102 | 83% | 9 |
| sherlock_secret_weapon | 68 | 93% | 0 |
| suddenly | 102 | 61% | 26 |
| the_stranger | 102 | 42% | 26 |
