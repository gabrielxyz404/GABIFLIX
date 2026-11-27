# Site (celular e Mac): base x novo

Chromium com o processador atrasado (Mac 2x, celular 4x), 3 execuções, mediana. "cast1500" = o script do Chromecast demora 1,5 s a responder.

## Mac 1440x900 - cast0

| medida | base | novo | diferença |
|---|---:|---:|---:|
| primeiro cartão da Home (ms) | 639 | 587 | -52.0 (-8%) |
| DOMContentLoaded (ms) | 281 | 243 | -38.0 (-14%) |
| evento load (ms) | 639 | 587 | -52.0 (-8%) |
| abrir o serviço até haver pôsteres (ms) | 153 | 156 | +3.0 (+2%) |
| abrir a aba Filmes (ms) | 23 | 26 | +3.0 (+13%) |
| rolagem: tempo médio por quadro (ms) | 17.9 | 17.8 | -0.1 (-1%) |
| rolagem: quadro p95 (ms) | 33.3 | 33.2 | -0.1 (-0%) |
| rolagem: pior quadro (ms) | 33.4 | 50 | +16.6 (+50%) |
| rolagem: quadros > 33 ms (de 149) | 4 | 2 | -2.0 (-50%) |
| rolagem: quadros > 50 ms | 0 | 0 | +0.0 |
| memória JS (MB) | 18 | 18 | +0.0 (+0%) |
| nós do DOM no fim | 4201 | 4202 | +1.0 (+0%) |
| chamadas de MutationObserver | 88 | 88 | +0.0 (+0%) |
| tempo nos MutationObserver (ms) | 1 | 1 | +0.0 (+0%) |
| tarefas longas (> 50 ms) | 1 | 1 | +0.0 (+0%) |
| tempo em tarefas longas (ms) | 103 | 82 | -21.0 (-20%) |
| CPU total da página (ms) | 2758 | 2744 | -14.0 (-1%) |
| JS (ms) | 301 | 272 | -29.0 (-10%) |
| layout (ms) | 159 | 159 | +0.0 (+0%) |
| estilo (ms) | 431 | 448 | +17.0 (+4%) |
| transferido (KB, comprimido) | 1193 | 1193 | +0.0 (+0%) |
| pedidos | 250 | 250 | +0.0 (+0%) |
| pediu o script do Chromecast | 1 | 0 | -1.0 (-100%) |

## Mac 1440x900 - cast1500

| medida | base | novo | diferença |
|---|---:|---:|---:|
| primeiro cartão da Home (ms) | 1742 | 570 | -1172.0 (-67%) |
| DOMContentLoaded (ms) | 1562 | 241 | -1321.0 (-85%) |
| evento load (ms) | 1743 | 570 | -1173.0 (-67%) |
| abrir o serviço até haver pôsteres (ms) | 124 | 126 | +2.0 (+2%) |
| abrir a aba Filmes (ms) | 29 | 25 | -4.0 (-14%) |
| rolagem: tempo médio por quadro (ms) | 17.5 | 17.5 | +0.0 (+0%) |
| rolagem: quadro p95 (ms) | 33.3 | 33.3 | +0.0 (+0%) |
| rolagem: pior quadro (ms) | 33.4 | 33.4 | +0.0 (+0%) |
| rolagem: quadros > 33 ms (de 149) | 1 | 1 | +0.0 (+0%) |
| rolagem: quadros > 50 ms | 0 | 0 | +0.0 |
| memória JS (MB) | 18 | 18 | +0.0 (+0%) |
| nós do DOM no fim | 4209 | 4202 | -7.0 (-0%) |
| chamadas de MutationObserver | 53 | 88 | +35.0 (+66%) |
| tempo nos MutationObserver (ms) | 1 | 0 | -1.0 (-100%) |
| tarefas longas (> 50 ms) | 1 | 1 | +0.0 (+0%) |
| tempo em tarefas longas (ms) | 83 | 84 | +1.0 (+1%) |
| CPU total da página (ms) | 2512 | 2662 | +150.0 (+6%) |
| JS (ms) | 273 | 285 | +12.0 (+4%) |
| layout (ms) | 119 | 150 | +31.0 (+26%) |
| estilo (ms) | 409 | 409 | +0.0 (+0%) |
| transferido (KB, comprimido) | 1176 | 1193 | +17.0 (+1%) |
| pedidos | 250 | 250 | +0.0 (+0%) |
| pediu o script do Chromecast | 1 | 0 | -1.0 (-100%) |

## Celular 412x915 - cast0

