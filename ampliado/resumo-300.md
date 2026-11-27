# Bancada dos 300 titulos: o motor de sincronia de legendas pelo audio

Medido com o laco real do app (guardas, plano de janelas, VAD Silero em Java puro, `AudioAligner` e a regra de decisao `fill`), sem alterar o motor.
Motores: **main** = `origin/main` (3 janelas densas, uma resolucao); **drift** = ramo `run-deriva` (3 densas + 6 espalhadas + ate 2 rodadas extras, prova de deriva).

## 1. Titulos usados

Titulos tentados: **483**. Com resultado valido (referencia nivel A/B): **163** (antigos filmes 148, antigos TV 8, novos 7).
Proporcao antigos/novos: 96% / 4%.


Destino de cada titulo tentado:

| destino | titulos |
|---|---|
| referencia ruim | 270 |
| ok | 163 |
| sem legenda utilizavel | 41 |
| download falhou | 5 |
| nenhuma legenda alinhou | 4 |

Qualidade da referencia (legenda alinhada ao audio pelo alass; 2o oraculo = ffsubsync sobre a referencia): nivel A = desvio p90 do alass <= 300 ms e ffsubsync <= 300 ms; B = p90 ate 1000 ms (ampliado); X = excluida.
Referencias por nivel entre os titulos que chegaram a ter uma: A=102, B=61, X=270.

| origem da legenda de referencia | titulos |
|---|---|
| opensubtitles - PT (parser do app) | 91 |
| opensubtitles - outro idioma (eng) | 42 |
| ia-item - outro idioma (eng) | 22 |
| opensubtitles - outro idioma (und) | 4 |
| opensubtitles - outro idioma (spa) | 4 |

| decada | titulos |
|---|---|
| 1920s | 1 |
| 1930s | 26 |
| 1940s | 46 |
| 1950s | 47 |
| 1960s | 29 |
| 1970s | 2 |
| 2010s | 3 |
| 2020s | 4 |

| fonte da midia / licenca declarada | titulos |
|---|---|
| sem licenca declarada (colecao de dominio publico do IA) /  | 72 |
| http://creativecommons.org/licenses/publicdomain/ /  | 47 |
| http://creativecommons.org/publicdomain/mark/1.0/ /  | 14 |
| https://creativecommons.org/licenses/by-nc-nd/4.0/ /  | 13 |
| https://creativecommons.org/publicdomain/mark/1.0/ /  | 13 |
| https://creativecommons.org/publicdomain/zero/1.0/ /  | 2 |
| https://creativecommons.org/licenses/by-sa/4.0/ /  | 1 |
| http://creativecommons.org/licenses/by-nc-sa/4.0/ /  | 1 |

## 2. Numeros principais (todas as variantes, por tipo e por grupo)

Definicoes: **aceita** = o app aplicaria (FIX) ou diria "ja esta certa" (OK); **certa** = aceita e erro p95 <= 400 ms e max <= 800 ms em todas as falas (60 s .. fim-60 s) contra a referencia; **aceite errado** = aceita mas fora disso; **aceite falso** = aceitou a legenda de OUTRO titulo; **certa mexida** = a legenda ja certa recebeu um ajuste (FIX).

| grupo | motor | variantes S (devem ser corrigidas) | aceitas | certas | ACEITES ERRADOS | recusadas | legenda ja certa: mexidas | outro titulo: ACEITES FALSOS |
|---|---|---|---|---|---|---|---|---|
| todos | main | 1793 | 49.5% | 47.6% | 33 | 50.5% | 0/163 | 0/488 |
| todos | drift | 1793 | 52.1% | 50.3% | 33 | 47.9% | 0/163 | 0/488 |
| antigos (filmes) | main | 1628 | 52.5% | 50.6% | 31 | 47.5% | 0/148 | 0/443 |
| antigos (filmes) | drift | 1628 | 55.1% | 53.2% | 31 | 44.9% | 0/148 | 0/443 |
| antigos (TV) | main | 88 | 12.5% | 11.4% | 1 | 87.5% | 0/8 | 0/24 |
| antigos (TV) | drift | 88 | 14.8% | 13.6% | 1 | 85.2% | 0/8 | 0/24 |
| novos | main | 77 | 28.6% | 27.3% | 1 | 71.4% | 0/7 | 0/21 |
| novos | drift | 77 | 32.5% | 31.2% | 1 | 67.5% | 0/7 | 0/21 |

### Por familia de variante


**todos**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 163 | 49.7% | certas 49.1% (80) | ERRADAS 1 | recusadas 82 | z mediana 15.7 |
| deslocamento +3 s | drift | 163 | 49.7% | certas 49.1% (80) | ERRADAS 1 | recusadas 82 | z mediana 15.7 |
| deslocamento -2 s | main | 163 | 51.5% | certas 49.7% (81) | ERRADAS 3 | recusadas 79 | z mediana 15.5 |
| deslocamento -2 s | drift | 163 | 51.5% | certas 49.7% (81) | ERRADAS 3 | recusadas 79 | z mediana 15.5 |
| deslocamento +7,5 s | main | 163 | 52.8% | certas 51.5% (84) | ERRADAS 2 | recusadas 77 | z mediana 15.8 |
| deslocamento +7,5 s | drift | 163 | 52.8% | certas 51.5% (84) | ERRADAS 2 | recusadas 77 | z mediana 15.8 |
| escala 1.001 (24/23.976) | main | 326 | 59.2% | certas 56.1% (183) | ERRADAS 10 | recusadas 133 | z mediana 15.9 |
| escala 1.001 (24/23.976) | drift | 326 | 59.2% | certas 56.1% (183) | ERRADAS 10 | recusadas 133 | z mediana 15.9 |
| escala 0.999 (23.976/24) | main | 326 | 59.8% | certas 57.4% (187) | ERRADAS 8 | recusadas 131 | z mediana 15.9 |
| escala 0.999 (23.976/24) | drift | 326 | 59.8% | certas 57.4% (187) | ERRADAS 8 | recusadas 131 | z mediana 15.9 |
| escala 25/23.976 | main | 326 | 37.1% | certas 34.7% (113) | ERRADAS 8 | recusadas 205 | z mediana 12.5 |
| escala 25/23.976 | drift | 326 | 46.3% | certas 44.2% (144) | ERRADAS 7 | recusadas 175 | z mediana 12.7 |
| escala 23.976/25 | main | 326 | 39.0% | certas 38.7% (126) | ERRADAS 1 | recusadas 199 | z mediana 12.9 |
| escala 23.976/25 | drift | 326 | 44.5% | certas 43.9% (143) | ERRADAS 2 | recusadas 181 | z mediana 12.9 |
| ja certa | main | 163 | 51.5% | intactas 163 | MEXIDAS 0 | - | z mediana 16.4 |
| ja certa | drift | 163 | 51.5% | intactas 163 | MEXIDAS 0 | - | z mediana 16.4 |
| outro titulo | main | 325 | 0.0% | recusou 325 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo | drift | 325 | 0.0% | recusou 325 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo (com escala) | main | 163 | 0.0% | recusou 163 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo (com escala) | drift | 163 | 0.0% | recusou 163 | ACEITES FALSOS 0 | - | z mediana 5.2 |

