"""Assemble the public report site from Gradle HTML reports."""
import html
from pathlib import Path
import shutil
import sys

source, destination = map(Path, sys.argv[1:])
for name in ("tests", "jacoco", "spotbugs"):
    if (source / name).is_dir():
        shutil.copytree(source / name, destination / "reports" / name, dirs_exist_ok=True)
links = []
for title, path in (
    ("Unit tests", "reports/tests/test/index.html"),
    ("Integration tests", "reports/tests/integrationTest/index.html"),
    ("Combined test coverage", "reports/jacoco/test/html/index.html"),
):
    if (destination / path).is_file():
        links.append((title, path))
for path in sorted((destination / "reports/spotbugs").glob("*.html")):
    links.append(("SpotBugs: " + path.stem, path.relative_to(destination).as_posix()))
links.append(("Qodana code quality", "qodana/index.html"))
items = "".join(f'<li><a href="{html.escape(path)}">{html.escape(title)}</a></li>' for title, path in links)
destination.mkdir(parents=True, exist_ok=True)
(destination / "index.html").write_text(
    '<!doctype html><html lang="en"><meta charset="utf-8">'
    '<meta name="viewport" content="width=device-width, initial-scale=1">'
    '<title>Email Management Agent reports</title><main>'
    '<h1>Email Management Agent reports</h1><p>Latest develop reports. '
    'Qodana is updated independently.</p><ul>' + items + '</ul></main></html>',
    encoding="utf-8",
)
