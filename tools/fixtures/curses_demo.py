#!/usr/bin/env python3
"""A small curses program that exercises windows, colours, line drawing and scrolling."""
import curses


def main(stdscr):
    curses.curs_set(1)
    curses.start_color()
    curses.use_default_colors()
    for i in range(1, 8):
        curses.init_pair(i, i, -1)
    curses.init_pair(8, curses.COLOR_BLACK, curses.COLOR_CYAN)
    height, width = stdscr.getmaxyx()

    stdscr.border()
    stdscr.addstr(0, 3, " curses demo ", curses.A_BOLD)
    for i in range(1, 8):
        stdscr.addstr(2, 2 + (i - 1) * 9, f"colour {i}", curses.color_pair(i) | (curses.A_BOLD if i % 2 else 0))
    stdscr.addstr(3, 2, "reverse", curses.A_REVERSE)
    stdscr.addstr(3, 12, "underline", curses.A_UNDERLINE)
    stdscr.addstr(3, 24, "dim", curses.A_DIM)
    stdscr.hline(4, 1, curses.ACS_HLINE, width - 2)

    box = curses.newwin(8, 30, 6, 4)
    box.box()
    box.addstr(0, 2, " list ")
    items = ["alpha", "beta", "gamma", "delta", "epsilon", "zeta"]
    log = curses.newwin(10, 36, 6, 40)
    log.scrollok(True)
    log.bkgd(" ", curses.color_pair(8))
    log.addstr("log window\n")
    selected = 0
    counter = 0

    while True:
        for i, item in enumerate(items):
            attr = curses.A_REVERSE if i == selected else curses.A_NORMAL
            box.addstr(1 + i, 2, f"{item:<24}", attr)
        stdscr.addstr(height - 2, 2, f"selected={items[selected]:<10} counter={counter:<4}")
        stdscr.noutrefresh()
        box.noutrefresh()
        log.noutrefresh()
        curses.doupdate()
        key = stdscr.getch()
        if key == ord("q"):
            break
        if key == ord("j"):
            selected = (selected + 1) % len(items)
        elif key == ord("k"):
            selected = (selected - 1) % len(items)
        elif key == ord("s"):
            for _ in range(5):
                counter += 1
                log.addstr(f"entry number {counter} scrolls the window\n")
        elif key == ord("w"):
            stdscr.addstr(height - 4, 2, "wide: 中文 ok", curses.color_pair(3))
            stdscr.insertln()
            stdscr.move(1, 1)
            stdscr.deleteln()


curses.wrapper(main)
