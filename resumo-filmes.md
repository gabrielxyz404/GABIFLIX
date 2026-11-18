# Silero em Java puro x ONNX Runtime x DSP, no corpus real (clipes de 20 min; mesmo AudioAligner do app; legendas PT)

PRE = 3 janelas de 90 s (plano por densidade) = o que o app decide hoje; FINAL = hipotese, se o app atendesse os pedidos de mais janelas do motor.

| filme | legenda | esperado | Java PRE (app) | ORT PRE | DSP PRE | Java FINAL (hipotese) | Java == ORT |
|---|---|---|---|---|---|---|---|
| brain_that_wouldnt_die | mais3 | aceitar, raw -3000 | z=5.8 raw=+38021 sem-confianca | z=5.8 raw=+38021 sem-confianca | z=5.8 raw=-2875 sem-confianca | z=8.3 raw=-2622 sem-confianca (3 rod, 9 jan) | sim |
| brain_that_wouldnt_die | menos2 | aceitar, raw +2000 | z=5.8 raw=+42665 sem-confianca | z=5.8 raw=+42665 sem-confianca | z=5.8 raw=+2125 sem-confianca | z=8.2 raw=+2378 sem-confianca (3 rod, 9 jan) | sim |
| brain_that_wouldnt_die | certa | nao mexer | z=5.8 raw=+40807 ok(intacta) | z=5.8 raw=+40807 ok(intacta) | z=5.8 raw=+125 ok(intacta) | z=8.1 raw=+378 ok(intacta) (3 rod, 9 jan) | sim |
| brain_that_wouldnt_die | outro | recusar | z=4.8 raw=-19292 ok(recusou) | z=4.8 raw=-19292 ok(recusou) | z=4.5 raw=-391 ok(recusou) | z=4.5 raw=-100811 ok(recusou) (2 rod, 7 jan) | sim |
| charade | mais3 | aceitar, raw -3000 | z=9.0 raw=+2521 sem-confianca | z=9.0 raw=+2521 sem-confianca | z=5.6 raw=-249597 sem-confianca | z=8.6 raw=+9621 sem-confianca (3 rod, 9 jan) | sim |
| charade | menos2 | aceitar, raw +2000 | z=8.9 raw=+7495 sem-confianca | z=8.9 raw=+7495 sem-confianca | z=5.8 raw=-244163 sem-confianca | z=8.7 raw=+14570 sem-confianca (3 rod, 9 jan) | sim |
| charade | certa | nao mexer | z=8.8 raw=+5506 ok(intacta) | z=8.8 raw=+5506 ok(intacta) | z=5.5 raw=-246337 ok(intacta) | z=8.6 raw=+12590 ok(intacta) (3 rod, 9 jan) | sim |
| charade | outro | recusar | z=6.1 raw=-100435 ok(recusou) | z=6.1 raw=-100435 ok(recusou) | z=4.5 raw=-487917 ok(recusou) | z=4.9 raw=+503529 ok(recusou) (3 rod, 9 jan) | sim |
| dementia_13 | mais3 | aceitar, raw -3000 | z=8.4 raw=-2821 sem-confianca | z=8.4 raw=-2821 sem-confianca | z=9.0 raw=-2907 sem-confianca | z=13.0 raw=-2865 sem-confianca (3 rod, 9 jan) | sim |
| dementia_13 | menos2 | aceitar, raw +2000 | z=9.1 raw=+2212 sem-confianca | z=9.1 raw=+2212 sem-confianca | z=9.6 raw=+2094 sem-confianca | z=11.9 raw=+2151 sem-confianca (3 rod, 9 jan) | sim |
| dementia_13 | certa | nao mexer | z=9.3 raw=+210 ok(intacta) | z=9.3 raw=+210 ok(intacta) | z=9.2 raw=+95 ok(intacta) | z=13.0 raw=+152 ok(intacta) (3 rod, 9 jan) | sim |
| dementia_13 | outro | recusar | z=5.3 raw=-78831 ok(recusou) | z=5.3 raw=-78831 ok(recusou) | z=4.9 raw=-118296 ok(recusou) | z=4.6 raw=+52039 ok(recusou) (3 rod, 9 jan) | sim |
| detour | mais3 | aceitar, raw -3000 | z=6.1 raw=+77885 sem-confianca | z=6.1 raw=+77885 sem-confianca | z=5.7 raw=-192093 sem-confianca | z=7.6 raw=+10599 sem-confianca (3 rod, 8 jan) | sim |
| detour | menos2 | aceitar, raw +2000 | z=7.1 raw=+95199 sem-confianca | z=7.1 raw=+95199 sem-confianca | z=6.1 raw=-164000 sem-confianca | z=7.1 raw=+20885 sem-confianca (3 rod, 8 jan) | sim |
| detour | certa | nao mexer | z=6.7 raw=-39840 ok(intacta) | z=6.7 raw=-39840 ok(intacta) | z=5.8 raw=-116651 ok(intacta) | z=7.8 raw=+16736 ok(intacta) (3 rod, 8 jan) | sim |
| detour | outro | recusar | z=5.1 raw=-449991 ok(recusou) | z=5.1 raw=-449991 ok(recusou) | z=5.6 raw=-788691 ok(recusou) | z=4.4 raw=+17308 ok(recusou) (1 rod, 5 jan) | sim |
| freaks | mais3 | aceitar, raw -3000 | z=6.0 raw=+43894 sem-confianca | z=6.0 raw=+43894 sem-confianca | z=7.1 raw=-3004 sem-confianca | z=7.0 raw=-2235 sem-confianca (2 rod, 7 jan) | sim |
| freaks | menos2 | aceitar, raw +2000 | z=7.2 raw=+29218 sem-confianca | z=7.2 raw=+29218 sem-confianca | z=6.9 raw=+1987 sem-confianca | z=5.9 raw=+67763 sem-confianca (2 rod, 7 jan) | sim |
| freaks | certa | nao mexer | z=5.8 raw=+86 ok(intacta) | z=5.8 raw=+86 ok(intacta) | z=6.8 raw=-4 ok(intacta) | z=6.7 raw=+15 ok(intacta) (2 rod, 7 jan) | sim |
| freaks | outro | recusar | z=0.0 raw=+0 ok(recusou) | z=0.0 raw=+0 ok(recusou) | z=5.4 raw=-646709 ok(recusou) | z=0.0 raw=+0 ok(recusou) (0 rod, 3 jan) | sim |
| his_girl_friday | mais3 | aceitar, raw -3000 | z=7.6 raw=-2768 sem-confianca | z=7.6 raw=-2768 sem-confianca | z=7.8 raw=-2884 sem-confianca | z=9.6 raw=-2785 ok(corrigiu) (1 rod, 5 jan) | sim |
| his_girl_friday | menos2 | aceitar, raw +2000 | z=6.2 raw=+2230 sem-confianca | z=6.2 raw=+2230 sem-confianca | z=7.4 raw=+2111 sem-confianca | z=11.8 raw=+2184 ok(corrigiu) (2 rod, 7 jan) | sim |
| his_girl_friday | certa | nao mexer | z=8.5 raw=+218 ok(intacta) | z=8.5 raw=+218 ok(intacta) | z=7.8 raw=+135 ok(intacta) | z=11.5 raw=+187 ok(intacta) (1 rod, 5 jan) | sim |
| his_girl_friday | outro | recusar | z=5.4 raw=-193005 ok(recusou) | z=5.4 raw=-193005 ok(recusou) | z=4.8 raw=-85817 ok(recusou) | z=4.2 raw=+169703 ok(recusou) (2 rod, 7 jan) | sim |
| hitch_hiker | mais3 | aceitar, raw -3000 | z=9.6 raw=-3175 sem-confianca | z=9.6 raw=-3175 sem-confianca | z=8.5 raw=-2910 sem-confianca | z=15.5 raw=-2382 sem-confianca (3 rod, 9 jan) | sim |
| hitch_hiker | menos2 | aceitar, raw +2000 | z=9.3 raw=+1830 sem-confianca | z=9.3 raw=+1830 sem-confianca | z=8.4 raw=+2090 sem-confianca | z=14.8 raw=+2618 sem-confianca (3 rod, 9 jan) | sim |
| hitch_hiker | certa | nao mexer | z=9.5 raw=-172 ok(intacta) | z=9.5 raw=-172 ok(intacta) | z=8.5 raw=+90 ok(intacta) | z=15.4 raw=+618 ok(intacta) (3 rod, 9 jan) | sim |
| hitch_hiker | outro | recusar | z=5.1 raw=-13619 ok(recusou) | z=5.1 raw=-13619 ok(recusou) | z=4.2 raw=-41262 ok(recusou) | z=4.4 raw=+295767 ok(recusou) (2 rod, 7 jan) | sim |
| house_on_haunted_hill | mais3 | aceitar, raw -3000 | z=18.1 raw=-1802 sem-confianca | z=18.1 raw=-1802 sem-confianca | z=9.5 raw=-1906 sem-confianca | z=21.1 raw=-1839 sem-confianca (3 rod, 8 jan) | sim |
| house_on_haunted_hill | menos2 | aceitar, raw +2000 | z=17.2 raw=+3192 sem-confianca | z=17.2 raw=+3192 sem-confianca | z=8.8 raw=+3076 sem-confianca | z=20.0 raw=+3169 sem-confianca (3 rod, 8 jan) | sim |
| house_on_haunted_hill | certa | nao mexer | z=18.4 raw=+1192 ok(intacta) | z=18.4 raw=+1192 ok(intacta) | z=11.2 raw=+1101 ok(intacta) | z=21.9 raw=+1171 ok(intacta) (3 rod, 8 jan) | sim |
| house_on_haunted_hill | outro | recusar | z=3.9 raw=-137743 ok(recusou) | z=3.9 raw=-137743 ok(recusou) | z=4.2 raw=-106945 ok(recusou) | z=3.9 raw=-137743 ok(recusou) (0 rod, 3 jan) | sim |
| little_shop | mais3 | aceitar, raw -3000 | z=6.7 raw=-2955 sem-confianca | z=6.7 raw=-2955 sem-confianca | z=7.7 raw=-3043 sem-confianca | z=8.0 raw=-3691 sem-confianca (3 rod, 8 jan) | sim |
| little_shop | menos2 | aceitar, raw +2000 | z=6.7 raw=+2014 sem-confianca | z=6.7 raw=+2014 sem-confianca | z=7.4 raw=+1956 sem-confianca | z=9.0 raw=+2324 sem-confianca (3 rod, 8 jan) | sim |
| little_shop | certa | nao mexer | z=6.6 raw=+43 ok(intacta) | z=6.6 raw=+43 ok(intacta) | z=6.9 raw=-37 ok(intacta) | z=8.1 raw=+320 ok(intacta) (3 rod, 8 jan) | sim |
| little_shop | outro | recusar | z=5.2 raw=-539431 ok(recusou) | z=5.2 raw=-539431 ok(recusou) | z=5.3 raw=+49262 ok(recusou) | z=4.7 raw=-354474 ok(recusou) (3 rod, 9 jan) | sim |
| meet_john_doe | mais3 | aceitar, raw -3000 | z=11.9 raw=-2867 sem-confianca | z=11.9 raw=-2867 sem-confianca | z=12.2 raw=-2825 sem-confianca | z=15.1 raw=-2793 sem-confianca (3 rod, 8 jan) | sim |
| meet_john_doe | menos2 | aceitar, raw +2000 | z=12.5 raw=+2281 sem-confianca | z=12.5 raw=+2281 sem-confianca | z=11.8 raw=+2315 sem-confianca | z=15.2 raw=+2312 sem-confianca (3 rod, 8 jan) | sim |
| meet_john_doe | certa | nao mexer | z=12.2 raw=+133 ok(intacta) | z=12.2 raw=+133 ok(intacta) | z=12.4 raw=+175 ok(intacta) | z=15.4 raw=+207 ok(intacta) (3 rod, 8 jan) | sim |
| meet_john_doe | outro | recusar | z=5.1 raw=-425485 ok(recusou) | z=5.1 raw=-425485 ok(recusou) | z=5.7 raw=-334237 ok(recusou) | z=4.4 raw=-426685 ok(recusou) (1 rod, 5 jan) | sim |
| my_man_godfrey | mais3 | aceitar, raw -3000 | z=5.7 raw=+18322 sem-confianca | z=5.7 raw=+18322 sem-confianca | z=6.2 raw=-6647 sem-confianca | z=6.9 raw=+13992 sem-confianca (3 rod, 9 jan) | sim |
| my_man_godfrey | menos2 | aceitar, raw +2000 | z=5.6 raw=+22798 sem-confianca | z=5.6 raw=+22798 sem-confianca | z=6.1 raw=-1378 sem-confianca | z=6.7 raw=+18681 sem-confianca (3 rod, 9 jan) | sim |
| my_man_godfrey | certa | nao mexer | z=5.6 raw=+21008 ok(intacta) | z=5.6 raw=+21008 ok(intacta) | z=6.2 raw=-3486 ok(intacta) | z=6.6 raw=+16806 ok(intacta) (3 rod, 9 jan) | sim |
| my_man_godfrey | outro | recusar | z=4.8 raw=+21775 ok(recusou) | z=4.8 raw=+21775 ok(recusou) | z=6.3 raw=-399687 ok(recusou) | z=4.5 raw=-92718 ok(recusou) (3 rod, 9 jan) | sim |
| santa_claus_martians | mais3 | aceitar, raw -3000 | z=6.4 raw=-5970 sem-confianca | z=6.4 raw=-5970 sem-confianca | z=6.9 raw=-2427 sem-confianca | z=7.1 raw=-3335 sem-confianca (3 rod, 9 jan) | sim |
| santa_claus_martians | menos2 | aceitar, raw +2000 | z=6.4 raw=-970 sem-confianca | z=6.4 raw=-970 sem-confianca | z=6.8 raw=+2495 sem-confianca | z=7.3 raw=+1608 sem-confianca (3 rod, 9 jan) | sim |
| santa_claus_martians | certa | nao mexer | z=6.4 raw=-2970 ok(intacta) | z=6.4 raw=-2970 ok(intacta) | z=7.0 raw=+526 ok(intacta) | z=7.2 raw=-369 ok(intacta) (3 rod, 9 jan) | sim |
| santa_claus_martians | outro | recusar | z=5.3 raw=-53780 ok(recusou) | z=5.3 raw=-53780 ok(recusou) | z=5.0 raw=-468363 ok(recusou) | z=4.4 raw=-146744 ok(recusou) (3 rod, 9 jan) | sim |
| scarlet_street | mais3 | aceitar, raw -3000 | z=11.7 raw=-3047 ok(corrigiu) | z=11.7 raw=-3047 ok(corrigiu) | z=9.0 raw=-3089 ok(corrigiu) | z=12.8 raw=-3047 ok(corrigiu) (1 rod, 4 jan) | sim |
| scarlet_street | menos2 | aceitar, raw +2000 | z=11.7 raw=+1970 ok(corrigiu) | z=11.7 raw=+1970 ok(corrigiu) | z=8.8 raw=+1921 sem-confianca | z=12.3 raw=+1953 ok(corrigiu) (1 rod, 4 jan) | sim |
| scarlet_street | certa | nao mexer | z=12.1 raw=-30 ok(intacta) | z=12.1 raw=-30 ok(intacta) | z=8.9 raw=-79 ok(intacta) | z=12.8 raw=-47 ok(intacta) (1 rod, 4 jan) | sim |
| scarlet_street | outro | recusar | z=5.2 raw=-90865 ok(recusou) | z=5.2 raw=-90865 ok(recusou) | z=5.8 raw=-463285 ok(recusou) | z=4.4 raw=+154212 ok(recusou) (2 rod, 7 jan) | sim |
| sherlock_secret_weapon | mais3 | aceitar, raw -3000 | z=6.1 raw=+9488 sem-confianca | z=6.1 raw=+9488 sem-confianca | z=5.2 raw=+85879 sem-confianca | z=6.0 raw=+17108 sem-confianca (3 rod, 8 jan) | sim |
| sherlock_secret_weapon | menos2 | aceitar, raw +2000 | z=5.2 raw=+19199 sem-confianca | z=5.2 raw=+19199 sem-confianca | z=5.0 raw=+99208 sem-confianca | z=5.6 raw=+20348 sem-confianca (3 rod, 8 jan) | sim |
| sherlock_secret_weapon | certa | nao mexer | z=5.2 raw=+17267 ok(intacta) | z=5.2 raw=+17267 ok(intacta) | z=5.0 raw=+97161 ok(intacta) | z=5.6 raw=+18414 ok(intacta) (3 rod, 8 jan) | sim |
| sherlock_secret_weapon | outro | recusar | z=4.7 raw=-111129 ok(recusou) | z=4.7 raw=-111129 ok(recusou) | z=4.7 raw=-111341 ok(recusou) | z=4.3 raw=-548340 ok(recusou) (2 rod, 7 jan) | sim |
| suddenly | mais3 | aceitar, raw -3000 | z=11.5 raw=-2711 ok(corrigiu) | z=11.5 raw=-2711 ok(corrigiu) | z=8.8 raw=-2811 sem-confianca | z=13.0 raw=-2464 sem-confianca (4 rod, 10 jan) | sim |
| suddenly | menos2 | aceitar, raw +2000 | z=11.5 raw=+2289 ok(corrigiu) | z=11.5 raw=+2289 ok(corrigiu) | z=8.8 raw=+2189 sem-confianca | z=12.9 raw=+2536 sem-confianca (4 rod, 10 jan) | sim |
| suddenly | certa | nao mexer | z=11.6 raw=+289 ok(intacta) | z=11.6 raw=+289 ok(intacta) | z=8.9 raw=+189 ok(intacta) | z=13.3 raw=+536 ok(intacta) (4 rod, 10 jan) | sim |
| suddenly | outro | recusar | z=4.1 raw=+195252 ok(recusou) | z=4.1 raw=+195252 ok(recusou) | z=4.6 raw=-691063 ok(recusou) | z=4.1 raw=+195252 ok(recusou) (0 rod, 3 jan) | sim |
| the_stranger | mais3 | aceitar, raw -3000 | z=12.1 raw=-2808 ok(corrigiu) | z=12.1 raw=-2808 ok(corrigiu) | z=6.8 raw=-2868 sem-confianca | z=12.1 raw=-2808 ok(corrigiu) (0 rod, 3 jan) | sim |
| the_stranger | menos2 | aceitar, raw +2000 | z=11.7 raw=+2192 ok(corrigiu) | z=11.7 raw=+2192 ok(corrigiu) | z=6.9 raw=+2132 sem-confianca | z=11.7 raw=+2192 ok(corrigiu) (0 rod, 3 jan) | sim |
| the_stranger | certa | nao mexer | z=12.1 raw=+192 ok(intacta) | z=12.1 raw=+192 ok(intacta) | z=6.9 raw=+132 ok(intacta) | z=12.1 raw=+192 ok(intacta) (0 rod, 3 jan) | sim |
| the_stranger | outro | recusar | z=4.8 raw=+104958 ok(recusou) | z=4.8 raw=+104958 ok(recusou) | z=5.2 raw=-524552 ok(recusou) | z=4.4 raw=-198968 ok(recusou) (1 rod, 5 jan) | sim |
| white_zombie | mais3 | aceitar, raw -3000 | z=8.9 raw=-2845 sem-confianca | z=8.9 raw=-2845 sem-confianca | z=5.5 raw=+12369 sem-confianca | z=10.5 raw=-2711 sem-confianca (3 rod, 8 jan) | sim |
| white_zombie | menos2 | aceitar, raw +2000 | z=8.8 raw=+2155 sem-confianca | z=8.8 raw=+2155 sem-confianca | z=5.0 raw=+2101 sem-confianca | z=10.5 raw=+2275 sem-confianca (3 rod, 8 jan) | sim |
| white_zombie | certa | nao mexer | z=9.0 raw=+155 ok(intacta) | z=9.0 raw=+155 ok(intacta) | z=5.1 raw=+101 ok(intacta) | z=10.7 raw=+275 ok(intacta) (3 rod, 8 jan) | sim |
| white_zombie | outro | recusar | z=4.8 raw=+119566 ok(recusou) | z=4.8 raw=+119566 ok(recusou) | z=5.0 raw=-7852 ok(recusou) | z=4.4 raw=-101963 ok(recusou) (2 rod, 7 jan) | sim |

