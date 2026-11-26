#!/bin/bash
# Wide, combining and special characters, including wrapping of wide characters.
e=$'\033'
printf "ASCII and café naïve Ünïcödé\n"
printf "CJK: 中文字符 日本語 한국어 done\n"
printf "Combining: é ä ỗ ñ end\n"
printf "Emoji: 😀 🎉 👍 🚀 end\n"
printf "Box: ┌─┬─┐ │ │ │ ├─┼─┤ └─┴─┘ ═║╔╗╚╝\n"
printf "Blocks: ░▒▓█ ▀▄▌▐ ←↑→↓ ✓✗ •◦\n"
printf "Fullwidth: ＡＢＣ１２３ half: ｱｲｳ\n"
printf "DEC graphics: ${e}(0lqqqwqqqk${e}(B\n"
printf "              ${e}(0x   x   x${e}(B\n"
printf "              ${e}(0mqqqvqqqj${e}(B ${e}(0a\`fgy z{|}~${e}(B\n"
# 78 columns of filler, then a wide character that cannot fit in the last cell.
printf '%078d' 0 | tr 0 x; printf "W中文W\n"
printf '%079d' 0 | tr 0 y; printf "中\n"
printf "中%.0s" $(seq 1 45); printf "\n"
printf "tab\tstops\tevery\teight\tcolumns\n"
printf "overwrite half: 中文\b\bX\n"
