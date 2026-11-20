# Bancada dos 300 titulos: o motor de sincronia de legendas pelo audio

Medido com o laco real do app (guardas, plano de janelas, VAD Silero em Java puro, `AudioAligner` e a regra de decisao `fill`), sem alterar o motor.
Motores: **main** = `origin/main` (3 janelas densas, uma resolucao); **drift** = ramo `run-deriva` (3 densas + 6 espalhadas + ate 2 rodadas extras, prova de deriva).

## 1. Titulos usados

Titulos tentados: **441**. Com resultado valido (referencia nivel A/B): **163** (antigos filmes 148, antigos TV 8, novos 7).
Proporcao antigos/novos: 96% / 4%.


Destino de cada titulo tentado:

| destino | titulos |
|---|---|
| referencia ruim | 269 |
| ok | 163 |
| sem legenda utilizavel | 5 |
| nenhuma legenda alinhou | 4 |

Qualidade da referencia (legenda alinhada ao audio pelo alass; 2o oraculo = ffsubsync sobre a referencia): nivel A = desvio p90 do alass <= 300 ms e ffsubsync <= 300 ms; B = p90 ate 1000 ms (ampliado); X = excluida.
Referencias por nivel entre os titulos que chegaram a ter uma: A=101, B=62, X=269.

| origem da legenda de referencia | titulos |
|---|---|
| opensubtitles - PT (parser do app) | 92 |
| opensubtitles - outro idioma (eng) | 41 |
| ia-item - outro idioma (eng) | 20 |
| opensubtitles - outro idioma (spa) | 5 |
| opensubtitles - outro idioma (und) | 5 |

| decada | titulos |
|---|---|
| 1920s | 1 |
| 1930s | 23 |
| 1940s | 47 |
| 1950s | 46 |
| 1960s | 32 |
| 1970s | 2 |
| 2010s | 3 |
| 2020s | 4 |

| fonte da midia / licenca declarada | titulos |
|---|---|
| sem licenca declarada (colecao de dominio publico do IA) /  | 73 |
| http://creativecommons.org/licenses/publicdomain/ /  | 47 |
| http://creativecommons.org/publicdomain/mark/1.0/ /  | 14 |
| https://creativecommons.org/licenses/by-nc-nd/4.0/ /  | 13 |
| https://creativecommons.org/publicdomain/mark/1.0/ /  | 11 |
| https://creativecommons.org/publicdomain/zero/1.0/ /  | 2 |
| http://creativecommons.org/publicdomain/zero/1.0/ /  | 1 |
| http://creativecommons.org/licenses/by-nc-sa/4.0/ /  | 1 |
| https://creativecommons.org/licenses/by-sa/4.0/ /  | 1 |

## 2. Numeros principais (todas as variantes, por tipo e por grupo)

Definicoes: **aceita** = o app aplicaria (FIX) ou diria "ja esta certa" (OK); **certa** = aceita e erro p95 <= 400 ms e max <= 800 ms em todas as falas (60 s .. fim-60 s) contra a referencia; **aceite errado** = aceita mas fora disso; **aceite falso** = aceitou a legenda de OUTRO titulo; **certa mexida** = a legenda ja certa recebeu um ajuste (FIX).

| grupo | motor | variantes S (devem ser corrigidas) | aceitas | certas | ACEITES ERRADOS | recusadas | legenda ja certa: mexidas | outro titulo: ACEITES FALSOS |
|---|---|---|---|---|---|---|---|---|
| todos | main | 1793 | 28.3% | 26.7% | 29 | 71.7% | 0/163 | 0/489 |
| todos | drift | 1793 | 45.7% | 44.3% | 25 | 54.3% | 0/163 | 0/489 |
| antigos (filmes) | main | 1628 | 29.7% | 28.3% | 23 | 70.3% | 0/148 | 0/444 |
| antigos (filmes) | drift | 1628 | 48.6% | 47.2% | 23 | 51.4% | 0/148 | 0/444 |
| antigos (TV) | main | 88 | 13.6% | 9.1% | 4 | 86.4% | 0/8 | 0/24 |
| antigos (TV) | drift | 88 | 12.5% | 11.4% | 1 | 87.5% | 0/8 | 0/24 |
| novos | main | 77 | 16.9% | 14.3% | 2 | 83.1% | 0/7 | 0/21 |
| novos | drift | 77 | 23.4% | 22.1% | 1 | 76.6% | 0/7 | 0/21 |

### Por familia de variante


**todos**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 163 | 52.8% | certas 49.7% (81) | ERRADAS 5 | recusadas 77 | z mediana 11.4 |
| deslocamento +3 s | drift | 163 | 50.9% | certas 50.3% (82) | ERRADAS 1 | recusadas 80 | z mediana 15.7 |
| deslocamento -2 s | main | 163 | 51.5% | certas 49.1% (80) | ERRADAS 4 | recusadas 79 | z mediana 11.0 |
| deslocamento -2 s | drift | 163 | 52.1% | certas 50.3% (82) | ERRADAS 3 | recusadas 78 | z mediana 15.5 |
| deslocamento +7,5 s | main | 163 | 50.3% | certas 47.9% (78) | ERRADAS 4 | recusadas 81 | z mediana 11.3 |
| deslocamento +7,5 s | drift | 163 | 53.4% | certas 52.1% (85) | ERRADAS 2 | recusadas 76 | z mediana 15.6 |
| escala 1.001 (24/23.976) | main | 326 | 0.3% | certas 0.0% (0) | ERRADAS 1 | recusadas 325 | z mediana 11.3 |
| escala 1.001 (24/23.976) | drift | 326 | 47.5% | certas 45.7% (149) | ERRADAS 6 | recusadas 171 | z mediana 15.9 |
| escala 0.999 (23.976/24) | main | 326 | 0.6% | certas 0.0% (0) | ERRADAS 2 | recusadas 324 | z mediana 11.5 |
| escala 0.999 (23.976/24) | drift | 326 | 48.2% | certas 46.9% (153) | ERRADAS 4 | recusadas 169 | z mediana 15.9 |
| escala 25/23.976 | main | 326 | 38.0% | certas 35.9% (117) | ERRADAS 7 | recusadas 202 | z mediana 9.5 |
| escala 25/23.976 | drift | 326 | 37.7% | certas 35.3% (115) | ERRADAS 8 | recusadas 203 | z mediana 12.5 |
| escala 23.976/25 | main | 326 | 39.6% | certas 37.7% (123) | ERRADAS 6 | recusadas 197 | z mediana 9.6 |
| escala 23.976/25 | drift | 326 | 39.9% | certas 39.6% (129) | ERRADAS 1 | recusadas 196 | z mediana 12.9 |
| ja certa | main | 163 | 52.8% | intactas 163 | MEXIDAS 0 | - | z mediana 11.6 |
| ja certa | drift | 163 | 53.4% | intactas 163 | MEXIDAS 0 | - | z mediana 16.4 |
| outro titulo | main | 326 | 0.0% | recusou 326 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo | drift | 326 | 0.0% | recusou 326 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo (com escala) | main | 163 | 0.0% | recusou 163 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo (com escala) | drift | 163 | 0.0% | recusou 163 | ACEITES FALSOS 0 | - | z mediana 5.2 |

**antigos (filmes)**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 148 | 55.4% | certas 52.7% (78) | ERRADAS 4 | recusadas 66 | z mediana 11.6 |
| deslocamento +3 s | drift | 148 | 53.4% | certas 52.7% (78) | ERRADAS 1 | recusadas 69 | z mediana 16.0 |
| deslocamento -2 s | main | 148 | 54.1% | certas 52.0% (77) | ERRADAS 3 | recusadas 68 | z mediana 11.3 |
| deslocamento -2 s | drift | 148 | 54.7% | certas 53.4% (79) | ERRADAS 2 | recusadas 67 | z mediana 15.7 |
| deslocamento +7,5 s | main | 148 | 53.4% | certas 50.7% (75) | ERRADAS 4 | recusadas 69 | z mediana 11.6 |
| deslocamento +7,5 s | drift | 148 | 56.8% | certas 55.4% (82) | ERRADAS 2 | recusadas 64 | z mediana 16.0 |
| escala 1.001 (24/23.976) | main | 296 | 0.3% | certas 0.0% (0) | ERRADAS 1 | recusadas 295 | z mediana 11.4 |
| escala 1.001 (24/23.976) | drift | 296 | 51.0% | certas 49.0% (145) | ERRADAS 6 | recusadas 145 | z mediana 16.1 |
| escala 0.999 (23.976/24) | main | 296 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 296 | z mediana 11.6 |
| escala 0.999 (23.976/24) | drift | 296 | 51.7% | certas 50.3% (149) | ERRADAS 4 | recusadas 143 | z mediana 16.4 |
| escala 25/23.976 | main | 296 | 39.9% | certas 38.2% (113) | ERRADAS 5 | recusadas 178 | z mediana 9.5 |
| escala 25/23.976 | drift | 296 | 39.9% | certas 37.5% (111) | ERRADAS 7 | recusadas 178 | z mediana 12.6 |
| escala 23.976/25 | main | 296 | 41.6% | certas 39.5% (117) | ERRADAS 6 | recusadas 173 | z mediana 9.7 |
| escala 23.976/25 | drift | 296 | 42.2% | certas 41.9% (124) | ERRADAS 1 | recusadas 171 | z mediana 13.0 |
| ja certa | main | 148 | 56.1% | intactas 148 | MEXIDAS 0 | - | z mediana 12.0 |
| ja certa | drift | 148 | 56.8% | intactas 148 | MEXIDAS 0 | - | z mediana 16.7 |
| outro titulo | main | 296 | 0.0% | recusou 296 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo | drift | 296 | 0.0% | recusou 296 | ACEITES FALSOS 0 | - | z mediana 5.3 |
| outro titulo (com escala) | main | 148 | 0.0% | recusou 148 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo (com escala) | drift | 148 | 0.0% | recusou 148 | ACEITES FALSOS 0 | - | z mediana 5.3 |

**antigos (TV)**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 8 | 12.5% | certas 12.5% (1) | ERRADAS 0 | recusadas 7 | z mediana 10.4 |
| deslocamento +3 s | drift | 8 | 12.5% | certas 12.5% (1) | ERRADAS 0 | recusadas 7 | z mediana 13.4 |
| deslocamento -2 s | main | 8 | 12.5% | certas 12.5% (1) | ERRADAS 0 | recusadas 7 | z mediana 9.5 |
| deslocamento -2 s | drift | 8 | 12.5% | certas 12.5% (1) | ERRADAS 0 | recusadas 7 | z mediana 13.2 |
| deslocamento +7,5 s | main | 8 | 12.5% | certas 12.5% (1) | ERRADAS 0 | recusadas 7 | z mediana 11.9 |
| deslocamento +7,5 s | drift | 8 | 12.5% | certas 12.5% (1) | ERRADAS 0 | recusadas 7 | z mediana 13.7 |
| escala 1.001 (24/23.976) | main | 16 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 16 | z mediana 9.7 |
| escala 1.001 (24/23.976) | drift | 16 | 12.5% | certas 12.5% (2) | ERRADAS 0 | recusadas 14 | z mediana 12.7 |
| escala 0.999 (23.976/24) | main | 16 | 12.5% | certas 0.0% (0) | ERRADAS 2 | recusadas 14 | z mediana 12.1 |
| escala 0.999 (23.976/24) | drift | 16 | 12.5% | certas 12.5% (2) | ERRADAS 0 | recusadas 14 | z mediana 14.0 |
| escala 25/23.976 | main | 16 | 25.0% | certas 12.5% (2) | ERRADAS 2 | recusadas 12 | z mediana 9.9 |
| escala 25/23.976 | drift | 16 | 18.8% | certas 12.5% (2) | ERRADAS 1 | recusadas 13 | z mediana 11.7 |
| escala 23.976/25 | main | 16 | 18.8% | certas 18.8% (3) | ERRADAS 0 | recusadas 13 | z mediana 9.1 |
| escala 23.976/25 | drift | 16 | 6.2% | certas 6.2% (1) | ERRADAS 0 | recusadas 15 | z mediana 11.1 |
| ja certa | main | 8 | 12.5% | intactas 8 | MEXIDAS 0 | - | z mediana 11.1 |
| ja certa | drift | 8 | 12.5% | intactas 8 | MEXIDAS 0 | - | z mediana 13.4 |
| outro titulo | main | 16 | 0.0% | recusou 16 | ACEITES FALSOS 0 | - | z mediana 5.0 |
| outro titulo | drift | 16 | 0.0% | recusou 16 | ACEITES FALSOS 0 | - | z mediana 4.8 |
| outro titulo (com escala) | main | 8 | 0.0% | recusou 8 | ACEITES FALSOS 0 | - | z mediana 5.0 |
| outro titulo (com escala) | drift | 8 | 0.0% | recusou 8 | ACEITES FALSOS 0 | - | z mediana 4.6 |

