# Bancada dos 300 titulos: o motor de sincronia de legendas pelo audio

Medido com o laco real do app (guardas, plano de janelas, VAD Silero em Java puro, `AudioAligner` e a regra de decisao `fill`), sem alterar o motor.
Motores: **main** = `origin/main` (3 janelas densas, uma resolucao); **drift** = ramo `run-deriva` (3 densas + 6 espalhadas + ate 2 rodadas extras, prova de deriva).

## 1. Titulos usados

Titulos tentados: **432**. Com resultado valido (referencia nivel A): **89** (antigos filmes 83, antigos TV 2, novos 4).
Proporcao antigos/novos: 96% / 4%.


Destino de cada titulo tentado:

| destino | titulos |
|---|---|
| referencia ruim | 241 |
| ok | 146 |
| sem legenda utilizavel | 37 |
| download falhou | 5 |
| nenhuma legenda alinhou | 3 |

Qualidade da referencia (legenda alinhada ao audio pelo alass; 2o oraculo = ffsubsync sobre a referencia): nivel A = desvio p90 do alass <= 300 ms e ffsubsync <= 300 ms; B = p90 ate 1000 ms (ampliado); X = excluida.
Referencias por nivel entre os titulos que chegaram a ter uma: A=89, B=57, X=241.

| origem da legenda de referencia | titulos |
|---|---|
| opensubtitles - PT (parser do app) | 47 |
| opensubtitles - outro idioma (eng) | 23 |
| ia-item - outro idioma (eng) | 13 |
| opensubtitles - outro idioma (und) | 4 |
| opensubtitles - outro idioma (spa) | 2 |

| decada | titulos |
|---|---|
| 1920s | 1 |
| 1930s | 17 |
| 1940s | 30 |
| 1950s | 23 |
| 1960s | 10 |
| 1970s | 2 |
| 2010s | 2 |
| 2020s | 2 |

| fonte da midia / licenca declarada | titulos |
|---|---|
| sem licenca declarada (colecao de dominio publico do IA) /  | 36 |
| http://creativecommons.org/licenses/publicdomain/ /  | 21 |
| https://creativecommons.org/licenses/by-nc-nd/4.0/ /  | 11 |
| http://creativecommons.org/publicdomain/mark/1.0/ /  | 9 |
| https://creativecommons.org/publicdomain/mark/1.0/ /  | 9 |
| https://creativecommons.org/licenses/by-sa/4.0/ /  | 1 |
| http://creativecommons.org/licenses/by-nc-sa/4.0/ /  | 1 |
| https://creativecommons.org/publicdomain/zero/1.0/ /  | 1 |

## 2. Numeros principais (todas as variantes, por tipo e por grupo)

Definicoes: **aceita** = o app aplicaria (FIX) ou diria "ja esta certa" (OK); **certa** = aceita e erro p95 <= 400 ms e max <= 800 ms em todas as falas (60 s .. fim-60 s) contra a referencia; **aceite errado** = aceita mas fora disso; **aceite falso** = aceitou a legenda de OUTRO titulo; **certa mexida** = a legenda ja certa recebeu um ajuste (FIX).

| grupo | motor | variantes S (devem ser corrigidas) | aceitas | certas | ACEITES ERRADOS | recusadas | legenda ja certa: mexidas | outro titulo: ACEITES FALSOS |
|---|---|---|---|---|---|---|---|---|
| todos | main | 979 | 65.7% | 63.8% | 18 | 34.3% | 0/89 | 0/267 |
| todos | drift | 979 | 68.5% | 66.8% | 17 | 31.5% | 0/89 | 0/267 |
| antigos (filmes) | main | 913 | 67.8% | 66.0% | 16 | 32.2% | 0/83 | 0/249 |
| antigos (filmes) | drift | 913 | 70.5% | 68.9% | 15 | 29.5% | 0/83 | 0/249 |
| antigos (TV) | main | 22 | 9.1% | 4.5% | 1 | 90.9% | 0/2 | 0/6 |
| antigos (TV) | drift | 22 | 9.1% | 4.5% | 1 | 90.9% | 0/2 | 0/6 |
| novos | main | 44 | 50.0% | 47.7% | 1 | 50.0% | 0/4 | 0/12 |
| novos | drift | 44 | 56.8% | 54.5% | 1 | 43.2% | 0/4 | 0/12 |

### Por familia de variante


**todos**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 89 | 67.4% | certas 66.3% (59) | ERRADAS 1 | recusadas 29 | z mediana 16.1 |
| deslocamento +3 s | drift | 89 | 67.4% | certas 66.3% (59) | ERRADAS 1 | recusadas 29 | z mediana 16.1 |
| deslocamento -2 s | main | 89 | 70.8% | certas 68.5% (61) | ERRADAS 2 | recusadas 26 | z mediana 15.5 |
| deslocamento -2 s | drift | 89 | 70.8% | certas 68.5% (61) | ERRADAS 2 | recusadas 26 | z mediana 15.5 |
| deslocamento +7,5 s | main | 89 | 73.0% | certas 70.8% (63) | ERRADAS 2 | recusadas 24 | z mediana 16.0 |
| deslocamento +7,5 s | drift | 89 | 73.0% | certas 70.8% (63) | ERRADAS 2 | recusadas 24 | z mediana 16.0 |
| escala 1.001 (24/23.976) | main | 178 | 74.7% | certas 71.9% (128) | ERRADAS 5 | recusadas 45 | z mediana 16.1 |
| escala 1.001 (24/23.976) | drift | 178 | 74.7% | certas 71.9% (128) | ERRADAS 5 | recusadas 45 | z mediana 16.1 |
| escala 0.999 (23.976/24) | main | 178 | 76.4% | certas 74.7% (133) | ERRADAS 3 | recusadas 42 | z mediana 16.4 |
| escala 0.999 (23.976/24) | drift | 178 | 76.4% | certas 74.7% (133) | ERRADAS 3 | recusadas 42 | z mediana 16.4 |
| escala 25/23.976 | main | 178 | 48.3% | certas 45.5% (81) | ERRADAS 5 | recusadas 92 | z mediana 13.0 |
| escala 25/23.976 | drift | 178 | 59.0% | certas 56.7% (101) | ERRADAS 4 | recusadas 73 | z mediana 13.2 |
| escala 23.976/25 | main | 178 | 56.2% | certas 56.2% (100) | ERRADAS 0 | recusadas 78 | z mediana 13.1 |
| escala 23.976/25 | drift | 178 | 61.2% | certas 61.2% (109) | ERRADAS 0 | recusadas 69 | z mediana 13.4 |
| ja certa | main | 89 | 69.7% | intactas 89 | MEXIDAS 0 | - | z mediana 16.7 |
| ja certa | drift | 89 | 69.7% | intactas 89 | MEXIDAS 0 | - | z mediana 16.7 |
| outro titulo | main | 178 | 0.0% | recusou 178 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo | drift | 178 | 0.0% | recusou 178 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo (com escala) | main | 89 | 0.0% | recusou 89 | ACEITES FALSOS 0 | - | z mediana 5.3 |
| outro titulo (com escala) | drift | 89 | 0.0% | recusou 89 | ACEITES FALSOS 0 | - | z mediana 5.3 |

**antigos (filmes)**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 83 | 68.7% | certas 67.5% (56) | ERRADAS 1 | recusadas 26 | z mediana 16.7 |
| deslocamento +3 s | drift | 83 | 68.7% | certas 67.5% (56) | ERRADAS 1 | recusadas 26 | z mediana 16.7 |
| deslocamento -2 s | main | 83 | 72.3% | certas 71.1% (59) | ERRADAS 1 | recusadas 23 | z mediana 15.7 |
| deslocamento -2 s | drift | 83 | 72.3% | certas 71.1% (59) | ERRADAS 1 | recusadas 23 | z mediana 15.7 |
| deslocamento +7,5 s | main | 83 | 75.9% | certas 73.5% (61) | ERRADAS 2 | recusadas 20 | z mediana 16.1 |
| deslocamento +7,5 s | drift | 83 | 75.9% | certas 73.5% (61) | ERRADAS 2 | recusadas 20 | z mediana 16.1 |
| escala 1.001 (24/23.976) | main | 166 | 77.7% | certas 74.7% (124) | ERRADAS 5 | recusadas 37 | z mediana 16.2 |
| escala 1.001 (24/23.976) | drift | 166 | 77.7% | certas 74.7% (124) | ERRADAS 5 | recusadas 37 | z mediana 16.2 |
| escala 0.999 (23.976/24) | main | 166 | 79.5% | certas 77.7% (129) | ERRADAS 3 | recusadas 34 | z mediana 16.6 |
| escala 0.999 (23.976/24) | drift | 166 | 79.5% | certas 77.7% (129) | ERRADAS 3 | recusadas 34 | z mediana 16.6 |
| escala 25/23.976 | main | 166 | 50.0% | certas 47.6% (79) | ERRADAS 4 | recusadas 83 | z mediana 13.1 |
| escala 25/23.976 | drift | 166 | 60.8% | certas 59.0% (98) | ERRADAS 3 | recusadas 65 | z mediana 13.4 |
| escala 23.976/25 | main | 166 | 57.2% | certas 57.2% (95) | ERRADAS 0 | recusadas 71 | z mediana 13.4 |
| escala 23.976/25 | drift | 166 | 61.4% | certas 61.4% (102) | ERRADAS 0 | recusadas 64 | z mediana 13.5 |
| ja certa | main | 83 | 72.3% | intactas 83 | MEXIDAS 0 | - | z mediana 17.1 |
| ja certa | drift | 83 | 72.3% | intactas 83 | MEXIDAS 0 | - | z mediana 17.1 |
| outro titulo | main | 166 | 0.0% | recusou 166 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo | drift | 166 | 0.0% | recusou 166 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo (com escala) | main | 83 | 0.0% | recusou 83 | ACEITES FALSOS 0 | - | z mediana 5.3 |
| outro titulo (com escala) | drift | 83 | 0.0% | recusou 83 | ACEITES FALSOS 0 | - | z mediana 5.3 |

