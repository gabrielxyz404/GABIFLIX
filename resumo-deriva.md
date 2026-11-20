# Deriva de escala (1,001 e afins): legendas deformadas a partir da certa, audio REAL (Silero em Java puro, o motor do app)

Filmes/recortes: 20 (brain_that_wouldnt_die, carnival_of_souls, charade, dementia_13, detour, freaks, his_girl_friday, hitch_hiker, house_on_haunted_hill, last_man_on_earth, little_shop, meet_john_doe, my_man_godfrey, night_of_the_living_dead, santa_claus_martians, scarlet_street, sherlock_secret_weapon, suddenly, the_stranger, white_zombie)


## ANTES (origin/main: 3 janelas densas, regra 0,999/1,001)

| familia | n | aceitas | certas (p95<=400 ms, max<=800 ms) | ERRADAS | recusadas | mexidas/falsas | janelas (media) |
|---|---|---|---|---|---|---|---|
| ja certa | 44 | 8 | 44 | 0 | 0 | 0 | 3.0 |
| escala 0.999 | 66 | 0 | 0 | 0 | 66 | 0 | 3.0 |
| escala 1.001 | 66 | 0 | 0 | 0 | 66 | 0 | 3.0 |
| escala 23.976/25 | 66 | 7 | 7 | 0 | 59 | 0 | 3.0 |
| escala 25/23.976 | 66 | 12 | 9 | 3 | 54 | 0 | 3.0 |
| so deslocamento | 44 | 9 | 8 | 1 | 35 | 0 | 3.0 |
| outro filme | 44 | 0 | 0 | 0 | 44 | 0 | 3.0 |
| outro filme (com escala) | 88 | 0 | 0 | 0 | 88 | 0 | 3.0 |

**Casos ruins (old):**

- hitch_hiker-clip `menos2` classe=ERRADO escala=1.000000 desl=2546 erroMax=546 z=11.7 janelas=3 | 
- hitch_hiker-clip `g25/23.976|o+0` classe=ERRADO escala=0.960000 desl=-168 erroMax=967 z=9.7 janelas=3 | 
- hitch_hiker-clip `g25/23.976|o+1800` classe=ERRADO escala=0.960000 desl=-1896 erroMax=967 z=9.5 janelas=3 | 
- hitch_hiker-clip `g25/23.976|o-2500` classe=ERRADO escala=0.960000 desl=2232 erroMax=967 z=9.5 janelas=3 | 

## NOVO com janelas densas (so a prova de deriva)

| familia | n | aceitas | certas (p95<=400 ms, max<=800 ms) | ERRADAS | recusadas | mexidas/falsas | janelas (media) |
|---|---|---|---|---|---|---|---|
| ja certa | 44 | 7 | 44 | 0 | 0 | 0 | 4.5 |
| escala 0.999 | 66 | 15 | 13 | 2 | 51 | 0 | 4.6 |
| escala 1.001 | 66 | 15 | 14 | 1 | 51 | 0 | 5.0 |
| escala 23.976/25 | 66 | 5 | 5 | 0 | 61 | 0 | 3.4 |
| escala 25/23.976 | 66 | 6 | 6 | 0 | 60 | 0 | 3.0 |
| so deslocamento | 44 | 7 | 7 | 0 | 37 | 0 | 4.3 |
| outro filme | 44 | 0 | 0 | 0 | 44 | 0 | 3.0 |
| outro filme (com escala) | 88 | 0 | 0 | 0 | 88 | 0 | 3.0 |

**Casos ruins (dense):**

