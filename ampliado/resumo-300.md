# Bancada dos 300 titulos: o motor de sincronia de legendas pelo audio

Medido com o laco real do app (guardas, plano de janelas, VAD Silero em Java puro, `AudioAligner` e a regra de decisao `fill`), sem alterar o motor.
Motores: **main** = `origin/main` (3 janelas densas, uma resolucao); **drift** = ramo `run-deriva` (3 densas + 6 espalhadas + ate 2 rodadas extras, prova de deriva).

## 1. Titulos usados

Titulos tentados: **375**. Com resultado valido (referencia nivel A/B): **131** (antigos filmes 119, antigos TV 6, novos 6).
Proporcao antigos/novos: 95% / 5%.


Destino de cada titulo tentado:

| destino | titulos |
|---|---|
| referencia ruim | 214 |
| ok | 131 |
| sem legenda utilizavel | 22 |
| download falhou | 5 |
| nenhuma legenda alinhou | 3 |

Qualidade da referencia (legenda alinhada ao audio pelo alass; 2o oraculo = ffsubsync sobre a referencia): nivel A = desvio p90 do alass <= 300 ms e ffsubsync <= 300 ms; B = p90 ate 1000 ms (ampliado); X = excluida.
Referencias por nivel entre os titulos que chegaram a ter uma: A=81, B=50, X=214.

| origem da legenda de referencia | titulos |
|---|---|
| opensubtitles - PT (parser do app) | 71 |
| opensubtitles - outro idioma (eng) | 35 |
| ia-item - outro idioma (eng) | 17 |
| opensubtitles - outro idioma (spa) | 4 |
| opensubtitles - outro idioma (und) | 4 |

| decada | titulos |
|---|---|
| 1920s | 1 |
| 1930s | 20 |
| 1940s | 39 |
| 1950s | 40 |
| 1960s | 21 |
| 1970s | 1 |
| 2010s | 3 |
| 2020s | 3 |

| fonte da midia / licenca declarada | titulos |
|---|---|
| sem licenca declarada (colecao de dominio publico do IA) /  | 60 |
| http://creativecommons.org/licenses/publicdomain/ /  | 37 |
| http://creativecommons.org/publicdomain/mark/1.0/ /  | 12 |
| https://creativecommons.org/licenses/by-nc-nd/4.0/ /  | 10 |
| https://creativecommons.org/publicdomain/mark/1.0/ /  | 9 |
| http://creativecommons.org/licenses/by-nc-sa/4.0/ /  | 1 |
| https://creativecommons.org/publicdomain/zero/1.0/ /  | 1 |
| https://creativecommons.org/licenses/by-sa/4.0/ /  | 1 |

## 2. Numeros principais (todas as variantes, por tipo e por grupo)

Definicoes: **aceita** = o app aplicaria (FIX) ou diria "ja esta certa" (OK); **certa** = aceita e erro p95 <= 400 ms e max <= 800 ms em todas as falas (60 s .. fim-60 s) contra a referencia; **aceite errado** = aceita mas fora disso; **aceite falso** = aceitou a legenda de OUTRO titulo; **certa mexida** = a legenda ja certa recebeu um ajuste (FIX).

| grupo | motor | variantes S (devem ser corrigidas) | aceitas | certas | ACEITES ERRADOS | recusadas | legenda ja certa: mexidas | outro titulo: ACEITES FALSOS |
|---|---|---|---|---|---|---|---|---|
| todos | main | 1441 | 49.5% | 48.0% | 22 | 50.5% | 0/131 | 0/393 |
| todos | drift | 1441 | 52.6% | 51.0% | 23 | 47.4% | 0/131 | 0/393 |
| antigos (filmes) | main | 1309 | 52.3% | 50.8% | 20 | 47.7% | 0/119 | 0/357 |
| antigos (filmes) | drift | 1309 | 55.4% | 53.8% | 21 | 44.6% | 0/119 | 0/357 |
| antigos (TV) | main | 66 | 16.7% | 15.2% | 1 | 83.3% | 0/6 | 0/18 |
| antigos (TV) | drift | 66 | 19.7% | 18.2% | 1 | 80.3% | 0/6 | 0/18 |
| novos | main | 66 | 25.8% | 24.2% | 1 | 74.2% | 0/6 | 0/18 |
| novos | drift | 66 | 30.3% | 28.8% | 1 | 69.7% | 0/6 | 0/18 |

### Por familia de variante


**todos**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 131 | 49.6% | certas 48.9% (64) | ERRADAS 1 | recusadas 66 | z mediana 15.7 |
| deslocamento +3 s | drift | 131 | 49.6% | certas 48.9% (64) | ERRADAS 1 | recusadas 66 | z mediana 15.7 |
| deslocamento -2 s | main | 131 | 50.4% | certas 48.9% (64) | ERRADAS 2 | recusadas 65 | z mediana 15.4 |
| deslocamento -2 s | drift | 131 | 50.4% | certas 48.9% (64) | ERRADAS 2 | recusadas 65 | z mediana 15.4 |
| deslocamento +7,5 s | main | 131 | 52.7% | certas 51.1% (67) | ERRADAS 2 | recusadas 62 | z mediana 15.6 |
| deslocamento +7,5 s | drift | 131 | 52.7% | certas 51.1% (67) | ERRADAS 2 | recusadas 62 | z mediana 15.6 |
| escala 1.001 (24/23.976) | main | 262 | 59.9% | certas 57.3% (150) | ERRADAS 7 | recusadas 105 | z mediana 15.9 |
| escala 1.001 (24/23.976) | drift | 262 | 59.9% | certas 57.3% (150) | ERRADAS 7 | recusadas 105 | z mediana 15.9 |
| escala 0.999 (23.976/24) | main | 262 | 60.7% | certas 58.8% (154) | ERRADAS 5 | recusadas 103 | z mediana 15.9 |
| escala 0.999 (23.976/24) | drift | 262 | 60.7% | certas 58.8% (154) | ERRADAS 5 | recusadas 103 | z mediana 15.9 |
| escala 25/23.976 | main | 262 | 37.0% | certas 35.1% (92) | ERRADAS 5 | recusadas 165 | z mediana 12.5 |
| escala 25/23.976 | drift | 262 | 47.7% | certas 45.8% (120) | ERRADAS 5 | recusadas 137 | z mediana 12.7 |
| escala 23.976/25 | main | 262 | 38.2% | certas 38.2% (100) | ERRADAS 0 | recusadas 162 | z mediana 12.9 |
| escala 23.976/25 | drift | 262 | 44.7% | certas 44.3% (116) | ERRADAS 1 | recusadas 145 | z mediana 13.0 |
| ja certa | main | 131 | 50.4% | intactas 131 | MEXIDAS 0 | - | z mediana 16.4 |
| ja certa | drift | 131 | 50.4% | intactas 131 | MEXIDAS 0 | - | z mediana 16.4 |
| outro titulo | main | 262 | 0.0% | recusou 262 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo | drift | 262 | 0.0% | recusou 262 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo (com escala) | main | 131 | 0.0% | recusou 131 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo (com escala) | drift | 131 | 0.0% | recusou 131 | ACEITES FALSOS 0 | - | z mediana 5.2 |

**antigos (filmes)**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 119 | 52.1% | certas 51.3% (61) | ERRADAS 1 | recusadas 57 | z mediana 16.0 |
| deslocamento +3 s | drift | 119 | 52.1% | certas 51.3% (61) | ERRADAS 1 | recusadas 57 | z mediana 16.0 |
| deslocamento -2 s | main | 119 | 52.9% | certas 52.1% (62) | ERRADAS 1 | recusadas 56 | z mediana 15.5 |
| deslocamento -2 s | drift | 119 | 52.9% | certas 52.1% (62) | ERRADAS 1 | recusadas 56 | z mediana 15.5 |
| deslocamento +7,5 s | main | 119 | 56.3% | certas 54.6% (65) | ERRADAS 2 | recusadas 52 | z mediana 16.0 |
| deslocamento +7,5 s | drift | 119 | 56.3% | certas 54.6% (65) | ERRADAS 2 | recusadas 52 | z mediana 16.0 |
| escala 1.001 (24/23.976) | main | 238 | 63.4% | certas 60.5% (144) | ERRADAS 7 | recusadas 87 | z mediana 16.1 |
| escala 1.001 (24/23.976) | drift | 238 | 63.4% | certas 60.5% (144) | ERRADAS 7 | recusadas 87 | z mediana 16.1 |
| escala 0.999 (23.976/24) | main | 238 | 64.3% | certas 62.2% (148) | ERRADAS 5 | recusadas 85 | z mediana 16.3 |
| escala 0.999 (23.976/24) | drift | 238 | 64.3% | certas 62.2% (148) | ERRADAS 5 | recusadas 85 | z mediana 16.3 |
| escala 25/23.976 | main | 238 | 38.7% | certas 37.0% (88) | ERRADAS 4 | recusadas 146 | z mediana 12.6 |
| escala 25/23.976 | drift | 238 | 50.0% | certas 48.3% (115) | ERRADAS 4 | recusadas 119 | z mediana 12.9 |
| escala 23.976/25 | main | 238 | 40.8% | certas 40.8% (97) | ERRADAS 0 | recusadas 141 | z mediana 13.0 |
| escala 23.976/25 | drift | 238 | 46.2% | certas 45.8% (109) | ERRADAS 1 | recusadas 128 | z mediana 13.1 |
| ja certa | main | 119 | 53.8% | intactas 119 | MEXIDAS 0 | - | z mediana 16.7 |
| ja certa | drift | 119 | 53.8% | intactas 119 | MEXIDAS 0 | - | z mediana 16.7 |
| outro titulo | main | 238 | 0.0% | recusou 238 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo | drift | 238 | 0.0% | recusou 238 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo (com escala) | main | 119 | 0.0% | recusou 119 | ACEITES FALSOS 0 | - | z mediana 5.3 |
| outro titulo (com escala) | drift | 119 | 0.0% | recusou 119 | ACEITES FALSOS 0 | - | z mediana 5.3 |

