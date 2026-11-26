#!/bin/bash
# Scroll regions combined with origin mode, plus index/reverse index at the region edges.
e=$'\033'
printf "${e}[2J${e}[H"
for i in $(seq 1 24); do printf "${e}[${i};1H%02d ----------------------------------------" "$i"; done
printf "${e}[5;10r${e}[?6h"               # region 5..10 with origin mode
printf "${e}[1;1Horigin top"
printf "${e}[6;1Horigin bottom"
printf "${e}[99;1H\nwrapped scroll"
printf "${e}[1;1H${e}Mreverse at top"
printf "${e}[?6l${e}[r"
printf "${e}[12;18r"
printf "${e}[18;1H"; for i in 1 2 3 4 5 6 7 8 9 10; do printf "\nfeed ${i}"; done
printf "${e}[12;1H${e}[3L"
printf "${e}[14;1H${e}[2M"
printf "${e}[20;1Houtside region stays"
printf "${e}[r${e}[24;1H"
printf "${e}D${e}D${e}Dindex at bottom"
printf "${e}[1;1H${e}M${e}Mreverse at top of screen"
printf "${e}[10;30H${e}Enext line"
