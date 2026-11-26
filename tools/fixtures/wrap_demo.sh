#!/bin/bash
# Behaviour around the right edge on a 40 column terminal.
# Cursor-left from the pending-wrap column is deliberately not covered here: tmux and xterm
# disagree about it (we follow xterm) and that case has a unit test of its own.
e=$'\033'
printf "${e}[2J${e}[H"
printf '%040d' 0 | tr 0 a; printf "b\n"                 # exactly fills the line, then wraps
printf '%040d' 0 | tr 0 c; printf "\r\nafter crlf\n"    # pending wrap cancelled by CR
printf '%040d' 0 | tr 0 d; printf "${e}[Kkept\n"        # erase with the wrap pending
printf '%040d' 0 | tr 0 f; printf "\tT\n"               # tab at the edge
printf "${e}[?7l"; printf '%050d' 0 | tr 0 g; printf "${e}[?7h\n"   # autowrap off
printf '%039d' 0 | tr 0 h; printf "中\n"                 # wide char at the last column
printf '%038d' 0 | tr 0 i; printf "中!\n"
printf "end"