**antigos (TV)**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 6 | 16.7% | certas 16.7% (1) | ERRADAS 0 | recusadas 5 | z mediana 14.4 |
| deslocamento +3 s | drift | 6 | 16.7% | certas 16.7% (1) | ERRADAS 0 | recusadas 5 | z mediana 14.4 |
| deslocamento -2 s | main | 6 | 16.7% | certas 16.7% (1) | ERRADAS 0 | recusadas 5 | z mediana 14.6 |
| deslocamento -2 s | drift | 6 | 16.7% | certas 16.7% (1) | ERRADAS 0 | recusadas 5 | z mediana 14.6 |
| deslocamento +7,5 s | main | 6 | 16.7% | certas 16.7% (1) | ERRADAS 0 | recusadas 5 | z mediana 13.7 |
| deslocamento +7,5 s | drift | 6 | 16.7% | certas 16.7% (1) | ERRADAS 0 | recusadas 5 | z mediana 13.7 |
| escala 1.001 (24/23.976) | main | 12 | 16.7% | certas 16.7% (2) | ERRADAS 0 | recusadas 10 | z mediana 13.6 |
| escala 1.001 (24/23.976) | drift | 12 | 16.7% | certas 16.7% (2) | ERRADAS 0 | recusadas 10 | z mediana 13.6 |
| escala 0.999 (23.976/24) | main | 12 | 16.7% | certas 16.7% (2) | ERRADAS 0 | recusadas 10 | z mediana 14.2 |
| escala 0.999 (23.976/24) | drift | 12 | 16.7% | certas 16.7% (2) | ERRADAS 0 | recusadas 10 | z mediana 14.2 |
| escala 25/23.976 | main | 12 | 25.0% | certas 16.7% (2) | ERRADAS 1 | recusadas 9 | z mediana 12.5 |
| escala 25/23.976 | drift | 12 | 25.0% | certas 16.7% (2) | ERRADAS 1 | recusadas 9 | z mediana 12.5 |
| escala 23.976/25 | main | 12 | 8.3% | certas 8.3% (1) | ERRADAS 0 | recusadas 11 | z mediana 11.8 |
| escala 23.976/25 | drift | 12 | 25.0% | certas 25.0% (3) | ERRADAS 0 | recusadas 9 | z mediana 11.8 |
| ja certa | main | 6 | 16.7% | intactas 6 | MEXIDAS 0 | - | z mediana 15.2 |
| ja certa | drift | 6 | 16.7% | intactas 6 | MEXIDAS 0 | - | z mediana 15.2 |
| outro titulo | main | 12 | 0.0% | recusou 12 | ACEITES FALSOS 0 | - | z mediana 4.9 |
| outro titulo | drift | 12 | 0.0% | recusou 12 | ACEITES FALSOS 0 | - | z mediana 4.9 |
| outro titulo (com escala) | main | 6 | 0.0% | recusou 6 | ACEITES FALSOS 0 | - | z mediana 4.8 |
| outro titulo (com escala) | drift | 6 | 0.0% | recusou 6 | ACEITES FALSOS 0 | - | z mediana 4.8 |

**novos**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 6 | 33.3% | certas 33.3% (2) | ERRADAS 0 | recusadas 4 | z mediana 9.5 |
| deslocamento +3 s | drift | 6 | 33.3% | certas 33.3% (2) | ERRADAS 0 | recusadas 4 | z mediana 9.5 |
| deslocamento -2 s | main | 6 | 33.3% | certas 16.7% (1) | ERRADAS 1 | recusadas 4 | z mediana 8.3 |
| deslocamento -2 s | drift | 6 | 33.3% | certas 16.7% (1) | ERRADAS 1 | recusadas 4 | z mediana 8.3 |
| deslocamento +7,5 s | main | 6 | 16.7% | certas 16.7% (1) | ERRADAS 0 | recusadas 5 | z mediana 7.9 |
| deslocamento +7,5 s | drift | 6 | 16.7% | certas 16.7% (1) | ERRADAS 0 | recusadas 5 | z mediana 7.9 |
| escala 1.001 (24/23.976) | main | 12 | 33.3% | certas 33.3% (4) | ERRADAS 0 | recusadas 8 | z mediana 7.6 |
| escala 1.001 (24/23.976) | drift | 12 | 33.3% | certas 33.3% (4) | ERRADAS 0 | recusadas 8 | z mediana 7.6 |
| escala 0.999 (23.976/24) | main | 12 | 33.3% | certas 33.3% (4) | ERRADAS 0 | recusadas 8 | z mediana 7.8 |
| escala 0.999 (23.976/24) | drift | 12 | 33.3% | certas 33.3% (4) | ERRADAS 0 | recusadas 8 | z mediana 7.8 |
| escala 25/23.976 | main | 12 | 16.7% | certas 16.7% (2) | ERRADAS 0 | recusadas 10 | z mediana 6.4 |
| escala 25/23.976 | drift | 12 | 25.0% | certas 25.0% (3) | ERRADAS 0 | recusadas 9 | z mediana 6.4 |
| escala 23.976/25 | main | 12 | 16.7% | certas 16.7% (2) | ERRADAS 0 | recusadas 10 | z mediana 7.2 |
| escala 23.976/25 | drift | 12 | 33.3% | certas 33.3% (4) | ERRADAS 0 | recusadas 8 | z mediana 7.2 |
| ja certa | main | 6 | 16.7% | intactas 6 | MEXIDAS 0 | - | z mediana 8.4 |
| ja certa | drift | 6 | 16.7% | intactas 6 | MEXIDAS 0 | - | z mediana 8.4 |
| outro titulo | main | 12 | 0.0% | recusou 12 | ACEITES FALSOS 0 | - | z mediana 5.1 |
| outro titulo | drift | 12 | 0.0% | recusou 12 | ACEITES FALSOS 0 | - | z mediana 5.1 |
| outro titulo (com escala) | main | 6 | 0.0% | recusou 6 | ACEITES FALSOS 0 | - | z mediana 4.7 |
| outro titulo (com escala) | drift | 6 | 0.0% | recusou 6 | ACEITES FALSOS 0 | - | z mediana 4.7 |

## 3. Cabeca a cabeca: main x deriva (mesma variante, mesmo titulo)

| familia | main | deriva | n |
|---|---|---|---|
| escala 23.976/25 | recusou | certa | 17 |
| escala 23.976/25 | certa | recusou | 1 |
| escala 23.976/25 | recusou | ERRADA | 1 |
| escala 25/23.976 | recusou | certa | 31 |
| escala 25/23.976 | certa | recusou | 3 |

Iguais nos dois motores: 1912 de 1965 variantes.


**Regressoes da deriva (certa/intacta no main que passou a errada/mexida/recusada):** 5