## Totais por VAD (quantas legendas em cada veredito)

- 3 janelas, **java**: ok(corrigiu)=6, ok(intacta)=17, ok(recusou)=17, sem-confianca=28
- 3 janelas, **ort**: ok(corrigiu)=6, ok(intacta)=17, ok(recusou)=17, sem-confianca=28
- 3 janelas, **dsp**: ok(corrigiu)=1, ok(intacta)=17, ok(recusou)=17, sem-confianca=33
- FINAL (hipotese: com rodadas extras), **java**: ok(corrigiu)=6, ok(intacta)=17, ok(recusou)=17, sem-confianca=28
- FINAL (hipotese: com rodadas extras), **ort**: ok(corrigiu)=6, ok(intacta)=17, ok(recusou)=17, sem-confianca=28
- FINAL (hipotese: com rodadas extras), **dsp**: ok(corrigiu)=3, ok(intacta)=17, ok(recusou)=17, sem-confianca=31

Java == ORT em PRE (mesma decisao, deslocamento +-1 ms, escala +-1e-5, z +-0,1): **68 de 68** legendas; maior |dz| = 0.000

Java == ORT em FULL (mesma decisao, deslocamento +-1 ms, escala +-1e-5, z +-0,1): **68 de 68** legendas; maior |dz| = 0.000

