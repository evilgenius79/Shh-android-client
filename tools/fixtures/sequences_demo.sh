#!/bin/bash
# Cursor addressing, erasing, insertion and deletion, scroll regions, alternate screen.
e=$'\033'
printf "${e}[2J${e}[H"
for i in $(seq 1 24); do printf "${e}[${i};1Hrow ${i} abcdefghijklmnopqrstuvwxyz0123456789"; done
printf "${e}[3;10H${e}[K"                 # erase to end of line
printf "${e}[4;10H${e}[1K"                # erase to start of line
printf "${e}[5;10H${e}[2K"                # erase whole line
printf "${e}[6;10H${e}[5@"                # insert five blanks
printf "${e}[7;10H${e}[5P"                # delete five characters
printf "${e}[8;10H${e}[5X"                # erase five characters
printf "${e}[9;1H${e}[2L"                 # insert two lines
printf "${e}[13;1H${e}[2M"                # delete two lines
printf "${e}[15;20r"                      # scroll region rows 15..20
printf "${e}[20;1H\n\n\nscrolled in region"
printf "${e}[15;1H${e}M${e}Mreverse index"
printf "${e}[r"
printf "${e}[22;1H${e}[3S"                # scroll up three
printf "${e}[2T"                          # scroll down two
printf "${e}[10;40Hsave${e}7${e}[1;60Hjump${e}8 restored"
printf "${e}[11;40Habc${e}[3b"            # repeat
printf "${e}[12;40H${e}[4hINS${e}[4l"     # insert mode
printf "${e}[1;1H${e}[5C${e}[2B${e}[3D${e}[1A*"
printf "${e}[23;1H${e}[0J"
printf "${e}[?1049h${e}[Halternate screen${e}[?1049l"
printf "${e}[20;50H${e}[1;31mcolour${e}[0m${e}[24;1H"
printf "tabs:\tA\tB${e}[3g\tC${e}[2G${e}[Kdone"