**antigos (filmes)**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 148 | 52.0% | certas 51.4% (76) | ERRADAS 1 | recusadas 71 | z mediana 16.0 |
| deslocamento +3 s | drift | 148 | 52.0% | certas 51.4% (76) | ERRADAS 1 | recusadas 71 | z mediana 16.0 |
| deslocamento -2 s | main | 148 | 54.1% | certas 52.7% (78) | ERRADAS 2 | recusadas 68 | z mediana 15.6 |
| deslocamento -2 s | drift | 148 | 54.1% | certas 52.7% (78) | ERRADAS 2 | recusadas 68 | z mediana 15.6 |
| deslocamento +7,5 s | main | 148 | 56.1% | certas 54.7% (81) | ERRADAS 2 | recusadas 65 | z mediana 16.0 |
| deslocamento +7,5 s | drift | 148 | 56.1% | certas 54.7% (81) | ERRADAS 2 | recusadas 65 | z mediana 16.0 |
| escala 1.001 (24/23.976) | main | 296 | 63.2% | certas 59.8% (177) | ERRADAS 10 | recusadas 109 | z mediana 16.1 |
| escala 1.001 (24/23.976) | drift | 296 | 63.2% | certas 59.8% (177) | ERRADAS 10 | recusadas 109 | z mediana 16.1 |
| escala 0.999 (23.976/24) | main | 296 | 63.9% | certas 61.1% (181) | ERRADAS 8 | recusadas 107 | z mediana 16.3 |
| escala 0.999 (23.976/24) | drift | 296 | 63.9% | certas 61.1% (181) | ERRADAS 8 | recusadas 107 | z mediana 16.3 |
| escala 25/23.976 | main | 296 | 39.2% | certas 36.8% (109) | ERRADAS 7 | recusadas 180 | z mediana 12.6 |
| escala 25/23.976 | drift | 296 | 49.0% | certas 47.0% (139) | ERRADAS 6 | recusadas 151 | z mediana 12.9 |
| escala 23.976/25 | main | 296 | 41.2% | certas 40.9% (121) | ERRADAS 1 | recusadas 174 | z mediana 13.0 |
| escala 23.976/25 | drift | 296 | 45.9% | certas 45.3% (134) | ERRADAS 2 | recusadas 160 | z mediana 13.1 |
| ja certa | main | 148 | 54.7% | intactas 148 | MEXIDAS 0 | - | z mediana 16.7 |
| ja certa | drift | 148 | 54.7% | intactas 148 | MEXIDAS 0 | - | z mediana 16.7 |
| outro titulo | main | 295 | 0.0% | recusou 295 | ACEITES FALSOS 0 | - | z mediana 5.3 |
| outro titulo | drift | 295 | 0.0% | recusou 295 | ACEITES FALSOS 0 | - | z mediana 5.3 |
| outro titulo (com escala) | main | 148 | 0.0% | recusou 148 | ACEITES FALSOS 0 | - | z mediana 5.3 |
| outro titulo (com escala) | drift | 148 | 0.0% | recusou 148 | ACEITES FALSOS 0 | - | z mediana 5.3 |

**antigos (TV)**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 8 | 12.5% | certas 12.5% (1) | ERRADAS 0 | recusadas 7 | z mediana 13.4 |
| deslocamento +3 s | drift | 8 | 12.5% | certas 12.5% (1) | ERRADAS 0 | recusadas 7 | z mediana 13.4 |
| deslocamento -2 s | main | 8 | 12.5% | certas 12.5% (1) | ERRADAS 0 | recusadas 7 | z mediana 13.2 |
| deslocamento -2 s | drift | 8 | 12.5% | certas 12.5% (1) | ERRADAS 0 | recusadas 7 | z mediana 13.2 |
| deslocamento +7,5 s | main | 8 | 12.5% | certas 12.5% (1) | ERRADAS 0 | recusadas 7 | z mediana 13.7 |
| deslocamento +7,5 s | drift | 8 | 12.5% | certas 12.5% (1) | ERRADAS 0 | recusadas 7 | z mediana 13.7 |
| escala 1.001 (24/23.976) | main | 16 | 12.5% | certas 12.5% (2) | ERRADAS 0 | recusadas 14 | z mediana 12.7 |
| escala 1.001 (24/23.976) | drift | 16 | 12.5% | certas 12.5% (2) | ERRADAS 0 | recusadas 14 | z mediana 12.7 |
| escala 0.999 (23.976/24) | main | 16 | 12.5% | certas 12.5% (2) | ERRADAS 0 | recusadas 14 | z mediana 14.0 |
| escala 0.999 (23.976/24) | drift | 16 | 12.5% | certas 12.5% (2) | ERRADAS 0 | recusadas 14 | z mediana 14.0 |
| escala 25/23.976 | main | 16 | 18.8% | certas 12.5% (2) | ERRADAS 1 | recusadas 13 | z mediana 11.7 |
| escala 25/23.976 | drift | 16 | 18.8% | certas 12.5% (2) | ERRADAS 1 | recusadas 13 | z mediana 11.7 |
| escala 23.976/25 | main | 16 | 6.2% | certas 6.2% (1) | ERRADAS 0 | recusadas 15 | z mediana 11.1 |
| escala 23.976/25 | drift | 16 | 18.8% | certas 18.8% (3) | ERRADAS 0 | recusadas 13 | z mediana 11.1 |
| ja certa | main | 8 | 12.5% | intactas 8 | MEXIDAS 0 | - | z mediana 13.4 |
| ja certa | drift | 8 | 12.5% | intactas 8 | MEXIDAS 0 | - | z mediana 13.4 |
| outro titulo | main | 16 | 0.0% | recusou 16 | ACEITES FALSOS 0 | - | z mediana 4.8 |
| outro titulo | drift | 16 | 0.0% | recusou 16 | ACEITES FALSOS 0 | - | z mediana 4.8 |
| outro titulo (com escala) | main | 8 | 0.0% | recusou 8 | ACEITES FALSOS 0 | - | z mediana 4.6 |
| outro titulo (com escala) | drift | 8 | 0.0% | recusou 8 | ACEITES FALSOS 0 | - | z mediana 4.6 |

**novos**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 7 | 42.9% | certas 42.9% (3) | ERRADAS 0 | recusadas 4 | z mediana 9.5 |
| deslocamento +3 s | drift | 7 | 42.9% | certas 42.9% (3) | ERRADAS 0 | recusadas 4 | z mediana 9.5 |
| deslocamento -2 s | main | 7 | 42.9% | certas 28.6% (2) | ERRADAS 1 | recusadas 4 | z mediana 8.3 |
| deslocamento -2 s | drift | 7 | 42.9% | certas 28.6% (2) | ERRADAS 1 | recusadas 4 | z mediana 8.3 |
| deslocamento +7,5 s | main | 7 | 28.6% | certas 28.6% (2) | ERRADAS 0 | recusadas 5 | z mediana 7.9 |
| deslocamento +7,5 s | drift | 7 | 28.6% | certas 28.6% (2) | ERRADAS 0 | recusadas 5 | z mediana 7.9 |
| escala 1.001 (24/23.976) | main | 14 | 28.6% | certas 28.6% (4) | ERRADAS 0 | recusadas 10 | z mediana 8.3 |
| escala 1.001 (24/23.976) | drift | 14 | 28.6% | certas 28.6% (4) | ERRADAS 0 | recusadas 10 | z mediana 8.3 |
| escala 0.999 (23.976/24) | main | 14 | 28.6% | certas 28.6% (4) | ERRADAS 0 | recusadas 10 | z mediana 8.7 |
| escala 0.999 (23.976/24) | drift | 14 | 28.6% | certas 28.6% (4) | ERRADAS 0 | recusadas 10 | z mediana 8.7 |
| escala 25/23.976 | main | 14 | 14.3% | certas 14.3% (2) | ERRADAS 0 | recusadas 12 | z mediana 6.7 |
| escala 25/23.976 | drift | 14 | 21.4% | certas 21.4% (3) | ERRADAS 0 | recusadas 11 | z mediana 6.7 |
| escala 23.976/25 | main | 14 | 28.6% | certas 28.6% (4) | ERRADAS 0 | recusadas 10 | z mediana 7.5 |
| escala 23.976/25 | drift | 14 | 42.9% | certas 42.9% (6) | ERRADAS 0 | recusadas 8 | z mediana 7.5 |
| ja certa | main | 7 | 28.6% | intactas 7 | MEXIDAS 0 | - | z mediana 8.4 |
| ja certa | drift | 7 | 28.6% | intactas 7 | MEXIDAS 0 | - | z mediana 8.4 |
| outro titulo | main | 14 | 0.0% | recusou 14 | ACEITES FALSOS 0 | - | z mediana 5.0 |
| outro titulo | drift | 14 | 0.0% | recusou 14 | ACEITES FALSOS 0 | - | z mediana 5.0 |
| outro titulo (com escala) | main | 7 | 0.0% | recusou 7 | ACEITES FALSOS 0 | - | z mediana 4.5 |
| outro titulo (com escala) | drift | 7 | 0.0% | recusou 7 | ACEITES FALSOS 0 | - | z mediana 4.5 |

## 3. Cabeca a cabeca: main x deriva (mesma variante, mesmo titulo)

| familia | main | deriva | n |
|---|---|---|---|
| escala 23.976/25 | recusou | certa | 18 |
| escala 23.976/25 | recusou | ERRADA | 1 |
| escala 23.976/25 | certa | recusou | 1 |
| escala 25/23.976 | recusou | certa | 33 |
| escala 25/23.976 | certa | recusou | 3 |
| escala 25/23.976 | ERRADA | certa | 1 |

Iguais nos dois motores: 2387 de 2444 variantes.


**Regressoes da deriva (certa/intacta no main que passou a errada/mexida/recusada):** 5

