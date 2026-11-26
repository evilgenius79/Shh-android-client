#!/usr/bin/env python3
"""Records terminal fixtures for the differential tests in :terminal-core.

Each scenario runs a real program inside a detached tmux pane that claims to be xterm-256color.
Two files are written per scenario:

  <name>.raw       every byte the program wrote to its terminal
  <name>.expected  what tmux, used here as the reference emulator, shows afterwards: a header
                   line "# cols rows cursor_x cursor_y alternate" followed by the screen text

The Kotlin test `ReferenceFixturesTest` replays the .raw file into our emulator and compares the
result with the .expected file, so the tests themselves need neither tmux nor the programs.

Usage: python3 tools/record_fixtures.py [scenario ...]
Needs: tmux, vim, less, nano, htop, bash, python3 (with curses), git.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "terminal-core", "src", "test", "resources", "fixtures")
ASSETS = os.path.join(ROOT, "tools", "fixtures")
SOCKET = "shh-fixtures"

TMUX_CONF = """
set -g default-terminal "xterm-256color"
set -g status off
set -g escape-time 0
set -g history-limit 2000
set -g focus-events off
"""


def tmux(*args, check=True):
    return subprocess.run(["tmux", "-L", SOCKET, *args], check=check, capture_output=True, text=True).stdout


class Scenario:
    def __init__(self, name, command, steps, cols=80, rows=24):
        self.name, self.command, self.steps, self.cols, self.rows = name, command, steps, cols, rows

    def run(self, workdir):
        raw = os.path.join(OUT, self.name + ".raw")
        if os.path.exists(raw):
            os.remove(raw)
        env = {
            "HOME": workdir, "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "PATH": os.environ["PATH"],
            "PS1": "$ ", "SHELL": "/bin/bash", "PAGER": "less", "LESSHISTFILE": "-",
            "TERM": "xterm-256color", "TZ": "UTC",
        }
        exports = " ".join(f"{k}={shell_quote(v)}" for k, v in env.items())
        # The short sleep gives us time to attach the output pipe before the program starts.
        command = self.command.replace("$ASSETS", ASSETS)
        inner = f"sleep 0.4; cd {shell_quote(workdir)}; env -i {exports} {command}; sleep 1000"
        tmux("-f", os.path.join(workdir, "tmux.conf"), "new-session", "-d", "-s", self.name,
             "-x", str(self.cols), "-y", str(self.rows), inner)
        tmux("pipe-pane", "-t", self.name, "-o", f"cat > {shell_quote(raw)}")
        time.sleep(1.0)
        for step in self.steps:
            if isinstance(step, (int, float)):
                time.sleep(step)
            elif isinstance(step, tuple):
                tmux("send-keys", "-t", self.name, *step)
                time.sleep(0.25)
            else:
                tmux("send-keys", "-t", self.name, "-l", step)
                time.sleep(0.25)
        time.sleep(0.8)
        # Freeze everything on the pane's tty so no output can arrive between the two snapshots.
        tty = tmux("display-message", "-p", "-t", self.name, "#{pane_tty}").strip()
        subprocess.run(["pkill", "-STOP", "-t", tty.replace("/dev/", "")], check=False)
        time.sleep(0.5)
        info = tmux("display-message", "-p", "-t", self.name,
                    "#{cursor_x} #{cursor_y} #{alternate_on}").split()
        screen = plain_text(tmux("capture-pane", "-e", "-p", "-t", self.name))
        subprocess.run(["pkill", "-KILL", "-t", tty.replace("/dev/", "")], check=False)
        tmux("kill-session", "-t", self.name, check=False)
        time.sleep(0.3)
        lines = screen.split("\n")
        if lines and lines[-1] == "":
            lines.pop()
        lines = [line.rstrip(" ") for line in lines]
        while len(lines) < self.rows:
            lines.append("")
        with open(os.path.join(OUT, self.name + ".expected"), "w", encoding="utf-8") as f:
            f.write(f"# {self.cols} {self.rows} {info[0]} {info[1]} {info[2]}\n")
            f.write("\n".join(lines[: self.rows]) + "\n")
        print(f"{self.name}: {os.path.getsize(raw)} bytes, cursor {info[0]},{info[1]} alt={info[2]}")


# DEC Special Graphics, the character set programs select for line drawing. tmux keeps such
# cells as the original ASCII letter plus a flag and marks them with SO/SI in "capture-pane -e".
DEC_SPECIAL = dict(zip(
    "_`abcdefghijklmnopqrstuvwxyz{|}~",
    "\u00a0\u25c6\u2592\u2409\u240c\u240d\u240a\u00b0\u00b1\u2424\u240b\u2518\u2510\u250c\u2514\u253c"
    "\u23ba\u23bb\u2500\u23bc\u23bd\u251c\u2524\u2534\u252c\u2502\u2264\u2265\u03c0\u2260\u00a3\u00b7",
))


def plain_text(captured):
    """Turns "capture-pane -e" output into plain text with line drawing characters resolved."""
    captured = re.sub(r"\x1b\[[0-9;:]*m", "", captured)
    captured = re.sub(r"\x1b\]8;[^\x1b\x07]*(?:\x1b\\\\|\x07)", "", captured)
    out, graphics = [], False
    for ch in captured:
        if ch == "\x0e":
            graphics = True
        elif ch == "\x0f":
            graphics = False
        elif graphics and ch in DEC_SPECIAL:
            out.append(DEC_SPECIAL[ch])
        else:
            out.append(ch)
    text = "".join(out)
    if "\x1b" in text:
        raise SystemExit("unexpected escape sequence left in captured screen: " + repr(text[:200]))
    return text


def shell_quote(text):
    return "'" + text.replace("'", "'\\''") + "'"


def make_workdir():
    workdir = tempfile.mkdtemp(prefix="shh-fixtures-")
    with open(os.path.join(workdir, "tmux.conf"), "w") as f:
        f.write(TMUX_CONF)
    with open(os.path.join(workdir, "sample.py"), "w") as f:
        f.write(SAMPLE_PY)
    with open(os.path.join(workdir, "long.txt"), "w") as f:
        for i in range(1, 301):
            f.write(f"line {i:03d}: the quick brown fox jumps over the lazy dog " + "=" * (i % 37) + "\n")
    with open(os.path.join(workdir, "notes.txt"), "w") as f:
        f.write("alpha\nbeta\ngamma\ndelta\nepsilon\n")
    with open(os.path.join(workdir, ".bashrc"), "w") as f:
        f.write("PS1='$ '\nunset PROMPT_COMMAND\n")
    with open(os.path.join(workdir, ".bash_profile"), "w") as f:
        f.write(". ~/.bashrc\n")
    # A small git repository with a branchy history for `git log --graph`.
    repo = os.path.join(workdir, "repo")
    os.makedirs(repo)
    git_env = dict(os.environ, HOME=workdir, GIT_AUTHOR_NAME="A", GIT_AUTHOR_EMAIL="a@example.org",
                   GIT_COMMITTER_NAME="A", GIT_COMMITTER_EMAIL="a@example.org",
                   GIT_AUTHOR_DATE="2024-01-01T00:00:00Z", GIT_COMMITTER_DATE="2024-01-01T00:00:00Z")
    def git(*args):
        subprocess.run(["git", "-c", "commit.gpgsign=false", *args], cwd=repo, check=True, env=git_env, capture_output=True)
    git("init", "-q", "-b", "main")
    for i in range(6):
        with open(os.path.join(repo, "f.txt"), "a") as f:
            f.write(f"{i}\n")
        git("add", "-A")
        git("commit", "-q", "-m", f"main commit {i}")
        if i == 2:
            git("checkout", "-q", "-b", "topic")
            with open(os.path.join(repo, "g.txt"), "w") as f:
                f.write("topic\n")
            git("add", "-A")
            git("commit", "-q", "-m", "topic work")
            git("checkout", "-q", "main")
    git("merge", "-q", "--no-ff", "-m", "merge topic", "topic")
    return workdir


SAMPLE_PY = '''#!/usr/bin/env python3
"""A little module used as editing material."""
import os
import sys


class Greeter:
    def __init__(self, name: str = "world") -> None:
        self.name = name

    def greet(self, times: int = 1) -> str:
        return "\\n".join(f"Hello, {self.name}!" for _ in range(times))


def main(argv):
    greeter = Greeter(argv[1] if len(argv) > 1 else "world")
    print(greeter.greet(3))
    for key, value in sorted(os.environ.items()):
        if key.startswith("LC_"):
            print(key, "=", value)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
'''

# Process viewers run in their own PID namespace (needs root) so that the recording shows a few
# dummy processes instead of whatever happens to be running on the recording machine.
PRIVATE_PROCESSES = "unshare --pid --fork --mount-proc sh -c 'sleep 1000 & sleep 2000 & (sleep 3000; true) & exec "

ESC = ("Escape",)
ENTER = ("Enter",)

SCENARIOS = [
    Scenario("ls-color", "sh -c 'ls -la --color=always /usr/bin | head -150'", []),
    Scenario("printf-sgr", "bash $ASSETS/sgr_demo.sh", []),
    Scenario("unicode", "bash $ASSETS/unicode_demo.sh", []),
    Scenario("sequences", "bash $ASSETS/sequences_demo.sh", []),
    Scenario("margins", "bash $ASSETS/margins_demo.sh", []),
    Scenario("wrap-edge", "bash $ASSETS/wrap_demo.sh", [], cols=40, rows=12),
    Scenario("curses", "python3 $ASSETS/curses_demo.py", [1.0, "j", "j", "k", "s", "s", "s", "w", 0.5]),
    Scenario("git-log", "sh -c 'cd repo && git --no-pager log --graph --color=always --oneline --decorate --all'", []),
    Scenario("vim-edit", "vim -u NONE -N notes.txt", [
        "ihello world", ESC, "o", "second line of text", ESC, "ggyyjp", "dd", "u", ":set number", ENTER,
        "G", "Othis is inserted above the last line", ESC, ":vsplit", ENTER, ":split", ENTER,
        ("C-w",), "j", "ix", ESC, ("C-w",), "l", "ggVGy", "P", ":set list", ENTER,
    ]),
    Scenario("vim-syntax", "vim -u DEFAULTS sample.py", [
        ":syntax on", ENTER, ":set number relativenumber cursorline", ENTER, "10G", "w", "v", "e",
        ESC, "/greet", ENTER, "n", ("C-e",), ("C-e",), ("C-e",), ("C-y",), ("C-d",), ("C-u",), "zz",
        ":set hlsearch", ENTER,
    ]),
    Scenario("vim-scroll", "vim -u NONE -N long.txt", [
        ("C-f",), ("C-f",), ("C-b",), "50G", "zt", "dd", "dd", "p", "10j", "O", "new", ESC,
        ("C-e",), ("C-e",), ("C-y",), "100G", ":25", ENTER, "J", "J", "x", "u",
    ]),
    Scenario("vim-help", "vim -u NONE -N", [":help", ENTER, ("C-f",), ("C-w",), "j", "ifoo", ESC, ":q!", ENTER, ("C-b",)]),
    Scenario("less", "less -R long.txt", [" ", " ", "b", "/fox", ENTER, "n", "50g", "j", "j", "k", "G", "g", ">", "<"]),
    Scenario("less-wrap", "less -R long.txt", [" ", "j", "j", "G", "k", "k"], cols=45, rows=15),
    Scenario("nano", "nano -I notes.txt", [
        "inserted text ", ("Down",), ("End",), " appended", ENTER, "a new line", ("C-k",), ("C-u",), ("C-u",),
        ("C-w",), "gamma", ENTER, ("M-u",), ("PageDown",), ("PageUp",),
    ]),
    Scenario("htop", PRIVATE_PROCESSES + "htop -d 600 -C'", [1.5, ("Down",), ("Down",), ("F6",), ESC, "t", 0.5]),
    Scenario("htop-color", PRIVATE_PROCESSES + "htop -d 600'", [1.5, ("F2",), 0.5, ("Down",), ("Right",), ESC, 0.5]),
    Scenario("bash", "bash --noprofile --rcfile .bashrc -i", [
        "echo hello", ENTER, "ls -la", ENTER,
        "echo this is a rather long command line that will certainly wrap around the right edge of the terminal window twice",
        ("C-a",), ("M-f",), ("M-f",), ("C-k",), "x", ENTER, ("Up",), ("Up",), ("C-e",), " | cat", ENTER,
        "seq 1 40", ENTER, ("C-l",), "echo cleared", ENTER, ("C-r",), "hel", ENTER, "printf 'a\\tb\\tc\\n'", ENTER,
    ]),
    Scenario("bash-narrow", "bash --noprofile --rcfile .bashrc -i", [
        "echo 0123456789012345678901234567890123456789", ENTER,
        "echo abcdefghijklmnopqrstuvwxyz", ("C-a",), ("Right",), ("Right",), "ZZ", ("C-e",), ("BSpace",), ("BSpace",), ENTER,
        "seq 1 30 | paste -sd' '", ENTER,
    ], cols=30, rows=10),
    Scenario("tmux-nested", "tmux -L shh-inner -f /dev/null new-session", [
        1.0, "echo top", ENTER, ("C-b",), "%", 0.5, "seq 1 50", ENTER, ("C-b",), "\"", 0.5, "echo bottom", ENTER,
        ("C-b",), "o", "ls /usr/share/terminfo", ENTER, ("C-b",), "z", 0.5, ("C-b",), "z", 0.5, ("C-b",), "[", ("PageUp",), "q",
    ]),
    Scenario("top", PRIVATE_PROCESSES + "top -d 600'", [1.5, "1", "z", "c", 0.5]),
    Scenario("man-like", "sh -c 'python3 -c \"help(str)\"'", [" ", " ", "b", "/replace", ENTER]),
]


def main():
    wanted = set(sys.argv[1:])
    for tool in ("tmux", "vim", "less", "nano", "htop", "git"):
        if shutil.which(tool) is None:
            sys.exit(f"missing tool: {tool}")
    os.makedirs(OUT, exist_ok=True)
    workdir = make_workdir()
    subprocess.run(["tmux", "-L", SOCKET, "kill-server"], capture_output=True)
    subprocess.run(["tmux", "-L", "shh-inner", "kill-server"], capture_output=True)
    try:
        for scenario in SCENARIOS:
            if wanted and scenario.name not in wanted:
                continue
            scenario.run(workdir)
    finally:
        subprocess.run(["tmux", "-L", SOCKET, "kill-server"], capture_output=True)
        subprocess.run(["tmux", "-L", "shh-inner", "kill-server"], capture_output=True)
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    main()