**novos**

| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |
|---|---|---|---|---|---|---|---|
| deslocamento +3 s | main | 7 | 42.9% | certas 28.6% (2) | ERRADAS 1 | recusadas 4 | z mediana 8.3 |
| deslocamento +3 s | drift | 7 | 42.9% | certas 42.9% (3) | ERRADAS 0 | recusadas 4 | z mediana 9.5 |
| deslocamento -2 s | main | 7 | 42.9% | certas 28.6% (2) | ERRADAS 1 | recusadas 4 | z mediana 8.3 |
| deslocamento -2 s | drift | 7 | 42.9% | certas 28.6% (2) | ERRADAS 1 | recusadas 4 | z mediana 8.3 |
| deslocamento +7,5 s | main | 7 | 28.6% | certas 28.6% (2) | ERRADAS 0 | recusadas 5 | z mediana 7.9 |
| deslocamento +7,5 s | drift | 7 | 28.6% | certas 28.6% (2) | ERRADAS 0 | recusadas 5 | z mediana 7.9 |
| escala 1.001 (24/23.976) | main | 14 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 14 | z mediana 8.0 |
| escala 1.001 (24/23.976) | drift | 14 | 14.3% | certas 14.3% (2) | ERRADAS 0 | recusadas 12 | z mediana 8.3 |
| escala 0.999 (23.976/24) | main | 14 | 0.0% | certas 0.0% (0) | ERRADAS 0 | recusadas 14 | z mediana 7.8 |
| escala 0.999 (23.976/24) | drift | 14 | 14.3% | certas 14.3% (2) | ERRADAS 0 | recusadas 12 | z mediana 8.7 |
| escala 25/23.976 | main | 14 | 14.3% | certas 14.3% (2) | ERRADAS 0 | recusadas 12 | z mediana 6.3 |
| escala 25/23.976 | drift | 14 | 14.3% | certas 14.3% (2) | ERRADAS 0 | recusadas 12 | z mediana 6.7 |
| escala 23.976/25 | main | 14 | 21.4% | certas 21.4% (3) | ERRADAS 0 | recusadas 11 | z mediana 6.7 |
| escala 23.976/25 | drift | 14 | 28.6% | certas 28.6% (4) | ERRADAS 0 | recusadas 10 | z mediana 7.5 |
| ja certa | main | 7 | 28.6% | intactas 7 | MEXIDAS 0 | - | z mediana 7.7 |
| ja certa | drift | 7 | 28.6% | intactas 7 | MEXIDAS 0 | - | z mediana 8.4 |
| outro titulo | main | 14 | 0.0% | recusou 14 | ACEITES FALSOS 0 | - | z mediana 5.2 |
| outro titulo | drift | 14 | 0.0% | recusou 14 | ACEITES FALSOS 0 | - | z mediana 5.0 |
| outro titulo (com escala) | main | 7 | 0.0% | recusou 7 | ACEITES FALSOS 0 | - | z mediana 4.9 |
| outro titulo (com escala) | drift | 7 | 0.0% | recusou 7 | ACEITES FALSOS 0 | - | z mediana 4.5 |

## 3. Cabeca a cabeca: main x deriva (mesma variante, mesmo titulo)

| familia | main | deriva | n |
|---|---|---|---|
| deslocamento +3 s | certa | recusou | 7 |
| deslocamento +3 s | recusou | certa | 6 |
| deslocamento +3 s | ERRADA | certa | 2 |
| deslocamento +3 s | ERRADA | recusou | 2 |
| deslocamento +7,5 s | recusou | certa | 12 |
| deslocamento +7,5 s | certa | recusou | 5 |
| deslocamento +7,5 s | ERRADA | recusou | 2 |
| deslocamento +7,5 s | certa | ERRADA | 1 |
| deslocamento +7,5 s | ERRADA | certa | 1 |
| deslocamento -2 s | certa | recusou | 6 |
| deslocamento -2 s | recusou | certa | 6 |
| deslocamento -2 s | ERRADA | certa | 2 |
| deslocamento -2 s | recusou | ERRADA | 1 |
| escala 0.999 (23.976/24) | recusou | certa | 153 |
| escala 0.999 (23.976/24) | recusou | ERRADA | 4 |
| escala 0.999 (23.976/24) | ERRADA | recusou | 2 |
| escala 1.001 (24/23.976) | recusou | certa | 149 |
| escala 1.001 (24/23.976) | recusou | ERRADA | 6 |
| escala 1.001 (24/23.976) | ERRADA | recusou | 1 |
| escala 23.976/25 | recusou | certa | 23 |
| escala 23.976/25 | certa | recusou | 17 |
| escala 23.976/25 | ERRADA | recusou | 5 |
| escala 25/23.976 | certa | recusou | 23 |
| escala 25/23.976 | recusou | certa | 21 |
| escala 25/23.976 | recusou | ERRADA | 2 |
| escala 25/23.976 | ERRADA | recusou | 1 |

Iguais nos dois motores: 1985 de 2445 variantes.


**Regressoes da deriva (certa/intacta no main que passou a errada/mexida/recusada):** 72