**antigos (TV)**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 2 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 2 | z mediana 14.4 |
| deslocamento +3 s | drift | 2 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 2 | z mediana 14.4 |
| deslocamento -2 s | main | 2 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 2 | z mediana 14.6 |
| deslocamento -2 s | drift | 2 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 2 | z mediana 14.6 |
| deslocamento +7,5 s | main | 2 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 2 | z mediana 13.7 |
| deslocamento +7,5 s | drift | 2 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 2 | z mediana 13.7 |
| escala 1.001 (24/23.976) | main | 4 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 4 | z mediana 13.2 |
| escala 1.001 (24/23.976) | drift | 4 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 4 | z mediana 13.2 |
| escala 0.999 (23.976/24) | main | 4 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 4 | z mediana 14.2 |
| escala 0.999 (23.976/24) | drift | 4 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 4 | z mediana 14.2 |
| escala 25/23.976 | main | 4 | 25.0% | certas 0.0% (0) | ERRADAS 1 | recusadas 3 | z mediana 11.7 |
| escala 25/23.976 | drift | 4 | 25.0% | certas 0.0% (0) | ERRADAS 1 | recusadas 3 | z mediana 11.7 |
| escala 23.976/25 | main | 4 | 25.0% | certas 25.0% (1) | ERRADAS 0 | recusadas 3 | z mediana 11.8 |
| escala 23.976/25 | drift | 4 | 25.0% | certas 25.0% (1) | ERRADAS 0 | recusadas 3 | z mediana 11.8 |
| ja certa | main | 2 | 0.0% | intactas 2 | MEXIDAS 0 | - | z mediana 15.2 |
| ja certa | drift | 2 | 0.0% | intactas 2 | MEXIDAS 0 | - | z mediana 15.2 |
| outro titulo | main | 4 | 0.0% | recusou 4 | ACEITES FALSOS 0 | - | z mediana 5.1 |
| outro titulo | drift | 4 | 0.0% | recusou 4 | ACEITES FALSOS 0 | - | z mediana 5.1 |
| outro titulo (com escala) | main | 2 | 0.0% | recusou 2 | ACEITES FALSOS 0 | - | z mediana 4.8 |
| outro titulo (com escala) | drift | 2 | 0.0% | recusou 2 | ACEITES FALSOS 0 | - | z mediana 4.8 |

**novos**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 4 | 75.0% | certas 75.0% (3) | ERRADAS 0 | recusadas 1 | z mediana 15.1 |
| deslocamento +3 s | drift | 4 | 75.0% | certas 75.0% (3) | ERRADAS 0 | recusadas 1 | z mediana 15.1 |
| deslocamento -2 s | main | 4 | 75.0% | certas 50.0% (2) | ERRADAS 1 | recusadas 1 | z mediana 13.9 |
| deslocamento -2 s | drift | 4 | 75.0% | certas 50.0% (2) | ERRADAS 1 | recusadas 1 | z mediana 13.9 |
| deslocamento +7,5 s | main | 4 | 50.0% | certas 50.0% (2) | ERRADAS 0 | recusadas 2 | z mediana 13.6 |
| deslocamento +7,5 s | drift | 4 | 50.0% | certas 50.0% (2) | ERRADAS 0 | recusadas 2 | z mediana 13.6 |
| escala 1.001 (24/23.976) | main | 8 | 50.0% | certas 50.0% (4) | ERRADAS 0 | recusadas 4 | z mediana 14.5 |
| escala 1.001 (24/23.976) | drift | 8 | 50.0% | certas 50.0% (4) | ERRADAS 0 | recusadas 4 | z mediana 14.5 |
| escala 0.999 (23.976/24) | main | 8 | 50.0% | certas 50.0% (4) | ERRADAS 0 | recusadas 4 | z mediana 14.6 |
| escala 0.999 (23.976/24) | drift | 8 | 50.0% | certas 50.0% (4) | ERRADAS 0 | recusadas 4 | z mediana 14.6 |
| escala 25/23.976 | main | 8 | 25.0% | certas 25.0% (2) | ERRADAS 0 | recusadas 6 | z mediana 13.2 |
| escala 25/23.976 | drift | 8 | 37.5% | certas 37.5% (3) | ERRADAS 0 | recusadas 5 | z mediana 13.2 |
| escala 23.976/25 | main | 8 | 50.0% | certas 50.0% (4) | ERRADAS 0 | recusadas 4 | z mediana 12.8 |
| escala 23.976/25 | drift | 8 | 75.0% | certas 75.0% (6) | ERRADAS 0 | recusadas 2 | z mediana 12.8 |
| ja certa | main | 4 | 50.0% | intactas 4 | MEXIDAS 0 | - | z mediana 14.4 |
| ja certa | drift | 4 | 50.0% | intactas 4 | MEXIDAS 0 | - | z mediana 14.4 |
| outro titulo | main | 8 | 0.0% | recusou 8 | ACEITES FALSOS 0 | - | z mediana 5.1 |
| outro titulo | drift | 8 | 0.0% | recusou 8 | ACEITES FALSOS 0 | - | z mediana 5.1 |
| outro titulo (com escala) | main | 4 | 0.0% | recusou 4 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo (com escala) | drift | 4 | 0.0% | recusou 4 | ACEITES FALSOS 0 | - | z mediana 5.2 |

## 3. Cabeca a cabeca: main x deriva (mesma variante, mesmo titulo)

| familia | main | deriva | n |
|---|---|---|---|
| escala 23.976/25 | recusou | certa | 10 |
| escala 23.976/25 | certa | recusou | 1 |
| escala 25/23.976 | recusou | certa | 22 |
| escala 25/23.976 | certa | recusou | 3 |
| escala 25/23.976 | ERRADA | certa | 1 |

Iguais nos dois motores: 1298 de 1335 variantes.


**Regressoes da deriva (certa/intacta no main que passou a errada/mexida/recusada):** 4