| medida | base | novo | diferença |
|---|---:|---:|---:|
| primeiro cartão da Home (ms) | 757 | 824 | +67.0 (+9%) |
| DOMContentLoaded (ms) | 324 | 329 | +5.0 (+2%) |
| evento load (ms) | 757 | 825 | +68.0 (+9%) |
| abrir o serviço até haver pôsteres (ms) | 228 | 254 | +26.0 (+11%) |
| abrir a aba Filmes (ms) | 44 | 50 | +6.0 (+14%) |
| rolagem: tempo médio por quadro (ms) | 16.6 | 16.6 | +0.0 (+0%) |
| rolagem: quadro p95 (ms) | 16.7 | 16.7 | +0.0 (+0%) |
| rolagem: pior quadro (ms) | 16.8 | 16.8 | +0.0 (+0%) |
| rolagem: quadros > 33 ms (de 149) | 0 | 0 | +0.0 |
| rolagem: quadros > 50 ms | 0 | 0 | +0.0 |
| memória JS (MB) | 18 | 18 | +0.0 (+0%) |
| nós do DOM no fim | 3542 | 3543 | +1.0 (+0%) |
| chamadas de MutationObserver | 57 | 54 | -3.0 (-5%) |
| tempo nos MutationObserver (ms) | 1 | 1 | +0.0 (+0%) |
| tarefas longas (> 50 ms) | 1 | 1 | +0.0 (+0%) |
| tempo em tarefas longas (ms) | 201 | 168 | -33.0 (-16%) |
| CPU total da página (ms) | 4001 | 3964 | -37.0 (-1%) |
| JS (ms) | 478 | 477 | -1.0 (-0%) |
| layout (ms) | 394 | 389 | -5.0 (-1%) |
| estilo (ms) | 682 | 666 | -16.0 (-2%) |
| transferido (KB, comprimido) | 1248 | 1248 | +0.0 (+0%) |
| pedidos | 158 | 157 | -1.0 (-1%) |
| pediu o script do Chromecast | 1 | 0 | -1.0 (-100%) |

## Celular 412x915 - cast1500

| medida | base | novo | diferença |
|---|---:|---:|---:|
| primeiro cartão da Home (ms) | 1672 | 825 | -847.0 (-51%) |
| DOMContentLoaded (ms) | 1551 | 392 | -1159.0 (-75%) |
| evento load (ms) | 1673 | 826 | -847.0 (-51%) |
| abrir o serviço até haver pôsteres (ms) | 265 | 261 | -4.0 (-2%) |
| abrir a aba Filmes (ms) | 40 | 45 | +5.0 (+12%) |
| rolagem: tempo médio por quadro (ms) | 16.7 | 16.6 | -0.1 (-1%) |
| rolagem: quadro p95 (ms) | 16.7 | 16.8 | +0.1 (+1%) |
| rolagem: pior quadro (ms) | 33.4 | 16.8 | -16.6 (-50%) |
| rolagem: quadros > 33 ms (de 149) | 0 | 0 | +0.0 |
| rolagem: quadros > 50 ms | 0 | 0 | +0.0 |
| memória JS (MB) | 18 | 18 | +0.0 (+0%) |
| nós do DOM no fim | 3486 | 3543 | +57.0 (+2%) |
| chamadas de MutationObserver | 31 | 54 | +23.0 (+74%) |
| tempo nos MutationObserver (ms) | 1 | 2 | +1.0 (+100%) |
| tarefas longas (> 50 ms) | 1 | 1 | +0.0 (+0%) |
| tempo em tarefas longas (ms) | 157 | 191 | +34.0 (+22%) |
| CPU total da página (ms) | 4149 | 4297 | +148.0 (+4%) |
| JS (ms) | 454 | 521 | +67.0 (+15%) |
| layout (ms) | 397 | 405 | +8.0 (+2%) |
| estilo (ms) | 672 | 756 | +84.0 (+12%) |
| transferido (KB, comprimido) | 1231 | 1248 | +17.0 (+1%) |
| pedidos | 157 | 157 | +0.0 (+0%) |
| pediu o script do Chromecast | 1 | 0 | -1.0 (-100%) |

## CPU por arquivo (mac, 1ª execução, total 10158 ms de amostras)

- (nativo):-1: 9631 ms
- scripts/main.js: 246 ms
- (nativo): 215 ms
- gabiflix/home.js?v=59: 61 ms
- gabiflix/app.js?v=15: 3 ms
- gabiflix/mac.js?v=2: 1 ms

### Funções que mais gastam (mac)

- (idle) @ (nativo):-1: 7426 ms
- (program) @ (nativo):-1: 2179 ms
- step @ (nativo):4: 173 ms
- (anônima) @ scripts/main.js:0: 71 ms
- T @ scripts/main.js:0: 30 ms
- posterEl @ gabiflix/home.js?v=59:413: 16 ms
- renderCatalog @ gabiflix/home.js?v=59:740: 15 ms
- (garbage collector) @ (nativo):-1: 12 ms
- Xt @ scripts/main.js:0: 10 ms
- render @ gabiflix/home.js?v=59:869: 10 ms
- isElementVisible @ (nativo):111: 9 ms
- A @ scripts/main.js:0: 7 ms

## CPU por arquivo (and, 1ª execução, total 10327 ms de amostras)

- (nativo):-1: 9488 ms
- scripts/main.js: 425 ms
- (nativo): 315 ms
- gabiflix/home.js?v=59: 93 ms
- gabiflix/app.js?v=15: 4 ms
- gabiflix/search.js?v=6: 1 ms
- scripts/worker.js: 1 ms

### Funções que mais gastam (and)

- (idle) @ (nativo):-1: 6411 ms
- (program) @ (nativo):-1: 3026 ms
- step @ (nativo):4: 265 ms
- (anônima) @ scripts/main.js:0: 103 ms
- T @ scripts/main.js:0: 78 ms
- render @ gabiflix/home.js?v=59:869: 29 ms
- posterEl @ gabiflix/home.js?v=59:413: 24 ms
- (garbage collector) @ (nativo):-1: 15 ms
- renderCatalog @ gabiflix/home.js?v=59:740: 12 ms
- O_ @ scripts/main.js:0: 10 ms
- (anônima) @ (nativo):-1: 9 ms
- jn @ scripts/main.js:0: 9 ms