- `meet_john_doe_ipod` `g23.976/25|o-4300` (escala 23.976/25): main=certa -> deriva=recusou; z=13.9 janelas=7 motivo=janelas discordam; | [490s d=6933 ncc=0.11 2o=0.10 z=2.7 -] [1165s d=-15000 ncc=0.41 2o=0.33 z=3.1 -] [3460s d=-14376 ncc=0.16 2o=0.13 z=3.7 -] [1340s d=12180 ncc=0.11 2o=0.11 z=1.7 -] [4285s d=11852 ncc=0.11 2o=0.10 z=2.6 -] [5440s d=6545 ncc=0.08 2o=0.07 z=2.1 -] [6055s d=-13639 ncc=0.19 2o=0.18 z=1.9 -] deriva{menos 
- `we_dive_at_dawn_1943` `mais3` (deslocamento +3 s): main=certa -> deriva=recusou; z=20.1 janelas=10 motivo=ajuste inseguro: janelas fora de uma reta de atraso constante (248 ms); possível | [560s d=-2990 ncc=0.54 2o=0.36 z=5.9 ok] [1070s d=-2824 ncc=0.56 2o=0.39 z=3.1 ok] [1460s d=-2804 ncc=0.60 2o=0.42 z=5.9 ok] [2275s d=-2773 ncc=0.57 2o=0.45 z=4.2 ok] [3420s d=-2891 ncc=0.74 2o=0.48 z=6.4 ok] [4280s d=-2741 ncc=0.35 2o=0.27 z=4.1 ok] [4570s d=-2572 ncc=0.59 2o=0.51 z=3.9 ok] [3890s 
- `youth_on_parole` `g23.976/25|o-4300` (escala 23.976/25): main=certa -> deriva=recusou; z=14.2 janelas=6 motivo=janelas discordam; | [450s d=14252 ncc=0.04 2o=0.01 z=2.7 -] [1085s d=-9795 ncc=-0.02 2o=-0.06 z=2.7 -] [2610s d=9945 ncc=0.19 2o=0.19 z=2.3 -] [615s d=-9642 ncc=0.29 2o=0.22 z=2.5 -] [1585s d=11385 ncc=0.22 2o=0.19 z=2.1 -] [2500s d=1953 ncc=0.15 2o=0.12 z=2.1 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiavei
- `alfred_hitchcock_presents_s_2e_13_colorized_sd_alfred_hitchc` `g23.976/25|o+6200` (escala 23.976/25): main=certa -> deriva=recusou; z=14.4 janelas=6 motivo=janelas discordam; | [160s d=1329 ncc=0.19 2o=0.18 z=3.0 -] [830s d=1803 ncc=0.18 2o=0.14 z=2.3 -] [1190s d=-15000 ncc=0.22 2o=0.22 z=2.0 -] [260s d=4910 ncc=0.39 2o=0.38 z=2.1 -] [670s d=7398 ncc=0.26 2o=0.23 z=2.1 -] [1090s d=-13498 ncc=-0.06 2o=-0.06 z=1.9 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiaveis=
- `alfred_hitchcock_presents_s_2e_13_colorized_sd_alfred_hitchc` `g23.976/25|o+2600` (escala 23.976/25): main=certa -> deriva=recusou; z=15.1 janelas=6 motivo=janelas discordam; | [150s d=4943 ncc=0.14 2o=0.14 z=2.2 -] [825s d=5383 ncc=0.18 2o=0.12 z=2.7 -] [1185s d=-12097 ncc=0.22 2o=0.21 z=1.7 -] [260s d=8510 ncc=0.39 2o=0.38 z=2.5 -] [675s d=10926 ncc=0.27 2o=0.26 z=1.9 -] [1085s d=-14611 ncc=-0.00 2o=-0.03 z=4.0 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiaveis
- `the_scar_1948` `g25/23.976|o+6200` (escala 25/23.976): main=certa -> deriva=recusou; z=15.7 janelas=6 motivo=janelas discordam; | [255s d=-1641 ncc=0.24 2o=0.23 z=2.7 -] [3440s d=-7959 ncc=0.13 2o=0.13 z=2.4 -] [4240s d=-1685 ncc=0.24 2o=0.22 z=1.5 -] [1265s d=-130 ncc=0.14 2o=0.14 z=2.1 -] [1805s d=-6018 ncc=0.25 2o=0.24 z=2.3 -] [3100s d=-14282 ncc=0.01 2o=0.01 z=2.3 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiave
- `rusty_knife` `g23.976/25|o+4000` (escala 23.976/25): main=certa -> deriva=recusou; z=11.6 janelas=7 motivo=janelas discordam; | [335s d=11151 ncc=0.51 2o=0.48 z=2.2 -] [895s d=7215 ncc=0.10 2o=0.08 z=2.3 -] [1330s d=-7791 ncc=0.13 2o=0.11 z=2.9 -] [2170s d=4788 ncc=0.34 2o=0.32 z=2.2 -] [2715s d=7842 ncc=0.25 2o=0.22 z=2.6 -] [4095s d=80 ncc=0.20 2o=0.18 z=3.1 -] [4540s d=5523 ncc=0.34 2o=0.33 z=2.1 -] deriva{menos de 2 jane
- `marriedtoawitch` `mais7.5` (deslocamento +7,5 s): main=certa -> deriva=recusou; z=13.0 janelas=6 motivo=janelas discordam; | [1610s d=-7362 ncc=0.41 2o=0.31 z=3.4 ok] [2955s d=-7458 ncc=0.49 2o=0.42 z=3.9 -] [3810s d=-7268 ncc=0.58 2o=0.46 z=2.8 ok] [600s d=-5099 ncc=0.50 2o=0.47 z=2.8 -] [795s d=-6968 ncc=0.50 2o=0.49 z=2.8 -] [2200s d=-7113 ncc=0.55 2o=0.43 z=4.4 ok] deriva{sem prova de deriva: resíduo da reta sem escal
- `riotincellblock` `mais3` (deslocamento +3 s): main=certa -> deriva=recusou; z=15.5 janelas=7 motivo=janelas discordam; | [1660s d=-2761 ncc=0.64 2o=0.53 z=5.2 ok] [2090s d=-2853 ncc=0.60 2o=0.48 z=4.4 ok] [3670s d=-2880 ncc=0.42 2o=0.29 z=5.0 ok] [205s d=-2940 ncc=0.78 2o=0.60 z=5.6 ok] [1515s d=-2890 ncc=0.72 2o=0.55 z=6.3 ok] [2410s d=-2718 ncc=0.71 2o=0.57 z=3.4 ok] [3960s d=-2780 ncc=0.43 2o=0.42 z=2.0 -] deriva{s
- `the_housemaid_1960` `mais3` (deslocamento +3 s): main=certa -> deriva=recusou; z=17.5 janelas=10 motivo=ajuste inseguro: janelas fora de uma reta de atraso constante (364 ms); possível | [640s d=-2647 ncc=0.59 2o=0.49 z=3.7 ok] [1695s d=-2747 ncc=0.52 2o=0.46 z=3.1 -] [2135s d=-2797 ncc=0.42 2o=0.37 z=2.3 -] [2395s d=-2891 ncc=0.63 2o=0.53 z=3.9 ok] [3535s d=-2619 ncc=0.66 2o=0.49 z=5.5 ok] [4670s d=-2949 ncc=0.64 2o=0.54 z=3.8 ok] [5700s d=-2388 ncc=0.45 2o=0.37 z=3.1 ok] [1165s d=
- `the_ghoul_1933` `g25/23.976|o-900` (escala 25/23.976): main=certa -> deriva=recusou; z=14.7 janelas=7 motivo=janelas discordam; | [1535s d=-9406 ncc=0.21 2o=0.19 z=2.9 -] [2100s d=14788 ncc=-0.15 2o=-0.17 z=3.7 -] [2610s d=6717 ncc=0.24 2o=0.20 z=1.9 -] [285s d=-12985 ncc=0.67 2o=0.66 z=1.5 -] [1630s d=13879 ncc=-0.12 2o=-0.14 z=2.1 -] [3335s d=12855 ncc=0.20 2o=0.19 z=2.1 -] [4210s d=-7299 ncc=0.43 2o=0.41 z=1.6 -] deriva{men
- `impact_201710` `g25/23.976|o+2600` (escala 25/23.976): main=certa -> deriva=recusou; z=15.9 janelas=7 motivo=janelas discordam; | [4130s d=-4052 ncc=0.11 2o=0.11 z=2.3 -] [5530s d=6928 ncc=0.17 2o=0.17 z=2.2 -] [5915s d=-6728 ncc=0.12 2o=0.10 z=2.2 -] [295s d=-12621 ncc=0.49 2o=0.45 z=2.6 -] [2160s d=-5972 ncc=0.25 2o=0.21 z=2.7 -] [2860s d=-15000 ncc=0.17 2o=0.17 z=2.4 -] [5305s d=-10770 ncc=0.17 2o=0.13 z=2.9 -] deriva{menos
- `theguilty` `menos2` (deslocamento -2 s): main=certa -> deriva=recusou; z=16.4 janelas=6 motivo=janelas discordam; | [370s d=2184 ncc=0.48 2o=0.33 z=5.8 ok] [1635s d=2116 ncc=0.58 2o=0.50 z=3.4 -] [3600s d=2079 ncc=0.51 2o=0.29 z=5.4 ok] [1260s d=2156 ncc=0.58 2o=0.49 z=4.8 ok] [2235s d=2131 ncc=0.63 2o=0.55 z=3.6 -] [2955s d=2452 ncc=0.57 2o=0.50 z=3.9 -] deriva{sem prova de deriva: resíduo da reta sem escala 61 
- `theguilty` `g23.976/25|o-1300` (escala 23.976/25): main=certa -> deriva=recusou; z=13.4 janelas=5 motivo=janelas discordam; | [305s d=14579 ncc=0.42 2o=0.40 z=3.0 -] [1600s d=-14560 ncc=0.23 2o=0.13 z=3.9 ok] [3455s d=-1768 ncc=0.31 2o=0.28 z=2.5 -] [1355s d=2232 ncc=0.30 2o=0.25 z=2.2 -] [2270s d=-13581 ncc=0.02 2o=0.00 z=3.1 -] deriva{menos de 2 janelas confiáveis (1 de 5); confiaveis=1 inclinacao=0.000000 provada=0}
- `aofg884545120` `g23.976/25|o+4000` (escala 23.976/25): main=certa -> deriva=recusou; z=15.4 janelas=5 motivo=janelas discordam; | [430s d=15000 ncc=0.28 2o=0.26 z=3.3 -] [2220s d=13927 ncc=0.09 2o=0.08 z=2.4 -] [2930s d=12121 ncc=0.25 2o=0.21 z=3.1 -] [1065s d=-14400 ncc=0.15 2o=0.14 z=2.7 -] [4020s d=15000 ncc=0.14 2o=0.14 z=2.3 -] deriva{menos de 2 janelas confiáveis (0 de 5); confiaveis=0 inclinacao=0.000000 provada=0}
- `i_wake_up_screaming_1941` `mais3` (deslocamento +3 s): main=certa -> deriva=recusou; z=15.5 janelas=7 motivo=janelas discordam; | [195s d=-2773 ncc=0.57 2o=0.51 z=2.7 -] [555s d=-2768 ncc=0.43 2o=0.35 z=3.3 -] [4185s d=-2803 ncc=0.64 2o=0.56 z=3.1 ok] [880s d=-3228 ncc=0.64 2o=0.60 z=2.3 -] [1670s d=-2773 ncc=0.66 2o=0.58 z=3.5 -] [2850s d=-2890 ncc=0.66 2o=0.58 z=2.5 ok] [3285s d=-2857 ncc=0.67 2o=0.59 z=4.0 -] deriva{sem pro
- `i_wake_up_screaming_1941` `menos2` (deslocamento -2 s): main=certa -> deriva=recusou; z=14.4 janelas=6 motivo=janelas discordam; | [550s d=2222 ncc=0.40 2o=0.35 z=3.0 -] [2845s d=2108 ncc=0.69 2o=0.63 z=2.4 -] [4180s d=2214 ncc=0.59 2o=0.50 z=4.0 ok] [880s d=1772 ncc=0.64 2o=0.60 z=2.3 -] [1670s d=2227 ncc=0.66 2o=0.58 z=3.5 -] [3280s d=2140 ncc=0.67 2o=0.58 z=4.1 ok] deriva{sem prova de deriva: resíduo da reta sem escala 39 ms
- `pitfalls3648` `menos2` (deslocamento -2 s): main=certa -> deriva=recusou; z=18.7 janelas=10 motivo=ajuste inseguro: janelas fora de uma reta de atraso constante (190 ms); possível | [275s d=2474 ncc=0.56 2o=0.44 z=4.3 ok] [715s d=2294 ncc=0.42 2o=0.30 z=5.8 ok] [2790s d=2271 ncc=0.65 2o=0.45 z=6.7 ok] [1465s d=2408 ncc=0.54 2o=0.47 z=3.1 -] [2095s d=2489 ncc=0.52 2o=0.44 z=3.4 -] [3580s d=2280 ncc=0.66 2o=0.52 z=4.3 ok] [4460s d=2098 ncc=0.77 2o=0.69 z=2.8 -] [3135s d=2230 ncc=
- `1951_the_prowler_el_merodeador_joseph_losey_vose` `mais7.5` (deslocamento +7,5 s): main=certa -> deriva=ERRADA; z=16.5 janelas=10 motivo= | [580s d=-7216 ncc=0.54 2o=0.38 z=4.8 ok] [1515s d=-7313 ncc=0.58 2o=0.43 z=5.2 ok] [3280s d=-7479 ncc=0.58 2o=0.39 z=6.3 ok] [2670s d=-7507 ncc=0.49 2o=0.39 z=4.0 ok] [3890s d=-7024 ncc=0.66 2o=0.63 z=3.5 -] [5090s d=-7190 ncc=0.72 2o=0.60 z=2.8 ok] [4410s d=-7349 ncc=0.77 2o=0.65 z=3.2 ok] [1775s d
- `last_holiday` `g23.976/25|o-2500` (escala 23.976/25): main=certa -> deriva=recusou; z=15.3 janelas=7 motivo=janelas discordam; | [2080s d=11707 ncc=0.21 2o=0.21 z=2.0 -] [3630s d=12277 ncc=0.21 2o=0.18 z=2.9 -] [4055s d=7973 ncc=0.08 2o=0.07 z=1.7 -] [835s d=2253 ncc=0.14 2o=0.12 z=2.4 -] [1485s d=-4932 ncc=0.26 2o=0.25 z=1.8 -] [3450s d=-7133 ncc=0.15 2o=0.15 z=2.6 -] [4565s d=7209 ncc=0.31 2o=0.28 z=2.3 -] deriva{menos de 2
- `murder_by_invitation_1941` `menos2` (deslocamento -2 s): main=recusou -> deriva=ERRADA; z=15.4 janelas=6 motivo= | [505s d=2395 ncc=0.62 2o=0.53 z=2.4 ok] [1655s d=2533 ncc=0.43 2o=0.20 z=5.8 ok] [2965s d=2463 ncc=0.42 2o=0.24 z=5.4 ok] [685s d=2388 ncc=0.66 2o=0.46 z=5.0 ok] [2415s d=2434 ncc=0.83 2o=0.78 z=2.7 -] [3280s d=2284 ncc=0.73 2o=0.61 z=3.2 ok] deriva{sem prova de deriva: resíduo da reta sem escala 13
- `murder_by_invitation_1941` `g1.001|o-4300` (escala 1.001 (24/23.976)): main=recusou -> deriva=ERRADA; z=15.0 janelas=6 motivo= | [500s d=4127 ncc=0.58 2o=0.52 z=2.3 -] [1675s d=3126 ncc=0.46 2o=0.23 z=5.1 ok] [2965s d=1750 ncc=0.41 2o=0.24 z=5.4 ok] [685s d=3955 ncc=0.66 2o=0.47 z=5.0 ok] [2135s d=2598 ncc=0.64 2o=0.52 z=4.2 ok] [3280s d=1256 ncc=0.73 2o=0.61 z=3.1 ok] deriva{deriva provada: escala 0.999000, resíduo 134 ms (r
- `murder_by_invitation_1941` `g1.001|o-900` (escala 1.001 (24/23.976)): main=recusou -> deriva=ERRADA; z=15.1 janelas=6 motivo= | [505s d=747 ncc=0.61 2o=0.53 z=2.4 -] [1670s d=-274 ncc=0.42 2o=0.22 z=4.9 ok] [2970s d=-1685 ncc=0.46 2o=0.25 z=5.7 ok] [685s d=555 ncc=0.66 2o=0.47 z=5.0 ok] [2135s d=-802 ncc=0.64 2o=0.52 z=4.2 ok] [3285s d=-2158 ncc=0.71 2o=0.59 z=3.3 ok] deriva{deriva provada: escala 0.999000, resíduo 135 ms (r
- `murder_by_invitation_1941` `g0.999|o+1800` (escala 0.999 (23.976/24)): main=recusou -> deriva=ERRADA; z=15.1 janelas=6 motivo= | [510s d=-860 ncc=0.65 2o=0.56 z=2.5 ok] [1655s d=440 ncc=0.43 2o=0.20 z=5.7 ok] [2965s d=1676 ncc=0.42 2o=0.25 z=5.3 ok] [685s d=-684 ncc=0.66 2o=0.47 z=4.8 ok] [2135s d=864 ncc=0.64 2o=0.53 z=4.1 ok] [3280s d=1809 ncc=0.73 2o=0.61 z=3.2 ok] deriva{deriva provada: escala 1.001001, resíduo 130 ms (re
- `murder_by_invitation_1941` `g0.999|o+4000` (escala 0.999 (23.976/24)): main=recusou -> deriva=ERRADA; z=15.9 janelas=6 motivo= | [515s d=-3068 ncc=0.65 2o=0.57 z=2.4 ok] [1680s d=-1732 ncc=0.47 2o=0.22 z=4.8 ok] [2965s d=-524 ncc=0.42 2o=0.25 z=5.3 ok] [685s d=-2884 ncc=0.66 2o=0.47 z=4.8 ok] [2130s d=-1364 ncc=0.64 2o=0.52 z=3.8 ok] [3280s d=-391 ncc=0.73 2o=0.61 z=3.2 ok] deriva{deriva provada: escala 1.001001, resíduo 137 
- `the_tingler_1959_202510` `mais3` (deslocamento +3 s): main=certa -> deriva=recusou; z=20.9 janelas=7 motivo=janelas discordam; | [990s d=-2793 ncc=0.70 2o=0.44 z=6.5 ok] [1415s d=-2733 ncc=0.54 2o=0.27 z=6.7 ok] [1905s d=-2706 ncc=0.57 2o=0.22 z=9.9 ok] [325s d=-2624 ncc=0.72 2o=0.45 z=4.6 ok] [2485s d=-2640 ncc=0.73 2o=0.52 z=8.3 ok] [3555s d=-2668 ncc=0.73 2o=0.54 z=6.4 ok] [4075s d=-2617 ncc=0.60 2o=0.49 z=2.6 ok] deriva{s
- `the_tingler_1959_202510` `mais7.5` (deslocamento +7,5 s): main=certa -> deriva=recusou; z=20.9 janelas=7 motivo=janelas discordam; | [995s d=-7290 ncc=0.71 2o=0.46 z=6.7 ok] [1420s d=-7228 ncc=0.58 2o=0.32 z=6.9 ok] [1950s d=-7161 ncc=0.65 2o=0.49 z=4.5 ok] [330s d=-7115 ncc=0.69 2o=0.42 z=4.5 ok] [2490s d=-7149 ncc=0.74 2o=0.50 z=8.6 ok] [3560s d=-7160 ncc=0.72 2o=0.52 z=6.0 ok] [4080s d=-7103 ncc=0.60 2o=0.49 z=3.3 ok] deriva{s
- `the_tingler_1959_202510` `g25/23.976|o+4000` (escala 25/23.976): main=certa -> deriva=recusou; z=17.4 janelas=7 motivo=janelas discordam; | [1035s d=-2275 ncc=0.13 2o=0.12 z=1.9 -] [1480s d=6022 ncc=0.11 2o=0.10 z=2.3 -] [2025s d=2061 ncc=0.22 2o=0.21 z=2.0 -] [320s d=5417 ncc=0.21 2o=0.19 z=2.1 -] [2490s d=7539 ncc=0.05 2o=0.04 z=2.3 -] [3715s d=-9574 ncc=0.08 2o=0.05 z=2.5 -] [4245s d=13762 ncc=0.31 2o=0.26 z=2.1 -] deriva{menos de 2 
- `the_tingler_1959_202510` `g25/23.976|o+1800` (escala 25/23.976): main=certa -> deriva=recusou; z=17.2 janelas=7 motivo=janelas discordam; | [1035s d=-14698 ncc=0.15 2o=0.13 z=1.9 -] [1480s d=8222 ncc=0.11 2o=0.10 z=2.3 -] [2020s d=4327 ncc=0.20 2o=0.18 z=2.4 -] [320s d=7617 ncc=0.21 2o=0.19 z=2.1 -] [2495s d=9880 ncc=0.03 2o=-0.01 z=2.7 -] [3715s d=-15000 ncc=0.09 2o=0.08 z=2.4 -] [4245s d=9200 ncc=0.22 2o=0.21 z=1.8 -] deriva{menos de 
- `black_angel_1946_202406` `g25/23.976|o-2500` (escala 25/23.976): main=certa -> deriva=recusou; z=15.3 janelas=7 motivo=janelas discordam; | [1025s d=9536 ncc=0.10 2o=0.07 z=2.0 -] [1395s d=7498 ncc=0.18 2o=0.18 z=2.2 -] [3720s d=7477 ncc=0.20 2o=0.20 z=2.1 -] [785s d=13228 ncc=0.20 2o=0.18 z=1.9 -] [2310s d=-4169 ncc=0.12 2o=0.10 z=1.8 -] [3105s d=11587 ncc=0.31 2o=0.27 z=3.0 -] [4005s d=-7835 ncc=0.51 2o=0.48 z=3.1 -] deriva{menos de 2
- `black_angel_1946_202406` `g25/23.976|o-1300` (escala 25/23.976): main=certa -> deriva=recusou; z=15.4 janelas=7 motivo=janelas discordam; | [1025s d=8336 ncc=0.10 2o=0.07 z=2.0 -] [1400s d=5683 ncc=0.21 2o=0.18 z=2.7 -] [3720s d=6277 ncc=0.20 2o=0.20 z=2.0 -] [785s d=12028 ncc=0.20 2o=0.18 z=1.9 -] [2310s d=-5369 ncc=0.12 2o=0.10 z=1.9 -] [3105s d=10387 ncc=0.31 2o=0.27 z=3.0 -] [4005s d=-9035 ncc=0.51 2o=0.48 z=3.1 -] deriva{menos de 2
- `pontodeviragem5250` `mais7.5` (deslocamento +7,5 s): main=certa -> deriva=recusou; z=19.2 janelas=10 motivo=possível deriva: janelas discordam entre si; janelas discordam; | [200s d=-7446 ncc=0.53 2o=0.51 z=2.3 -] [695s d=-7424 ncc=0.74 2o=0.63 z=4.4 ok] [2710s d=-7510 ncc=0.51 2o=0.34 z=4.3 ok] [1385s d=-7366 ncc=0.73 2o=0.70 z=2.2 -] [2465s d=-7248 ncc=0.55 2o=0.40 z=4.6 ok] [3525s d=-7127 ncc=0.58 2o=0.52 z=2.3 -] [4215s d=-7455 ncc=0.60 2o=0.49 z=3.3 ok] [2980s d=-7
- `the_paradine_case_1947_drama` `g25/23.976|o-4300` (escala 25/23.976): main=certa -> deriva=recusou; z=17.4 janelas=7 motivo= | [1795s d=13699 ncc=0.24 2o=0.20 z=3.1 -] [4700s d=5049 ncc=0.16 2o=0.15 z=2.6 -] [5370s d=8859 ncc=0.24 2o=0.23 z=2.2 -] [855s d=-14510 ncc=0.14 2o=0.13 z=2.5 -] [3085s d=4491 ncc=0.23 2o=0.21 z=2.4 -] [4090s d=6729 ncc=0.10 2o=0.09 z=1.4 -] [6275s d=964 ncc=0.01 2o=0.00 z=2.0 -] deriva{menos de 2 j
- `diary_of_a_madman_1963` `g1.001|o-1300` (escala 1.001 (24/23.976)): main=recusou -> deriva=ERRADA; z=15.6 janelas=5 motivo= | [455s d=1422 ncc=0.68 2o=0.55 z=4.3 ok] [1695s d=11 ncc=0.77 2o=0.68 z=2.2 ok] [4690s d=-2973 ncc=0.61 2o=0.39 z=5.8 ok] [2315s d=-628 ncc=0.56 2o=0.44 z=4.4 ok] [3095s d=-1300 ncc=0.58 2o=0.43 z=5.3 ok] deriva{deriva provada: escala 0.999000, resíduo 118 ms (reta sem escala deixaria 2361 ms), deriv
- `diary_of_a_madman_1963` `g1.001|o-2500` (escala 1.001 (24/23.976)): main=recusou -> deriva=ERRADA; z=15.2 janelas=5 motivo= | [445s d=2622 ncc=0.70 2o=0.59 z=4.1 ok] [1695s d=1211 ncc=0.77 2o=0.68 z=2.2 ok] [4730s d=-1794 ncc=0.71 2o=0.57 z=4.3 ok] [2315s d=572 ncc=0.56 2o=0.44 z=4.4 ok] [3190s d=-237 ncc=0.68 2o=0.59 z=4.1 ok] deriva{deriva provada: escala 0.999000, resíduo 113 ms (reta sem escala deixaria 2276 ms), deriv
- `diary_of_a_madman_1963` `g0.999|o+6200` (escala 0.999 (23.976/24)): main=recusou -> deriva=ERRADA; z=15.4 janelas=5 motivo= | [455s d=-5106 ncc=0.68 2o=0.55 z=4.4 ok] [1695s d=-4052 ncc=0.77 2o=0.69 z=2.2 ok] [4730s d=-958 ncc=0.71 2o=0.56 z=4.3 ok] [2315s d=-3389 ncc=0.55 2o=0.44 z=4.4 ok] [3200s d=-2437 ncc=0.67 2o=0.57 z=4.5 ok] deriva{deriva provada: escala 1.001001, resíduo 108 ms (reta sem escala deixaria 2230 ms), d
- `diary_of_a_madman_1963` `g0.999|o+4000` (escala 0.999 (23.976/24)): main=recusou -> deriva=ERRADA; z=15.6 janelas=5 motivo= | [450s d=-2887 ncc=0.67 2o=0.54 z=4.9 ok] [1695s d=-1852 ncc=0.77 2o=0.69 z=2.2 ok] [4715s d=1226 ncc=0.73 2o=0.57 z=5.2 ok] [2315s d=-1189 ncc=0.55 2o=0.44 z=4.4 ok] [3190s d=-285 ncc=0.67 2o=0.59 z=4.0 -] deriva{deriva provada: escala 1.001001, resíduo 134 ms (reta sem escala deixaria 2374 ms), der
- `house_of_usher_1960` `g25/23.976|o-1300` (escala 25/23.976): main=certa -> deriva=recusou; z=11.9 janelas=6 motivo=escala fora das razões de fps; janelas discordam; | [650s d=-7619 ncc=0.22 2o=0.22 z=2.3 -] [1860s d=11791 ncc=0.22 2o=0.19 z=2.7 -] [2545s d=2382 ncc=0.20 2o=0.17 z=2.5 -] [1205s d=-12706 ncc=0.17 2o=0.13 z=2.9 -] [3470s d=7692 ncc=0.21 2o=0.20 z=2.1 -] [4140s d=-15000 ncc=0.25 2o=0.24 z=2.4 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiave
- `paranoiac_202107` `menos2` (deslocamento -2 s): main=certa -> deriva=recusou; z=18.4 janelas=7 motivo=janelas discordam; | [1080s d=2042 ncc=0.51 2o=0.27 z=6.4 ok] [1710s d=2062 ncc=0.46 2o=0.29 z=4.9 ok] [2265s d=2074 ncc=0.63 2o=0.48 z=5.2 ok] [235s d=2101 ncc=0.69 2o=0.61 z=3.1 -] [2445s d=2115 ncc=0.57 2o=0.34 z=5.1 ok] [3765s d=2063 ncc=0.53 2o=0.42 z=3.6 ok] [3970s d=2240 ncc=0.64 2o=0.52 z=4.5 ok] deriva{sem prov
- `paranoiac_202107` `mais7.5` (deslocamento +7,5 s): main=certa -> deriva=recusou; z=18.3 janelas=7 motivo=janelas discordam; | [1085s d=-7465 ncc=0.55 2o=0.31 z=6.7 ok] [1725s d=-7444 ncc=0.44 2o=0.27 z=4.9 ok] [2280s d=-7485 ncc=0.62 2o=0.44 z=5.6 ok] [245s d=-7415 ncc=0.67 2o=0.61 z=2.8 -] [2455s d=-7369 ncc=0.50 2o=0.30 z=5.0 ok] [3775s d=-7467 ncc=0.57 2o=0.47 z=3.2 ok] [3980s d=-7267 ncc=0.64 2o=0.50 z=4.8 ok] deriva{s
- `the_last_man_on_earth_1964_20251031` `g25/23.976|o-2500` (escala 25/23.976): main=certa -> deriva=recusou; z=12.6 janelas=7 motivo= | [1925s d=-14569 ncc=-0.11 2o=-0.11 z=2.1 -] [2285s d=14487 ncc=0.35 2o=0.30 z=2.7 -] [4365s d=13044 ncc=0.24 2o=0.23 z=2.4 -] [375s d=-14669 ncc=0.52 2o=0.45 z=3.2 -] [1680s d=786 ncc=0.28 2o=0.20 z=3.3 -] [2695s d=-13316 ncc=0.48 2o=0.45 z=2.0 -] [4175s d=12254 ncc=0.18 2o=0.17 z=1.6 -] deriva{meno
- `screambloodymurder1975` `menos2` (deslocamento -2 s): main=certa -> deriva=recusou; z=19.1 janelas=10 motivo=possíveis cortes na legenda (só aviso); | [840s d=2416 ncc=0.73 2o=0.63 z=3.6 ok] [2020s d=2370 ncc=0.70 2o=0.42 z=5.9 ok] [2695s d=2399 ncc=0.56 2o=0.33 z=7.5 ok] [4095s d=2172 ncc=0.56 2o=0.50 z=3.6 -] [4205s d=2171 ncc=0.68 2o=0.47 z=6.9 ok] [305s d=3429 ncc=0.47 2o=0.45 z=2.5 -] [1320s d=2410 ncc=0.41 2o=0.27 z=4.9 ok] [4595s d=2253 ncc
- `teenage_zombies` `g25/23.976|o+1800` (escala 25/23.976): main=certa -> deriva=recusou; z=14.2 janelas=6 motivo=janelas discordam; | [895s d=-15000 ncc=-0.08 2o=-0.12 z=2.1 -] [1655s d=-67 ncc=0.17 2o=0.16 z=2.3 -] [2870s d=5316 ncc=0.13 2o=0.12 z=2.1 -] [680s d=2377 ncc=0.36 2o=0.32 z=2.8 -] [2170s d=15000 ncc=0.07 2o=0.03 z=3.1 -] [3700s d=3714 ncc=0.25 2o=0.25 z=1.7 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiaveis=
- `highwaydragnet` `g23.976/25|o+6200` (escala 23.976/25): main=certa -> deriva=recusou; z=15.3 janelas=6 motivo=janelas discordam; | [440s d=15000 ncc=0.31 2o=0.29 z=2.8 -] [1115s d=5289 ncc=0.27 2o=0.25 z=3.5 -] [2025s d=1062 ncc=0.22 2o=0.22 z=1.8 -] [2145s d=-13435 ncc=0.17 2o=0.14 z=2.7 -] [3345s d=-12088 ncc=0.20 2o=0.17 z=2.8 -] [3460s d=-14722 ncc=0.22 2o=0.22 z=2.2 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiav
- `stranger_in_town_1943` `g25/23.976|o+1800` (escala 25/23.976): main=certa -> deriva=recusou; z=15.0 janelas=6 motivo=janelas discordam; | [515s d=13737 ncc=0.11 2o=0.07 z=2.5 -] [1060s d=-5297 ncc=0.22 2o=0.20 z=2.6 -] [3150s d=-9530 ncc=0.43 2o=0.41 z=2.3 -] [1445s d=-844 ncc=0.08 2o=0.07 z=2.4 -] [2050s d=6565 ncc=0.34 2o=0.32 z=2.5 -] [3575s d=-14745 ncc=0.10 2o=0.09 z=2.4 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiavei
- `unfaithfully_yours` `g25/23.976|o-2500` (escala 25/23.976): main=recusou -> deriva=ERRADA; z=9.0 janelas=7 motivo= | [505s d=9188 ncc=0.15 2o=0.14 z=2.7 -] [1465s d=-12588 ncc=-0.01 2o=-0.03 z=2.1 -] [1825s d=8917 ncc=0.27 2o=0.26 z=1.7 -] [2150s d=11643 ncc=0.18 2o=0.16 z=2.6 -] [3645s d=-6163 ncc=0.35 2o=0.33 z=1.9 -] [4495s d=-15000 ncc=-0.05 2o=-0.10 z=2.4 -] [5745s d=-15000 ncc=0.08 2o=0.02 z=2.6 -] deriva{me
- `the_gorgon_1964` `g25/23.976|o-4300` (escala 25/23.976): main=certa -> deriva=recusou; z=13.4 janelas=6 motivo=possíveis cortes na legenda (só aviso); | [990s d=14414 ncc=0.08 2o=0.06 z=2.6 -] [3490s d=12142 ncc=0.22 2o=0.22 z=2.0 -] [4520s d=-4249 ncc=0.26 2o=0.24 z=2.7 -] [715s d=14162 ncc=0.11 2o=0.10 z=2.0 -] [2075s d=15000 ncc=0.21 2o=0.18 z=2.1 -] [2550s d=13388 ncc=0.03 2o=0.02 z=1.9 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiavei
- `eyes_in_the_night` `g25/23.976|o-2500` (escala 25/23.976): main=certa -> deriva=recusou; z=12.8 janelas=7 motivo=possíveis cortes na legenda (só aviso); | [275s d=-10454 ncc=0.43 2o=0.38 z=3.1 -] [750s d=2441 ncc=0.18 2o=0.16 z=2.5 -] [2875s d=7902 ncc=0.28 2o=0.24 z=4.0 -] [1190s d=-13754 ncc=0.15 2o=0.13 z=2.4 -] [2145s d=-14594 ncc=0.24 2o=0.21 z=3.4 -] [3385s d=-13103 ncc=0.11 2o=0.10 z=2.4 -] [4350s d=12335 ncc=0.23 2o=0.20 z=2.1 -] deriva{menos 
- `onlythevalaint` `g25/23.976|o+4000` (escala 25/23.976): main=certa -> deriva=recusou; z=15.6 janelas=7 motivo=janelas discordam; | [460s d=-7566 ncc=0.17 2o=0.16 z=2.2 -] [1590s d=-5293 ncc=0.18 2o=0.14 z=2.9 -] [2035s d=-3977 ncc=0.23 2o=0.15 z=2.9 -] [2765s d=-11387 ncc=0.20 2o=0.16 z=2.3 -] [4050s d=6291 ncc=0.16 2o=0.16 z=2.2 -] [4155s d=-12245 ncc=0.17 2o=0.17 z=2.4 -] [5905s d=-6328 ncc=0.15 2o=0.15 z=1.1 -] deriva{menos 
- `onlythevalaint` `g25/23.976|o+1800` (escala 25/23.976): main=certa -> deriva=recusou; z=15.1 janelas=7 motivo=janelas discordam; | [455s d=-4923 ncc=0.17 2o=0.16 z=2.1 -] [1585s d=649 ncc=0.18 2o=0.15 z=2.9 -] [2035s d=-1777 ncc=0.23 2o=0.15 z=3.2 -] [2765s d=-9187 ncc=0.20 2o=0.16 z=2.4 -] [4055s d=3335 ncc=0.20 2o=0.17 z=2.6 -] [4155s d=-10045 ncc=0.17 2o=0.17 z=2.5 -] [5905s d=-4128 ncc=0.15 2o=0.15 z=1.2 -] deriva{menos de 
- `raiders_of_old_california_ipod` `menos2` (deslocamento -2 s): main=certa -> deriva=recusou; z=21.0 janelas=10 motivo=possível deriva: janelas discordam entre si; janelas discordam; | [805s d=2111 ncc=0.74 2o=0.54 z=5.8 ok] [1165s d=2058 ncc=0.76 2o=0.48 z=5.8 ok] [3540s d=2018 ncc=0.83 2o=0.53 z=7.9 ok] [685s d=2045 ncc=0.72 2o=0.30 z=7.7 ok] [1650s d=2050 ncc=0.72 2o=0.45 z=6.9 ok] [2245s d=2008 ncc=0.67 2o=0.58 z=3.2 ok] [3400s d=1865 ncc=0.76 2o=0.48 z=6.5 ok] [3055s d=2072 n
- `raiders_of_old_california_ipod` `g25/23.976|o-4300` (escala 25/23.976): main=certa -> deriva=recusou; z=13.2 janelas=7 motivo=janelas discordam; | [580s d=-10921 ncc=0.16 2o=0.14 z=2.1 -] [975s d=6701 ncc=0.15 2o=0.14 z=2.4 -] [3710s d=5028 ncc=0.34 2o=0.32 z=2.0 -] [695s d=-8325 ncc=0.18 2o=0.13 z=3.2 -] [1725s d=13921 ncc=0.29 2o=0.26 z=2.0 -] [2345s d=703 ncc=0.23 2o=0.19 z=2.2 -] [3185s d=-9404 ncc=0.40 2o=0.36 z=2.9 -] deriva{menos de 2 j
- `tower_of_london_1962_202510` `g1.001|o-2500` (escala 1.001 (24/23.976)): main=recusou -> deriva=ERRADA; z=15.9 janelas=6 motivo= | [200s d=2690 ncc=0.71 2o=0.47 z=6.3 ok] [1210s d=1651 ncc=0.58 2o=0.38 z=4.8 ok] [3210s d=-291 ncc=0.51 2o=0.35 z=4.2 ok] [1815s d=1070 ncc=0.69 2o=0.54 z=4.7 ok] [2395s d=554 ncc=0.41 2o=0.33 z=2.7 ok] [4250s d=-1527 ncc=0.50 2o=0.41 z=5.9 ok] deriva{deriva provada: escala 0.999000, resíduo 145 ms 
- `tower_of_london_1962_202510` `g1.001|o-4300` (escala 1.001 (24/23.976)): main=recusou -> deriva=ERRADA; z=16.3 janelas=6 motivo= | [195s d=4473 ncc=0.70 2o=0.46 z=6.4 ok] [1205s d=3497 ncc=0.57 2o=0.38 z=4.5 ok] [3210s d=1509 ncc=0.51 2o=0.35 z=4.2 ok] [1825s d=2898 ncc=0.64 2o=0.49 z=5.3 ok] [2395s d=2354 ncc=0.41 2o=0.33 z=2.7 ok] [4250s d=273 ncc=0.50 2o=0.41 z=5.9 ok] deriva{deriva provada: escala 0.999000, resíduo 155 ms (
- `tower_of_london_1962_202510` `g25/23.976|o+6200` (escala 25/23.976): main=recusou -> deriva=ERRADA; z=11.1 janelas=6 motivo= | [1250s d=9534 ncc=0.14 2o=0.13 z=1.8 -] [1935s d=-11437 ncc=0.14 2o=0.12 z=2.1 -] [3360s d=-5666 ncc=0.13 2o=0.10 z=2.2 -] [295s d=4657 ncc=0.23 2o=0.21 z=2.4 -] [2390s d=15000 ncc=0.12 2o=0.09 z=2.4 -] [4445s d=15000 ncc=0.18 2o=0.14 z=2.4 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiavei
- `boys_of_the_city` `g23.976/25|o-2500` (escala 23.976/25): main=certa -> deriva=recusou; z=12.9 janelas=5 motivo=janelas discordam; | [430s d=11963 ncc=0.67 2o=0.65 z=1.2 -] [2420s d=-5290 ncc=0.14 2o=0.14 z=2.4 -] [3320s d=-14425 ncc=0.39 2o=0.31 z=2.6 -] [655s d=12873 ncc=0.17 2o=0.14 z=2.4 -] [1750s d=-13124 ncc=0.07 2o=0.03 z=2.5 -] deriva{menos de 2 janelas confiáveis (0 de 5); confiaveis=0 inclinacao=0.000000 provada=0}
- `boys_of_the_city` `g23.976/25|o-900` (escala 23.976/25): main=certa -> deriva=recusou; z=13.2 janelas=5 motivo=janelas discordam; | [450s d=14787 ncc=0.51 2o=0.50 z=1.8 -] [2425s d=3057 ncc=0.13 2o=0.11 z=2.7 -] [3310s d=14557 ncc=0.17 2o=0.12 z=2.5 -] [655s d=11273 ncc=0.17 2o=0.14 z=2.1 -] [1750s d=-14724 ncc=0.07 2o=0.03 z=2.4 -] deriva{menos de 2 janelas confiáveis (0 de 5); confiaveis=0 inclinacao=0.000000 provada=0}
- `the_pearl_of_death_1944` `g23.976/25|o+2600` (escala 23.976/25): main=certa -> deriva=recusou; z=15.0 janelas=6 motivo=janelas discordam; | [1545s d=-5975 ncc=0.14 2o=0.10 z=3.1 -] [2320s d=-8339 ncc=0.23 2o=0.15 z=3.5 -] [2750s d=-7645 ncc=0.10 2o=0.10 z=2.3 -] [375s d=14831 ncc=0.52 2o=0.43 z=3.3 ok] [1320s d=14655 ncc=0.29 2o=0.26 z=3.1 -] [3630s d=-5229 ncc=0.26 2o=0.22 z=2.4 -] deriva{menos de 2 janelas confiáveis (1 de 6); confiav
- `the_killer_shrews` `g25/23.976|o+4000` (escala 25/23.976): main=certa -> deriva=recusou; z=12.6 janelas=6 motivo=pico ambíguo; janelas discordam; | [720s d=8 ncc=0.15 2o=0.14 z=2.4 -] [1505s d=13562 ncc=0.30 2o=0.25 z=3.0 -] [1875s d=-7363 ncc=0.16 2o=0.12 z=2.2 -] [2055s d=-11868 ncc=0.37 2o=0.30 z=4.1 -] [2725s d=-5357 ncc=0.25 2o=0.21 z=3.3 -] [3585s d=8139 ncc=0.21 2o=0.18 z=2.7 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiaveis=0
- `theghosttrain` `mais3` (deslocamento +3 s): main=certa -> deriva=recusou; z=19.7 janelas=7 motivo=janelas discordam; | [940s d=-2890 ncc=0.67 2o=0.49 z=5.0 ok] [1560s d=-2881 ncc=0.54 2o=0.39 z=4.4 ok] [2375s d=-2913 ncc=0.63 2o=0.43 z=5.8 ok] [250s d=-2751 ncc=0.64 2o=0.43 z=3.4 ok] [1680s d=-2877 ncc=0.60 2o=0.39 z=7.3 ok] [3525s d=-2871 ncc=0.51 2o=0.36 z=4.8 ok] [4355s d=-2809 ncc=0.57 2o=0.42 z=3.6 ok] deriva{s

## 4. Distribuicao do z (confianca do alinhador)

| motor | classe | n | z p50 | p90 | p99 | min |
|---|---|---|---|---|---|---|
| main | corrigida certa | 479 | 11.5 | 14.3 | 17.0 | 9.0 |
| main | ACEITE ERRADO | 29 | 11.4 | 12.5 | 13.0 | 9.2 |
| main | recusada (S) | 1774 | 8.3 | 13.4 | 16.9 | 0.0 |
| main | ja certa intacta | 163 | 11.6 | 15.1 | 18.9 | 5.2 |
| drift | corrigida certa | 795 | 15.7 | 19.7 | 23.2 | 9.0 |
| drift | ACEITE ERRADO | 25 | 15.0 | 15.9 | 16.5 | 9.0 |
| drift | recusada (S) | 1462 | 10.6 | 18.0 | 23.2 | 0.0 |
| drift | ja certa intacta | 163 | 16.4 | 21.2 | 24.7 | 4.9 |

## 5. Tempo de analise (CPU do runner do GitHub, 2 fios de VAD; NAO e o tempo no celular/TV)

| motor | janelas (media) | VAD p50 / p95 (s) | alinhador p50 / p95 (s) | total p50 / p95 (s) |
|---|---|---|---|---|
| main | 3.0 | 1.7 / 2.3 | 0.1 / 0.3 | 1.8 / 2.4 |
| drift | 6.8 | 3.0 / 5.7 | 0.7 / 1.9 | 3.8 / 7.5 |

## 6. As 20 razoes de recusa mais comuns (variantes S recusadas: deviam ter sido corrigidas)

Cada recusa pode ter varias clausulas (o motor junta os motivos); conta-se cada clausula.


**main** (1285 variantes S recusadas)

| # | clausula | vezes | exemplos |
|---|---|---|---|
| 1 | janelas discordam | 623 | fast_and_loose_1930/mais3 (z=8.5); fast_and_loose_1930/menos2 (z=7.1); fast_and_loose_1930/mais7.5 (z=8.2) |
| 2 | escala N/N recusada | 406 | maniac_202107/g1.001|o-2500 (z=13.9); maniac_202107/g1.001|o+6200 (z=13.2); maniac_202107/g0.999|o+4000 (z=14.5) |
| 3 | pico ambíguo | 368 | fast_and_loose_1930/g25/23.976|o-1300 (z=5.2); son_of_the_navy/mais3 (z=9.2); son_of_the_navy/menos2 (z=8.4) |
| 4 | escala fora das razões de fps | 300 | meet_john_doe_ipod/g25/23.976|o-2500 (z=8.6); meet_john_doe_ipod/g25/23.976|o+1800 (z=8.7); high_and_low/g23.976/25|o-1300 (z=6.6) |
| 5 | possíveis cortes na legenda (só aviso) | 102 | meet_john_doe_ipod/g0.999|o+2600 (z=11.5); kansas_city_confidential_1952/g0.999|o+1800 (z=9.9); sotheyweremarried/mais7.5 (z=10.1) |
| 6 | z baixo | 90 | fast_and_loose_1930/g25/23.976|o-1300 (z=5.2); high_and_low/g25/23.976|o+2600 (z=5.7); 14_au_fil_de_l_histoire/g23.976/25|o-4300 (z=5.7) |
| 7 | z abaixo do limiar de aceitação (N) | 59 | fast_and_loose_1930/g25/23.976|o-900 (z=7.1); fast_and_loose_1930/g23.976/25|o-2500 (z=8.9); fast_and_loose_1930/g23.976/25|o+2600 (z=8.2) |
| 8 | correlação fraca | 3 | his_girl_friday/g0.999|o+2600 (z=5.7); the_white_sheik/menos2 (z=7.3); the_white_sheik/g1.001|o+2600 (z=5.5) |
| 9 | poucos blocos concordam | 1 | jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/g1.001|o-2500 (z=4.9) |

**drift** (973 variantes S recusadas)

| # | clausula | vezes | exemplos |
|---|---|---|---|
| 1 | janelas discordam | 718 | fast_and_loose_1930/mais3 (z=14.4); fast_and_loose_1930/menos2 (z=13.2); fast_and_loose_1930/g25/23.976|o-900 (z=12.2) |
| 2 | pico ambíguo | 207 | son_of_the_navy/mais3 (z=12.9); son_of_the_navy/menos2 (z=15.0); son_of_the_navy/g0.999|o-900 (z=13.1) |
| 3 | possível deriva: janelas discordam entre si | 197 | son_of_the_navy/menos2 (z=15.0); son_of_the_navy/mais7.5 (z=13.5); son_of_the_navy/g1.001|o+6200 (z=13.7) |
| 4 | escala fora das razões de fps | 196 | fast_and_loose_1930/g25/23.976|o-1300 (z=11.2); meet_john_doe_ipod/g25/23.976|o-2500 (z=10.6); high_and_low/g25/23.976|o+2600 (z=9.4) |
| 5 | ajuste inseguro: janelas fora de uma reta de atraso constante (N ms) | 174 | son_of_the_navy/menos2 (z=15.0); son_of_the_navy/mais7.5 (z=13.5); son_of_the_navy/g0.999|o-900 (z=13.1) |
| 6 | ajuste contradiz as janelas (> N s) | 132 | meet_john_doe_ipod/g1.001|o+6200 (z=18.9); meet_john_doe_ipod/g0.999|o-900 (z=21.1); meet_john_doe_ipod/g0.999|o+2600 (z=19.4) |
| 7 | possíveis cortes na legenda (só aviso) | 119 | son_of_the_navy/g1.001|o+1800 (z=15.4); son_of_the_navy/g0.999|o+2600 (z=15.5); high_and_low/menos2 (z=10.8) |
| 8 | z baixo | 37 | aap44779882/g25/23.976|o-2500 (z=5.8); thenakedhills/g25/23.976|o+6200 (z=5.9); thenakedhills/g25/23.976|o-4300 (z=5.8) |
| 9 | escala N/N sem prova de deriva | 35 | son_of_the_navy/g1.001|o+6200 (z=13.7); high_and_low/g1.001|o-2500 (z=10.6); high_and_low/g1.001|o+6200 (z=10.7) |
| 10 | z abaixo do limiar de aceitação (N) | 4 | jsc2023m000080_ariss_st_lucia_hamm_radio_event/menos2 (z=8.3); jsc2023m000080_ariss_st_lucia_hamm_radio_event/g23.976/25|o+4000 (z=7.5); jsc2023m000080_ariss_st_lucia_hamm_radio_event/g23.976/25|o-2500 (z=6.7) |
| 11 | correlação fraca | 4 | themagicsword/mais3 (z=5.4); jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/menos2 (z=5.0); jsc2022m000260_expedition_68_nasa_en_espanol_y_univision_221/g23.976/25|o-4300 (z=6.1) |

**Motivo completo (todas as clausulas juntas) mais comum, so S recusadas**


main:

- 326 x escala N/N recusada;
- 269 x janelas discordam;
- 102 x possíveis cortes na legenda (só aviso);
- 96 x pico ambíguo; janelas discordam;
- 87 x escala fora das razões de fps;
- 78 x pico ambíguo; escala fora das razões de fps; janelas discordam;
- 63 x z baixo; pico ambíguo; escala fora das razões de fps; janelas discordam;
- 59 x z abaixo do limiar de aceitação (N);

drift:

- 277 x janelas discordam;
- 119 x possíveis cortes na legenda (só aviso);
- 109 x ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;
- 68 x (sem motivo)
- 53 x escala fora das razões de fps; janelas discordam;
- 52 x pico ambíguo; janelas discordam;
- 44 x pico ambíguo; escala fora das razões de fps; janelas discordam;
- 42 x ajuste contradiz as janelas (> N s); ajuste inseguro: janelas fora de uma reta de atraso constante (N ms); possível deriva: janelas discordam entre si; janelas discordam;

## 7. TODOS os aceites errados, aceites falsos e legendas certas mexidas


### main: 29 casos

| titulo | tipo | variante | classe | kind | escala | desl. aplicado (ms) | z | janelas conc. | erro max / p95 (ms) | motivo |
|---|---|---|---|---|---|---|---|---|---|---|
| big_trees (old, 1952) | S | `g1.001|o+6200` | ERRADO | FIX | 1.00000 | -6601 | 9.4 | 3/3 | 4871 / 4139 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g23.976/25|o+1800` | ERRADO | FIX | 1.04271 | -1431 | 11.5 | 3/3 | 446 / 446 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g23.976/25|o+2600` | ERRADO | FIX | 1.04271 | -2254 | 11.0 | 3/3 | 457 / 457 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g25/23.976|o-4300` | ERRADO | FIX | 0.95904 | 4583 | 10.9 | 3/3 | 459 / 459 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `g25/23.976|o-900` | ERRADO | FIX | 0.95904 | 1333 | 11.2 | 3/3 | 469 / 469 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `mais3` | ERRADO | FIX | 1.00000 | -2496 | 11.9 | 3/3 | 504 / 504 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -6996 | 12.1 | 3/3 | 504 / 504 |  |
| diary_of_a_madman_1963 (old, 1963) | S | `menos2` | ERRADO | FIX | 1.00000 | 2503 | 11.6 | 3/3 | 503 / 503 |  |
| fogisland720p1945 (old, 1945) | S | `menos2` | ERRADO | FIX | 1.00000 | 2401 | 13.0 | 3/3 | 401 / 401 |  |
| love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ (old-tv, None) | S | `g25/23.976|o+1800` | ERRADO | FIX | 0.96000 | -2216 | 10.0 | 3/3 | 1005 / 913 |  |
| love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ (old-tv, None) | S | `g25/23.976|o+2600` | ERRADO | FIX | 0.96000 | -2995 | 9.8 | 3/3 | 995 / 903 |  |
| murder_by_invitation_1941 (old, 1941) | S | `mais3` | ERRADO | FIX | 1.00000 | -2562 | 9.6 | 3/3 | 438 / 438 |  |
| murder_by_invitation_1941 (old, 1941) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7073 | 9.9 | 3/3 | 427 / 427 |  |
| pit_and_the_pendulum (old, 1961) | S | `g23.976/25|o+4000` | ERRADO | FIX | 1.04271 | -3679 | 9.7 | 3/3 | 492 / 492 |  |
| pit_and_the_pendulum (old, 1961) | S | `g23.976/25|o-4300` | ERRADO | FIX | 1.04271 | 5007 | 10.5 | 3/3 | 523 / 523 |  |
| pit_and_the_pendulum (old, 1961) | S | `mais3` | ERRADO | FIX | 1.00000 | -2505 | 11.4 | 3/3 | 495 / 495 |  |
| pit_and_the_pendulum (old, 1961) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7005 | 11.6 | 3/3 | 495 / 495 |  |
| robin_hood_18_the_intruders_robin_hood_18_the_intruders (old-tv, None) | S | `g0.999|o+1800` | ERRADO | FIX | 1.00000 | -1177 | 12.6 | 3/3 | 784 / 713 |  |
| robin_hood_18_the_intruders_robin_hood_18_the_intruders (old-tv, None) | S | `g0.999|o+6200` | ERRADO | FIX | 1.00000 | -5591 | 12.1 | 3/3 | 797 / 726 |  |
| screambloodymurder1975 (old, 1973) | S | `g23.976/25|o+1800` | ERRADO | FIX | 1.04271 | -1473 | 11.7 | 3/3 | 404 / 404 |  |
| screambloodymurder1975 (old, 1973) | S | `g23.976/25|o+2600` | ERRADO | FIX | 1.04271 | -2303 | 12.0 | 3/3 | 408 / 408 |  |
| screambloodymurder1975 (old, 1973) | S | `g25/23.976|o-4300` | ERRADO | FIX | 0.95904 | 4538 | 9.6 | 3/3 | 414 / 414 |  |
| the_gorgon_1964 (old, 1964) | S | `g25/23.976|o+1800` | ERRADO | FIX | 0.95904 | -1322 | 9.2 | 3/3 | 404 / 404 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `g25/23.976|o-900` | ERRADO | FIX | 0.95904 | 1298 | 9.3 | 3/3 | 435 / 435 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `mais3` | ERRADO | FIX | 1.00000 | -2557 | 12.2 | 3/3 | 443 / 443 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7059 | 11.8 | 3/3 | 441 / 441 |  |
| tower_of_london_1962_202510 (old, 1962) | S | `menos2` | ERRADO | FIX | 1.00000 | 2436 | 12.5 | 3/3 | 436 / 436 |  |
| valkaama (new, 2010) | S | `mais3` | ERRADO | FIX | 1.00000 | -2471 | 11.4 | 3/3 | 529 / 529 |  |
| valkaama (new, 2010) | S | `menos2` | ERRADO | FIX | 1.00000 | 2540 | 10.8 | 3/3 | 540 / 540 |  |

- **big_trees / g1.001|o+6200 / ERRADO** diagnostico completo: `janelas:3`

- **diary_of_a_madman_1963 / g23.976/25|o+1800 / ERRADO** diagnostico completo: `janelas:3`

- **diary_of_a_madman_1963 / g23.976/25|o+2600 / ERRADO** diagnostico completo: `janelas:3`

- **diary_of_a_madman_1963 / g25/23.976|o-4300 / ERRADO** diagnostico completo: `janelas:3`

- **diary_of_a_madman_1963 / g25/23.976|o-900 / ERRADO** diagnostico completo: `janelas:3`

- **diary_of_a_madman_1963 / mais3 / ERRADO** diagnostico completo: `janelas:3`

- **diary_of_a_madman_1963 / mais7.5 / ERRADO** diagnostico completo: `janelas:3`

- **diary_of_a_madman_1963 / menos2 / ERRADO** diagnostico completo: `janelas:3`

- **fogisland720p1945 / menos2 / ERRADO** diagnostico completo: `janelas:3`

- **love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ / g25/23.976|o+1800 / ERRADO** diagnostico completo: `janelas:3`

- **love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ / g25/23.976|o+2600 / ERRADO** diagnostico completo: `janelas:3`

- **murder_by_invitation_1941 / mais3 / ERRADO** diagnostico completo: `janelas:3`

- **murder_by_invitation_1941 / mais7.5 / ERRADO** diagnostico completo: `janelas:3`

- **pit_and_the_pendulum / g23.976/25|o+4000 / ERRADO** diagnostico completo: `janelas:3`

- **pit_and_the_pendulum / g23.976/25|o-4300 / ERRADO** diagnostico completo: `janelas:3`

- **pit_and_the_pendulum / mais3 / ERRADO** diagnostico completo: `janelas:3`

- **pit_and_the_pendulum / mais7.5 / ERRADO** diagnostico completo: `janelas:3`

- **robin_hood_18_the_intruders_robin_hood_18_the_intruders / g0.999|o+1800 / ERRADO** diagnostico completo: `janelas:3`

- **robin_hood_18_the_intruders_robin_hood_18_the_intruders / g0.999|o+6200 / ERRADO** diagnostico completo: `janelas:3`

- **screambloodymurder1975 / g23.976/25|o+1800 / ERRADO** diagnostico completo: `janelas:3`

- **screambloodymurder1975 / g23.976/25|o+2600 / ERRADO** diagnostico completo: `janelas:3`

- **screambloodymurder1975 / g25/23.976|o-4300 / ERRADO** diagnostico completo: `janelas:3`

- **the_gorgon_1964 / g25/23.976|o+1800 / ERRADO** diagnostico completo: `janelas:3`

- **tower_of_london_1962_202510 / g25/23.976|o-900 / ERRADO** diagnostico completo: `janelas:3`

- **tower_of_london_1962_202510 / mais3 / ERRADO** diagnostico completo: `janelas:3`

- **tower_of_london_1962_202510 / mais7.5 / ERRADO** diagnostico completo: `janelas:3`

- **tower_of_london_1962_202510 / menos2 / ERRADO** diagnostico completo: `janelas:3`

- **valkaama / mais3 / ERRADO** diagnostico completo: `janelas:3`

- **valkaama / menos2 / ERRADO** diagnostico completo: `janelas:3`

### drift: 25 casos

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
| love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ (old-tv, None) | S | `g25/23.976|o+2600` | ERRADO | FIX | 0.96000 | -2995 | 9.8 | 3/3 | 995 / 903 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g0.999|o+1800` | ERRADO | FIX | 1.00100 | -1391 | 15.1 | 6/6 | 411 / 411 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g0.999|o+4000` | ERRADO | FIX | 1.00100 | -3598 | 15.9 | 6/6 | 406 / 406 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g1.001|o-4300` | ERRADO | FIX | 0.99900 | 4713 | 15.0 | 6/6 | 417 / 417 |  |
| murder_by_invitation_1941 (old, 1941) | S | `g1.001|o-900` | ERRADO | FIX | 0.99900 | 1309 | 15.1 | 6/6 | 410 / 410 |  |
| murder_by_invitation_1941 (old, 1941) | S | `mais7.5` | ERRADO | FIX | 1.00000 | -7096 | 15.1 | 6/6 | 404 / 404 |  |
| murder_by_invitation_1941 (old, 1941) | S | `menos2` | ERRADO | FIX | 1.00000 | 2404 | 15.4 | 6/6 | 404 / 404 |  |
| pit_and_the_pendulum (old, 1961) | S | `mais3` | ERRADO | FIX | 1.00000 | -2505 | 11.4 | 3/3 | 495 / 495 |  |
| screambloodymurder1975 (old, 1973) | S | `g25/23.976|o-4300` | ERRADO | FIX | 0.95904 | 4538 | 9.6 | 3/3 | 414 / 414 |  |
| the_gorgon_1964 (old, 1964) | S | `g25/23.976|o+1800` | ERRADO | FIX | 0.95904 | -1322 | 9.2 | 3/3 | 404 / 404 |  |
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

- **love_that_bob_5x02_bob_and_the_dumb_blonde_lovethatbob_5x02_ / g25/23.976|o+2600 / ERRADO** diagnostico completo: `[335s d=-8922 ncc=0.22 2o=0.19 z=2.2 -] [765s d=6485 ncc=0.15 2o=0.14 z=1.3 -] [1230s d=5283 ncc=0.21 2o=0.19 z=2.3 -] [205s d=-11871 ncc=0.47 2o=0.41 z=3.1 -] [575s d=-9113 ncc=0.40 2o=0.39 z=1.8 -] [1020s d=15000 ncc=0.28 2o=0.26 z=2.7 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: ); confiaveis=0 inclinacao=0.000000 provada=0}`

- **murder_by_invitation_1941 / g0.999|o+1800 / ERRADO** diagnostico completo: `[510s d=-860 ncc=0.65 2o=0.56 z=2.5 ok] [1655s d=440 ncc=0.43 2o=0.20 z=5.7 ok] [2965s d=1676 ncc=0.42 2o=0.25 z=5.3 ok] [685s d=-684 ncc=0.66 2o=0.47 z=4.8 ok] [2135s d=864 ncc=0.64 2o=0.53 z=4.1 ok] [3280s d=1809 ncc=0.73 2o=0.61 z=3.2 ok] deriva{deriva provada: escala 1.001001, resíduo 130 ms (reta sem escala deixaria 1356 ms), deriva medida 2770 ms nas janelas e 3888 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=0.000985 provada=1}`

- **murder_by_invitation_1941 / g0.999|o+4000 / ERRADO** diagnostico completo: `[515s d=-3068 ncc=0.65 2o=0.57 z=2.4 ok] [1680s d=-1732 ncc=0.47 2o=0.22 z=4.8 ok] [2965s d=-524 ncc=0.42 2o=0.25 z=5.3 ok] [685s d=-2884 ncc=0.66 2o=0.47 z=4.8 ok] [2130s d=-1364 ncc=0.64 2o=0.52 z=3.8 ok] [3280s d=-391 ncc=0.73 2o=0.61 z=3.2 ok] deriva{deriva provada: escala 1.001001, resíduo 137 ms (reta sem escala deixaria 1362 ms), deriva medida 2765 ms nas janelas e 3888 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=0.000986 provada=1}`

- **murder_by_invitation_1941 / g1.001|o-4300 / ERRADO** diagnostico completo: `[500s d=4127 ncc=0.58 2o=0.52 z=2.3 -] [1675s d=3126 ncc=0.46 2o=0.23 z=5.1 ok] [2965s d=1750 ncc=0.41 2o=0.24 z=5.4 ok] [685s d=3955 ncc=0.66 2o=0.47 z=5.0 ok] [2135s d=2598 ncc=0.64 2o=0.52 z=4.2 ok] [3280s d=1256 ncc=0.73 2o=0.61 z=3.1 ok] deriva{deriva provada: escala 0.999000, resíduo 134 ms (reta sem escala deixaria 1426 ms), deriva medida 2598 ms nas janelas e 3892 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001036 provada=1}`

- **murder_by_invitation_1941 / g1.001|o-900 / ERRADO** diagnostico completo: `[505s d=747 ncc=0.61 2o=0.53 z=2.4 -] [1670s d=-274 ncc=0.42 2o=0.22 z=4.9 ok] [2970s d=-1685 ncc=0.46 2o=0.25 z=5.7 ok] [685s d=555 ncc=0.66 2o=0.47 z=5.0 ok] [2135s d=-802 ncc=0.64 2o=0.52 z=4.2 ok] [3285s d=-2158 ncc=0.71 2o=0.59 z=3.3 ok] deriva{deriva provada: escala 0.999000, resíduo 135 ms (reta sem escala deixaria 1443 ms), deriva medida 2603 ms nas janelas e 3892 ms no filme, 5 janelas confiáveis; confiaveis=5 inclinacao=-0.001039 provada=1}`

- **murder_by_invitation_1941 / mais7.5 / ERRADO** diagnostico completo: `[515s d=-7131 ncc=0.65 2o=0.56 z=2.5 ok] [1685s d=-6948 ncc=0.42 2o=0.19 z=4.4 ok] [2975s d=-7105 ncc=0.50 2o=0.27 z=6.7 ok] [685s d=-7112 ncc=0.66 2o=0.46 z=5.0 ok] [2140s d=-6992 ncc=0.65 2o=0.54 z=3.9 ok] [3290s d=-7227 ncc=0.72 2o=0.59 z=3.3 ok] deriva{sem prova de deriva: resíduo da reta sem escala 148 ms; confiaveis=6 inclinacao=-0.000021 provada=0}`

- **murder_by_invitation_1941 / menos2 / ERRADO** diagnostico completo: `[505s d=2395 ncc=0.62 2o=0.53 z=2.4 ok] [1655s d=2533 ncc=0.43 2o=0.20 z=5.8 ok] [2965s d=2463 ncc=0.42 2o=0.24 z=5.4 ok] [685s d=2388 ncc=0.66 2o=0.46 z=5.0 ok] [2415s d=2434 ncc=0.83 2o=0.78 z=2.7 -] [3280s d=2284 ncc=0.73 2o=0.61 z=3.2 ok] deriva{sem prova de deriva: resíduo da reta sem escala 137 ms; confiaveis=5 inclinacao=-0.000023 provada=0}`

- **pit_and_the_pendulum / mais3 / ERRADO** diagnostico completo: `[830s d=-2585 ncc=0.63 2o=0.47 z=3.7 ok] [1220s d=-2368 ncc=0.60 2o=0.45 z=5.0 ok] [2250s d=-2556 ncc=0.59 2o=0.55 z=3.2 -] [2510s d=-2548 ncc=0.59 2o=0.54 z=3.0 -] [3825s d=-2684 ncc=0.59 2o=0.54 z=3.4 -] [3965s d=-2759 ncc=0.65 2o=0.58 z=3.5 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: janelas discordam;); confiaveis=0 inclinacao=0.000000 provada=0}`

- **screambloodymurder1975 / g25/23.976|o-4300 / ERRADO** diagnostico completo: `[835s d=-13707 ncc=0.10 2o=0.09 z=2.2 -] [1975s d=15000 ncc=0.12 2o=0.11 z=1.7 -] [2805s d=-14843 ncc=0.13 2o=0.11 z=2.2 -] [930s d=-8518 ncc=0.29 2o=0.28 z=1.8 -] [4110s d=-6205 ncc=0.08 2o=0.05 z=2.4 -] [4265s d=8044 ncc=0.12 2o=0.11 z=2.5 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: pico ambíguo; escala fora das razões de fps;); confiaveis=0 inclinacao=0.000000 provada=0}`

- **the_gorgon_1964 / g25/23.976|o+1800 / ERRADO** diagnostico completo: `[885s d=-13443 ncc=0.17 2o=0.14 z=2.5 -] [3495s d=6131 ncc=0.27 2o=0.23 z=2.2 -] [4525s d=-10357 ncc=0.24 2o=0.22 z=2.7 -] [720s d=14735 ncc=0.25 2o=0.17 z=2.6 -] [2080s d=14680 ncc=0.25 2o=0.24 z=2.2 -] [2560s d=10862 ncc=0.11 2o=0.11 z=2.5 -] deriva{plano B: motor anterior sobre as 3 janelas densas (o ajuste de todas as janelas não passou: ); confiaveis=0 inclinacao=0.000000 provada=0}`

- **tower_of_london_1962_202510 / g1.001|o-2500 / ERRADO** diagnostico completo: `[200s d=2690 ncc=0.71 2o=0.47 z=6.3 ok] [1210s d=1651 ncc=0.58 2o=0.38 z=4.8 ok] [3210s d=-291 ncc=0.51 2o=0.35 z=4.2 ok] [1815s d=1070 ncc=0.69 2o=0.54 z=4.7 ok] [2395s d=554 ncc=0.41 2o=0.33 z=2.7 ok] [4250s d=-1527 ncc=0.50 2o=0.41 z=5.9 ok] deriva{deriva provada: escala 0.999000, resíduo 145 ms (reta sem escala deixaria 2379 ms), deriva medida 4054 ms nas janelas e 4783 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=-0.001024 provada=1}`

- **tower_of_london_1962_202510 / g1.001|o-4300 / ERRADO** diagnostico completo: `[195s d=4473 ncc=0.70 2o=0.46 z=6.4 ok] [1205s d=3497 ncc=0.57 2o=0.38 z=4.5 ok] [3210s d=1509 ncc=0.51 2o=0.35 z=4.2 ok] [1825s d=2898 ncc=0.64 2o=0.49 z=5.3 ok] [2395s d=2354 ncc=0.41 2o=0.33 z=2.7 ok] [4250s d=273 ncc=0.50 2o=0.41 z=5.9 ok] deriva{deriva provada: escala 0.999000, resíduo 155 ms (reta sem escala deixaria 2382 ms), deriva medida 4059 ms nas janelas e 4783 ms no filme, 6 janelas confiáveis; confiaveis=6 inclinacao=-0.001024 provada=1}`

- **tower_of_london_1962_202510 / g25/23.976|o+6200 / ERRADO** diagnostico completo: `[1250s d=9534 ncc=0.14 2o=0.13 z=1.8 -] [1935s d=-11437 ncc=0.14 2o=0.12 z=2.1 -] [3360s d=-5666 ncc=0.13 2o=0.10 z=2.2 -] [295s d=4657 ncc=0.23 2o=0.21 z=2.4 -] [2390s d=15000 ncc=0.12 2o=0.09 z=2.4 -] [4445s d=15000 ncc=0.18 2o=0.14 z=2.4 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiaveis=0 inclinacao=0.000000 provada=0}`

- **tower_of_london_1962_202510 / g25/23.976|o-900 / ERRADO** diagnostico completo: `[1260s d=-887 ncc=0.13 2o=0.12 z=1.6 -] [1935s d=-4337 ncc=0.14 2o=0.12 z=2.1 -] [3355s d=-14450 ncc=0.12 2o=0.08 z=2.0 -] [275s d=-12528 ncc=0.34 2o=0.30 z=2.6 -] [2390s d=-14284 ncc=0.25 2o=0.22 z=2.8 -] [4435s d=-8783 ncc=0.17 2o=0.17 z=1.7 -] deriva{menos de 2 janelas confiáveis (0 de 6); confiaveis=0 inclinacao=0.000000 provada=0}`

- **unfaithfully_yours / g25/23.976|o-2500 / ERRADO** diagnostico completo: `[505s d=9188 ncc=0.15 2o=0.14 z=2.7 -] [1465s d=-12588 ncc=-0.01 2o=-0.03 z=2.1 -] [1825s d=8917 ncc=0.27 2o=0.26 z=1.7 -] [2150s d=11643 ncc=0.18 2o=0.16 z=2.6 -] [3645s d=-6163 ncc=0.35 2o=0.33 z=1.9 -] [4495s d=-15000 ncc=-0.05 2o=-0.10 z=2.4 -] [5745s d=-15000 ncc=0.08 2o=0.02 z=2.6 -] deriva{menos de 2 janelas confiáveis (0 de 7); confiaveis=0 inclinacao=0.000000 provada=0}`

- **valkaama / menos2 / ERRADO** diagnostico completo: `[1965s d=2415 ncc=0.51 2o=0.39 z=4.4 ok] [2870s d=2660 ncc=0.75 2o=0.68 z=3.2 -] [4980s d=2489 ncc=0.50 2o=0.41 z=3.3 ok] [735s d=10520 ncc=0.07 2o=0.07 z=4.9 -] [1015s d=2343 ncc=0.61 2o=0.51 z=3.9 ok] [3820s d=2243 ncc=0.70 2o=0.59 z=5.6 ok] deriva{sem prova de deriva: resíduo da reta sem escala 129 ms; confiaveis=4 inclinacao=0.000010 provada=0}`

## 7b. Onde o motor falha: % de variantes S corrigidas CERTAS por caracteristica do titulo

So variantes S (deslocadas/deformadas). `n` = variantes. Serve para achar a causa: se a taxa cai com poucas falas, com a abertura silenciosa, com filmes muito longos, etc.


**por tipo de titulo**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| antigo (TV) | 88 | 9.1% | 86.4% | 88 | 11.4% | 87.5% |
| antigo (filme) | 1628 | 28.3% | 70.3% | 1628 | 47.2% | 51.4% |
| novo | 77 | 14.3% | 83.1% | 77 | 22.1% | 76.6% |

**por decada**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 1920s | 11 | 18.2% | 81.8% | 11 | 100.0% | 0.0% |
| 1930s | 253 | 26.5% | 73.5% | 253 | 57.3% | 42.7% |
| 1940s | 517 | 30.6% | 68.9% | 517 | 47.2% | 51.5% |
| 1950s | 506 | 30.8% | 69.0% | 506 | 48.6% | 51.2% |
| 1960s | 352 | 21.9% | 73.6% | 352 | 33.8% | 62.2% |
| 1970s | 22 | 31.8% | 54.5% | 22 | 54.5% | 40.9% |
| 2010s | 33 | 18.2% | 75.8% | 33 | 33.3% | 63.6% |
| 2020s | 44 | 11.4% | 88.6% | 44 | 13.6% | 86.4% |
| sem ano | 55 | 1.8% | 90.9% | 55 | 1.8% | 96.4% |

**por densidade de falas da legenda**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 4-8 falas/min | 121 | 18.2% | 80.2% | 121 | 24.8% | 74.4% |
| 8-12 falas/min | 528 | 22.0% | 74.4% | 528 | 37.7% | 59.3% |
| >= 12 falas/min | 1144 | 29.8% | 69.5% | 1144 | 49.5% | 49.8% |

**por duracao**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 30-75 min | 660 | 30.9% | 68.6% | 660 | 52.7% | 46.4% |
| 75-120 min | 957 | 26.6% | 71.1% | 957 | 43.9% | 54.2% |
| < 30 min | 143 | 9.1% | 88.1% | 143 | 11.2% | 88.1% |
| > 120 min | 33 | 21.2% | 78.8% | 33 | 33.3% | 66.7% |

**idioma da legenda de referencia**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| PT (filtro do app) | 1012 | 25.5% | 72.8% | 1012 | 41.4% | 57.0% |
| outro idioma | 781 | 28.3% | 70.2% | 781 | 48.1% | 50.7% |

**abertura silenciosa**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| sem silencio longo | 1793 | 26.7% | 71.7% | 1793 | 44.3% | 54.3% |

**nivel da referencia**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| A (so desloca) | 1111 | 34.3% | 63.4% | 1111 | 59.1% | 38.7% |
| B (alass por partes) | 682 | 14.4% | 85.2% | 682 | 20.2% | 79.6% |

**canais de audio da faixa 1**

| classe | n (main) | main certas | main recusadas | n (deriva) | deriva certas | deriva recusadas |
|---|---|---|---|---|---|---|
| 1 canais | 22 | 31.8% | 68.2% | 22 | 72.7% | 27.3% |
| 2 canais | 1771 | 26.7% | 71.7% | 1771 | 44.0% | 54.6% |

## 8. Bugs e comportamentos estranhos achados na execucao

- Excecoes / timeouts do alinhador: **0** variantes (de 4890).
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
| srt-cp1252 | 163 | 157 | 2 | robin_hood_66_the_york_treasure_robin_hood_66_the_york_treas: falas 397 em vez de 400; oy7743534123: tempos certos mas o texto (acentos) saiu diferente |
| srt-cp1252-crlf | 163 | 157 | 2 | robin_hood_66_the_york_treasure_robin_hood_66_the_york_treas: falas 397 em vez de 400; oy7743534123: tempos certos mas o texto (acentos) saiu diferente |
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
| microdvd(nao suportado) | 163 | 6 | 0 | maniac_202107: 215 falas com tempo/classificacao diferente (desvio max 21 ms); fast_and_loose_1930: 393 falas com tempo/classificacao diferente (desvio max 16 ms) |

### Legendas reais baixadas (1072 arquivos de 441 titulos) e o parser do app

- decode do app devolveu null (descarta): 2; destes, o python leu >= 100 falas (legenda valida que o app descartaria): 0
- numero de falas do app diferente do python em >3%: 129
  - fmam_tv_pilot_fmam_tv_pilot `FMAM_TV_Pilot.autogenerated.vtt`: falas app=633 python=0
  - fmam_tv_pilot_fmam_tv_pilot `FMAM_TV_Pilot_512kb.autogenerated.vtt`: falas app=512 python=0
  - 1939_persons_in_hiding_louis_king_vo `1939 - Persons in Hiding - Louis King - VO.autogenerated.vtt`: falas app=1333 python=158
  - osb_15_the_aerialist_osb_15_the_aerialist `OSB-15_The_Aerialist.autogenerated.vtt`: falas app=303 python=0
  - osb_15_the_aerialist_osb_15_the_aerialist `OSB-15_The_Aerialist_512kb.autogenerated.vtt`: falas app=330 python=0
  - 1944_bluebeard_barbazul_edgar_g_ulmer_ve_202012 `1944 - Bluebeard - Barbazul - Edgar G. Ulmer - VE.autogenerated.vtt`: falas app=1155 python=104
  - robin_hood_66_the_york_treasure_robin_hood_66_the_york_treas `Robin_Hood_66_-_The_York_Treasure.autogenerated.vtt`: falas app=477 python=0
  - robin_hood_66_the_york_treasure_robin_hood_66_the_york_treas `Robin_Hood_66_-_The_York_Treasure_512kb.autogenerated.vtt`: falas app=455 python=0
  - tales_of_tomorrow_sneak_attack_tot_sneak_attack `TOT_Sneak_Attack.autogenerated.vtt`: falas app=460 python=0
  - 14_au_fil_de_l_histoire `14_Au_fil_de_l_histoire.autogenerated.vtt`: falas app=1740 python=236
- legendas com falas fora de ordem no arquivo: 15; com sobreposicao > 200 ms em mais de 5 falas: 10; com fim <= inicio em mais de 3 falas: 3; mojibake que sobrou: 1; VTT: 132
  - fora de ordem: maniac_202107 `7334013` (63 falas fora de ordem)
  - fora de ordem: meet_john_doe_ipod `3613424` (7 falas fora de ordem)
  - fora de ordem: 14_au_fil_de_l_histoire `14_Au_fil_de_l_histoire.autogenerated.vtt` (9 falas fora de ordem)
  - fora de ordem: dragnet_thebigtrunk1954_dragnet_big_trunk_01_07_54 `Dragnet_Big_Trunk_01_07_54.autogenerated.vtt` (2 falas fora de ordem)
  - fora de ordem: beverly_hillbillies_ep04_the_clampetts_meet_mrs_drysdale_bh0 `BH04_The_Clampetts_Meet_Mrs_Drysdale_512kb.autogenerated.vtt` (1 falas fora de ordem)
  - fora de ordem: house_of_usher_1960 `5828007` (26 falas fora de ordem)
  - fora de ordem: hells_house_1932 `4893497` (1 falas fora de ordem)
  - fora de ordem: screambloodymurder1975 `7935843` (7 falas fora de ordem)
- idioma das legendas decodificadas segundo o filtro do app (looksPortuguese): nao-PT (eng)=669, PT (parser do app)=298, nao-PT (spa)=66, nao-PT (und)=37

### Casos especiais de midia

- titulos com 2+ faixas de audio: 0; com 5.1 (6+ canais) na faixa 1: 0; filmes de mais de 3 h: 0; com abertura silenciosa >= 60 s: 0