z medio nas legendas que existem para aceitar (mais3, menos2, certa; FINAL): Java/Silero 10.7, DSP 8.8 (n=51); Silero > DSP em 43, DSP > Silero em 8

## Legendas deslocadas que o app RECUSA (Java, 3 janelas): por que?

| filme | legenda | z | deslocamento certo? | motivo do motor |
|---|---|---|---|---|
| brain_that_wouldnt_die | mais3 | 5.8 | sim | z baixo; pico ambíguo; escala fora das razões de fps; janelas discordam;  |
| brain_that_wouldnt_die | menos2 | 5.8 | sim | z baixo; pico ambíguo; escala fora das razões de fps; janelas discordam;  |
| charade | mais3 | 9.0 | sim | pico ambíguo; escala fora das razões de fps;  |
| charade | menos2 | 8.9 | sim | pico ambíguo; escala fora das razões de fps;  |
| dementia_13 | mais3 | 8.4 | sim | janelas discordam;  |
| dementia_13 | menos2 | 9.1 | sim | janelas discordam;  |
| detour | mais3 | 6.1 | nao | possíveis cortes na legenda (só aviso); |
| detour | menos2 | 7.1 | nao | pico ambíguo; escala fora das razões de fps;  |
| freaks | mais3 | 6.0 | nao | z baixo; pico ambíguo; escala fora das razões de fps;  |
| freaks | menos2 | 7.2 | nao | pico ambíguo; escala fora das razões de fps; janelas discordam;  |
| his_girl_friday | mais3 | 7.6 | sim | z abaixo do limiar de aceitação (9.0); |
| his_girl_friday | menos2 | 6.2 | sim | z abaixo do limiar de aceitação (9.0); |
| hitch_hiker | mais3 | 9.6 | sim | escala 0,999/1,001 recusada;  |
| hitch_hiker | menos2 | 9.3 | sim | escala 0,999/1,001 recusada;  |
| house_on_haunted_hill | mais3 | 18.1 | sim | escala 0,999/1,001 recusada;  |
| house_on_haunted_hill | menos2 | 17.2 | sim | escala 0,999/1,001 recusada;  |
| little_shop | mais3 | 6.7 | sim | z abaixo do limiar de aceitação (9.0); |
| little_shop | menos2 | 6.7 | sim | janelas discordam;  |
| meet_john_doe | mais3 | 11.9 | sim | janelas discordam;  |
| meet_john_doe | menos2 | 12.5 | sim | janelas discordam;  |
| my_man_godfrey | mais3 | 5.7 | sim | z baixo; pico ambíguo; escala fora das razões de fps; janelas discordam;  |
| my_man_godfrey | menos2 | 5.6 | sim | z baixo; pico ambíguo; escala fora das razões de fps; janelas discordam;  |
| santa_claus_martians | mais3 | 6.4 | sim | pico ambíguo; janelas discordam;  |
| santa_claus_martians | menos2 | 6.4 | sim | pico ambíguo; janelas discordam;  |
| sherlock_secret_weapon | mais3 | 6.1 | nao | pico ambíguo; escala fora das razões de fps; janelas discordam;  |
| sherlock_secret_weapon | menos2 | 5.2 | sim | z baixo; pico ambíguo; escala fora das razões de fps; janelas discordam;  |
| white_zombie | mais3 | 8.9 | sim | z abaixo do limiar de aceitação (9.0); |
| white_zombie | menos2 | 8.8 | sim | pico ambíguo;  |

Recusadas com z >= 9: 8 de 28 (deslocamento certo em 8); recusadas com z < 9: 20 (deslocamento certo em 15)