- `pontodeviragem5250` `g25/23.976|o+6200` (escala 25/23.976): main=certa -> deriva=recusou; z=17.5 janelas=10 motivo= | [200s d=-14843 ncc=0.28 2o=0.26 z=3.9 -] [1455s d=2193 ncc=0.54 2o=0.49 z=2.0 -] [2825s d=2097 ncc=0.21 2o=0.18 z=3.3 -] [1730s d=13099 ncc=0.16 2o=0.13 z=2.5 -] [3680s d=-10847 ncc=0.17 2o=0.14 z=2.3 -] [4400s d=8138 ncc=0.29 2o=0.27 z=2.4 -] [715s d=890 ncc=0.09 2o=0.08 z=2.3 -] [2480s d=7594 ncc=
- `sabotage_1936` `g25/23.976|o+1800` (escala 25/23.976): main=certa -> deriva=recusou; z=12.2 janelas=10 motivo=pico ambíguo; janelas discordam; | [990s d=14225 ncc=0.14 2o=0.11 z=2.7 -] [1420s d=12747 ncc=0.15 2o=0.13 z=1.4 -] [2830s d=-6209 ncc=0.18 2o=0.14 z=2.7 -] [440s d=-3474 ncc=0.23 2o=0.18 z=3.1 -] [1540s d=7064 ncc=0.20 2o=0.19 z=2.0 -] [2995s d=-8918 ncc=0.24 2o=0.24 z=1.8 -] [4255s d=9219 ncc=0.34 2o=0.33 z=1.5 -] [2250s d=14273 nc
- `moonrise_1948_mp_4` `g25/23.976|o-1300` (escala 25/23.976): main=certa -> deriva=recusou; z=11.9 janelas=6 motivo=janelas discordam; | [1320s d=-56601 ncc=0.59 2o=0.41 z=5.2 ok] [1980s d=-84915 ncc=0.69 2o=0.38 z=8.1 ok] [3155s d=-135367 ncc=0.26 2o=0.22 z=2.7 -] [870s d=-37328 ncc=0.31 2o=0.16 z=3.1 -] [4350s d=-186837 ncc=0.76 2o=0.72 z=3.8 -] [5110s d=-218492 ncc=0.75 2o=0.66 z=3.5 ok] deriva{deriva provada: escala 0.959040, res
- `moonrise_1948_mp_4` `g23.976/25|o-900` (escala 23.976/25): main=certa -> deriva=recusou; z=18.1 janelas=10 motivo=janelas discordam; | [1195s d=12486 ncc=0.25 2o=0.24 z=2.1 -] [1805s d=-8544 ncc=0.22 2o=0.18 z=2.6 -] [2895s d=-8375 ncc=0.21 2o=0.21 z=3.8 -] [780s d=-4404 ncc=0.21 2o=0.18 z=2.7 -] [1895s d=-7985 ncc=0.14 2o=0.13 z=2.1 -] [3985s d=13794 ncc=0.25 2o=0.22 z=1.6 -] [4715s d=-15000 ncc=0.27 2o=0.21 z=3.1 -] [3180s d=-679

## 4. Distribuicao do z (confianca do alinhador)

| motor | classe | n | z p50 | p90 | p99 | min |
|---|---|---|---|---|---|---|
| main | corrigida certa | 625 | 15.7 | 20.0 | 24.5 | 9.0 |
| main | ACEITE ERRADO | 18 | 15.1 | 16.3 | 16.5 | 9.2 |
| main | recusada (S) | 603 | 9.7 | 17.2 | 23.0 | 0.0 |
| main | ja certa intacta | 89 | 16.7 | 22.0 | 26.9 | 7.1 |
| drift | corrigida certa | 654 | 15.6 | 19.9 | 24.5 | 6.4 |
| drift | ACEITE ERRADO | 17 | 15.1 | 16.3 | 16.5 | 9.2 |
| drift | recusada (S) | 575 | 8.7 | 17.3 | 23.0 | 0.0 |
| drift | ja certa intacta | 89 | 16.7 | 22.0 | 26.9 | 7.1 |

## 5. Tempo de analise (CPU do runner do GitHub, 2 fios de VAD; NAO e o tempo no celular/TV)

| motor | janelas (media) | VAD p50 / p95 (s) | alinhador p50 / p95 (s) | total p50 / p95 (s) |
|---|---|---|---|---|
| main | 6.6 | 3.4 / 5.5 | 0.7 / 1.7 | 4.1 / 7.0 |
| drift | 6.6 | 3.4 / 5.5 | 0.7 / 1.7 | 4.1 / 7.2 |

## 6. As 20 razoes de recusa mais comuns (variantes S recusadas: deviam ter sido corrigidas)

Cada recusa pode ter varias clausulas (o motor junta os motivos); conta-se cada clausula.


**main** (336 variantes S recusadas)

| # | clausula | vezes | exemplos |
|---|---|---|---|
| 1 | janelas discordam | 214 | born_to_the_saddle/mais3 (z=8.1); born_to_the_saddle/menos2 (z=7.2); born_to_the_saddle/mais7.5 (z=7.6) |
| 2 | possível deriva: janelas discordam entre si | 58 | eyes_in_the_night/g1.001|o+1800 (z=16.2); cloudsovereurope/g1.001|o+6200 (z=13.7); cloudsovereurope/g1.001|o-900 (z=16.9) |
| 3 | ajuste inseguro: janelas fora de uma reta de atraso constante (N ms) | 54 | eyes_in_the_night/g1.001|o+1800 (z=16.2); cloudsovereurope/g1.001|o-900 (z=16.9); jsc2023m000080_ariss_st_lucia_hamm_radio_event/g1.001|o+6200 (z=7.6) |
| 4 | escala fora das razões de fps | 53 | born_to_the_saddle/g25/23.976|o-4300 (z=7.3); born_to_the_saddle/g23.976/25|o-900 (z=8.0); born_to_the_saddle/g23.976/25|o-1300 (z=8.6) |
| 5 | possíveis cortes na legenda (só aviso) | 44 | eyes_in_the_night/g1.001|o+6200 (z=15.2); eyes_in_the_night/g25/23.976|o-2500 (z=12.8); valkaama/g25/23.976|o-900 (z=11.9) |
| 6 | pico ambíguo | 39 | born_to_the_saddle/mais3 (z=8.1); born_to_the_saddle/menos2 (z=7.2); born_to_the_saddle/mais7.5 (z=7.6) |
| 7 | ajuste contradiz as janelas (> N s) | 22 | born_to_the_saddle/g23.976/25|o-900 (z=8.0); born_to_the_saddle/g23.976/25|o-1300 (z=8.6); high_and_low/g1.001|o-2500 (z=10.6) |
| 8 | escala N/N sem prova de deriva | 14 | born_to_the_saddle/g1.001|o+2600 (z=8.3); born_to_the_saddle/g0.999|o+6200 (z=8.1); cloudsovereurope/g1.001|o+6200 (z=13.7) |
| 9 | z abaixo do limiar de aceitação (N) | 3 | jsc2023m000080_ariss_st_lucia_hamm_radio_event/menos2 (z=8.3); jsc2023m000080_ariss_st_lucia_hamm_radio_event/g23.976/25|o+4000 (z=7.5); jsc2023m000080_ariss_st_lucia_hamm_radio_event/g23.976/25|o-2500 (z=6.7) |
| 10 | z baixo | 2 | jsc2023m000080_ariss_st_lucia_hamm_radio_event/g25/23.976|o-1300 (z=5.5); aap44779882/g25/23.976|o-2500 (z=5.8) |

**drift** (308 variantes S recusadas)

| # | clausula | vezes | exemplos |
|---|---|---|---|
| 1 | janelas discordam | 190 | born_to_the_saddle/mais3 (z=8.1); born_to_the_saddle/menos2 (z=7.2); born_to_the_saddle/mais7.5 (z=7.6) |
| 2 | possível deriva: janelas discordam entre si | 60 | eyes_in_the_night/g1.001|o+1800 (z=16.2); cloudsovereurope/g1.001|o+6200 (z=13.7); cloudsovereurope/g1.001|o-900 (z=16.9) |
| 3 | ajuste inseguro: janelas fora de uma reta de atraso constante (N ms) | 54 | eyes_in_the_night/g1.001|o+1800 (z=16.2); cloudsovereurope/g1.001|o-900 (z=16.9); jsc2023m000080_ariss_st_lucia_hamm_radio_event/g1.001|o+6200 (z=7.6) |
| 4 | escala fora das razões de fps | 53 | born_to_the_saddle/g25/23.976|o-4300 (z=7.3); born_to_the_saddle/g23.976/25|o-900 (z=8.0); born_to_the_saddle/g23.976/25|o-1300 (z=8.6) |
| 5 | possíveis cortes na legenda (só aviso) | 42 | eyes_in_the_night/g1.001|o+6200 (z=15.2); valkaama/g25/23.976|o-900 (z=11.9); high_and_low/menos2 (z=10.8) |
| 6 | pico ambíguo | 39 | born_to_the_saddle/mais3 (z=8.1); born_to_the_saddle/menos2 (z=7.2); born_to_the_saddle/mais7.5 (z=7.6) |
| 7 | ajuste contradiz as janelas (> N s) | 22 | born_to_the_saddle/g23.976/25|o-900 (z=8.0); born_to_the_saddle/g23.976/25|o-1300 (z=8.6); high_and_low/g1.001|o-2500 (z=10.6) |
| 8 | escala N/N sem prova de deriva | 14 | born_to_the_saddle/g1.001|o+2600 (z=8.3); born_to_the_saddle/g0.999|o+6200 (z=8.1); cloudsovereurope/g1.001|o+6200 (z=13.7) |
| 9 | z baixo | 2 | jsc2023m000080_ariss_st_lucia_hamm_radio_event/g25/23.976|o-1300 (z=5.5); aap44779882/g25/23.976|o-2500 (z=5.8) |
| 10 | z abaixo do limiar de aceitação (N) | 1 | jsc2023m000080_ariss_st_lucia_hamm_radio_event/menos2 (z=8.3) |

**Motivo completo (todas as clausulas juntas) mais comum, so S recusadas**


main:

- 102 x janelas discordam;
- 44 x possíveis cortes na legenda (só aviso);
- 40 x (sem motivo)
- 34 x ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;
- 23 x escala fora das razões de fps; janelas discordam;
- 16 x escala fora das razões de fps;
- 12 x pico ambíguo; janelas discordam;
- 10 x ajuste contradiz as janelas (> N s); ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;

drift:

- 76 x janelas discordam;
- 42 x possíveis cortes na legenda (só aviso);
- 41 x (sem motivo)
- 34 x ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;
- 23 x escala fora das razões de fps; janelas discordam;
- 16 x escala fora das razões de fps;
- 12 x pico ambíguo; janelas discordam;
- 10 x ajuste contradiz as janelas (> N s); ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;

## 7. TODOS os aceites errados, aceites falsos e legendas certas mexidas


### main: 18 casos

| titulo | tipo | variante | classe | kind | escala | desl. aplicado (ms) | z | janelas conc. | erro max / p95 (ms) | motivo |
|---|---|---|---|---|---|---|---|---|---|---|
| 1951_the_prowler_el_merodeador_joseph_losey_vose (old, 1951) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7083 | 16.5 | 4/10 | 417 / 417 |  |
| love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ (old-tv, None) | S | `g25/23.976|o+2600` | ERRADO | FIX | 0.96000 | -2995 | 9.8 | 3/3 | 995 / 903 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g0.999|o+1800` | ERRADO | FIX | 1.00100 | -1391 | 15.1 | 6/6 | 411 / 411 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3598 | 15.9 | 6/6 | 406 / 406 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g1.001|o-4300` | ERRADO | FIX | 0.99900 | 4713 | 15.0 | 6/6 | 417 / 417 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g1.001|o-900` | ERRADO | FIX | 0.99900 | 1309 | 15.1 | 6/6 | 410 / 410 |  |
| murder_by_invitation_1941 (old, 1941) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7096 | 15.1 | 6/6 | 404 / 404 |  |
| murder_by_invitation_1941 (old, 1941) | S | `menos2` | ERRADO | FIX | 1.00000 | 2404 | 15.4 | 6/6 | 404 / 404 |  |
| pit_and_the_pendulum (old, 1961) | S | `mais3` | ERRADO | FIX | 1.00000 | -2505 | 11.4 | 3/3 | 495 / 495 |  |
| the_gorgon_1964 (old, 1964) | S | `g25/23.976|o+1800` | ERRADO | FIX | 0.95904 | -1322 | 9.2 | 3/3 | 404 / 404 |  |
| the_housemaid_1960 (old, 1960) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3582 | 16.0 | 3/6 | 422 / 422 |  |
| the_mummys_hand (old, 1940) | S | `g1.001|o+4000` | ERRADO | FIX | 0.99900 | -3588 | 15.0 | 6/7 | 408 / 408 |  |
| the_mummys_hand (old, 1940) | S | `g25/23.976|o-1300` | ERRADO | FIX | 0.95904 | 1653 | 14.8 | 6/7 | 406 / 406 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g1.001|o-2500` | ERRADO | FIX | 0.99900 | 2915 | 15.9 | 5/6 | 417 / 417 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g1.001|o-4300` | ERRADO | FIX | 0.99900 | 4723 | 16.3 | 5/6 | 427 / 427 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g25/23.976|o+6200` | ERRADO | FIX | 0.95904 | -5466 | 11.1 | 6/6 | 480 / 480 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g25/23.976|o-900` | ERRADO | FIX | 0.95904 | 1316 | 12.5 | 6/6 | 453 / 453 |  |
| valkaama (new, 2010) | S | `menos2` | ERRADO | FIX | 1.00000 | 2433 | 13.7 | 3/5 | 433 / 433 |  |

- **1951_the_prowler_el_merodeador_joseph_losey_vose / mais7.5 / ERRADO** diagnostico completo: `[580s d=-7216 ncc=0.54 2o=0.38 z=4.8 ok] [1515s d=-7313 ncc=0.58 2o=0.43 z=5.2 ok] [3280s d=-7479 ncc=0.58 2o=0.39 z=6.3 ok] [2670s d=-7507 ncc=0.49 2o=0.39 z=4.0 ok] [3890s d=-7024 ncc=0.66 2o=0.63 z=3.5 -] [5090s d=-7190 ncc=0.72 2o=0.60 z=2.8 ok] [4410s d=-7349 ncc=0.77 2o=0.65 z=3.2 ok] [1775s d=-5609 ncc=0.70 2o=0.70 z=1.0 -] [785s d=-7192 ncc=0.52 2o=0.44 z=2.6 -] [3080s d=-6840 ncc=0.68 2o=0.64 z=2.0 -] deriva{sem prova de deriva: resíduo da reta sem escala 173 ms; confiaveis=6 inclinacao=0.000003 provada=0}`

- **love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ / g25/23.976|o+2600 / ERRADO** diagnostico completo: `[335s d=-8922 ncc=0.22 2o=0.19 z=2.2 -] [765s d=6485 ncc=0.15 2o=0.14 z=1.3 -] [1230s d=5283 ncc=0.21 2o=0.19 z=2.3 -] [205s d=-11871 ncc=0.47 2o=0.41 z=3.1 -] [575s d=-9113 ncc=0.40 2o=0.39 z=1.8 -] [1020s d=15000 ncc=0.28 2o=0.26 z=2.7 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: ); confiaveis=0 inclinacao=0.000000 provada=0}`

- **murder_by_invitation_1941 / g0.999|o+1800 / ERRADO** diagnostico completo: `[510s d=-860 ncc=0.65 2o=0.56 z=2.5 ok] [1655s d=440 ncc=0.43 2o=0.20 z=5.7 ok] [2965s d=1676 ncc=0.42 2o=0.25 z=5.3 ok] [685s d=-684 ncc=0.66 2o=0.47 z=4.8 ok] [2135s d=864 ncc=0.64 2o=0.53 z=4.1 ok] [3280s d=1809 ncc=0.73 2o=0.61 z=3.2 ok] deriva{deriva provada: escala 1.001001, resíduo 130 ms (reta sem escala deixaria 1356 ms), deriva medida 2770 ms nas janelas e 3888 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=0.000985 provada=1}`

- **murder_by_invitation_1941 / g0.999|o+4000 / ERRADO** diagnostico completo: `[515s d=-3068 ncc=0.65 2o=0.57 z=2.4 ok] [1680s d=-1732 ncc=0.47 2o=0.22 z=4.8 ok] [2965s d=-524 ncc=0.42 2o=0.25 z=5.3 ok] [685s d=-2884 ncc=0.66 2o=0.47 z=4.8 ok] [2130s d=-1364 ncc=0.64 2o=0.52 z=3.8 ok] [3280s d=-391 ncc=0.73 2o=0.61 z=3.2 ok] deriva{deriva provada: escala 1.001001, resíduo 137 ms (reta sem escala deixaria 1362 ms), deriva medida 2765 ms nas janelas e 3888 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=0.000986 provada=1}`

- **murder_by_invitation_1941 / g1.001|o-4300 / ERRADO** diagnostico completo: `[500s d=4127 ncc=0.58 2o=0.52 z=2.3 -] [1675s d=3126 ncc=0.46 2o=0.23 z=5.1 ok] [2965s d=1750 ncc=0.41 2o=0.24 z=5.4 ok] [685s d=3955 ncc=0.66 2o=0.47 z=5.0 ok] [2135s d=2598 ncc=0.64 2o=0.52 z=4.2 ok] [3280s d=1256 ncc=0.73 2o=0.61 z=3.1 ok] deriva{deriva provada: escala 0.999000, resíduo 134 ms (reta sem escala deixaria 1426 ms), deriva medida 2598 ms nas janelas e 3892 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001036 provada=1}`

- **murder_by_invitation_1941 / g1.001|o-900 / ERRADO** diagnostico completo: `[505s d=747 ncc=0.61 2o=0.53 z=2.4 -] [1670s d=-274 ncc=0.42 2o=0.22 z=4.9 ok] [2970s d=-1685 ncc=0.46 2o=0.25 z=5.7 ok] [685s d=555 ncc=0.66 2o=0.47 z=5.0 ok] [2135s d=-802 ncc=0.64 2o=0.52 z=4.2 ok] [3285s d=-2158 ncc=0.71 2o=0.59 z=3.3 ok] deriva{deriva provada: escala 0.999000, resíduo 135 ms (reta sem escala deixaria 1443 ms), deriva medida 2603 ms nas janelas e 3892 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001039 provada=1}`

- **murder_by_invitation_1941 / mais7.5 / ERRADO** diagnostico completo: `[515s d=-7131 ncc=0.65 2o=0.56 z=2.5 ok] [1685s d=-6948 ncc=0.42 2o=0.19 z=4.4 ok] [2975s d=-7105 ncc=0.50 2o=0.27 z=6.7 ok] [685s d=-7112 ncc=0.66 2o=0.46 z=5.0 ok] [2140s d=-6992 ncc=0.65 2o=0.54 z=3.9 ok] [3290s d=-7227 ncc=0.72 2o=0.59 z=3.3 ok] deriva{sem prova de deriva: resíduo da reta sem escala 148 ms; confiaveis=6 inclinacao=-0.000021 provada=0}`

- **murder_by_invitation_1941 / menos2 / ERRADO** diagnostico completo: `[505s d=2395 ncc=0.62 2o=0.53 z=2.4 ok] [1655s d=2533 ncc=0.43 2o=0.20 z=5.8 ok] [2965s d=2463 ncc=0.42 2o=0.24 z=5.4 ok] [685s d=2388 ncc=0.66 2o=0.46 z=5.0 ok] [2415s d=2434 ncc=0.83 2o=0.78 z=2.7 -] [3280s d=2284 ncc=0.73 2o=0.61 z=3.2 ok] deriva{sem prova de deriva: resíduo da reta sem escala 137 ms; confiaveis=5 inclinacao=-0.000023 provada=0}`

- **pit_and_the_pendulum / mais3 / ERRADO** diagnostico completo: `[830s d=-2585 ncc=0.63 2o=0.47 z=3.7 ok] [1220s d=-2368 ncc=0.60 2o=0.45 z=5.0 ok] [2250s d=-2556 ncc=0.59 2o=0.55 z=3.2 -] [2510s d=-2548 ncc=0.59 2o=0.54 z=3.0 -] [3825s d=-2684 ncc=0.59 2o=0.54 z=3.4 -] [3965s d=-2759 ncc=0.65 2o=0.58 z=3.5 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: janelas discordam;); confiaveis=0 inclinacao=0.000000 provada=0}`

- **the_gorgon_1964 / g25/23.976|o+1800 / ERRADO** diagnostico completo: `[885s d=-13443 ncc=0.17 2o=0.14 z=2.5 -] [3495s d=6131 ncc=0.27 2o=0.23 z=2.2 -] [4525s d=-10357 ncc=0.24 2o=0.22 z=2.7 -] [720s d=14735 ncc=0.25 2o=0.17 z=2.6 -] [2080s d=14680 ncc=0.25 2o=0.24 z=2.2 -] [2560s d=10862 ncc=0.11 2o=0.11 z=2.5 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: ); confiaveis=0 inclinacao=0.000000 provada=0}`

- **the_housemaid_1960 / g0.999|o+4000 / ERRADO** diagnostico completo: `[640s d=-2963 ncc=0.59 2o=0.49 z=3.5 ok] [1695s d=-2024 ncc=0.53 2o=0.46 z=3.3 -] [2135s d=-1645 ncc=0.41 2o=0.37 z=2.2 -] [2390s d=-1450 ncc=0.58 2o=0.51 z=3.5 -] [3535s d=-68 ncc=0.66 2o=0.49 z=5.4 ok] [4665s d=740 ncc=0.62 2o=0.52 z=3.4 ok] [5695s d=2325 ncc=0.53 2o=0.39 z=3.9 ok] deriva{deriva provada: escala 1.001001, resíduo 163 ms (reta sem escala deixaria 2920 ms), deriva medida 5055 ms nas janelas e 6664 ms no filme, 3 janelas confiáveis (1 janela fora da reta descartada); confiaveis=4 inclinacao=0.001005 provada=1}`

- **the_mummys_hand / g1.001|o+4000 / ERRADO** diagnostico completo: `[785s d=-4521 ncc=0.59 2o=0.48 z=4.5 ok] [1315s d=-4813 ncc=0.32 2o=0.20 z=5.7 ok] [1825s d=-5564 ncc=0.55 2o=0.51 z=2.9 -] [640s d=-3748 ncc=0.71 2o=0.69 z=1.8 -] [1990s d=-5673 ncc=0.68 2o=0.60 z=3.5 -] [3045s d=-6669 ncc=0.78 2o=0.62 z=3.4 ok] [3645s d=-7360 ncc=0.83 2o=0.80 z=2.9 -] deriva{deriva provada: escala 0.999000, resíduo 140 ms (reta sem escala deixaria 1106 ms), deriva medida 2262 ms nas janelas e 4013 ms no filme, 3 janelas confiáveis; confiaveis=3 inclinacao=-0.000972 provada=1}`

- **the_mummys_hand / g25/23.976|o-1300 / ERRADO** diagnostico completo: `[800s d=14617 ncc=0.24 2o=0.23 z=2.8 -] [1380s d=-9932 ncc=0.23 2o=0.17 z=2.3 -] [1970s d=-10388 ncc=0.07 2o=0.05 z=3.0 -] [640s d=-13676 ncc=0.47 2o=0.46 z=2.0 -] [2080s d=-14786 ncc=0.23 2o=0.23 z=2.7 -] [2630s d=-14769 ncc=0.18 2o=0.14 z=3.0 -] [3355s d=14688 ncc=-0.02 2o=-0.05 z=2.8 -] deriva{menos de 2 janelas confiáveis (0 de 7); confiaveis=0 inclinacao=0.000000 provada=0}`

- **tower_of_london_1962_202510 / g1.001|o-2500 / ERRADO** diagnostico completo: `[200s d=2690 ncc=0.71 2o=0.47 z=6.3 ok] [1210s d=1651 ncc=0.58 2o=0.38 z=4.8 ok] [3210s d=-291 ncc=0.51 2o=0.35 z=4.2 ok] [1815s d=1070 ncc=0.69 2o=0.54 z=4.7 ok] [2395s d=554 ncc=0.41 2o=0.33 z=2.7 ok] [4250s d=-1527 ncc=0.50 2o=0.41 z=5.9 ok] deriva{deriva provada: escala 0.999000, resíduo 145 ms (reta sem escala deixaria 2379 ms), deriva medida 4054 ms nas janelas e 4783 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=-0.001024 provada=1}`

- **tower_of_london_1962_202510 / g1.001|o-4300 / ERRADO** diagnostico completo: `[195s d=4473 ncc=0.70 2o=0.46 z=6.4 ok] [1205s d=3497 ncc=0.57 2o=0.38 z=4.5 ok] [3210s d=1509 ncc=0.51 2o=0.35 z=4.2 ok] [1825s d=2898 ncc=0.64 2o=0.49 z=5.3 ok] [2395s d=2354 ncc=0.41 2o=0.33 z=2.7 ok] [4250s d=273 ncc=0.50 2o=0.41 z=5.9 ok] deriva{deriva provada: escala 0.999000, resíduo 155 ms (reta sem escala deixaria 2382 ms), deriva medida 4059 ms nas janelas e 4783 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=-0.001024 provada=1}`

- **tower_of_london_1962_202510 / g25/23.976|o+6200 / ERRADO** diagnostico completo: `[1250s d=9534 ncc=0.14 2o=0.13 z=1.8 -] [1935s d=-11437 ncc=0.14 2o=0.12 z=2.1 -] [3360s d=-5666 ncc=0.13 2o=0.10 z=2.2 -] [295s d=4657 ncc=0.23 2o=0.21 z=2.4 -] [2390s d=15000 ncc=0.12 2o=0.09 z=2.4 -] [4445s d=15000 ncc=0.18 2o=0.14 z=2.4 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiaveis=0 inclinacao=0.000000 provada=0}`

- **tower_of_london_1962_202510 / g25/23.976|o-900 / ERRADO** diagnostico completo: `[1260s d=-887 ncc=0.13 2o=0.12 z=1.6 -] [1935s d=-4337 ncc=0.14 2o=0.12 z=2.1 -] [3355s d=-14450 ncc=0.12 2o=0.08 z=2.0 -] [275s d=-12528 ncc=0.34 2o=0.30 z=2.6 -] [2390s d=-14284 ncc=0.25 2o=0.22 z=2.8 -] [4435s d=-8783 ncc=0.17 2o=0.17 z=1.7 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiaveis=0 inclinacao=0.000000 provada=0}`

- **valkaama / menos2 / ERRADO** diagnostico completo: `[1965s d=2415 ncc=0.51 2o=0.39 z=4.4 ok] [2870s d=2660 ncc=0.75 2o=0.68 z=3.2 -] [4980s d=2489 ncc=0.50 2o=0.41 z=3.3 ok] [735s d=10520 ncc=0.07 2o=0.07 z=4.9 -] [1015s d=2343 ncc=0.61 2o=0.51 z=3.9 ok] [3820s d=2243 ncc=0.70 2o=0.59 z=5.6 ok] deriva{sem prova de deriva: resíduo da reta sem escala 129 ms; confiaveis=4 inclinacao=0.000010 provada=0}`

### drift: 17 casos

| titulo | tipo | variante | classe | kind | escala | desl. aplicado (ms) | z | janelas conc. | erro max / p95 (ms) | motivo |
|---|---|---|---|---|---|---|---|---|---|---|
| 1951_the_prowler_el_merodeador_joseph_losey_vose (old, 1951) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7083 | 16.5 | 4/10 | 417 / 417 |  |
| love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ (old-tv, None) | S | `g25/23.976|o+2600` | ERRADO | FIX | 0.96000 | -2995 | 9.8 | 3/3 | 995 / 903 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g0.999|o+1800` | ERRADO | FIX | 1.00100 | -1391 | 15.1 | 6/6 | 411 / 411 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3598 | 15.9 | 6/6 | 406 / 406 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g1.001|o-4300` | ERRADO | FIX | 0.99900 | 4713 | 15.0 | 6/6 | 417 / 417 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g1.001|o-900` | ERRADO | FIX | 0.99900 | 1309 | 15.1 | 6/6 | 410 / 410 |  |
| murder_by_invitation_1941 (old, 1941) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7096 | 15.1 | 6/6 | 404 / 404 |  |
| murder_by_invitation_1941 (old, 1941) | S | `menos2` | ERRADO | FIX | 1.00000 | 2404 | 15.4 | 6/6 | 404 / 404 |  |
| pit_and_the_pendulum (old, 1961) | S | `mais3` | ERRADO | FIX | 1.00000 | -2505 | 11.4 | 3/3 | 495 / 495 |  |
| the_gorgon_1964 (old, 1964) | S | `g25/23.976|o+1800` | ERRADO | FIX | 0.95904 | -1322 | 9.2 | 3/3 | 404 / 404 |  |
| the_housemaid_1960 (old, 1960) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3582 | 16.0 | 3/6 | 422 / 422 |  |
| the_mummys_hand (old, 1940) | S | `g1.001|o+4000` | ERRADO | FIX | 0.99900 | -3588 | 15.0 | 6/7 | 408 / 408 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g1.001|o-2500` | ERRADO | FIX | 0.99900 | 2915 | 15.9 | 5/6 | 417 / 417 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g1.001|o-4300` | ERRADO | FIX | 0.99900 | 4723 | 16.3 | 5/6 | 427 / 427 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g25/23.976|o+6200` | ERRADO | FIX | 0.95904 | -5453 | 11.1 | 6/6 | 493 / 493 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g25/23.976|o-900` | ERRADO | FIX | 0.95904 | 1328 | 12.5 | 6/6 | 465 / 465 |  |
| valkaama (new, 2010) | S | `menos2` | ERRADO | FIX | 1.00000 | 2433 | 13.7 | 3/5 | 433 / 433 |  |

- **1951_the_prowler_el_merodeador_joseph_losey_vose / mais7.5 / ERRADO** diagnostico completo: `[580s d=-7216 ncc=0.54 2o=0.38 z=4.8 ok] [1515s d=-7313 ncc=0.58 2o=0.43 z=5.2 ok] [3280s d=-7479 ncc=0.58 2o=0.39 z=6.3 ok] [2670s d=-7507 ncc=0.49 2o=0.39 z=4.0 ok] [3890s d=-7024 ncc=0.66 2o=0.63 z=3.5 -] [5090s d=-7190 ncc=0.72 2o=0.60 z=2.8 ok] [4410s d=-7349 ncc=0.77 2o=0.65 z=3.2 ok] [1775s d=-5609 ncc=0.70 2o=0.70 z=1.0 -] [785s d=-7192 ncc=0.52 2o=0.44 z=2.6 -] [3080s d=-6840 ncc=0.68 2o=0.64 z=2.0 -] deriva{sem prova de deriva: resíduo da reta sem escala 173 ms; confiaveis=6 inclinacao=0.000003 provada=0}`

- **love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ / g25/23.976|o+2600 / ERRADO** diagnostico completo: `[335s d=-8922 ncc=0.22 2o=0.19 z=2.2 -] [765s d=6485 ncc=0.15 2o=0.14 z=1.3 -] [1230s d=5283 ncc=0.21 2o=0.19 z=2.3 -] [205s d=-11871 ncc=0.47 2o=0.41 z=3.1 -] [575s d=-9113 ncc=0.40 2o=0.39 z=1.8 -] [1020s d=15000 ncc=0.28 2o=0.26 z=2.7 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: ); confiaveis=0 inclinacao=0.000000 provada=0}`

- **murder_by_invitation_1941 / g0.999|o+1800 / ERRADO** diagnostico completo: `[510s d=-860 ncc=0.65 2o=0.56 z=2.5 ok] [1655s d=440 ncc=0.43 2o=0.20 z=5.7 ok] [2965s d=1676 ncc=0.42 2o=0.25 z=5.3 ok] [685s d=-684 ncc=0.66 2o=0.47 z=4.8 ok] [2135s d=864 ncc=0.64 2o=0.53 z=4.1 ok] [3280s d=1809 ncc=0.73 2o=0.61 z=3.2 ok] deriva{deriva provada: escala 1.001001, resíduo 130 ms (reta sem escala deixaria 1356 ms), deriva medida 2770 ms nas janelas e 3888 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=0.000985 provada=1}`

- **murder_by_invitation_1941 / g0.999|o+4000 / ERRADO** diagnostico completo: `[515s d=-3068 ncc=0.65 2o=0.57 z=2.4 ok] [1680s d=-1732 ncc=0.47 2o=0.22 z=4.8 ok] [2965s d=-524 ncc=0.42 2o=0.25 z=5.3 ok] [685s d=-2884 ncc=0.66 2o=0.47 z=4.8 ok] [2130s d=-1364 ncc=0.64 2o=0.52 z=3.8 ok] [3280s d=-391 ncc=0.73 2o=0.61 z=3.2 ok] deriva{deriva provada: escala 1.001001, resíduo 137 ms (reta sem escala deixaria 1362 ms), deriva medida 2765 ms nas janelas e 3888 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=0.000986 provada=1}`

- **murder_by_invitation_1941 / g1.001|o-4300 / ERRADO** diagnostico completo: `[500s d=4127 ncc=0.58 2o=0.52 z=2.3 -] [1675s d=3126 ncc=0.46 2o=0.23 z=5.1 ok] [2965s d=1750 ncc=0.41 2o=0.24 z=5.4 ok] [685s d=3955 ncc=0.66 2o=0.47 z=5.0 ok] [2135s d=2598 ncc=0.64 2o=0.52 z=4.2 ok] [3280s d=1256 ncc=0.73 2o=0.61 z=3.1 ok] deriva{deriva provada: escala 0.999000, resíduo 134 ms (reta sem escala deixaria 1426 ms), deriva medida 2598 ms nas janelas e 3892 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001036 provada=1}`

- **murder_by_invitation_1941 / g1.001|o-900 / ERRADO** diagnostico completo: `[505s d=747 ncc=0.61 2o=0.53 z=2.4 -] [1670s d=-274 ncc=0.42 2o=0.22 z=4.9 ok] [2970s d=-1685 ncc=0.46 2o=0.25 z=5.7 ok] [685s d=555 ncc=0.66 2o=0.47 z=5.0 ok] [2135s d=-802 ncc=0.64 2o=0.52 z=4.2 ok] [3285s d=-2158 ncc=0.71 2o=0.59 z=3.3 ok] deriva{deriva provada: escala 0.999000, resíduo 135 ms (reta sem escala deixaria 1443 ms), deriva medida 2603 ms nas janelas e 3892 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001039 provada=1}`

- **murder_by_invitation_1941 / mais7.5 / ERRADO** diagnostico completo: `[515s d=-7131 ncc=0.65 2o=0.56 z=2.5 ok] [1685s d=-6948 ncc=0.42 2o=0.19 z=4.4 ok] [2975s d=-7105 ncc=0.50 2o=0.27 z=6.7 ok] [685s d=-7112 ncc=0.66 2o=0.46 z=5.0 ok] [2140s d=-6992 ncc=0.65 2o=0.54 z=3.9 ok] [3290s d=-7227 ncc=0.72 2o=0.59 z=3.3 ok] deriva{sem prova de deriva: resíduo da reta sem escala 148 ms; confiaveis=6 inclinacao=-0.000021 provada=0}`

- **murder_by_invitation_1941 / menos2 / ERRADO** diagnostico completo: `[505s d=2395 ncc=0.62 2o=0.53 z=2.4 ok] [1655s d=2533 ncc=0.43 2o=0.20 z=5.8 ok] [2965s d=2463 ncc=0.42 2o=0.24 z=5.4 ok] [685s d=2388 ncc=0.66 2o=0.46 z=5.0 ok] [2415s d=2434 ncc=0.83 2o=0.78 z=2.7 -] [3280s d=2284 ncc=0.73 2o=0.61 z=3.2 ok] deriva{sem prova de deriva: resíduo da reta sem escala 137 ms; confiaveis=5 inclinacao=-0.000023 provada=0}`

- **pit_and_the_pendulum / mais3 / ERRADO** diagnostico completo: `[830s d=-2585 ncc=0.63 2o=0.47 z=3.7 ok] [1220s d=-2368 ncc=0.60 2o=0.45 z=5.0 ok] [2250s d=-2556 ncc=0.59 2o=0.55 z=3.2 -] [2510s d=-2548 ncc=0.59 2o=0.54 z=3.0 -] [3825s d=-2684 ncc=0.59 2o=0.54 z=3.4 -] [3965s d=-2759 ncc=0.65 2o=0.58 z=3.5 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: janelas discordam;); confiaveis=0 inclinacao=0.000000 provada=0}`

- **the_gorgon_1964 / g25/23.976|o+1800 / ERRADO** diagnostico completo: `[885s d=-13443 ncc=0.17 2o=0.14 z=2.5 -] [3495s d=6131 ncc=0.27 2o=0.23 z=2.2 -] [4525s d=-10357 ncc=0.24 2o=0.22 z=2.7 -] [720s d=14735 ncc=0.25 2o=0.17 z=2.6 -] [2080s d=14680 ncc=0.25 2o=0.24 z=2.2 -] [2560s d=10862 ncc=0.11 2o=0.11 z=2.5 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: ); confiaveis=0 inclinacao=0.000000 provada=0}`

- **the_housemaid_1960 / g0.999|o+4000 / ERRADO** diagnostico completo: `[640s d=-2963 ncc=0.59 2o=0.49 z=3.5 ok] [1695s d=-2024 ncc=0.53 2o=0.46 z=3.3 -] [2135s d=-1645 ncc=0.41 2o=0.37 z=2.2 -] [2390s d=-1450 ncc=0.58 2o=0.51 z=3.5 -] [3535s d=-68 ncc=0.66 2o=0.49 z=5.4 ok] [4665s d=740 ncc=0.62 2o=0.52 z=3.4 ok] [5695s d=2325 ncc=0.53 2o=0.39 z=3.9 ok] deriva{deriva provada: escala 1.001001, resíduo 163 ms (reta sem escala deixaria 2920 ms), deriva medida 5055 ms nas janelas e 6664 ms no filme, 3 janelas confiáveis (1 janela fora da reta descartada); confiaveis=4 inclinacao=0.001005 provada=1}`

- **the_mummys_hand / g1.001|o+4000 / ERRADO** diagnostico completo: `[785s d=-4521 ncc=0.59 2o=0.48 z=4.5 ok] [1315s d=-4813 ncc=0.32 2o=0.20 z=5.7 ok] [1825s d=-5564 ncc=0.55 2o=0.51 z=2.9 -] [640s d=-3748 ncc=0.71 2o=0.69 z=1.8 -] [1990s d=-5673 ncc=0.68 2o=0.60 z=3.5 -] [3045s d=-6669 ncc=0.78 2o=0.62 z=3.4 ok] [3645s d=-7360 ncc=0.83 2o=0.80 z=2.9 -] deriva{deriva provada: escala 0.999000, resíduo 140 ms (reta sem escala deixaria 1106 ms), deriva medida 2262 ms nas janelas e 4013 ms no filme, 3 janelas confiáveis; confiaveis=3 inclinacao=-0.000972 provada=1}`

- **tower_of_london_1962_202510 / g1.001|o-2500 / ERRADO** diagnostico completo: `[200s d=2690 ncc=0.71 2o=0.47 z=6.3 ok] [1210s d=1651 ncc=0.58 2o=0.38 z=4.8 ok] [3210s d=-291 ncc=0.51 2o=0.35 z=4.2 ok] [1815s d=1070 ncc=0.69 2o=0.54 z=4.7 ok] [2395s d=554 ncc=0.41 2o=0.33 z=2.7 ok] [4250s d=-1527 ncc=0.50 2o=0.41 z=5.9 ok] deriva{deriva provada: escala 0.999000, resíduo 145 ms (reta sem escala deixaria 2379 ms), deriva medida 4054 ms nas janelas e 4783 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=-0.001024 provada=1}`

- **tower_of_london_1962_202510 / g1.001|o-4300 / ERRADO** diagnostico completo: `[195s d=4473 ncc=0.70 2o=0.46 z=6.4 ok] [1205s d=3497 ncc=0.57 2o=0.38 z=4.5 ok] [3210s d=1509 ncc=0.51 2o=0.35 z=4.2 ok] [1825s d=2898 ncc=0.64 2o=0.49 z=5.3 ok] [2395s d=2354 ncc=0.41 2o=0.33 z=2.7 ok] [4250s d=273 ncc=0.50 2o=0.41 z=5.9 ok] deriva{deriva provada: escala 0.999000, resíduo 155 ms (reta sem escala deixaria 2382 ms), deriva medida 4059 ms nas janelas e 4783 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=-0.001024 provada=1}`

- **tower_of_london_1962_202510 / g25/23.976|o+6200 / ERRADO** diagnostico completo: `[1250s d=-61100 ncc=0.52 2o=0.32 z=4.7 ok] [1935s d=-90224 ncc=0.53 2o=0.43 z=3.8 ok] [3360s d=-151132 ncc=0.67 2o=0.56 z=4.3 ok] [295s d=-20154 ncc=0.57 2o=0.45 z=3.7 ok] [2390s d=-109622 ncc=0.42 2o=0.34 z=3.0 ok] [4445s d=-197516 ncc=0.74 2o=0.66 z=3.2 -] deriva{deriva provada: escala 0.959040, resíduo 106 ms (reta sem escala deixaria 67867 ms), deriva medida 130904 ms nas janelas e 204083 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.042715 provada=1}`

- **tower_of_london_1962_202510 / g25/23.976|o-900 / ERRADO** diagnostico completo: `[1260s d=-54450 ncc=0.50 2o=0.29 z=4.8 ok] [1935s d=-83125 ncc=0.53 2o=0.43 z=3.8 ok] [3355s d=-143876 ncc=0.68 2o=0.56 z=5.1 ok] [275s d=-12253 ncc=0.54 2o=0.45 z=3.9 ok] [2390s d=-102522 ncc=0.42 2o=0.34 z=3.0 ok] [4435s d=-190011 ncc=0.73 2o=0.65 z=4.1 -] deriva{deriva provada: escala 0.959040, resíduo 99 ms (reta sem escala deixaria 69661 ms), deriva medida 131545 ms nas janelas e 204083 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.042716 provada=1}`

- **valkaama / menos2 / ERRADO** diagnostico completo: `[1965s d=2415 ncc=0.51 2o=0.39 z=4.4 ok] [2870s d=2660 ncc=0.75 2o=0.68 z=3.2 -] [4980s d=2489 ncc=0.50 2o=0.41 z=3.3 ok] [735s d=10520 ncc=0.07 2o=0.07 z=4.9 -] [1015s d=2343 ncc=0.61 2o=0.51 z=3.9 ok] [3820s d=2243 ncc=0.70 2o=0.59 z=5.6 ok] deriva{sem prova de deriva: resíduo da reta sem escala 129 ms; confiaveis=4 inclinacao=0.000010 provada=0}`

## 7b. Onde o motor falha: % de variantes S corrigidas CERTAS por caracteristica do titulo

So variantes S (deslocadas/deformadas). `n` = variantes. Serve para achar a causa: se a taxa cai com poucas falas, com a abertura silenciosa, com filmes muito longos, etc.


**por tipo de titulo**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| antigo (TV) | 22 | 4.5% | 90.9% | 22 | 4.5% | 90.9% |
| antigo (filme) | 913 | 66.0% | 32.2% | 913 | 68.9% | 29.5% |
| novo | 44 | 47.7% | 50.0% | 44 | 54.5% | 43.2% |

**por decada**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 1920s | 11 | 100.0% | 0.0% | 11 | 100.0% | 0.0% |
| 1930s | 187 | 62.0% | 38.0% | 187 | 64.7% | 35.3% |
| 1940s | 330 | 67.3% | 30.3% | 330 | 70.6% | 27.3% |
| 1950s | 253 | 73.1% | 26.5% | 253 | 75.9% | 23.7% |
| 1960s | 110 | 45.5% | 48.2% | 110 | 47.3% | 46.4% |
| 1970s | 22 | 86.4% | 13.6% | 22 | 90.9% | 9.1% |
| 2010s | 22 | 68.2% | 27.3% | 22 | 68.2% | 27.3% |
| 2020s | 22 | 27.3% | 72.7% | 22 | 40.9% | 59.1% |
| sem ano | 22 | 4.5% | 90.9% | 22 | 4.5% | 90.9% |

**por densidade de falas da legenda**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 4-8 falas/min | 55 | 21.8% | 74.5% | 55 | 21.8% | 74.5% |
| 8-12 falas/min | 297 | 63.0% | 34.3% | 297 | 67.3% | 30.3% |
| >= 12 falas/min | 627 | 67.9% | 30.8% | 627 | 70.5% | 28.2% |

**por duracao**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 30-75 min | 407 | 67.6% | 30.5% | 407 | 70.8% | 27.5% |
| 75-120 min | 506 | 65.8% | 32.4% | 506 | 68.2% | 30.0% |
| < 30 min | 44 | 15.9% | 81.8% | 44 | 22.7% | 75.0% |
| > 120 min | 22 | 45.5% | 54.5% | 22 | 50.0% | 50.0% |

**idioma da legenda de referencia**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| PT (filtro do app) | 517 | 64.0% | 34.0% | 517 | 66.9% | 31.3% |
| outro idioma | 462 | 63.6% | 34.6% | 462 | 66.7% | 31.6% |

**abertura silenciosa**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| sem silencio longo | 979 | 63.8% | 34.3% | 979 | 66.8% | 31.5% |

**nivel da referencia**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| A (so desloca) | 979 | 63.8% | 34.3% | 979 | 66.8% | 31.5% |

**canais de audio da faixa 1**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 1 canais | 11 | 54.5% | 45.5% | 11 | 54.5% | 45.5% |
| 2 canais | 968 | 63.9% | 34.2% | 968 | 66.9% | 31.3% |

## 8. Bugs e comportamentos estranhos achados na execucao

- Excecoes / timeouts do alinhador: **0** variantes (de 2670).
- NaN/Infinity em campos do resultado: **0** variantes.
- Variantes que o app nem analisaria (guarda: poucas falas ou video < 8 min): 0
- Processos Java dos motores que terminaram com erro: **0**

### Parser de legendas do app (SubtitleText.decode + Cue.parseSrt) sobre a referencia reescrita em varios formatos

| formato | titulos | ok | tempos certos mas texto diferente | exemplo do problema |
|---|---|---|---|---|
| srt-utf8 | 146 | 146 | 0 |  |
| srt-utf8-bom | 146 | 146 | 0 |  |
| srt-crlf | 146 | 146 | 0 |  |
| srt-cr-only | 146 | 146 | 0 |  |
| srt-cp1252 | 146 | 142 | 2 | oy7743534123: tempos certos mas o texto (acentos) saiu diferente; robin_hood_66_the_york_treasure_robin_hood_66_the_york_treas: falas 397 em vez de 400 |
| srt-cp1252-crlf | 146 | 142 | 2 | oy7743534123: tempos certos mas o texto (acentos) saiu diferente; robin_hood_66_the_york_treasure_robin_hood_66_the_york_treas: falas 397 em vez de 400 |
| srt-utf16le-bom | 146 | 146 | 0 |  |
| srt-utf16be-bom | 146 | 146 | 0 |  |
| srt-utf16le-sem-bom | 146 | 146 | 0 |  |
| srt-ponto-no-ms | 146 | 146 | 0 |  |
| srt-ms-2-digitos | 146 | 146 | 0 |  |
| srt-ms-1-digito | 146 | 146 | 0 |  |
| srt-hora-1-digito | 146 | 146 | 0 |  |
| srt-sem-numeros | 146 | 146 | 0 |  |
| srt-tags-html-an8 | 146 | 146 | 0 |  |
| srt-mojibake | 146 | 146 | 0 |  |
| ass | 146 | 146 | 0 |  |
| ssa-marked | 146 | 146 | 0 |  |
| ass-utf8-bom | 146 | 146 | 0 |  |
| vtt | 146 | 146 | 0 |  |
| vtt-ids-mm:ss | 146 | 146 | 0 |  |
| srt-gzip | 146 | 146 | 0 |  |
| srt-zip | 146 | 146 | 0 |  |
| microdvd(nao suportado) | 146 | 5 | 0 | wrong_road: 182 falas com tempo/classificacao diferente (desvio max 21 ms); beginningoftheend_201509: 207 falas com tempo/classificacao diferente (desvio max 21 ms) |

### Legendas reais baixadas (941 arquivos de 432 titulos) e o parser do app

- decode do app devolveu null (descarta): 2; destes, o python leu >= 100 falas (legenda valida que o app descartaria): 0
- numero de falas do app diferente do python em >3%: 114
  - 15_un_poing_final `15_Un_poing_final.autogenerated.vtt`: falas app=1723 python=364
  - ourmissbrooks_homecookedmeal_ourmissbrooks_homecookedmeal `OurMissBrooks-HomeCookedMeal.autogenerated.vtt`: falas app=421 python=0
  - ourmissbrooks_homecookedmeal_ourmissbrooks_homecookedmeal `OurMissBrooks-HomeCookedMeal_512kb.autogenerated.vtt`: falas app=500 python=0
  - tot_many_happy_returns_tot_many_happy_returns `TOT_Many_Happy_Returns.autogenerated.vtt`: falas app=407 python=0
  - cisco_kid_carrier_pigeon_cisco_kid_carrier_pigeon `Cisco_Kid_-_Carrier_Pigeon.autogenerated.vtt`: falas app=284 python=0
  - cisco_kid_carrier_pigeon_cisco_kid_carrier_pigeon `Cisco_Kid_-_Carrier_Pigeon_512kb.autogenerated.vtt`: falas app=274 python=0
  - perry_como_1952_perrycomo1952 `PerryComo1952.autogenerated.vtt`: falas app=107 python=0
  - tot_youth_on_tap_tot_youth_on_tap `TOT_Youth_On_Tap.autogenerated.vtt`: falas app=517 python=0
  - beverly_hillbillies_ep31_the_clampetts_entertain_bh31_the_cl `BH31_The_Clampetts_Entertain.autogenerated.vtt`: falas app=247 python=0
  - beverly_hillbillies_ep31_the_clampetts_entertain_bh31_the_cl `BH31_The_Clampetts_Entertain_512kb.autogenerated.vtt`: falas app=503 python=0
- legendas com falas fora de ordem no arquivo: 12; com sobreposicao > 200 ms em mais de 5 falas: 9; com fim <= inicio em mais de 3 falas: 2; mojibake que sobrou: 1; VTT: 118
  - fora de ordem: 15_un_poing_final `15_Un_poing_final.autogenerated.vtt` (12 falas fora de ordem)
  - fora de ordem: meet_john_doe_ipod `3613424` (7 falas fora de ordem)
  - fora de ordem: maniac_202107 `7334013` (63 falas fora de ordem)
  - fora de ordem: beverly_hillbillies_ep04_the_clampetts_meet_mrs_drysdale_bh0 `BH04_The_Clampetts_Meet_Mrs_Drysdale_512kb.autogenerated.vtt` (1 falas fora de ordem)
  - fora de ordem: 14_au_fil_de_l_histoire `14_Au_fil_de_l_histoire.autogenerated.vtt` (9 falas fora de ordem)
  - fora de ordem: 1943_shadow_of_a_doubt_la_sombra_de_una_duda_alfred_hitchcoc `1943 - Shadow of a Doubt - La sombra de una duda - Alfred Hitchcock - VOSE.autogenerated.vtt` (3 falas fora de ordem)
  - fora de ordem: house_of_usher_1960 `5828007` (26 falas fora de ordem)
  - fora de ordem: dragnet_thebigtrunk1954_dragnet_big_trunk_01_07_54 `Dragnet_Big_Trunk_01_07_54.autogenerated.vtt` (2 falas fora de ordem)
- idioma das legendas decodificadas segundo o filtro do app (looksPortuguese): nao-PT (eng)=584, PT (parser do app)=269, nao-PT (spa)=53, nao-PT (und)=33

### Casos especiais de midia

- titulos com 2+ faixas de audio: 0; com 5.1 (6+ canais) na faixa 1: 0; filmes de mais de 3 h: 0; com abertura silenciosa >= 60 s: 0