- `randyridesalone` `g23.976/25|o-2500` (escala 23.976/25): main=recusou -> deriva=ERRADA; z=13.5 janelas=6 motivo= | [790s d=36782 ncc=0.76 2o=0.52 z=5.5 ok] [1150s d=51626 ncc=0.20 2o=0.15 z=2.3 -] [1760s d=76470 ncc=0.86 2o=0.46 z=4.9 ok] [355s d=20340 ncc=0.90 2o=0.82 z=2.1 ok] [2050s d=88126 ncc=0.83 2o=0.56 z=4.9 ok] [2770s d=107825 ncc=-0.03 2o=-0.04 z=4.9 -] deriva{deriva provada: escala 1.041667, resíduo 4
- `pontodeviragem5250` `g25/23.976|o+6200` (escala 25/23.976): main=certa -> deriva=recusou; z=17.5 janelas=10 motivo= | [200s d=-14843 ncc=0.28 2o=0.26 z=3.9 -] [1455s d=2193 ncc=0.54 2o=0.49 z=2.0 -] [2825s d=2097 ncc=0.21 2o=0.18 z=3.3 -] [1730s d=13099 ncc=0.16 2o=0.13 z=2.5 -] [3680s d=-10847 ncc=0.17 2o=0.14 z=2.3 -] [4400s d=8138 ncc=0.29 2o=0.27 z=2.4 -] [715s d=890 ncc=0.09 2o=0.08 z=2.3 -] [2480s d=7594 ncc=
- `sabotage_1936` `g25/23.976|o+1800` (escala 25/23.976): main=certa -> deriva=recusou; z=12.2 janelas=10 motivo=pico ambíguo; janelas discordam; | [990s d=14225 ncc=0.14 2o=0.11 z=2.7 -] [1420s d=12747 ncc=0.15 2o=0.13 z=1.4 -] [2830s d=-6209 ncc=0.18 2o=0.14 z=2.7 -] [440s d=-3474 ncc=0.23 2o=0.18 z=3.1 -] [1540s d=7064 ncc=0.20 2o=0.19 z=2.0 -] [2995s d=-8918 ncc=0.24 2o=0.24 z=1.8 -] [4255s d=9219 ncc=0.34 2o=0.33 z=1.5 -] [2250s d=14273 nc
- `moonrise_1948_mp_4` `g25/23.976|o-1300` (escala 25/23.976): main=certa -> deriva=recusou; z=11.9 janelas=6 motivo=janelas discordam; | [1320s d=-56601 ncc=0.59 2o=0.41 z=5.2 ok] [1980s d=-84915 ncc=0.69 2o=0.38 z=8.1 ok] [3155s d=-135367 ncc=0.26 2o=0.22 z=2.7 -] [870s d=-37328 ncc=0.31 2o=0.16 z=3.1 -] [4350s d=-186837 ncc=0.76 2o=0.72 z=3.8 -] [5110s d=-218492 ncc=0.75 2o=0.66 z=3.5 ok] deriva{deriva provada: escala 0.959040, res
- `moonrise_1948_mp_4` `g23.976/25|o-900` (escala 23.976/25): main=certa -> deriva=recusou; z=18.1 janelas=10 motivo=janelas discordam; | [1195s d=12486 ncc=0.25 2o=0.24 z=2.1 -] [1805s d=-8544 ncc=0.22 2o=0.18 z=2.6 -] [2895s d=-8375 ncc=0.21 2o=0.21 z=3.8 -] [780s d=-4404 ncc=0.21 2o=0.18 z=2.7 -] [1895s d=-7985 ncc=0.14 2o=0.13 z=2.1 -] [3985s d=13794 ncc=0.25 2o=0.22 z=1.6 -] [4715s d=-15000 ncc=0.27 2o=0.21 z=3.1 -] [3180s d=-679

## 4. Distribuicao do z (confianca do alinhador)

| motor | classe | n | z p50 | p90 | p99 | min |
|---|---|---|---|---|---|---|
| main | corrigida certa | 854 | 15.7 | 19.8 | 23.2 | 9.0 |
| main | ACEITE ERRADO | 33 | 14.8 | 15.9 | 16.5 | 9.0 |
| main | recusada (S) | 1394 | 10.2 | 18.0 | 23.6 | 0.0 |
| main | ja certa intacta | 163 | 16.4 | 21.5 | 25.7 | 4.9 |
| drift | corrigida certa | 902 | 15.5 | 19.7 | 23.2 | 6.4 |
| drift | ACEITE ERRADO | 33 | 14.5 | 15.9 | 16.5 | 9.2 |
| drift | recusada (S) | 1346 | 10.0 | 18.2 | 23.6 | 0.0 |
| drift | ja certa intacta | 163 | 16.4 | 21.5 | 25.7 | 4.9 |

## 5. Tempo de analise (CPU do runner do GitHub, 2 fios de VAD; NAO e o tempo no celular/TV)

| motor | janelas (media) | VAD p50 / p95 (s) | alinhador p50 / p95 (s) | total p50 / p95 (s) |
|---|---|---|---|---|
| main | 6.8 | 3.7 / 5.9 | 0.7 / 1.9 | 4.5 / 7.9 |
| drift | 6.9 | 3.8 / 6.2 | 0.7 / 2.0 | 4.5 / 8.2 |

## 6. As 20 razoes de recusa mais comuns (variantes S recusadas: deviam ter sido corrigidas)

Cada recusa pode ter varias clausulas (o motor junta os motivos); conta-se cada clausula.


**main** (906 variantes S recusadas)

| # | clausula | vezes | exemplos |
|---|---|---|---|
| 1 | janelas discordam | 643 | born_to_the_saddle/mais3 (z=8.1); born_to_the_saddle/menos2 (z=7.2); born_to_the_saddle/mais7.5 (z=7.6) |
| 2 | pico ambíguo | 206 | born_to_the_saddle/mais3 (z=8.1); born_to_the_saddle/menos2 (z=7.2); born_to_the_saddle/mais7.5 (z=7.6) |
| 3 | possível deriva: janelas discordam entre si | 196 | eyes_in_the_night/g1.001|o+1800 (z=16.2); cloudsovereurope/g1.001|o+6200 (z=13.7); cloudsovereurope/g1.001|o-900 (z=16.9) |
| 4 | escala fora das razões de fps | 194 | born_to_the_saddle/g25/23.976|o-4300 (z=7.3); born_to_the_saddle/g23.976/25|o-900 (z=8.0); born_to_the_saddle/g23.976/25|o-1300 (z=8.6) |
| 5 | ajuste inseguro: janelas fora de uma reta de atraso constante (N ms) | 174 | eyes_in_the_night/g1.001|o+1800 (z=16.2); cloudsovereurope/g1.001|o-900 (z=16.9); apob3453454355/mais7.5 (z=19.4) |
| 6 | ajuste contradiz as janelas (> N s) | 130 | born_to_the_saddle/g23.976/25|o-900 (z=8.0); born_to_the_saddle/g23.976/25|o-1300 (z=8.6); jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/mais3 (z=5.0) |
| 7 | possíveis cortes na legenda (só aviso) | 129 | eyes_in_the_night/g1.001|o+6200 (z=15.2); eyes_in_the_night/g25/23.976|o-2500 (z=12.8); valkaama/g25/23.976|o-900 (z=11.9) |
| 8 | z baixo | 37 | jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/mais3 (z=5.0); jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/menos2 (z=5.0); jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/mais7.5 (z=5.1) |
| 9 | escala N/N sem prova de deriva | 35 | born_to_the_saddle/g1.001|o+2600 (z=8.3); born_to_the_saddle/g0.999|o+6200 (z=8.1); cloudsovereurope/g1.001|o+6200 (z=13.7) |
| 10 | correlação fraca | 4 | jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/menos2 (z=5.0); jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/g23.976/25|o-4300 (z=6.1); themagicsword/mais3 (z=5.4) |
| 11 | z abaixo do limiar de aceitação (N) | 4 | jsc2023m000080_ariss_st_lucia_hamm_radio_event/menos2 (z=8.3); jsc2023m000080_ariss_st_lucia_hamm_radio_event/g23.976/25|o+4000 (z=7.5); jsc2023m000080_ariss_st_lucia_hamm_radio_event/g23.976/25|o-2500 (z=6.7) |

**drift** (858 variantes S recusadas)

| # | clausula | vezes | exemplos |
|---|---|---|---|
| 1 | janelas discordam | 602 | born_to_the_saddle/mais3 (z=8.1); born_to_the_saddle/menos2 (z=7.2); born_to_the_saddle/mais7.5 (z=7.6) |
| 2 | pico ambíguo | 209 | born_to_the_saddle/mais3 (z=8.1); born_to_the_saddle/menos2 (z=7.2); born_to_the_saddle/mais7.5 (z=7.6) |
| 3 | possível deriva: janelas discordam entre si | 208 | eyes_in_the_night/g1.001|o+1800 (z=16.2); cloudsovereurope/g1.001|o+6200 (z=13.7); cloudsovereurope/g1.001|o-900 (z=16.9) |
| 4 | escala fora das razões de fps | 201 | born_to_the_saddle/g25/23.976|o-4300 (z=7.3); born_to_the_saddle/g23.976/25|o-900 (z=8.0); born_to_the_saddle/g23.976/25|o-1300 (z=8.6) |
| 5 | ajuste inseguro: janelas fora de uma reta de atraso constante (N ms) | 174 | eyes_in_the_night/g1.001|o+1800 (z=16.2); cloudsovereurope/g1.001|o-900 (z=16.9); apob3453454355/mais7.5 (z=19.4) |
| 6 | ajuste contradiz as janelas (> N s) | 130 | born_to_the_saddle/g23.976/25|o-900 (z=8.0); born_to_the_saddle/g23.976/25|o-1300 (z=8.6); jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/mais3 (z=5.0) |
| 7 | possíveis cortes na legenda (só aviso) | 124 | eyes_in_the_night/g1.001|o+6200 (z=15.2); valkaama/g25/23.976|o-900 (z=11.9); breathless_1960/g1.001|o+2600 (z=19.9) |
| 8 | z baixo | 37 | jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/mais3 (z=5.0); jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/menos2 (z=5.0); jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/mais7.5 (z=5.1) |
| 9 | escala N/N sem prova de deriva | 35 | born_to_the_saddle/g1.001|o+2600 (z=8.3); born_to_the_saddle/g0.999|o+6200 (z=8.1); cloudsovereurope/g1.001|o+6200 (z=13.7) |
| 10 | correlação fraca | 4 | jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/menos2 (z=5.0); jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/g23.976/25|o-4300 (z=6.1); themagicsword/mais3 (z=5.4) |
| 11 | z abaixo do limiar de aceitação (N) | 1 | jsc2023m000080_ariss_st_lucia_hamm_radio_event/menos2 (z=8.3) |

**Motivo completo (todas as clausulas juntas) mais comum, so S recusadas**


main:

- 201 x janelas discordam;
- 129 x possíveis cortes na legenda (só aviso);
- 111 x ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;
- 69 x (sem motivo)
- 55 x escala fora das razões de fps; janelas discordam;
- 52 x pico ambíguo; janelas discordam;
- 44 x pico ambíguo; escala fora das razões de fps; janelas discordam;
- 39 x ajuste contradiz as janelas (> N s); ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;

drift:

- 154 x janelas discordam;
- 124 x possíveis cortes na legenda (só aviso);
- 111 x ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;
- 70 x (sem motivo)
- 57 x escala fora das razões de fps; janelas discordam;
- 47 x pico ambíguo; escala fora das razões de fps; janelas discordam;
- 44 x pico ambíguo; janelas discordam;
- 39 x ajuste contradiz as janelas (> N s); ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;

## 7. TODOS os aceites errados, aceites falsos e legendas certas mexidas


### main: 33 casos

| titulo | tipo | variante | classe | kind | escala | desl. aplicado (ms) | z | janelas conc. | erro max / p95 (ms) | motivo |
|---|---|---|---|---|---|---|---|---|---|---|
| 1951_the_prowler_el_merodeador_joseph_losey_vose (old, 1951) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7083 | 16.5 | 4/10 | 417 / 417 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3520 | 15.6 | 5/5 | 484 / 484 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g0.999|o+6200` | ERRADO | FIX | 1.00100 | -5719 | 15.4 | 5/5 | 487 / 487 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g1.001|o-1300` | ERRADO | FIX | 0.99900 | 1803 | 15.6 | 5/5 | 504 / 504 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g1.001|o-2500` | ERRADO | FIX | 0.99900 | 2996 | 15.2 | 5/5 | 499 / 499 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g23.976/25|o+1800` | ERRADO | FIX | 1.04271 | -1431 | 11.5 | 3/3 | 446 / 446 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g25/23.976|o-4300` | ERRADO | FIX | 0.95904 | 4610 | 13.0 | 6/6 | 486 / 486 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g25/23.976|o-900` | ERRADO | FIX | 0.95904 | 1352 | 14.3 | 6/6 | 489 / 489 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `menos2` | ERRADO | FIX | 1.00000 | 2480 | 14.5 | 5/5 | 480 / 480 |  |
| houseonbaremountain (old, 1962) | S | `g0.999|o+1800` | ERRADO | FIX | 1.00100 | -1283 | 13.4 | 3/5 | 519 / 519 |  |
| love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ (old-tv, None) | S | `g25/23.976|o+2600` | ERRADO | FIX | 0.96000 | -2995 | 9.8 | 3/3 | 995 / 903 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g0.999|o+1800` | ERRADO | FIX | 1.00100 | -1391 | 15.1 | 6/6 | 411 / 411 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3598 | 15.9 | 6/6 | 406 / 406 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g1.001|o-4300` | ERRADO | FIX | 0.99900 | 4713 | 15.0 | 6/6 | 417 / 417 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g1.001|o-900` | ERRADO | FIX | 0.99900 | 1309 | 15.1 | 6/6 | 410 / 410 |  |
| murder_by_invitation_1941 (old, 1941) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7096 | 15.1 | 6/6 | 404 / 404 |  |
| murder_by_invitation_1941 (old, 1941) | S | `menos2` | ERRADO | FIX | 1.00000 | 2404 | 15.4 | 6/6 | 404 / 404 |  |
| pit_and_the_pendulum (old, 1961) | S | `mais3` | ERRADO | FIX | 1.00000 | -2505 | 11.4 | 3/3 | 495 / 495 |  |
| sabrina_1954 (old, 1954) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3561 | 14.9 | 4/5 | 443 / 443 |  |
| the_gorgon_1964 (old, 1964) | S | `g25/23.976|o+1800` | ERRADO | FIX | 0.95904 | -1322 | 9.2 | 3/3 | 404 / 404 |  |
| the_housemaid_1960 (old, 1960) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3582 | 16.0 | 3/6 | 422 / 422 |  |
| the_mummys_hand (old, 1940) | S | `g1.001|o+4000` | ERRADO | FIX | 0.99900 | -3588 | 15.0 | 6/7 | 408 / 408 |  |
| the_mummys_hand (old, 1940) | S | `g25/23.976|o-1300` | ERRADO | FIX | 0.95904 | 1653 | 14.8 | 6/7 | 406 / 406 |  |
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

- **diary_of_a_madman_1963 / g0.999|o+4000 / ERRADO** diagnostico completo: `[450s d=-2887 ncc=0.67 2o=0.54 z=4.9 ok] [1695s d=-1852 ncc=0.77 2o=0.69 z=2.2 ok] [4715s d=1226 ncc=0.73 2o=0.57 z=5.2 ok] [2315s d=-1189 ncc=0.55 2o=0.44 z=4.4 ok] [3190s d=-285 ncc=0.67 2o=0.59 z=4.0 -] deriva{deriva provada: escala 1.001001, resíduo 134 ms (reta sem escala deixaria 2374 ms), deriva medida 4265 ms nas janelas e 5797 ms no filme, 4 janelas confiáveis; confiaveis=4 inclinacao=0.000976 provada=1}`

- **diary_of_a_madman_1963 / g0.999|o+6200 / ERRADO** diagnostico completo: `[455s d=-5106 ncc=0.68 2o=0.55 z=4.4 ok] [1695s d=-4052 ncc=0.77 2o=0.69 z=2.2 ok] [4730s d=-958 ncc=0.71 2o=0.56 z=4.3 ok] [2315s d=-3389 ncc=0.55 2o=0.44 z=4.4 ok] [3200s d=-2437 ncc=0.67 2o=0.57 z=4.5 ok] deriva{deriva provada: escala 1.001001, resíduo 108 ms (reta sem escala deixaria 2230 ms), deriva medida 4275 ms nas janelas e 5797 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=0.000984 provada=1}`

- **diary_of_a_madman_1963 / g1.001|o-1300 / ERRADO** diagnostico completo: `[455s d=1422 ncc=0.68 2o=0.55 z=4.3 ok] [1695s d=11 ncc=0.77 2o=0.68 z=2.2 ok] [4690s d=-2973 ncc=0.61 2o=0.39 z=5.8 ok] [2315s d=-628 ncc=0.56 2o=0.44 z=4.4 ok] [3095s d=-1300 ncc=0.58 2o=0.43 z=5.3 ok] deriva{deriva provada: escala 0.999000, resíduo 118 ms (reta sem escala deixaria 2361 ms), deriva medida 4239 ms nas janelas e 5803 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001026 provada=1}`

- **diary_of_a_madman_1963 / g1.001|o-2500 / ERRADO** diagnostico completo: `[445s d=2622 ncc=0.70 2o=0.59 z=4.1 ok] [1695s d=1211 ncc=0.77 2o=0.68 z=2.2 ok] [4730s d=-1794 ncc=0.71 2o=0.57 z=4.3 ok] [2315s d=572 ncc=0.56 2o=0.44 z=4.4 ok] [3190s d=-237 ncc=0.68 2o=0.59 z=4.1 ok] deriva{deriva provada: escala 0.999000, resíduo 113 ms (reta sem escala deixaria 2276 ms), deriva medida 4289 ms nas janelas e 5803 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001021 provada=1}`

- **diary_of_a_madman_1963 / g23.976/25|o+1800 / ERRADO** diagnostico completo: `[385s d=15000 ncc=0.38 2o=0.34 z=3.8 -] [1625s d=1960 ncc=0.15 2o=0.13 z=2.3 -] [4520s d=-12631 ncc=0.09 2o=0.06 z=2.9 -] [2225s d=-11075 ncc=0.22 2o=0.21 z=2.7 -] [2930s d=-11166 ncc=0.08 2o=0.08 z=2.0 -] [4815s d=-10007 ncc=0.04 2o=0.03 z=2.2 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: janelas discordam;); confiaveis=0 inclinacao=0.000000 provada=0}`

- **diary_of_a_madman_1963 / g25/23.976|o-4300 / ERRADO** diagnostico completo: `[470s d=-12254 ncc=0.29 2o=0.29 z=2.5 -] [1950s d=-11339 ncc=0.16 2o=0.12 z=2.3 -] [4865s d=5765 ncc=0.27 2o=0.25 z=1.8 -] [1770s d=-14692 ncc=0.18 2o=0.17 z=3.1 -] [3170s d=-7769 ncc=0.14 2o=0.12 z=1.9 -] [3910s d=-15000 ncc=0.17 2o=0.07 z=2.9 ok] deriva{menos de 2 janelas confiáveis (1 de 6); confiaveis=1 inclinacao=0.000000 provada=0}`

- **diary_of_a_madman_1963 / g25/23.976|o-900 / ERRADO** diagnostico completo: `[470s d=-14526 ncc=0.25 2o=0.22 z=2.8 -] [1955s d=11812 ncc=0.08 2o=0.08 z=2.0 -] [4870s d=5957 ncc=0.26 2o=0.26 z=1.9 -] [1775s d=-13123 ncc=0.03 2o=0.02 z=3.0 -] [3175s d=-11162 ncc=0.16 2o=0.11 z=2.2 -] [3915s d=-9748 ncc=0.04 2o=0.04 z=1.9 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiaveis=0 inclinacao=0.000000 provada=0}`

- **diary_of_a_madman_1963 / menos2 / ERRADO** diagnostico completo: `[445s d=2609 ncc=0.70 2o=0.59 z=4.1 ok] [1695s d=2431 ncc=0.77 2o=0.68 z=2.2 ok] [4725s d=2477 ncc=0.71 2o=0.57 z=4.2 ok] [2310s d=2417 ncc=0.51 2o=0.42 z=4.0 ok] [3195s d=2452 ncc=0.67 2o=0.58 z=4.4 ok] deriva{sem prova de deriva: resíduo da reta sem escala 129 ms; confiaveis=5 inclinacao=-0.000023 provada=0}`

- **houseonbaremountain / g0.999|o+1800 / ERRADO** diagnostico completo: `[175s d=-944 ncc=0.66 2o=0.55 z=4.5 ok] [535s d=-1492 ncc=0.48 2o=0.44 z=2.8 -] [1145s d=-251 ncc=0.41 2o=0.25 z=3.4 ok] [650s d=-1299 ncc=0.57 2o=0.23 z=6.8 ok] [1255s d=-126 ncc=0.73 2o=0.63 z=2.7 ok] [2270s d=1173 ncc=0.67 2o=0.56 z=2.3 ok] [2915s d=-10251 ncc=0.15 2o=0.10 z=2.5 -] [3200s d=-10251 ncc=0.00 2o=0.00 z=0.0 -] deriva{deriva provada: escala 1.001001, resíduo 160 ms (reta sem escala deixaria 1424 ms), deriva medida 2095 ms nas janelas e 3719 ms no filme, 4 janelas confiáveis (1 janela fora da reta descartada); confiaveis=5 inclinacao=0.001128 provada=1}`

- **love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ / g25/23.976|o+2600 / ERRADO** diagnostico completo: `[335s d=-8922 ncc=0.22 2o=0.19 z=2.2 -] [765s d=6485 ncc=0.15 2o=0.14 z=1.3 -] [1230s d=5283 ncc=0.21 2o=0.19 z=2.3 -] [205s d=-11871 ncc=0.47 2o=0.41 z=3.1 -] [575s d=-9113 ncc=0.40 2o=0.39 z=1.8 -] [1020s d=15000 ncc=0.28 2o=0.26 z=2.7 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: ); confiaveis=0 inclinacao=0.000000 provada=0}`

- **murder_by_invitation_1941 / g0.999|o+1800 / ERRADO** diagnostico completo: `[510s d=-860 ncc=0.65 2o=0.56 z=2.5 ok] [1655s d=440 ncc=0.43 2o=0.20 z=5.7 ok] [2965s d=1676 ncc=0.42 2o=0.25 z=5.3 ok] [685s d=-684 ncc=0.66 2o=0.47 z=4.8 ok] [2135s d=864 ncc=0.64 2o=0.53 z=4.1 ok] [3280s d=1809 ncc=0.73 2o=0.61 z=3.2 ok] deriva{deriva provada: escala 1.001001, resíduo 130 ms (reta sem escala deixaria 1356 ms), deriva medida 2770 ms nas janelas e 3888 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=0.000985 provada=1}`

- **murder_by_invitation_1941 / g0.999|o+4000 / ERRADO** diagnostico completo: `[515s d=-3068 ncc=0.65 2o=0.57 z=2.4 ok] [1680s d=-1732 ncc=0.47 2o=0.22 z=4.8 ok] [2965s d=-524 ncc=0.42 2o=0.25 z=5.3 ok] [685s d=-2884 ncc=0.66 2o=0.47 z=4.8 ok] [2130s d=-1364 ncc=0.64 2o=0.52 z=3.8 ok] [3280s d=-391 ncc=0.73 2o=0.61 z=3.2 ok] deriva{deriva provada: escala 1.001001, resíduo 137 ms (reta sem escala deixaria 1362 ms), deriva medida 2765 ms nas janelas e 3888 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=0.000986 provada=1}`

- **murder_by_invitation_1941 / g1.001|o-4300 / ERRADO** diagnostico completo: `[500s d=4127 ncc=0.58 2o=0.52 z=2.3 -] [1675s d=3126 ncc=0.46 2o=0.23 z=5.1 ok] [2965s d=1750 ncc=0.41 2o=0.24 z=5.4 ok] [685s d=3955 ncc=0.66 2o=0.47 z=5.0 ok] [2135s d=2598 ncc=0.64 2o=0.52 z=4.2 ok] [3280s d=1256 ncc=0.73 2o=0.61 z=3.1 ok] deriva{deriva provada: escala 0.999000, resíduo 134 ms (reta sem escala deixaria 1426 ms), deriva medida 2598 ms nas janelas e 3892 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001036 provada=1}`

- **murder_by_invitation_1941 / g1.001|o-900 / ERRADO** diagnostico completo: `[505s d=747 ncc=0.61 2o=0.53 z=2.4 -] [1670s d=-274 ncc=0.42 2o=0.22 z=4.9 ok] [2970s d=-1685 ncc=0.46 2o=0.25 z=5.7 ok] [685s d=555 ncc=0.66 2o=0.47 z=5.0 ok] [2135s d=-802 ncc=0.64 2o=0.52 z=4.2 ok] [3285s d=-2158 ncc=0.71 2o=0.59 z=3.3 ok] deriva{deriva provada: escala 0.999000, resíduo 135 ms (reta sem escala deixaria 1443 ms), deriva medida 2603 ms nas janelas e 3892 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001039 provada=1}`

- **murder_by_invitation_1941 / mais7.5 / ERRADO** diagnostico completo: `[515s d=-7131 ncc=0.65 2o=0.56 z=2.5 ok] [1685s d=-6948 ncc=0.42 2o=0.19 z=4.4 ok] [2975s d=-7105 ncc=0.50 2o=0.27 z=6.7 ok] [685s d=-7112 ncc=0.66 2o=0.46 z=5.0 ok] [2140s d=-6992 ncc=0.65 2o=0.54 z=3.9 ok] [3290s d=-7227 ncc=0.72 2o=0.59 z=3.3 ok] deriva{sem prova de deriva: resíduo da reta sem escala 148 ms; confiaveis=6 inclinacao=-0.000021 provada=0}`

- **murder_by_invitation_1941 / menos2 / ERRADO** diagnostico completo: `[505s d=2395 ncc=0.62 2o=0.53 z=2.4 ok] [1655s d=2533 ncc=0.43 2o=0.20 z=5.8 ok] [2965s d=2463 ncc=0.42 2o=0.24 z=5.4 ok] [685s d=2388 ncc=0.66 2o=0.46 z=5.0 ok] [2415s d=2434 ncc=0.83 2o=0.78 z=2.7 -] [3280s d=2284 ncc=0.73 2o=0.61 z=3.2 ok] deriva{sem prova de deriva: resíduo da reta sem escala 137 ms; confiaveis=5 inclinacao=-0.000023 provada=0}`

- **pit_and_the_pendulum / mais3 / ERRADO** diagnostico completo: `[830s d=-2585 ncc=0.63 2o=0.47 z=3.7 ok] [1220s d=-2368 ncc=0.60 2o=0.45 z=5.0 ok] [2250s d=-2556 ncc=0.59 2o=0.55 z=3.2 -] [2510s d=-2548 ncc=0.59 2o=0.54 z=3.0 -] [3825s d=-2684 ncc=0.59 2o=0.54 z=3.4 -] [3965s d=-2759 ncc=0.65 2o=0.58 z=3.5 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: janelas discordam;); confiaveis=0 inclinacao=0.000000 provada=0}`

- **sabrina_1954 / g0.999|o+4000 / ERRADO** diagnostico completo: `[1760s d=-2198 ncc=0.51 2o=0.30 z=6.0 ok] [3335s d=-365 ncc=0.79 2o=0.71 z=3.0 -] [4520s d=991 ncc=0.42 2o=0.31 z=3.8 ok] [1125s d=-2510 ncc=0.40 2o=0.33 z=4.7 -] [3860s d=294 ncc=0.53 2o=0.44 z=3.6 ok] [6260s d=2802 ncc=0.67 2o=0.56 z=4.3 ok] deriva{deriva provada: escala 1.001001, resíduo 54 ms (reta sem escala deixaria 2821 ms), deriva medida 2400 ms nas janelas e 6815 ms no filme, 3 janelas confiáveis (1 janela fora da reta descartada); confiaveis=4 inclinacao=0.001108 provada=1}`

- **the_gorgon_1964 / g25/23.976|o+1800 / ERRADO** diagnostico completo: `[885s d=-13443 ncc=0.17 2o=0.14 z=2.5 -] [3495s d=6131 ncc=0.27 2o=0.23 z=2.2 -] [4525s d=-10357 ncc=0.24 2o=0.22 z=2.7 -] [720s d=14735 ncc=0.25 2o=0.17 z=2.6 -] [2080s d=14680 ncc=0.25 2o=0.24 z=2.2 -] [2560s d=10862 ncc=0.11 2o=0.11 z=2.5 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: ); confiaveis=0 inclinacao=0.000000 provada=0}`

- **the_housemaid_1960 / g0.999|o+4000 / ERRADO** diagnostico completo: `[640s d=-2963 ncc=0.59 2o=0.49 z=3.5 ok] [1695s d=-2024 ncc=0.53 2o=0.46 z=3.3 -] [2135s d=-1645 ncc=0.41 2o=0.37 z=2.2 -] [2390s d=-1450 ncc=0.58 2o=0.51 z=3.5 -] [3535s d=-68 ncc=0.66 2o=0.49 z=5.4 ok] [4665s d=740 ncc=0.62 2o=0.52 z=3.4 ok] [5695s d=2325 ncc=0.53 2o=0.39 z=3.9 ok] deriva{deriva provada: escala 1.001001, resíduo 163 ms (reta sem escala deixaria 2920 ms), deriva medida 5055 ms nas janelas e 6664 ms no filme, 3 janelas confiáveis (1 janela fora da reta descartada); confiaveis=4 inclinacao=0.001005 provada=1}`

- **the_mummys_hand / g1.001|o+4000 / ERRADO** diagnostico completo: `[785s d=-4521 ncc=0.59 2o=0.48 z=4.5 ok] [1315s d=-4813 ncc=0.32 2o=0.20 z=5.7 ok] [1825s d=-5564 ncc=0.55 2o=0.51 z=2.9 -] [640s d=-3748 ncc=0.71 2o=0.69 z=1.8 -] [1990s d=-5673 ncc=0.68 2o=0.60 z=3.5 -] [3045s d=-6669 ncc=0.78 2o=0.62 z=3.4 ok] [3645s d=-7360 ncc=0.83 2o=0.80 z=2.9 -] deriva{deriva provada: escala 0.999000, resíduo 140 ms (reta sem escala deixaria 1106 ms), deriva medida 2262 ms nas janelas e 4013 ms no filme, 3 janelas confiáveis; confiaveis=3 inclinacao=-0.000972 provada=1}`

- **the_mummys_hand / g25/23.976|o-1300 / ERRADO** diagnostico completo: `[800s d=14617 ncc=0.24 2o=0.23 z=2.8 -] [1380s d=-9932 ncc=0.23 2o=0.17 z=2.3 -] [1970s d=-10388 ncc=0.07 2o=0.05 z=3.0 -] [640s d=-13676 ncc=0.47 2o=0.46 z=2.0 -] [2080s d=-14786 ncc=0.23 2o=0.23 z=2.7 -] [2630s d=-14769 ncc=0.18 2o=0.14 z=3.0 -] [3355s d=14688 ncc=-0.02 2o=-0.05 z=2.8 -] deriva{menos de 2 janelas confiáveis (0 de 7); confiaveis=0 inclinacao=0.000000 provada=0}`

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

### drift: 33 casos

| titulo | tipo | variante | classe | kind | escala | desl. aplicado (ms) | z | janelas conc. | erro max / p95 (ms) | motivo |
|---|---|---|---|---|---|---|---|---|---|---|
| 1951_the_prowler_el_merodeador_joseph_losey_vose (old, 1951) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7083 | 16.5 | 4/10 | 417 / 417 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3520 | 15.6 | 5/5 | 484 / 484 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g0.999|o+6200` | ERRADO | FIX | 1.00100 | -5719 | 15.4 | 5/5 | 487 / 487 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g1.001|o-1300` | ERRADO | FIX | 0.99900 | 1803 | 15.6 | 5/5 | 504 / 504 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g1.001|o-2500` | ERRADO | FIX | 0.99900 | 2996 | 15.2 | 5/5 | 499 / 499 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g23.976/25|o+1800` | ERRADO | FIX | 1.04271 | -1431 | 11.5 | 3/3 | 446 / 446 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g25/23.976|o-4300` | ERRADO | FIX | 0.95904 | 4592 | 13.0 | 6/6 | 468 / 468 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g25/23.976|o-900` | ERRADO | FIX | 0.95904 | 1317 | 14.3 | 6/6 | 454 / 454 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `menos2` | ERRADO | FIX | 1.00000 | 2480 | 14.5 | 5/5 | 480 / 480 |  |
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
| sabrina_1954 (old, 1954) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3561 | 14.9 | 4/5 | 443 / 443 |  |
| the_gorgon_1964 (old, 1964) | S | `g25/23.976|o+1800` | ERRADO | FIX | 0.95904 | -1322 | 9.2 | 3/3 | 404 / 404 |  |
| the_housemaid_1960 (old, 1960) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3582 | 16.0 | 3/6 | 422 / 422 |  |
| the_mummys_hand (old, 1940) | S | `g1.001|o+4000` | ERRADO | FIX | 0.99900 | -3588 | 15.0 | 6/7 | 408 / 408 |  |
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

- **diary_of_a_madman_1963 / g0.999|o+4000 / ERRADO** diagnostico completo: `[450s d=-2887 ncc=0.67 2o=0.54 z=4.9 ok] [1695s d=-1852 ncc=0.77 2o=0.69 z=2.2 ok] [4715s d=1226 ncc=0.73 2o=0.57 z=5.2 ok] [2315s d=-1189 ncc=0.55 2o=0.44 z=4.4 ok] [3190s d=-285 ncc=0.67 2o=0.59 z=4.0 -] deriva{deriva provada: escala 1.001001, resíduo 134 ms (reta sem escala deixaria 2374 ms), deriva medida 4265 ms nas janelas e 5797 ms no filme, 4 janelas confiáveis; confiaveis=4 inclinacao=0.000976 provada=1}`

- **diary_of_a_madman_1963 / g0.999|o+6200 / ERRADO** diagnostico completo: `[455s d=-5106 ncc=0.68 2o=0.55 z=4.4 ok] [1695s d=-4052 ncc=0.77 2o=0.69 z=2.2 ok] [4730s d=-958 ncc=0.71 2o=0.56 z=4.3 ok] [2315s d=-3389 ncc=0.55 2o=0.44 z=4.4 ok] [3200s d=-2437 ncc=0.67 2o=0.57 z=4.5 ok] deriva{deriva provada: escala 1.001001, resíduo 108 ms (reta sem escala deixaria 2230 ms), deriva medida 4275 ms nas janelas e 5797 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=0.000984 provada=1}`

- **diary_of_a_madman_1963 / g1.001|o-1300 / ERRADO** diagnostico completo: `[455s d=1422 ncc=0.68 2o=0.55 z=4.3 ok] [1695s d=11 ncc=0.77 2o=0.68 z=2.2 ok] [4690s d=-2973 ncc=0.61 2o=0.39 z=5.8 ok] [2315s d=-628 ncc=0.56 2o=0.44 z=4.4 ok] [3095s d=-1300 ncc=0.58 2o=0.43 z=5.3 ok] deriva{deriva provada: escala 0.999000, resíduo 118 ms (reta sem escala deixaria 2361 ms), deriva medida 4239 ms nas janelas e 5803 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001026 provada=1}`

- **diary_of_a_madman_1963 / g1.001|o-2500 / ERRADO** diagnostico completo: `[445s d=2622 ncc=0.70 2o=0.59 z=4.1 ok] [1695s d=1211 ncc=0.77 2o=0.68 z=2.2 ok] [4730s d=-1794 ncc=0.71 2o=0.57 z=4.3 ok] [2315s d=572 ncc=0.56 2o=0.44 z=4.4 ok] [3190s d=-237 ncc=0.68 2o=0.59 z=4.1 ok] deriva{deriva provada: escala 0.999000, resíduo 113 ms (reta sem escala deixaria 2276 ms), deriva medida 4289 ms nas janelas e 5803 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001021 provada=1}`

- **diary_of_a_madman_1963 / g23.976/25|o+1800 / ERRADO** diagnostico completo: `[385s d=15000 ncc=0.38 2o=0.34 z=3.8 -] [1625s d=1960 ncc=0.15 2o=0.13 z=2.3 -] [4520s d=-12631 ncc=0.09 2o=0.06 z=2.9 -] [2225s d=-11075 ncc=0.22 2o=0.21 z=2.7 -] [2930s d=-11166 ncc=0.08 2o=0.08 z=2.0 -] [4815s d=-10007 ncc=0.04 2o=0.03 z=2.2 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: janelas discordam;); confiaveis=0 inclinacao=0.000000 provada=0}`

- **diary_of_a_madman_1963 / g25/23.976|o-4300 / ERRADO** diagnostico completo: `[470s d=-17174 ncc=0.68 2o=0.55 z=5.1 ok] [1950s d=-80461 ncc=0.65 2o=0.49 z=5.5 ok] [4865s d=-204932 ncc=0.79 2o=0.70 z=3.7 ok] [1770s d=-72787 ncc=0.56 2o=0.43 z=3.5 ok] [3170s d=-132439 ncc=0.62 2o=0.55 z=5.2 ok] [3910s d=-164010 ncc=0.64 2o=0.59 z=4.0 -] deriva{deriva provada: escala 0.959040, resíduo 84 ms (reta sem escala deixaria 99269 ms), deriva medida 187708 ms nas janelas e 247588 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.042710 provada=1}`

- **diary_of_a_madman_1963 / g25/23.976|o-900 / ERRADO** diagnostico completo: `[470s d=-20574 ncc=0.68 2o=0.55 z=5.1 ok] [1955s d=-84052 ncc=0.65 2o=0.50 z=5.6 ok] [4870s d=-208536 ncc=0.78 2o=0.70 z=3.5 ok] [1775s d=-76412 ncc=0.63 2o=0.50 z=4.3 ok] [3175s d=-136072 ncc=0.65 2o=0.58 z=4.8 -] [3915s d=-167614 ncc=0.71 2o=0.65 z=4.0 -] deriva{deriva provada: escala 0.959040, resíduo 54 ms (reta sem escala deixaria 106438 ms), deriva medida 187921 ms nas janelas e 247588 ms no filme, 4 janelas confiáveis; confiaveis=4 inclinacao=-0.042713 provada=1}`

- **diary_of_a_madman_1963 / menos2 / ERRADO** diagnostico completo: `[445s d=2609 ncc=0.70 2o=0.59 z=4.1 ok] [1695s d=2431 ncc=0.77 2o=0.68 z=2.2 ok] [4725s d=2477 ncc=0.71 2o=0.57 z=4.2 ok] [2310s d=2417 ncc=0.51 2o=0.42 z=4.0 ok] [3195s d=2452 ncc=0.67 2o=0.58 z=4.4 ok] deriva{sem prova de deriva: resíduo da reta sem escala 129 ms; confiaveis=5 inclinacao=-0.000023 provada=0}`

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

- **sabrina_1954 / g0.999|o+4000 / ERRADO** diagnostico completo: `[1760s d=-2198 ncc=0.51 2o=0.30 z=6.0 ok] [3335s d=-365 ncc=0.79 2o=0.71 z=3.0 -] [4520s d=991 ncc=0.42 2o=0.31 z=3.8 ok] [1125s d=-2510 ncc=0.40 2o=0.33 z=4.7 -] [3860s d=294 ncc=0.53 2o=0.44 z=3.6 ok] [6260s d=2802 ncc=0.67 2o=0.56 z=4.3 ok] deriva{deriva provada: escala 1.001001, resíduo 54 ms (reta sem escala deixaria 2821 ms), deriva medida 2400 ms nas janelas e 6815 ms no filme, 3 janelas confiáveis (1 janela fora da reta descartada); confiaveis=4 inclinacao=0.001108 provada=1}`

- **the_gorgon_1964 / g25/23.976|o+1800 / ERRADO** diagnostico completo: `[885s d=-13443 ncc=0.17 2o=0.14 z=2.5 -] [3495s d=6131 ncc=0.27 2o=0.23 z=2.2 -] [4525s d=-10357 ncc=0.24 2o=0.22 z=2.7 -] [720s d=14735 ncc=0.25 2o=0.17 z=2.6 -] [2080s d=14680 ncc=0.25 2o=0.24 z=2.2 -] [2560s d=10862 ncc=0.11 2o=0.11 z=2.5 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: ); confiaveis=0 inclinacao=0.000000 provada=0}`

- **the_housemaid_1960 / g0.999|o+4000 / ERRADO** diagnostico completo: `[640s d=-2963 ncc=0.59 2o=0.49 z=3.5 ok] [1695s d=-2024 ncc=0.53 2o=0.46 z=3.3 -] [2135s d=-1645 ncc=0.41 2o=0.37 z=2.2 -] [2390s d=-1450 ncc=0.58 2o=0.51 z=3.5 -] [3535s d=-68 ncc=0.66 2o=0.49 z=5.4 ok] [4665s d=740 ncc=0.62 2o=0.52 z=3.4 ok] [5695s d=2325 ncc=0.53 2o=0.39 z=3.9 ok] deriva{deriva provada: escala 1.001001, resíduo 163 ms (reta sem escala deixaria 2920 ms), deriva medida 5055 ms nas janelas e 6664 ms no filme, 3 janelas confiáveis (1 janela fora da reta descartada); confiaveis=4 inclinacao=0.001005 provada=1}`

- **the_mummys_hand / g1.001|o+4000 / ERRADO** diagnostico completo: `[785s d=-4521 ncc=0.59 2o=0.48 z=4.5 ok] [1315s d=-4813 ncc=0.32 2o=0.20 z=5.7 ok] [1825s d=-5564 ncc=0.55 2o=0.51 z=2.9 -] [640s d=-3748 ncc=0.71 2o=0.69 z=1.8 -] [1990s d=-5673 ncc=0.68 2o=0.60 z=3.5 -] [3045s d=-6669 ncc=0.78 2o=0.62 z=3.4 ok] [3645s d=-7360 ncc=0.83 2o=0.80 z=2.9 -] deriva{deriva provada: escala 0.999000, resíduo 140 ms (reta sem escala deixaria 1106 ms), deriva medida 2262 ms nas janelas e 4013 ms no filme, 3 janelas confiáveis; confiaveis=3 inclinacao=-0.000972 provada=1}`

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
| antigo (TV) | 88 | 11.4% | 87.5% | 88 | 13.6% | 85.2% |
| antigo (filme) | 1628 | 50.6% | 47.5% | 1628 | 53.2% | 44.9% |
| novo | 77 | 27.3% | 71.4% | 77 | 31.2% | 67.5% |

**por decada**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 1920s | 11 | 100.0% | 0.0% | 11 | 100.0% | 0.0% |
| 1930s | 286 | 61.2% | 38.8% | 286 | 62.9% | 36.7% |
| 1940s | 506 | 52.0% | 45.7% | 506 | 54.5% | 43.3% |
| 1950s | 517 | 51.3% | 48.2% | 517 | 54.4% | 45.1% |
| 1960s | 319 | 31.0% | 63.9% | 319 | 34.2% | 60.8% |
| 1970s | 22 | 86.4% | 13.6% | 22 | 90.9% | 9.1% |
| 2010s | 33 | 45.5% | 51.5% | 33 | 45.5% | 51.5% |
| 2020s | 44 | 13.6% | 86.4% | 44 | 20.5% | 79.5% |
| sem ano | 55 | 1.8% | 96.4% | 55 | 1.8% | 96.4% |

**por densidade de falas da legenda**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 4-8 falas/min | 121 | 28.1% | 70.2% | 121 | 29.8% | 67.8% |
| 8-12 falas/min | 539 | 43.2% | 52.9% | 539 | 47.1% | 49.2% |
| >= 12 falas/min | 1133 | 51.8% | 47.3% | 1133 | 54.0% | 45.1% |

**por duracao**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 30-75 min | 649 | 55.5% | 43.0% | 649 | 58.7% | 39.8% |
| 75-120 min | 968 | 48.2% | 49.5% | 968 | 50.4% | 47.3% |
| < 30 min | 143 | 11.2% | 88.1% | 143 | 14.7% | 84.6% |
| > 120 min | 33 | 33.3% | 66.7% | 33 | 36.4% | 63.6% |

**idioma da legenda de referencia**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| PT (filtro do app) | 1001 | 45.0% | 53.0% | 1001 | 47.9% | 50.1% |
| outro idioma | 792 | 51.0% | 47.3% | 792 | 53.4% | 44.9% |

**abertura silenciosa**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| sem silencio longo | 1793 | 47.6% | 50.5% | 1793 | 50.3% | 47.9% |

**nivel da referencia**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| A (so desloca) | 1122 | 62.9% | 34.7% | 1122 | 65.6% | 32.1% |
| B (alass por partes) | 671 | 22.1% | 77.0% | 671 | 24.7% | 74.2% |

**canais de audio da faixa 1**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 1 canais | 33 | 48.5% | 51.5% | 33 | 51.5% | 48.5% |
| 2 canais | 1760 | 47.6% | 50.5% | 1760 | 50.3% | 47.8% |

## 8. Bugs e comportamentos estranhos achados na execucao

- Excecoes / timeouts do alinhador: **0** variantes (de 4888).
- NaN/Infinity em campos do resultado: **0** variantes.
- Variantes que o app nem analisaria (guarda: poucas falas ou video < 8 min): 0
- Processos Java dos motores que terminaram com erro: **0**

### Parser de legendas do app (SubtitleText.decode + Cue.parseSrt) sobre a referencia reescrita em varios formatos

| formato | titulos | ok | tempos certos mas texto diferente | exemplo do problema |
|---|---|---|---|---|
| srt-utf8 | 163 | 163 | 0 |  |
| srt-utf8-bom | 163 | 163 | 0 |  |
| srt-crlf | 163 | 163 | 0 |  |
| srt-cr-only | 163 | 163 | 0 |  |
| srt-cp1252 | 163 | 157 | 2 | oy7743534123: tempos certos mas o texto (acentos) saiu diferente; robin_hood_01_the_coming_of_robin_hood_robin_hood_01_the_com: falas 313 em vez de 318 |
| srt-cp1252-crlf | 163 | 157 | 2 | oy7743534123: tempos certos mas o texto (acentos) saiu diferente; robin_hood_01_the_coming_of_robin_hood_robin_hood_01_the_com: falas 313 em vez de 318 |
| srt-utf16le-bom | 163 | 163 | 0 |  |
| srt-utf16be-bom | 163 | 163 | 0 |  |
| srt-utf16le-sem-bom | 163 | 163 | 0 |  |
| srt-ponto-no-ms | 163 | 163 | 0 |  |
| srt-ms-2-digitos | 163 | 163 | 0 |  |
| srt-ms-1-digito | 163 | 163 | 0 |  |
| srt-hora-1-digito | 163 | 163 | 0 |  |
| srt-sem-numeros | 163 | 163 | 0 |  |
| srt-tags-html-an8 | 163 | 163 | 0 |  |
| srt-mojibake | 163 | 163 | 0 |  |
| ass | 163 | 163 | 0 |  |
| ssa-marked | 163 | 163 | 0 |  |
| ass-utf8-bom | 163 | 163 | 0 |  |
| vtt | 163 | 163 | 0 |  |
| vtt-ids-mm:ss | 163 | 163 | 0 |  |
| srt-gzip | 163 | 163 | 0 |  |
| srt-zip | 163 | 163 | 0 |  |
| microdvd(nao suportado) | 163 | 7 | 0 | wrong_road: 182 falas com tempo/classificacao diferente (desvio max 21 ms); beginningoftheend_201509: 207 falas com tempo/classificacao diferente (desvio max 21 ms) |

### Legendas reais baixadas (1062 arquivos de 483 titulos) e o parser do app

- decode do app devolveu null (descarta): 2; destes, o python leu >= 100 falas (legenda valida que o app descartaria): 0
- numero de falas do app diferente do python em >3%: 126
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
- legendas com falas fora de ordem no arquivo: 13; com sobreposicao > 200 ms em mais de 5 falas: 9; com fim <= inicio em mais de 3 falas: 3; mojibake que sobrou: 1; VTT: 130
  - fora de ordem: 15_un_poing_final `15_Un_poing_final.autogenerated.vtt` (12 falas fora de ordem)
  - fora de ordem: darkmirrorcrime505 `5660460` (2 falas fora de ordem)
  - fora de ordem: meet_john_doe_ipod `3613424` (7 falas fora de ordem)
  - fora de ordem: maniac_202107 `7334013` (63 falas fora de ordem)
  - fora de ordem: beverly_hillbillies_ep04_the_clampetts_meet_mrs_drysdale_bh0 `BH04_The_Clampetts_Meet_Mrs_Drysdale_512kb.autogenerated.vtt` (1 falas fora de ordem)
  - fora de ordem: 14_au_fil_de_l_histoire `14_Au_fil_de_l_histoire.autogenerated.vtt` (9 falas fora de ordem)
  - fora de ordem: 1943_shadow_of_a_doubt_la_sombra_de_una_duda_alfred_hitchcoc `1943 - Shadow of a Doubt - La sombra de una duda - Alfred Hitchcock - VOSE.autogenerated.vtt` (3 falas fora de ordem)
  - fora de ordem: house_of_usher_1960 `5828007` (26 falas fora de ordem)
- idioma das legendas decodificadas segundo o filtro do app (looksPortuguese): nao-PT (eng)=662, PT (parser do app)=304, nao-PT (spa)=60, nao-PT (und)=34

### Casos especiais de midia

- titulos com 2+ faixas de audio: 0; com 5.1 (6+ canais) na faixa 1: 0; filmes de mais de 3 h: 0; com abertura silenciosa >= 60 s: 0