- `moonrise_1948_mp_4` `g25/23.976|o-1300` (escala 25/23.976): main=certa -> deriva=recusou; z=11.9 janelas=6 motivo=janelas discordam; | [1320s d=-56601 ncc=0.59 2o=0.41 z=5.2 ok] [1980s d=-84915 ncc=0.69 2o=0.38 z=8.1 ok] [3155s d=-135367 ncc=0.26 2o=0.22 z=2.7 -] [870s d=-37328 ncc=0.31 2o=0.16 z=3.1 -] [4350s d=-186837 ncc=0.76 2o=0.72 z=3.8 -] [5110s d=-218492 ncc=0.75 2o=0.66 z=3.5 ok] deriva{deriva provada: escala 0.959040, res
- `moonrise_1948_mp_4` `g23.976/25|o-900` (escala 23.976/25): main=certa -> deriva=recusou; z=18.1 janelas=10 motivo=janelas discordam; | [1195s d=12486 ncc=0.25 2o=0.24 z=2.1 -] [1805s d=-8544 ncc=0.22 2o=0.18 z=2.6 -] [2895s d=-8375 ncc=0.21 2o=0.21 z=3.8 -] [780s d=-4404 ncc=0.21 2o=0.18 z=2.7 -] [1895s d=-7985 ncc=0.14 2o=0.13 z=2.1 -] [3985s d=13794 ncc=0.25 2o=0.22 z=1.6 -] [4715s d=-15000 ncc=0.27 2o=0.21 z=3.1 -] [3180s d=-679
- `sabotage_1936` `g25/23.976|o+1800` (escala 25/23.976): main=certa -> deriva=recusou; z=12.2 janelas=10 motivo=pico ambíguo; janelas discordam; | [990s d=14225 ncc=0.14 2o=0.11 z=2.7 -] [1420s d=12747 ncc=0.15 2o=0.13 z=1.4 -] [2830s d=-6209 ncc=0.18 2o=0.14 z=2.7 -] [440s d=-3474 ncc=0.23 2o=0.18 z=3.1 -] [1540s d=7064 ncc=0.20 2o=0.19 z=2.0 -] [2995s d=-8918 ncc=0.24 2o=0.24 z=1.8 -] [4255s d=9219 ncc=0.34 2o=0.33 z=1.5 -] [2250s d=14273 nc
- `pontodeviragem5250` `g25/23.976|o+6200` (escala 25/23.976): main=certa -> deriva=recusou; z=17.5 janelas=10 motivo= | [200s d=-14843 ncc=0.28 2o=0.26 z=3.9 -] [1455s d=2193 ncc=0.54 2o=0.49 z=2.0 -] [2825s d=2097 ncc=0.21 2o=0.18 z=3.3 -] [1730s d=13099 ncc=0.16 2o=0.13 z=2.5 -] [3680s d=-10847 ncc=0.17 2o=0.14 z=2.3 -] [4400s d=8138 ncc=0.29 2o=0.27 z=2.4 -] [715s d=890 ncc=0.09 2o=0.08 z=2.3 -] [2480s d=7594 ncc=
- `randyridesalone` `g23.976/25|o-2500` (escala 23.976/25): main=recusou -> deriva=ERRADA; z=13.5 janelas=6 motivo= | [790s d=36782 ncc=0.76 2o=0.52 z=5.5 ok] [1150s d=51626 ncc=0.20 2o=0.15 z=2.3 -] [1760s d=76470 ncc=0.86 2o=0.46 z=4.9 ok] [355s d=20340 ncc=0.90 2o=0.82 z=2.1 ok] [2050s d=88126 ncc=0.83 2o=0.56 z=4.9 ok] [2770s d=107825 ncc=-0.03 2o=-0.04 z=4.9 -] deriva{deriva provada: escala 1.041667, resíduo 4

## 4. Distribuicao do z (confianca do alinhador)

| motor | classe | n | z p50 | p90 | p99 | min |
|---|---|---|---|---|---|---|
| main | corrigida certa | 691 | 15.7 | 19.8 | 24.5 | 9.0 |
| main | ACEITE ERRADO | 22 | 13.7 | 16.0 | 16.5 | 9.0 |
| main | recusada (S) | 1121 | 9.9 | 18.0 | 23.6 | 0.0 |
| main | ja certa intacta | 131 | 16.4 | 21.5 | 25.7 | 4.9 |
| drift | corrigida certa | 735 | 15.5 | 19.7 | 23.6 | 6.4 |
| drift | ACEITE ERRADO | 23 | 13.5 | 16.0 | 16.5 | 9.2 |
| drift | recusada (S) | 1076 | 9.7 | 18.1 | 23.7 | 0.0 |
| drift | ja certa intacta | 131 | 16.4 | 21.5 | 25.7 | 4.9 |

## 5. Tempo de analise (CPU do runner do GitHub, 2 fios de VAD; NAO e o tempo no celular/TV)

| motor | janelas (media) | VAD p50 / p95 (s) | alinhador p50 / p95 (s) | total p50 / p95 (s) |
|---|---|---|---|---|
| main | 6.8 | 3.4 / 5.7 | 0.7 / 1.8 | 4.2 / 7.4 |
| drift | 6.9 | 3.4 / 5.9 | 0.7 / 1.9 | 4.1 / 7.7 |

## 6. As 20 razoes de recusa mais comuns (variantes S recusadas: deviam ter sido corrigidas)

Cada recusa pode ter varias clausulas (o motor junta os motivos); conta-se cada clausula.


**main** (728 variantes S recusadas)

| # | clausula | vezes | exemplos |
|---|---|---|---|
| 1 | janelas discordam | 521 | phantomplanet/mais3 (z=19.9); phantomplanet/menos2 (z=19.6); phantomplanet/mais7.5 (z=19.3) |
| 2 | pico ambíguo | 180 | the_son_of_monte_cristo/g23.976/25|o+6200 (z=9.5); the_son_of_monte_cristo/g23.976/25|o-2500 (z=10.0); iss058m260661619_spacex_dm1_network_interviews_march_7_2019/mais3 (z=5.5) |
| 3 | possível deriva: janelas discordam entre si | 151 | phantomplanet/mais3 (z=19.9); phantomplanet/menos2 (z=19.6); phantomplanet/mais7.5 (z=19.3) |
| 4 | escala fora das razões de fps | 151 | pit_and_the_pendulum/g23.976/25|o+4000 (z=11.1); the_son_of_monte_cristo/g25/23.976|o+2600 (z=9.3); the_son_of_monte_cristo/g25/23.976|o+1800 (z=9.7) |
| 5 | ajuste inseguro: janelas fora de uma reta de atraso constante (N ms) | 135 | phantomplanet/mais3 (z=19.9); phantomplanet/menos2 (z=19.6); phantomplanet/mais7.5 (z=19.3) |
| 6 | ajuste contradiz as janelas (> N s) | 108 | pit_and_the_pendulum/g1.001|o+1800 (z=14.7); pit_and_the_pendulum/g0.999|o-1300 (z=18.4); the_son_of_monte_cristo/g23.976/25|o+6200 (z=9.5) |
| 7 | possíveis cortes na legenda (só aviso) | 106 | theguilty/g0.999|o-2500 (z=20.6); johnnyone_eye/g0.999|o+1800 (z=21.9); bride_for_henry_ipod/g1.001|o-1300 (z=18.0) |
| 8 | z baixo | 30 | iss058m260661619_spacex_dm1_network_interviews_march_7_2019/mais3 (z=5.5); iss058m260661619_spacex_dm1_network_interviews_march_7_2019/menos2 (z=5.0); iss058m260661619_spacex_dm1_network_interviews_march_7_2019/g1.001|o+2600 (z=4.8) |
| 9 | escala N/N sem prova de deriva | 29 | iss058m260661619_spacex_dm1_network_interviews_march_7_2019/g23.976/25|o-4300 (z=6.3); born_to_the_saddle/g1.001|o+2600 (z=8.3); born_to_the_saddle/g0.999|o+6200 (z=8.1) |
| 10 | z abaixo do limiar de aceitação (N) | 4 | jsc2023m000080_ariss_st_lucia_hamm_radio_event/menos2 (z=8.3); jsc2023m000080_ariss_st_lucia_hamm_radio_event/g23.976/25|o+4000 (z=7.5); jsc2023m000080_ariss_st_lucia_hamm_radio_event/g23.976/25|o-2500 (z=6.7) |
| 11 | correlação fraca | 3 | jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/menos2 (z=5.0); jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/g23.976/25|o-4300 (z=6.1); iss070m260361713_expedition70_space_station_crew_answers_cal/g0.999|o+4000 (z=5.1) |

**drift** (683 variantes S recusadas)

| # | clausula | vezes | exemplos |
|---|---|---|---|
| 1 | janelas discordam | 485 | phantomplanet/mais3 (z=19.9); phantomplanet/menos2 (z=19.6); phantomplanet/mais7.5 (z=19.3) |
| 2 | pico ambíguo | 184 | the_son_of_monte_cristo/g23.976/25|o+6200 (z=11.0); the_son_of_monte_cristo/g23.976/25|o-2500 (z=10.0); iss058m260661619_spacex_dm1_network_interviews_march_7_2019/mais3 (z=5.5) |
| 3 | possível deriva: janelas discordam entre si | 163 | phantomplanet/mais3 (z=19.9); phantomplanet/menos2 (z=19.6); phantomplanet/mais7.5 (z=19.3) |
| 4 | escala fora das razões de fps | 158 | pit_and_the_pendulum/g23.976/25|o+4000 (z=11.1); the_son_of_monte_cristo/g25/23.976|o+2600 (z=9.3); the_son_of_monte_cristo/g25/23.976|o+1800 (z=9.7) |
| 5 | ajuste inseguro: janelas fora de uma reta de atraso constante (N ms) | 135 | phantomplanet/mais3 (z=19.9); phantomplanet/menos2 (z=19.6); phantomplanet/mais7.5 (z=19.3) |
| 6 | ajuste contradiz as janelas (> N s) | 108 | pit_and_the_pendulum/g1.001|o+1800 (z=14.7); pit_and_the_pendulum/g0.999|o-1300 (z=18.4); iss058m260661619_spacex_dm1_network_interviews_march_7_2019/menos2 (z=5.0) |
| 7 | possíveis cortes na legenda (só aviso) | 99 | theguilty/g0.999|o-2500 (z=20.6); theguilty/g25/23.976|o-900 (z=16.4); johnnyone_eye/g0.999|o+1800 (z=21.9) |
| 8 | z baixo | 30 | iss058m260661619_spacex_dm1_network_interviews_march_7_2019/mais3 (z=5.5); iss058m260661619_spacex_dm1_network_interviews_march_7_2019/menos2 (z=5.0); iss058m260661619_spacex_dm1_network_interviews_march_7_2019/g1.001|o+2600 (z=4.8) |
| 9 | escala N/N sem prova de deriva | 29 | iss058m260661619_spacex_dm1_network_interviews_march_7_2019/g23.976/25|o-4300 (z=6.3); born_to_the_saddle/g1.001|o+2600 (z=8.3); born_to_the_saddle/g0.999|o+6200 (z=8.1) |
| 10 | correlação fraca | 3 | jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/menos2 (z=5.0); jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/g23.976/25|o-4300 (z=6.1); iss070m260361713_expedition70_space_station_crew_answers_cal/g0.999|o+4000 (z=5.1) |
| 11 | z abaixo do limiar de aceitação (N) | 1 | jsc2023m000080_ariss_st_lucia_hamm_radio_event/menos2 (z=8.3) |

**Motivo completo (todas as clausulas juntas) mais comum, so S recusadas**


main:

- 169 x janelas discordam;
- 106 x possíveis cortes na legenda (só aviso);
- 86 x ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;
- 52 x (sem motivo)
- 45 x pico ambíguo; janelas discordam;
- 37 x escala fora das razões de fps; janelas discordam;
- 36 x pico ambíguo; escala fora das razões de fps; janelas discordam;
- 27 x ajuste contradiz as janelas (> N s); ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;

drift:

- 126 x janelas discordam;
- 99 x possíveis cortes na legenda (só aviso);
- 86 x ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;
- 53 x (sem motivo)
- 39 x escala fora das razões de fps; janelas discordam;
- 39 x pico ambíguo; escala fora das razões de fps; janelas discordam;
- 38 x pico ambíguo; janelas discordam;
- 27 x ajuste contradiz as janelas (> N s); ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;

## 7. TODOS os aceites errados, aceites falsos e legendas certas mexidas


### main: 22 casos

| titulo | tipo | variante | classe | kind | escala | desl. aplicado (ms) | z | janelas conc. | erro max / p95 (ms) | motivo |
|---|---|---|---|---|---|---|---|---|---|---|
| 1951_the_prowler_el_merodeador_joseph_losey_vose (old, 1951) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7083 | 16.5 | 4/10 | 417 / 417 |  |
| houseonbaremountain (old, 1962) | S | `g0.999|o+1800` | ERRADO | FIX | 1.00100 | -1283 | 13.4 | 3/5 | 519 / 519 |  |
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
| the_son_of_monte_cristo (old, 1940) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3437 | 12.8 | 4/10 | 567 / 567 |  |
| the_son_of_monte_cristo (old, 1940) | S | `g1.001|o-1300` | ERRADO | FIX | 0.99900 | 1925 | 13.1 | 3/9 | 627 / 627 |  |
| the_son_of_monte_cristo (old, 1940) | S | `g1.001|o-900` | ERRADO | FIX | 0.99900 | 1493 | 12.7 | 4/10 | 594 / 594 |  |
| therunawaybus1954 (old, 1954) | S | `g1.001|o-4300` | ERRADO | FIX | 0.99900 | 4866 | 11.5 | 2/5 | 570 / 570 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g1.001|o-2500` | ERRADO | FIX | 0.99900 | 2915 | 15.9 | 5/6 | 417 / 417 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g1.001|o-4300` | ERRADO | FIX | 0.99900 | 4723 | 16.3 | 5/6 | 427 / 427 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g25/23.976|o+6200` | ERRADO | FIX | 0.95904 | -5466 | 11.1 | 6/6 | 480 / 480 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g25/23.976|o-900` | ERRADO | FIX | 0.95904 | 1316 | 12.5 | 6/6 | 453 / 453 |  |
| unfaithfully_yours (old, 1948) | S | `g25/23.976|o-2500` | ERRADO | FIX | 0.95904 | 2839 | 9.0 | 5/6 | 441 / 441 |  |
| valkaama (new, 2010) | S | `menos2` | ERRADO | FIX | 1.00000 | 2433 | 13.7 | 3/5 | 433 / 433 |  |

- **1951_the_prowler_el_merodeador_joseph_losey_vose / mais7.5 / ERRADO** diagnostico completo: `[580s d=-7216 ncc=0.54 2o=0.38 z=4.8 ok] [1515s d=-7313 ncc=0.58 2o=0.43 z=5.2 ok] [3280s d=-7479 ncc=0.58 2o=0.39 z=6.3 ok] [2670s d=-7507 ncc=0.49 2o=0.39 z=4.0 ok] [3890s d=-7024 ncc=0.66 2o=0.63 z=3.5 -] [5090s d=-7190 ncc=0.72 2o=0.60 z=2.8 ok] [4410s d=-7349 ncc=0.77 2o=0.65 z=3.2 ok] [1775s d=-5609 ncc=0.70 2o=0.70 z=1.0 -] [785s d=-7192 ncc=0.52 2o=0.44 z=2.6 -] [3080s d=-6840 ncc=0.68 2o=0.64 z=2.0 -] deriva{sem prova de deriva: resíduo da reta sem escala 173 ms; confiaveis=6 inclinacao=0.000003 provada=0}`

- **houseonbaremountain / g0.999|o+1800 / ERRADO** diagnostico completo: `[175s d=-944 ncc=0.66 2o=0.55 z=4.5 ok] [535s d=-1492 ncc=0.48 2o=0.44 z=2.8 -] [1145s d=-251 ncc=0.41 2o=0.25 z=3.4 ok] [650s d=-1299 ncc=0.57 2o=0.23 z=6.8 ok] [1255s d=-126 ncc=0.73 2o=0.63 z=2.7 ok] [2270s d=1173 ncc=0.67 2o=0.56 z=2.3 ok] [2915s d=-10251 ncc=0.15 2o=0.10 z=2.5 -] [3200s d=-10251 ncc=0.00 2o=0.00 z=0.0 -] deriva{deriva provada: escala 1.001001, resíduo 160 ms (reta sem escala deixaria 1424 ms), deriva medida 2095 ms nas janelas e 3719 ms no filme, 4 janelas confiáveis (1 janela fora da reta descartada); confiaveis=5 inclinacao=0.001128 provada=1}`

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

- **the_son_of_monte_cristo / g0.999|o+4000 / ERRADO** diagnostico completo: `[1680s d=-1600 ncc=0.42 2o=0.27 z=5.0 ok] [2045s d=-1920 ncc=0.52 2o=0.49 z=2.7 -] [3315s d=2319 ncc=0.34 2o=0.31 z=2.1 -] [240s d=-3288 ncc=0.65 2o=0.56 z=3.8 ok] [2135s d=-1960 ncc=0.54 2o=0.48 z=2.2 -] [4180s d=846 ncc=0.35 2o=0.31 z=2.5 -] [5095s d=296 ncc=0.56 2o=0.52 z=2.8 -] [1100s d=-3141 ncc=0.24 2o=0.18 z=3.4 -] [5550s d=-705 ncc=0.54 2o=0.52 z=2.3 -] [4645s d=1355 ncc=0.46 2o=0.37 z=3.0 ok] deriva{deriva provada: escala 1.001001, resíduo 139 ms (reta sem escala deixaria 2787 ms), deriva medida 4405 ms nas janelas e 6088 ms no filme, 3 janelas confiáveis; confiaveis=3 inclinacao=0.001050 provada=1}`

- **the_son_of_monte_cristo / g1.001|o-1300 / ERRADO** diagnostico completo: `[1645s d=415 ncc=0.42 2o=0.30 z=3.7 ok] [2135s d=-1034 ncc=0.54 2o=0.48 z=2.2 -] [3315s d=984 ncc=0.35 2o=0.30 z=2.1 -] [240s d=1506 ncc=0.66 2o=0.57 z=3.8 ok] [4185s d=-2303 ncc=0.38 2o=0.31 z=2.6 -] [5100s d=-4776 ncc=0.54 2o=0.51 z=2.7 -] [1025s d=52 ncc=0.57 2o=0.45 z=3.4 ok] [4645s d=-2735 ncc=0.46 2o=0.37 z=3.0 ok] [5555s d=-6631 ncc=0.60 2o=0.55 z=2.8 -] [2930s d=-999 ncc=0.14 2o=0.12 z=2.4 -] deriva{deriva provada: escala 0.999000, resíduo 180 ms (reta sem escala deixaria 2710 ms), deriva medida 4409 ms nas janelas e 6094 ms no filme, 3 janelas confiáveis (1 janela fora da reta descartada); confiaveis=4 inclinacao=-0.000916 provada=1}`

- **the_son_of_monte_cristo / g1.001|o-900 / ERRADO** diagnostico completo: `[1685s d=-140 ncc=0.40 2o=0.25 z=5.3 ok] [2045s d=-1274 ncc=0.53 2o=0.49 z=2.8 -] [3315s d=584 ncc=0.35 2o=0.30 z=2.1 -] [240s d=1106 ncc=0.66 2o=0.57 z=3.8 ok] [2135s d=-1434 ncc=0.54 2o=0.48 z=2.2 -] [4185s d=-2703 ncc=0.38 2o=0.31 z=2.6 -] [5100s d=-5176 ncc=0.54 2o=0.51 z=2.7 -] [1095s d=-474 ncc=0.19 2o=0.14 z=2.6 -] [4645s d=-3135 ncc=0.46 2o=0.37 z=3.0 ok] [5555s d=-7031 ncc=0.60 2o=0.55 z=2.8 -] deriva{deriva provada: escala 0.999000, resíduo 104 ms (reta sem escala deixaria 2635 ms), deriva medida 4409 ms nas janelas e 6094 ms no filme, 3 janelas confiáveis; confiaveis=3 inclinacao=-0.000965 provada=1}`

- **therunawaybus1954 / g1.001|o-4300 / ERRADO** diagnostico completo: `[295s d=2004 ncc=0.40 2o=0.30 z=4.9 ok] [1680s d=3290 ncc=0.68 2o=0.57 z=3.8 ok] [3180s d=1332 ncc=0.52 2o=0.47 z=2.7 -] [1120s d=6602 ncc=0.78 2o=0.74 z=1.7 -] [2625s d=2076 ncc=0.67 2o=0.58 z=2.6 ok] [4010s d=789 ncc=0.70 2o=0.55 z=4.1 ok] deriva{deriva provada: escala 0.999000, resíduo 145 ms (reta sem escala deixaria 1258 ms), deriva medida 2332 ms nas janelas e 4397 ms no filme, 3 janelas confiáveis (1 janela fora da reta descartada); confiaveis=4 inclinacao=-0.000499 provada=1}`

- **tower_of_london_1962_202510 / g1.001|o-2500 / ERRADO** diagnostico completo: `[200s d=2690 ncc=0.71 2o=0.47 z=6.3 ok] [1210s d=1651 ncc=0.58 2o=0.38 z=4.8 ok] [3210s d=-291 ncc=0.51 2o=0.35 z=4.2 ok] [1815s d=1070 ncc=0.69 2o=0.54 z=4.7 ok] [2395s d=554 ncc=0.41 2o=0.33 z=2.7 ok] [4250s d=-1527 ncc=0.50 2o=0.41 z=5.9 ok] deriva{deriva provada: escala 0.999000, resíduo 145 ms (reta sem escala deixaria 2379 ms), deriva medida 4054 ms nas janelas e 4783 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=-0.001024 provada=1}`

- **tower_of_london_1962_202510 / g1.001|o-4300 / ERRADO** diagnostico completo: `[195s d=4473 ncc=0.70 2o=0.46 z=6.4 ok] [1205s d=3497 ncc=0.57 2o=0.38 z=4.5 ok] [3210s d=1509 ncc=0.51 2o=0.35 z=4.2 ok] [1825s d=2898 ncc=0.64 2o=0.49 z=5.3 ok] [2395s d=2354 ncc=0.41 2o=0.33 z=2.7 ok] [4250s d=273 ncc=0.50 2o=0.41 z=5.9 ok] deriva{deriva provada: escala 0.999000, resíduo 155 ms (reta sem escala deixaria 2382 ms), deriva medida 4059 ms nas janelas e 4783 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=-0.001024 provada=1}`

- **tower_of_london_1962_202510 / g25/23.976|o+6200 / ERRADO** diagnostico completo: `[1250s d=9534 ncc=0.14 2o=0.13 z=1.8 -] [1935s d=-11437 ncc=0.14 2o=0.12 z=2.1 -] [3360s d=-5666 ncc=0.13 2o=0.10 z=2.2 -] [295s d=4657 ncc=0.23 2o=0.21 z=2.4 -] [2390s d=15000 ncc=0.12 2o=0.09 z=2.4 -] [4445s d=15000 ncc=0.18 2o=0.14 z=2.4 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiaveis=0 inclinacao=0.000000 provada=0}`

- **tower_of_london_1962_202510 / g25/23.976|o-900 / ERRADO** diagnostico completo: `[1260s d=-887 ncc=0.13 2o=0.12 z=1.6 -] [1935s d=-4337 ncc=0.14 2o=0.12 z=2.1 -] [3355s d=-14450 ncc=0.12 2o=0.08 z=2.0 -] [275s d=-12528 ncc=0.34 2o=0.30 z=2.6 -] [2390s d=-14284 ncc=0.25 2o=0.22 z=2.8 -] [4435s d=-8783 ncc=0.17 2o=0.17 z=1.7 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiaveis=0 inclinacao=0.000000 provada=0}`

- **unfaithfully_yours / g25/23.976|o-2500 / ERRADO** diagnostico completo: `[505s d=9188 ncc=0.15 2o=0.14 z=2.7 -] [1465s d=-12588 ncc=-0.01 2o=-0.03 z=2.1 -] [1825s d=8917 ncc=0.27 2o=0.26 z=1.7 -] [2150s d=11643 ncc=0.18 2o=0.16 z=2.6 -] [3645s d=-6163 ncc=0.35 2o=0.33 z=1.9 -] [4495s d=-15000 ncc=-0.05 2o=-0.10 z=2.4 -] [5745s d=-15000 ncc=0.08 2o=0.02 z=2.6 -] deriva{menos de 2 janelas confiáveis (0 de 7); confiaveis=0 inclinacao=0.000000 provada=0}`

- **valkaama / menos2 / ERRADO** diagnostico completo: `[1965s d=2415 ncc=0.51 2o=0.39 z=4.4 ok] [2870s d=2660 ncc=0.75 2o=0.68 z=3.2 -] [4980s d=2489 ncc=0.50 2o=0.41 z=3.3 ok] [735s d=10520 ncc=0.07 2o=0.07 z=4.9 -] [1015s d=2343 ncc=0.61 2o=0.51 z=3.9 ok] [3820s d=2243 ncc=0.70 2o=0.59 z=5.6 ok] deriva{sem prova de deriva: resíduo da reta sem escala 129 ms; confiaveis=4 inclinacao=0.000010 provada=0}`

### drift: 23 casos

| titulo | tipo | variante | classe | kind | escala | desl. aplicado (ms) | z | janelas conc. | erro max / p95 (ms) | motivo |
|---|---|---|---|---|---|---|---|---|---|---|
| 1951_the_prowler_el_merodeador_joseph_losey_vose (old, 1951) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7083 | 16.5 | 4/10 | 417 / 417 |  |
| houseonbaremountain (old, 1962) | S | `g0.999|o+1800` | ERRADO | FIX | 1.00100 | -1283 | 13.4 | 3/5 | 519 / 519 |  |
| love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ (old-tv, None) | S | `g25/23.976|o+2600` | ERRADO | FIX | 0.96000 | -2995 | 9.8 | 3/3 | 995 / 903 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g0.999|o+1800` | ERRADO | FIX | 1.00100 | -1391 | 15.1 | 6/6 | 411 / 411 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3598 | 15.9 | 6/6 | 406 / 406 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g1.001|o-4300` | ERRADO | FIX | 0.99900 | 4713 | 15.0 | 6/6 | 417 / 417 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g1.001|o-900` | ERRADO | FIX | 0.99900 | 1309 | 15.1 | 6/6 | 410 / 410 |  |
| murder_by_invitation_1941 (old, 1941) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7096 | 15.1 | 6/6 | 404 / 404 |  |
| murder_by_invitation_1941 (old, 1941) | S | `menos2` | ERRADO | FIX | 1.00000 | 2404 | 15.4 | 6/6 | 404 / 404 |  |
| pit_and_the_pendulum (old, 1961) | S | `mais3` | ERRADO | FIX | 1.00000 | -2505 | 11.4 | 3/3 | 495 / 495 |  |
| randyridesalone (old, 1934) | S | `g23.976/25|o-2500` | ERRADO | FIX | 1.04167 | 4492 | 13.5 | 3/3 | 1785 / 1543 |  |
| the_gorgon_1964 (old, 1964) | S | `g25/23.976|o+1800` | ERRADO | FIX | 0.95904 | -1322 | 9.2 | 3/3 | 404 / 404 |  |
| the_housemaid_1960 (old, 1960) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3582 | 16.0 | 3/6 | 422 / 422 |  |
| the_son_of_monte_cristo (old, 1940) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3437 | 12.8 | 4/10 | 567 / 567 |  |
| the_son_of_monte_cristo (old, 1940) | S | `g1.001|o-1300` | ERRADO | FIX | 0.99900 | 1925 | 13.1 | 3/9 | 627 / 627 |  |
| the_son_of_monte_cristo (old, 1940) | S | `g1.001|o-900` | ERRADO | FIX | 0.99900 | 1493 | 12.7 | 4/10 | 594 / 594 |  |
| therunawaybus1954 (old, 1954) | S | `g1.001|o-4300` | ERRADO | FIX | 0.99900 | 4866 | 11.5 | 2/5 | 570 / 570 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g1.001|o-2500` | ERRADO | FIX | 0.99900 | 2915 | 15.9 | 5/6 | 417 / 417 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g1.001|o-4300` | ERRADO | FIX | 0.99900 | 4723 | 16.3 | 5/6 | 427 / 427 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g25/23.976|o+6200` | ERRADO | FIX | 0.95904 | -5453 | 11.1 | 6/6 | 493 / 493 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g25/23.976|o-900` | ERRADO | FIX | 0.95904 | 1328 | 12.5 | 6/6 | 465 / 465 |  |
| unfaithfully_yours (old, 1948) | S | `g25/23.976|o-2500` | ERRADO | FIX | 0.95904 | 2814 | 10.4 | 6/7 | 417 / 417 |  |
| valkaama (new, 2010) | S | `menos2` | ERRADO | FIX | 1.00000 | 2433 | 13.7 | 3/5 | 433 / 433 |  |

- **1951_the_prowler_el_merodeador_joseph_losey_vose / mais7.5 / ERRADO** diagnostico completo: `[580s d=-7216 ncc=0.54 2o=0.38 z=4.8 ok] [1515s d=-7313 ncc=0.58 2o=0.43 z=5.2 ok] [3280s d=-7479 ncc=0.58 2o=0.39 z=6.3 ok] [2670s d=-7507 ncc=0.49 2o=0.39 z=4.0 ok] [3890s d=-7024 ncc=0.66 2o=0.63 z=3.5 -] [5090s d=-7190 ncc=0.72 2o=0.60 z=2.8 ok] [4410s d=-7349 ncc=0.77 2o=0.65 z=3.2 ok] [1775s d=-5609 ncc=0.70 2o=0.70 z=1.0 -] [785s d=-7192 ncc=0.52 2o=0.44 z=2.6 -] [3080s d=-6840 ncc=0.68 2o=0.64 z=2.0 -] deriva{sem prova de deriva: resíduo da reta sem escala 173 ms; confiaveis=6 inclinacao=0.000003 provada=0}`

- **houseonbaremountain / g0.999|o+1800 / ERRADO** diagnostico completo: `[175s d=-944 ncc=0.66 2o=0.55 z=4.5 ok] [535s d=-1492 ncc=0.48 2o=0.44 z=2.8 -] [1145s d=-251 ncc=0.41 2o=0.25 z=3.4 ok] [650s d=-1299 ncc=0.57 2o=0.23 z=6.8 ok] [1255s d=-126 ncc=0.73 2o=0.63 z=2.7 ok] [2270s d=1173 ncc=0.67 2o=0.56 z=2.3 ok] [2915s d=-10251 ncc=0.15 2o=0.10 z=2.5 -] [3200s d=-10251 ncc=0.00 2o=0.00 z=0.0 -] deriva{deriva provada: escala 1.001001, resíduo 160 ms (reta sem escala deixaria 1424 ms), deriva medida 2095 ms nas janelas e 3719 ms no filme, 4 janelas confiáveis (1 janela fora da reta descartada); confiaveis=5 inclinacao=0.001128 provada=1}`

- **love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ / g25/23.976|o+2600 / ERRADO** diagnostico completo: `[335s d=-8922 ncc=0.22 2o=0.19 z=2.2 -] [765s d=6485 ncc=0.15 2o=0.14 z=1.3 -] [1230s d=5283 ncc=0.21 2o=0.19 z=2.3 -] [205s d=-11871 ncc=0.47 2o=0.41 z=3.1 -] [575s d=-9113 ncc=0.40 2o=0.39 z=1.8 -] [1020s d=15000 ncc=0.28 2o=0.26 z=2.7 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: ); confiaveis=0 inclinacao=0.000000 provada=0}`

- **murder_by_invitation_1941 / g0.999|o+1800 / ERRADO** diagnostico completo: `[510s d=-860 ncc=0.65 2o=0.56 z=2.5 ok] [1655s d=440 ncc=0.43 2o=0.20 z=5.7 ok] [2965s d=1676 ncc=0.42 2o=0.25 z=5.3 ok] [685s d=-684 ncc=0.66 2o=0.47 z=4.8 ok] [2135s d=864 ncc=0.64 2o=0.53 z=4.1 ok] [3280s d=1809 ncc=0.73 2o=0.61 z=3.2 ok] deriva{deriva provada: escala 1.001001, resíduo 130 ms (reta sem escala deixaria 1356 ms), deriva medida 2770 ms nas janelas e 3888 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=0.000985 provada=1}`

- **murder_by_invitation_1941 / g0.999|o+4000 / ERRADO** diagnostico completo: `[515s d=-3068 ncc=0.65 2o=0.57 z=2.4 ok] [1680s d=-1732 ncc=0.47 2o=0.22 z=4.8 ok] [2965s d=-524 ncc=0.42 2o=0.25 z=5.3 ok] [685s d=-2884 ncc=0.66 2o=0.47 z=4.8 ok] [2130s d=-1364 ncc=0.64 2o=0.52 z=3.8 ok] [3280s d=-391 ncc=0.73 2o=0.61 z=3.2 ok] deriva{deriva provada: escala 1.001001, resíduo 137 ms (reta sem escala deixaria 1362 ms), deriva medida 2765 ms nas janelas e 3888 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=0.000986 provada=1}`

- **murder_by_invitation_1941 / g1.001|o-4300 / ERRADO** diagnostico completo: `[500s d=4127 ncc=0.58 2o=0.52 z=2.3 -] [1675s d=3126 ncc=0.46 2o=0.23 z=5.1 ok] [2965s d=1750 ncc=0.41 2o=0.24 z=5.4 ok] [685s d=3955 ncc=0.66 2o=0.47 z=5.0 ok] [2135s d=2598 ncc=0.64 2o=0.52 z=4.2 ok] [3280s d=1256 ncc=0.73 2o=0.61 z=3.1 ok] deriva{deriva provada: escala 0.999000, resíduo 134 ms (reta sem escala deixaria 1426 ms), deriva medida 2598 ms nas janelas e 3892 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001036 provada=1}`

- **murder_by_invitation_1941 / g1.001|o-900 / ERRADO** diagnostico completo: `[505s d=747 ncc=0.61 2o=0.53 z=2.4 -] [1670s d=-274 ncc=0.42 2o=0.22 z=4.9 ok] [2970s d=-1685 ncc=0.46 2o=0.25 z=5.7 ok] [685s d=555 ncc=0.66 2o=0.47 z=5.0 ok] [2135s d=-802 ncc=0.64 2o=0.52 z=4.2 ok] [3285s d=-2158 ncc=0.71 2o=0.59 z=3.3 ok] deriva{deriva provada: escala 0.999000, resíduo 135 ms (reta sem escala deixaria 1443 ms), deriva medida 2603 ms nas janelas e 3892 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001039 provada=1}`

- **murder_by_invitation_1941 / mais7.5 / ERRADO** diagnostico completo: `[515s d=-7131 ncc=0.65 2o=0.56 z=2.5 ok] [1685s d=-6948 ncc=0.42 2o=0.19 z=4.4 ok] [2975s d=-7105 ncc=0.50 2o=0.27 z=6.7 ok] [685s d=-7112 ncc=0.66 2o=0.46 z=5.0 ok] [2140s d=-6992 ncc=0.65 2o=0.54 z=3.9 ok] [3290s d=-7227 ncc=0.72 2o=0.59 z=3.3 ok] deriva{sem prova de deriva: resíduo da reta sem escala 148 ms; confiaveis=6 inclinacao=-0.000021 provada=0}`

- **murder_by_invitation_1941 / menos2 / ERRADO** diagnostico completo: `[505s d=2395 ncc=0.62 2o=0.53 z=2.4 ok] [1655s d=2533 ncc=0.43 2o=0.20 z=5.8 ok] [2965s d=2463 ncc=0.42 2o=0.24 z=5.4 ok] [685s d=2388 ncc=0.66 2o=0.46 z=5.0 ok] [2415s d=2434 ncc=0.83 2o=0.78 z=2.7 -] [3280s d=2284 ncc=0.73 2o=0.61 z=3.2 ok] deriva{sem prova de deriva: resíduo da reta sem escala 137 ms; confiaveis=5 inclinacao=-0.000023 provada=0}`

- **pit_and_the_pendulum / mais3 / ERRADO** diagnostico completo: `[830s d=-2585 ncc=0.63 2o=0.47 z=3.7 ok] [1220s d=-2368 ncc=0.60 2o=0.45 z=5.0 ok] [2250s d=-2556 ncc=0.59 2o=0.55 z=3.2 -] [2510s d=-2548 ncc=0.59 2o=0.54 z=3.0 -] [3825s d=-2684 ncc=0.59 2o=0.54 z=3.4 -] [3965s d=-2759 ncc=0.65 2o=0.58 z=3.5 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: janelas discordam;); confiaveis=0 inclinacao=0.000000 provada=0}`

- **randyridesalone / g23.976/25|o-2500 / ERRADO** diagnostico completo: `[790s d=36782 ncc=0.76 2o=0.52 z=5.5 ok] [1150s d=51626 ncc=0.20 2o=0.15 z=2.3 -] [1760s d=76470 ncc=0.86 2o=0.46 z=4.9 ok] [355s d=20340 ncc=0.90 2o=0.82 z=2.1 ok] [2050s d=88126 ncc=0.83 2o=0.56 z=4.9 ok] [2770s d=107825 ncc=-0.03 2o=-0.04 z=4.9 -] deriva{deriva provada: escala 1.041667, resíduo 43 ms (reta sem escala deixaria 34905 ms), deriva medida 67800 ms nas janelas e 125593 ms no filme, 3 janelas confiáveis (1 janela fora da reta descartada); confiaveis=4 inclinacao=0.040173 provada=1}`

- **the_gorgon_1964 / g25/23.976|o+1800 / ERRADO** diagnostico completo: `[885s d=-13443 ncc=0.17 2o=0.14 z=2.5 -] [3495s d=6131 ncc=0.27 2o=0.23 z=2.2 -] [4525s d=-10357 ncc=0.24 2o=0.22 z=2.7 -] [720s d=14735 ncc=0.25 2o=0.17 z=2.6 -] [2080s d=14680 ncc=0.25 2o=0.24 z=2.2 -] [2560s d=10862 ncc=0.11 2o=0.11 z=2.5 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: ); confiaveis=0 inclinacao=0.000000 provada=0}`

- **the_housemaid_1960 / g0.999|o+4000 / ERRADO** diagnostico completo: `[640s d=-2963 ncc=0.59 2o=0.49 z=3.5 ok] [1695s d=-2024 ncc=0.53 2o=0.46 z=3.3 -] [2135s d=-1645 ncc=0.41 2o=0.37 z=2.2 -] [2390s d=-1450 ncc=0.58 2o=0.51 z=3.5 -] [3535s d=-68 ncc=0.66 2o=0.49 z=5.4 ok] [4665s d=740 ncc=0.62 2o=0.52 z=3.4 ok] [5695s d=2325 ncc=0.53 2o=0.39 z=3.9 ok] deriva{deriva provada: escala 1.001001, resíduo 163 ms (reta sem escala deixaria 2920 ms), deriva medida 5055 ms nas janelas e 6664 ms no filme, 3 janelas confiáveis (1 janela fora da reta descartada); confiaveis=4 inclinacao=0.001005 provada=1}`

- **the_son_of_monte_cristo / g0.999|o+4000 / ERRADO** diagnostico completo: `[1680s d=-1600 ncc=0.42 2o=0.27 z=5.0 ok] [2045s d=-1920 ncc=0.52 2o=0.49 z=2.7 -] [3315s d=2319 ncc=0.34 2o=0.31 z=2.1 -] [240s d=-3288 ncc=0.65 2o=0.56 z=3.8 ok] [2135s d=-1960 ncc=0.54 2o=0.48 z=2.2 -] [4180s d=846 ncc=0.35 2o=0.31 z=2.5 -] [5095s d=296 ncc=0.56 2o=0.52 z=2.8 -] [1100s d=-3141 ncc=0.24 2o=0.18 z=3.4 -] [5550s d=-705 ncc=0.54 2o=0.52 z=2.3 -] [4645s d=1355 ncc=0.46 2o=0.37 z=3.0 ok] deriva{deriva provada: escala 1.001001, resíduo 139 ms (reta sem escala deixaria 2787 ms), deriva medida 4405 ms nas janelas e 6088 ms no filme, 3 janelas confiáveis; confiaveis=3 inclinacao=0.001050 provada=1}`

- **the_son_of_monte_cristo / g1.001|o-1300 / ERRADO** diagnostico completo: `[1645s d=415 ncc=0.42 2o=0.30 z=3.7 ok] [2135s d=-1034 ncc=0.54 2o=0.48 z=2.2 -] [3315s d=984 ncc=0.35 2o=0.30 z=2.1 -] [240s d=1506 ncc=0.66 2o=0.57 z=3.8 ok] [4185s d=-2303 ncc=0.38 2o=0.31 z=2.6 -] [5100s d=-4776 ncc=0.54 2o=0.51 z=2.7 -] [1025s d=52 ncc=0.57 2o=0.45 z=3.4 ok] [4645s d=-2735 ncc=0.46 2o=0.37 z=3.0 ok] [5555s d=-6631 ncc=0.60 2o=0.55 z=2.8 -] [2930s d=-999 ncc=0.14 2o=0.12 z=2.4 -] deriva{deriva provada: escala 0.999000, resíduo 180 ms (reta sem escala deixaria 2710 ms), deriva medida 4409 ms nas janelas e 6094 ms no filme, 3 janelas confiáveis (1 janela fora da reta descartada); confiaveis=4 inclinacao=-0.000916 provada=1}`

- **the_son_of_monte_cristo / g1.001|o-900 / ERRADO** diagnostico completo: `[1685s d=-140 ncc=0.40 2o=0.25 z=5.3 ok] [2045s d=-1274 ncc=0.53 2o=0.49 z=2.8 -] [3315s d=584 ncc=0.35 2o=0.30 z=2.1 -] [240s d=1106 ncc=0.66 2o=0.57 z=3.8 ok] [2135s d=-1434 ncc=0.54 2o=0.48 z=2.2 -] [4185s d=-2703 ncc=0.38 2o=0.31 z=2.6 -] [5100s d=-5176 ncc=0.54 2o=0.51 z=2.7 -] [1095s d=-474 ncc=0.19 2o=0.14 z=2.6 -] [4645s d=-3135 ncc=0.46 2o=0.37 z=3.0 ok] [5555s d=-7031 ncc=0.60 2o=0.55 z=2.8 -] deriva{deriva provada: escala 0.999000, resíduo 104 ms (reta sem escala deixaria 2635 ms), deriva medida 4409 ms nas janelas e 6094 ms no filme, 3 janelas confiáveis; confiaveis=3 inclinacao=-0.000965 provada=1}`

- **therunawaybus1954 / g1.001|o-4300 / ERRADO** diagnostico completo: `[295s d=2004 ncc=0.40 2o=0.30 z=4.9 ok] [1680s d=3290 ncc=0.68 2o=0.57 z=3.8 ok] [3180s d=1332 ncc=0.52 2o=0.47 z=2.7 -] [1120s d=6602 ncc=0.78 2o=0.74 z=1.7 -] [2625s d=2076 ncc=0.67 2o=0.58 z=2.6 ok] [4010s d=789 ncc=0.70 2o=0.55 z=4.1 ok] deriva{deriva provada: escala 0.999000, resíduo 145 ms (reta sem escala deixaria 1258 ms), deriva medida 2332 ms nas janelas e 4397 ms no filme, 3 janelas confiáveis (1 janela fora da reta descartada); confiaveis=4 inclinacao=-0.000499 provada=1}`

- **tower_of_london_1962_202510 / g1.001|o-2500 / ERRADO** diagnostico completo: `[200s d=2690 ncc=0.71 2o=0.47 z=6.3 ok] [1210s d=1651 ncc=0.58 2o=0.38 z=4.8 ok] [3210s d=-291 ncc=0.51 2o=0.35 z=4.2 ok] [1815s d=1070 ncc=0.69 2o=0.54 z=4.7 ok] [2395s d=554 ncc=0.41 2o=0.33 z=2.7 ok] [4250s d=-1527 ncc=0.50 2o=0.41 z=5.9 ok] deriva{deriva provada: escala 0.999000, resíduo 145 ms (reta sem escala deixaria 2379 ms), deriva medida 4054 ms nas janelas e 4783 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=-0.001024 provada=1}`

- **tower_of_london_1962_202510 / g1.001|o-4300 / ERRADO** diagnostico completo: `[195s d=4473 ncc=0.70 2o=0.46 z=6.4 ok] [1205s d=3497 ncc=0.57 2o=0.38 z=4.5 ok] [3210s d=1509 ncc=0.51 2o=0.35 z=4.2 ok] [1825s d=2898 ncc=0.64 2o=0.49 z=5.3 ok] [2395s d=2354 ncc=0.41 2o=0.33 z=2.7 ok] [4250s d=273 ncc=0.50 2o=0.41 z=5.9 ok] deriva{deriva provada: escala 0.999000, resíduo 155 ms (reta sem escala deixaria 2382 ms), deriva medida 4059 ms nas janelas e 4783 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=-0.001024 provada=1}`

- **tower_of_london_1962_202510 / g25/23.976|o+6200 / ERRADO** diagnostico completo: `[1250s d=-61100 ncc=0.52 2o=0.32 z=4.7 ok] [1935s d=-90224 ncc=0.53 2o=0.43 z=3.8 ok] [3360s d=-151132 ncc=0.67 2o=0.56 z=4.3 ok] [295s d=-20154 ncc=0.57 2o=0.45 z=3.7 ok] [2390s d=-109622 ncc=0.42 2o=0.34 z=3.0 ok] [4445s d=-197516 ncc=0.74 2o=0.66 z=3.2 -] deriva{deriva provada: escala 0.959040, resíduo 106 ms (reta sem escala deixaria 67867 ms), deriva medida 130904 ms nas janelas e 204083 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.042715 provada=1}`

- **tower_of_london_1962_202510 / g25/23.976|o-900 / ERRADO** diagnostico completo: `[1260s d=-54450 ncc=0.50 2o=0.29 z=4.8 ok] [1935s d=-83125 ncc=0.53 2o=0.43 z=3.8 ok] [3355s d=-143876 ncc=0.68 2o=0.56 z=5.1 ok] [275s d=-12253 ncc=0.54 2o=0.45 z=3.9 ok] [2390s d=-102522 ncc=0.42 2o=0.34 z=3.0 ok] [4435s d=-190011 ncc=0.73 2o=0.65 z=4.1 -] deriva{deriva provada: escala 0.959040, resíduo 99 ms (reta sem escala deixaria 69661 ms), deriva medida 131545 ms nas janelas e 204083 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.042716 provada=1}`

- **unfaithfully_yours / g25/23.976|o-2500 / ERRADO** diagnostico completo: `[505s d=-15092 ncc=0.20 2o=0.14 z=3.0 -] [1465s d=-61499 ncc=0.63 2o=0.54 z=3.0 ok] [1825s d=-76943 ncc=0.46 2o=0.35 z=4.6 ok] [2150s d=-90819 ncc=0.47 2o=0.31 z=5.1 ok] [3645s d=-154634 ncc=0.53 2o=0.49 z=2.3 -] [4495s d=-190928 ncc=0.79 2o=0.76 z=2.5 -] [5745s d=-254780 ncc=0.00 2o=0.00 z=0.0 -] [3070s d=-129254 ncc=-0.06 2o=-0.06 z=3.0 -] [5315s d=-230396 ncc=-0.07 2o=-0.07 z=2.1 -] [4205s d=-178677 ncc=0.75 2o=0.73 z=2.4 -] deriva{sem prova de deriva: resíduo da reta sem escala 15918 ms (janelas cobrem pouco do filme;); confiaveis=3 inclinacao=-0.042808 provada=0}`

- **valkaama / menos2 / ERRADO** diagnostico completo: `[1965s d=2415 ncc=0.51 2o=0.39 z=4.4 ok] [2870s d=2660 ncc=0.75 2o=0.68 z=3.2 -] [4980s d=2489 ncc=0.50 2o=0.41 z=3.3 ok] [735s d=10520 ncc=0.07 2o=0.07 z=4.9 -] [1015s d=2343 ncc=0.61 2o=0.51 z=3.9 ok] [3820s d=2243 ncc=0.70 2o=0.59 z=5.6 ok] deriva{sem prova de deriva: resíduo da reta sem escala 129 ms; confiaveis=4 inclinacao=0.000010 provada=0}`

## 7b. Onde o motor falha: % de variantes S corrigidas CERTAS por caracteristica do titulo

So variantes S (deslocadas/deformadas). `n` = variantes. Serve para achar a causa: se a taxa cai com poucas falas, com a abertura silenciosa, com filmes muito longos, etc.


**por tipo de titulo**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| antigo (TV) | 66 | 15.2% | 83.3% | 66 | 18.2% | 80.3% |
| antigo (filme) | 1309 | 50.8% | 47.7% | 1309 | 53.8% | 44.6% |
| novo | 66 | 24.2% | 74.2% | 66 | 28.8% | 69.7% |

**por decada**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 1920s | 11 | 100.0% | 0.0% | 11 | 100.0% | 0.0% |
| 1930s | 220 | 59.5% | 40.5% | 220 | 61.8% | 37.7% |
| 1940s | 429 | 52.2% | 45.5% | 429 | 54.8% | 42.9% |
| 1950s | 440 | 53.2% | 46.4% | 440 | 56.4% | 43.2% |
| 1960s | 231 | 28.6% | 68.0% | 231 | 32.9% | 63.6% |
| 1970s | 11 | 72.7% | 27.3% | 11 | 81.8% | 18.2% |
| 2010s | 33 | 45.5% | 51.5% | 33 | 45.5% | 51.5% |
| 2020s | 33 | 3.0% | 97.0% | 33 | 12.1% | 87.9% |
| sem ano | 33 | 3.0% | 93.9% | 33 | 3.0% | 93.9% |

**por densidade de falas da legenda**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 4-8 falas/min | 88 | 21.6% | 76.1% | 88 | 23.9% | 72.7% |
| 8-12 falas/min | 440 | 45.7% | 51.8% | 440 | 49.8% | 47.7% |
| >= 12 falas/min | 913 | 51.6% | 47.4% | 913 | 54.2% | 44.8% |

**por duracao**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 30-75 min | 550 | 52.9% | 45.6% | 550 | 56.2% | 42.2% |
| 75-120 min | 748 | 50.5% | 47.7% | 748 | 53.2% | 45.1% |
| < 30 min | 110 | 10.0% | 89.1% | 110 | 14.5% | 84.5% |
| > 120 min | 33 | 33.3% | 66.7% | 33 | 36.4% | 63.6% |

**idioma da legenda de referencia**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| PT (filtro do app) | 781 | 47.0% | 51.9% | 781 | 50.3% | 48.4% |
| outro idioma | 660 | 49.1% | 48.9% | 660 | 51.8% | 46.2% |

**abertura silenciosa**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| sem silencio longo | 1441 | 48.0% | 50.5% | 1441 | 51.0% | 47.4% |

**nivel da referencia**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| A (so desloca) | 891 | 64.5% | 33.7% | 891 | 67.6% | 30.6% |
| B (alass por partes) | 550 | 21.1% | 77.8% | 550 | 24.2% | 74.5% |

**canais de audio da faixa 1**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 1 canais | 22 | 27.3% | 72.7% | 22 | 31.8% | 68.2% |
| 2 canais | 1419 | 48.3% | 50.2% | 1419 | 51.3% | 47.1% |

## 8. Bugs e comportamentos estranhos achados na execucao

- Excecoes / timeouts do alinhador: **0** variantes (de 3930).
- NaN/Infinity em campos do resultado: **0** variantes.
- Variantes que o app nem analisaria (guarda: poucas falas ou video < 8 min): 0
- Processos Java dos motores que terminaram com erro: **0**

### Parser de legendas do app (SubtitleText.decode + Cue.parseSrt) sobre a referencia reescrita em varios formatos

| formato | titulos | ok | tempos certos mas texto diferente | exemplo do problema |
|---|---|---|---|---|
| srt-utf8 | 131 | 131 | 0 |  |
| srt-utf8-bom | 131 | 131 | 0 |  |
| srt-crlf | 131 | 131 | 0 |  |
| srt-cr-only | 131 | 131 | 0 |  |
| srt-cp1252 | 131 | 127 | 2 | robin_hood_66_the_york_treasure_robin_hood_66_the_york_treas: falas 397 em vez de 400; black_angel_1946_202406: tempos certos mas o texto (acentos) saiu diferente |
| srt-cp1252-crlf | 131 | 127 | 2 | robin_hood_66_the_york_treasure_robin_hood_66_the_york_treas: falas 397 em vez de 400; black_angel_1946_202406: tempos certos mas o texto (acentos) saiu diferente |
| srt-utf16le-bom | 131 | 131 | 0 |  |
| srt-utf16be-bom | 131 | 131 | 0 |  |
| srt-utf16le-sem-bom | 131 | 131 | 0 |  |
| srt-ponto-no-ms | 131 | 131 | 0 |  |
| srt-ms-2-digitos | 131 | 131 | 0 |  |
| srt-ms-1-digito | 131 | 131 | 0 |  |
| srt-hora-1-digito | 131 | 131 | 0 |  |
| srt-sem-numeros | 131 | 131 | 0 |  |
| srt-tags-html-an8 | 131 | 131 | 0 |  |
| srt-mojibake | 131 | 131 | 0 |  |
| ass | 131 | 131 | 0 |  |
| ssa-marked | 131 | 131 | 0 |  |
| ass-utf8-bom | 131 | 131 | 0 |  |
| vtt | 131 | 131 | 0 |  |
| vtt-ids-mm:ss | 131 | 131 | 0 |  |
| srt-gzip | 131 | 131 | 0 |  |
| srt-zip | 131 | 131 | 0 |  |
| microdvd(nao suportado) | 131 | 5 | 0 | femjungle: 170 falas com tempo/classificacao diferente (desvio max 21 ms); phantomplanet: 208 falas com tempo/classificacao diferente (desvio max 21 ms) |

### Legendas reais baixadas (853 arquivos de 375 titulos) e o parser do app

- decode do app devolveu null (descarta): 2; destes, o python leu >= 100 falas (legenda valida que o app descartaria): 0
- numero de falas do app diferente do python em >3%: 100
  - beverly_hillbillies_ep04_the_clampetts_meet_mrs_drysdale_bh0 `BH04_The_Clampetts_Meet_Mrs_Drysdale.autogenerated.vtt`: falas app=347 python=0
  - beverly_hillbillies_ep04_the_clampetts_meet_mrs_drysdale_bh0 `BH04_The_Clampetts_Meet_Mrs_Drysdale_512kb.autogenerated.vtt`: falas app=411 python=0
  - andy_griffith_a_wife_for_andy_upgraded_ep_andy_griffith_a_wi `Andy_Griffith_A_Wife_for_Andy.autogenerated.vtt`: falas app=452 python=0
  - geneautrydoubleswitch08271950_gene_autry_double_switch_08_27 `Gene_Autry_ Double_Switch_08_27_1950.autogenerated.vtt`: falas app=275 python=0
  - dragnet_the_biggrandma_s02e09_the_big_grandma `S02E09_The_Big_Grandma.autogenerated.vtt`: falas app=504 python=0
  - dragnet_the_biggrandma_s02e09_the_big_grandma `S02E09_The_Big_Grandma_512kb.autogenerated.vtt`: falas app=508 python=0
  - 1942_dr_broadway_anthony_mann_vo `1942 - Dr. Broadway - Anthony Mann - VO.autogenerated.vtt`: falas app=1381 python=143
  - robin_hood_05_maid_marian_robin_hood_05_maid_marian `Robin_Hood_05_Maid_Marian.autogenerated.vtt`: falas app=241 python=0
  - robin_hood_05_maid_marian_robin_hood_05_maid_marian `Robin_Hood_05_Maid_Marian_512kb.autogenerated.vtt`: falas app=360 python=0
  - ozzieandharriet_busychristmas_ozzieandharriet_busychristmas `OzzieAndHarriet-BusyChristmas.autogenerated.vtt`: falas app=498 python=0
- legendas com falas fora de ordem no arquivo: 9; com sobreposicao > 200 ms em mais de 5 falas: 8; com fim <= inicio em mais de 3 falas: 2; mojibake que sobrou: 1; VTT: 103
  - fora de ordem: beverly_hillbillies_ep04_the_clampetts_meet_mrs_drysdale_bh0 `BH04_The_Clampetts_Meet_Mrs_Drysdale_512kb.autogenerated.vtt` (1 falas fora de ordem)
  - fora de ordem: 15_un_poing_final `15_Un_poing_final.autogenerated.vtt` (12 falas fora de ordem)
  - fora de ordem: night_of_the_living_dead_raw_hd_ws `3280670` (1 falas fora de ordem)
  - fora de ordem: 14_au_fil_de_l_histoire `14_Au_fil_de_l_histoire.autogenerated.vtt` (9 falas fora de ordem)
  - fora de ordem: thebigshow_drag_ep025_01_22_53_the_big_show `drag_ep025_01_22_53_The_Big_Show.autogenerated.vtt` (1 falas fora de ordem)
  - fora de ordem: meet_john_doe_ipod `3613424` (7 falas fora de ordem)
  - fora de ordem: maniac_202107 `7334013` (63 falas fora de ordem)
  - fora de ordem: stranger_in_town_1943 `8300859` (1 falas fora de ordem)
- idioma das legendas decodificadas segundo o filtro do app (looksPortuguese): nao-PT (eng)=528, PT (parser do app)=241, nao-PT (spa)=50, nao-PT (und)=32

### Casos especiais de midia

- titulos com 2+ faixas de audio: 0; com 5.1 (6+ canais) na faixa 1: 0; filmes de mais de 3 h: 0; com abertura silenciosa >= 60 s: 0
