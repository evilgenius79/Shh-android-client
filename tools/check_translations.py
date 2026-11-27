#!/usr/bin/env python3
"""Holds the translations against the English texts, without building the app.

    tools/check_translations.py            # every language
    tools/check_translations.py de fr      # these only

Checks what the test TranslationsTest checks (nothing missing, nothing left over, the same
things filled in) and, on top of that, what the Android resource compiler would reject:
apostrophes and quotation marks that are not escaped. It cannot tell a good translation
from a bad one.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

RES = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res"
PLACEHOLDER = re.compile(r"%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]")


def texts(directory):
    found = {}
    for file in sorted(directory.glob("strings*.xml")):
        root = ET.parse(file).getroot()
        for element in root:
            if element.get("translatable") == "false":
                continue
            name = element.get("name")
            if element.tag == "string":
                found[name] = (file.name, "".join(element.itertext()))
            elif element.tag == "plurals":
                for item in element.findall("item"):
                    found[f"{name}#{item.get('quantity')}"] = (file.name, "".join(item.itertext()))
    return found


def placeholders(text):
    return sorted(match.group(0) for match in PLACEHOLDER.finditer(text.replace("%%", "")))


def unescaped(text):
    """Characters the resource compiler wants a backslash in front of."""
    problems = []
    if text.startswith('"') and text.endswith('"') and len(text) > 1:
        return problems  # a text wholly in double quotes needs no escapes
    for match in re.finditer(r"(?<!\\)(\\\\)*(['\"])", text):
        problems.append(match.group(2))
    return problems


def check(language, english):
    directory = RES / f"values-{language}"
    problems = []
    if not directory.is_dir():
        return [f"{language}: there is no folder {directory}"]
    try:
        translated = texts(directory)
    except ET.ParseError as error:
        return [f"{language}: not well-formed XML: {error}"]
    english_files = {name.split("#")[0]: file for name, (file, _) in english.items()}
    names = lambda entries: {name.split("#")[0] for name in entries}
    for missing in sorted(names(english) - names(translated)):
        problems.append(f"{language}: {missing} is not translated (it is in {english_files[missing]})")
    for stale in sorted(names(translated) - names(english)):
        problems.append(f"{language}: {stale} translates a text that does not exist")
    for name, (file, text) in translated.items():
        plain = name.split("#")[0]
        if plain in english_files and file != english_files[plain]:
            problems.append(f"{language}: {plain} is in {file} but belongs in {english_files[plain]}")
        if not text.strip():
            problems.append(f"{language}: {name} is empty")
        for character in unescaped(text):
            problems.append(f"{language}: {name} has a {character} without a backslash in front of it: {text!r}")
        if "#" in name:
            if f"{plain}#other" not in translated:
                problems.append(f"{language}: the plural {plain} lacks the form for \"other\"")
            allowed = placeholders(english.get(f"{plain}#other", ("", ""))[1])
            extra = [p for p in placeholders(text) if p not in allowed]
            if extra:
                problems.append(f"{language}: {name} fills in {extra}, which the English text does not")
        elif name in english:
            wanted = placeholders(english[name][1])
            if placeholders(text) != wanted:
                problems.append(f"{language}: {name} fills in {placeholders(text)}, the English text {wanted}")
    return sorted(set(problems))


def main():
    english = texts(RES / "values")
    languages = sys.argv[1:] or sorted(
        directory.name[len("values-"):] for directory in RES.glob("values-*") if any(directory.glob("strings*.xml"))
    )
    problems = []
    for name, (file, text) in english.items():
        for character in unescaped(text):
            problems.append(f"English: {name} in {file} has a {character} without a backslash in front of it: {text!r}")
    for language in languages:
        problems += check(language, english)
    for problem in problems:
        print(problem)
    print(f"{len(english)} English texts, {len(languages)} languages checked, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