- hitch_hiker-clip `g1.001|o-2500` classe=ERRADO escala=0.999000 desl=3035 erroMax=537 z=11.4 janelas=3 | [345s d=2563 ncc=0.48 2o=0.39 z=3.8 ok] [720s d=2225 ncc=0.79 2o=0.65 z=3.0 ok] [1060s d=2053 ncc=0.64 2o=0.49 z=4.6 ok] fin{valid=3 contra=0 consenso=0 planB=0 [345s n=-83 L=-83 z=3.5][720s n=-37 L=-37 z=3.1][1060s n=136 L=136 z=3.4]}deriva{deriva provada: escala 0.999000, resíduo 121 ms (reta sem escala deixaria 311 ms), deriva medida 716 ms nas janelas e 1201 ms no filme, 3 janelas confiáveis; 
- hitch_hiker-clip `g0.999|o+0` classe=ERRADO escala=1.001001 desl=535 erroMax=535 z=12.6 janelas=3 | [350s d=809 ncc=0.64 2o=0.54 z=5.8 ok] [720s d=1274 ncc=0.79 2o=0.65 z=3.0 ok] [1060s d=1792 ncc=0.64 2o=0.48 z=4.8 ok] fin{valid=3 contra=0 consenso=0 planB=0 [350s n=-131 L=-131 z=2.9][720s n=-36 L=-36 z=3.1][1060s n=137 L=137 z=3.4]}deriva{deriva provada: escala 1.001001, resíduo 152 ms (reta sem escala deixaria 501 ms), deriva medida 710 ms nas janelas e 1200 ms no filme, 3 janelas confiáveis;
- hitch_hiker-clip `g0.999|o+1800` classe=ERRADO escala=1.001001 desl=-1242 erroMax=559 z=13.2 janelas=3 | [350s d=-991 ncc=0.64 2o=0.54 z=5.8 ok] [695s d=-487 ncc=0.72 2o=0.58 z=4.4 ok] [1060s d=-8 ncc=0.64 2o=0.48 z=4.8 ok] fin{valid=3 contra=0 consenso=0 planB=0 [350s n=-155 L=-155 z=2.9][695s n=37 L=37 z=3.7][1060s n=113 L=113 z=3.4]}deriva{deriva provada: escala 1.001001, resíduo 145 ms (reta sem escala deixaria 497 ms), deriva medida 710 ms nas janelas e 1200 ms no filme, 3 janelas confiáveis; co

## NOVO com 6 janelas de 90 s espalhadas (+ extras ate 8)

| familia | n | aceitas | certas (p95<=400 ms, max<=800 ms) | ERRADAS | recusadas | mexidas/falsas | janelas (media) |
|---|---|---|---|---|---|---|---|
| ja certa | 44 | 7 | 44 | 0 | 0 | 0 | 6.7 |
| escala 0.999 | 66 | 23 | 23 | 0 | 43 | 0 | 6.4 |
| escala 1.001 | 66 | 20 | 20 | 0 | 46 | 0 | 6.4 |
| escala 23.976/25 | 66 | 9 | 6 | 3 | 57 | 0 | 6.4 |
| escala 25/23.976 | 66 | 8 | 7 | 1 | 58 | 0 | 6.0 |
| so deslocamento | 44 | 8 | 8 | 0 | 36 | 0 | 6.7 |
| outro filme | 44 | 0 | 0 | 0 | 44 | 0 | 6.0 |
| outro filme (com escala) | 88 | 0 | 0 | 0 | 88 | 0 | 6.0 |

**Casos ruins (spread6):**

- hitch_hiker-clip `g25/23.976|o-2500` classe=ERRADO escala=0.959040 desl=2808 erroMax=410 z=13.0 janelas=6 | [80s d=-1027 ncc=0.10 2o=0.09 z=3.4 -] [350s d=-15000 ncc=0.35 2o=0.31 z=2.6 -] [525s d=-4800 ncc=0.30 2o=0.20 z=4.1 ok] [620s d=-12315 ncc=0.20 2o=0.17 z=2.2 -] [845s d=6432 ncc=0.19 2o=0.18 z=1.9 -] [1025s d=-2947 ncc=0.12 2o=0.12 z=2.8 -] fin{valid=4 contra=0 consenso=1 planB=0 [350s n=-1 L=-1 z=2.9][525s n=-103 L=-103 z=4.7][620s n=101 L=101 z=3.6][845s n=1 L=1 z=2.9]}deriva{menos de 2 janelas
- sherlock_secret_weapon `g23.976/25|o+0` classe=ERRADO escala=0.959040 desl=72014 erroMax=238884 z=11.1 janelas=6 | [555s d=-15000 ncc=0.20 2o=0.19 z=1.4 -] [690s d=5782 ncc=0.21 2o=0.19 z=1.9 -] [1745s d=-18 ncc=0.41 2o=0.37 z=2.8 -] [2350s d=-12818 ncc=0.20 2o=0.16 z=2.8 -] [2695s d=-12008 ncc=0.26 2o=0.20 z=3.0 -] [3380s d=10655 ncc=0.20 2o=0.15 z=2.4 -] fin{valid=6 contra=0 consenso=1 planB=0 [555s n=34 L=34 z=4.4][690s n=-43 L=-43 z=5.4][1745s n=-44 L=-44 z=8.8][2350s n=47 L=47 z=5.1][2695s n=-3 L=-3 z=5.1
- sherlock_secret_weapon `g23.976/25|o+1800` classe=ERRADO escala=0.959040 desl=70286 erroMax=238886 z=10.6 janelas=6 | [560s d=-15000 ncc=0.16 2o=0.13 z=1.5 -] [690s d=3982 ncc=0.21 2o=0.19 z=1.8 -] [1745s d=-1818 ncc=0.41 2o=0.37 z=2.8 -] [2350s d=-14618 ncc=0.20 2o=0.13 z=3.3 -] [2695s d=-13808 ncc=0.26 2o=0.20 z=3.0 -] [3405s d=11229 ncc=0.24 2o=0.23 z=2.5 -] fin{valid=6 contra=0 consenso=1 planB=0 [560s n=30 L=30 z=4.6][690s n=-41 L=-41 z=5.5][1745s n=-42 L=-42 z=8.9][2350s n=49 L=49 z=5.1][2695s n=-1 L=-1 z=5
- sherlock_secret_weapon `g23.976/25|o-2500` classe=ERRADO escala=0.959040 desl=74412 erroMax=238884 z=10.8 janelas=6 | [555s d=-13224 ncc=0.30 2o=0.25 z=2.1 -] [690s d=8282 ncc=0.21 2o=0.19 z=2.1 -] [1740s d=2440 ncc=0.41 2o=0.36 z=3.0 -] [2345s d=-10290 ncc=0.20 2o=0.18 z=2.4 -] [2690s d=-9606 ncc=0.27 2o=0.22 z=2.8 -] [3400s d=13079 ncc=0.25 2o=0.24 z=2.5 -] fin{valid=6 contra=0 consenso=1 planB=0 [555s n=34 L=34 z=4.4][690s n=-43 L=-43 z=5.4][1740s n=-33 L=-33 z=7.1][2345s n=35 L=35 z=5.1][2690s n=7 L=7 z=5.0][

## NOVO FINAL: 3 janelas densas (como antes) + 6 espalhadas + extras (ate 10)

| familia | n | aceitas | certas (p95<=400 ms, max<=800 ms) | ERRADAS | recusadas | mexidas/falsas | janelas (media) |
|---|---|---|---|---|---|---|---|
| ja certa | 44 | 7 | 44 | 0 | 0 | 0 | 6.9 |
| escala 0.999 | 66 | 23 | 22 | 1 | 43 | 0 | 6.6 |
| escala 1.001 | 66 | 20 | 20 | 0 | 46 | 0 | 6.5 |
| escala 23.976/25 | 66 | 11 | 8 | 3 | 55 | 0 | 6.5 |
| escala 25/23.976 | 66 | 6 | 6 | 0 | 60 | 0 | 6.1 |
| so deslocamento | 44 | 8 | 8 | 0 | 36 | 0 | 6.8 |
| outro filme | 44 | 0 | 0 | 0 | 44 | 0 | 6.2 |
| outro filme (com escala) | 88 | 0 | 0 | 0 | 88 | 0 | 6.4 |

**Casos ruins (uniao):**

- hitch_hiker-clip `g0.999|o-2500` classe=ERRADO escala=1.001001 desl=2919 erroMax=417 z=13.8 janelas=6 | [134s d=5930 ncc=0.36 2o=0.32 z=2.7 -] [554s d=3417 ncc=0.65 2o=0.41 z=5.3 ok] [974s d=4352 ncc=0.61 2o=0.51 z=5.0 ok] [345s d=3344 ncc=0.49 2o=0.39 z=3.7 ok] [735s d=3796 ncc=0.79 2o=0.65 z=3.1 ok] [870s d=3779 ncc=0.60 2o=0.50 z=4.0 ok] fin{valid=4 contra=0 consenso=0 planB=0 [554s n=-87 L=-87 z=6.9][345s n=37 L=37 z=3.4][735s n=107 L=107 z=2.5][870s n=-19 L=-19 z=3.0]}deriva{deriva provada: esc
- sherlock_secret_weapon `g23.976/25|o+0` classe=ERRADO escala=0.959040 desl=72014 erroMax=238884 z=11.1 janelas=6 | [1745s d=-18 ncc=0.41 2o=0.37 z=2.8 -] [2695s d=-12008 ncc=0.26 2o=0.20 z=3.0 -] [3380s d=10655 ncc=0.20 2o=0.15 z=2.4 -] [555s d=-15000 ncc=0.20 2o=0.19 z=1.4 -] [690s d=5782 ncc=0.21 2o=0.19 z=1.9 -] [2350s d=-12818 ncc=0.20 2o=0.16 z=2.8 -] fin{valid=6 contra=0 consenso=1 planB=0 [1745s n=-44 L=-44 z=8.8][2695s n=-3 L=-3 z=5.1][3380s n=1121 L=-4473 z=3.4][555s n=34 L=34 z=4.4][690s n=-43 L=-43 
- sherlock_secret_weapon `g23.976/25|o+1800` classe=ERRADO escala=0.959040 desl=70286 erroMax=238886 z=10.6 janelas=6 | [1745s d=-1818 ncc=0.41 2o=0.37 z=2.8 -] [2695s d=-13808 ncc=0.26 2o=0.20 z=3.0 -] [3405s d=11229 ncc=0.24 2o=0.23 z=2.5 -] [560s d=-15000 ncc=0.16 2o=0.13 z=1.5 -] [690s d=3982 ncc=0.21 2o=0.19 z=1.8 -] [2350s d=-14618 ncc=0.20 2o=0.13 z=3.3 -] fin{valid=6 contra=0 consenso=1 planB=0 [1745s n=-42 L=-42 z=8.9][2695s n=-1 L=-1 z=5.1][3405s n=1156 L=13964 z=3.3][560s n=30 L=30 z=4.6][690s n=-41 L=-4
- sherlock_secret_weapon `g23.976/25|o-2500` classe=ERRADO escala=0.959040 desl=74412 erroMax=238884 z=10.8 janelas=6 | [1740s d=2440 ncc=0.41 2o=0.36 z=3.0 -] [2690s d=-9606 ncc=0.27 2o=0.22 z=2.8 -] [3400s d=13079 ncc=0.25 2o=0.24 z=2.5 -] [555s d=-13224 ncc=0.30 2o=0.25 z=2.1 -] [690s d=8282 ncc=0.21 2o=0.19 z=2.1 -] [2345s d=-10290 ncc=0.20 2o=0.18 z=2.4 -] fin{valid=6 contra=0 consenso=1 planB=0 [1740s n=-33 L=-33 z=7.1][2690s n=7 L=7 z=5.0][3400s n=1146 L=13890 z=3.3][555s n=34 L=34 z=4.4][690s n=-43 L=-43 z=

## Os 4 casos antes recusados so pela regra 0,999/1,001 (recorte de 20 min, hitch_hiker e house_on_haunted_hill, +3 s e -2 s)

| recorte | legenda | modo | classe | escala | desl (ms) | erroMax (ms) | z | janelas |
|---|---|---|---|---|---|---|---|---|
| hitch_hiker-clip | mais3 | dense | recusou | 1.000000 | -2513 | 3000 | 15.6 | 6 |
| hitch_hiker-clip | mais3 | spread6 | recusou | 1.000000 | -2572 | 3000 | 17.9 | 7 |
| hitch_hiker-clip | mais3 | uniao | recusou | 1.000000 | -2556 | 3000 | 17.5 | 7 |
| hitch_hiker-clip | menos2 | dense | recusou | 1.000000 | 2546 | 2000 | 11.7 | 3 |
| hitch_hiker-clip | menos2 | spread6 | recusou | 1.000000 | 2436 | 2000 | 15.8 | 7 |
| hitch_hiker-clip | menos2 | uniao | recusou | 1.000000 | 2432 | 2000 | 16.4 | 7 |
| hitch_hiker-clip | mais3 | old | recusou | 1.000000 | -2430 | 3000 | 12.2 | 3 |
| hitch_hiker-clip | menos2 | old | ERRADO | 1.000000 | 2546 | 546 | 11.7 | 3 |
| house_on_haunted_hill-clip | mais3 | dense | recusou | 1.000000 | -2965 | 3000 | 15.9 | 6 |
| house_on_haunted_hill-clip | mais3 | spread6 | recusou | 1.000000 | -2937 | 3000 | 16.6 | 6 |
| house_on_haunted_hill-clip | mais3 | uniao | recusou | 1.000000 | -2937 | 3000 | 16.3 | 6 |
| house_on_haunted_hill-clip | menos2 | dense | recusou | 1.000000 | 2664 | 2000 | 15.1 | 6 |
| house_on_haunted_hill-clip | menos2 | spread6 | recusou | 1.000000 | 2069 | 2000 | 15.5 | 6 |
| house_on_haunted_hill-clip | menos2 | uniao | recusou | 1.000000 | 2069 | 2000 | 15.3 | 6 |
| house_on_haunted_hill-clip | mais3 | old | recusou | 0.999000 | -1692 | 3000 | 12.0 | 3 |
| house_on_haunted_hill-clip | menos2 | old | recusou | 0.999000 | 3288 | 2000 | 11.4 | 3 |

## Por filme (modo final: uniao densas + espalhadas)

| filme | ja certas intactas | deriva 1,001/0,999 certas | 25/23,976 certas | so deslocamento certas | outro filme falsos |
|---|---|---|---|---|---|
| brain_that_wouldnt_die | 2/2 | 0/6 | 0/6 | 0/2 | 0/6 |
| carnival_of_souls | 2/2 | 6/6 | 5/6 | 1/2 | 0/6 |
| charade | 2/2 | 6/6 | 3/6 | 2/2 | 0/6 |
| dementia_13 | 2/2 | 6/6 | 0/6 | 0/2 | 0/6 |
| detour | 2/2 | 0/6 | 0/6 | 0/2 | 0/6 |
| freaks | 2/2 | 0/6 | 0/6 | 0/2 | 0/6 |
| his_girl_friday | 2/2 | 4/6 | 0/6 | 1/2 | 0/6 |
| hitch_hiker | 2/2 | 1/6 | 0/6 | 0/2 | 0/6 |
| hitch_hiker-clip | 2/2 | 1/6 | 0/6 | 0/2 | 0/6 |
| house_on_haunted_hill | 2/2 | 0/6 | 0/6 | 0/2 | 0/6 |
| house_on_haunted_hill-clip | 2/2 | 0/6 | 0/6 | 0/2 | 0/6 |
| last_man_on_earth | 2/2 | 0/6 | 0/6 | 0/2 | 0/6 |
| little_shop | 2/2 | 0/6 | 0/6 | 0/2 | 0/6 |
| meet_john_doe | 2/2 | 0/6 | 0/6 | 0/2 | 0/6 |
| my_man_godfrey | 2/2 | 0/6 | 0/6 | 0/2 | 0/6 |
| night_of_the_living_dead | 2/2 | 0/6 | 0/6 | 0/2 | 0/6 |
| santa_claus_martians | 2/2 | 0/6 | 0/6 | 0/2 | 0/6 |
| scarlet_street | 2/2 | 6/6 | 0/6 | 0/2 | 0/6 |
| sherlock_secret_weapon | 2/2 | 0/6 | 0/6 | 0/2 | 0/6 |
| suddenly | 2/2 | 6/6 | 0/6 | 2/2 | 0/6 |
| the_stranger | 2/2 | 6/6 | 6/6 | 2/2 | 0/6 |
| white_zombie | 2/2 | 0/6 | 0/6 | 0/2 | 0/6 |
