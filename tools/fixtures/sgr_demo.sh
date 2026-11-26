#!/bin/bash
# Text attributes and colours in every notation.
e=$'\033'
printf "${e}[1mbold${e}[0m ${e}[2mfaint${e}[0m ${e}[3mitalic${e}[0m ${e}[4munderline${e}[0m ${e}[7minverse${e}[0m ${e}[9mstrike${e}[0m\n"
for i in 0 1 2 3 4 5 6 7; do printf "${e}[3${i}m fg${i} ${e}[4${i}m bg${i} ${e}[0m"; done; printf "\n"
for i in 0 1 2 3 4 5 6 7; do printf "${e}[9${i}m fg${i} ${e}[10${i};30m bg${i} ${e}[0m"; done; printf "\n"
for i in $(seq 16 51); do printf "${e}[48;5;${i}m  "; done; printf "${e}[0m\n"
for i in $(seq 232 255); do printf "${e}[38;5;${i}m#"; done; printf "${e}[0m\n"
for i in $(seq 0 8 255); do printf "${e}[48;2;${i};0;$((255 - i))m "; done; printf "${e}[0m\n"
printf "${e}[38:2::255:128:0mcolon rgb${e}[0m ${e}[38:5:45mcolon indexed${e}[0m ${e}[4:3mcurly${e}[4:0m plain\n"
printf "${e}[1;31;44mstacked${e}[22;39mstill blue bg${e}[49m done${e}[m\n"
printf "${e}[41m${e}[Kred line to the edge${e}[0m\n"
printf "normal after reset\n"
