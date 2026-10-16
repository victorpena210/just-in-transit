#!/usr/bin/env python3
"""Create a source-only shareable archive; never include local DBs or Git history."""
from datetime import date
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
output = root / 'dist' / f'just-in-transit-updated-{date.today().isoformat()}.zip'
output.parent.mkdir(exist_ok=True)
files = [root / name for name in ('pom.xml', 'README.md', 'UPDATE-GUIDE.md', 'mvnw', 'mvnw.cmd', 'apply-update.sh', '.gitignore', '.gitattributes')]
files += [root / '.mvn/wrapper/maven-wrapper.properties']
for directory in ('src', 'scripts'):
    files += [p for p in (root / directory).rglob('*') if p.is_file()
              and not p.is_symlink() and not any(part.startswith('.') or part == '__pycache__' for part in p.relative_to(root).parts)
              and p.suffix in ('.java', '.properties', '.html', '.css', '.js', '.py', '.md', '.txt', '.svg', '.png', '.webmanifest')]
# Mockito's extension file intentionally has no suffix.
files += [root / 'src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker']
with ZipFile(output, 'w', ZIP_DEFLATED) as archive:
    for path in sorted(set(files)):
        if path.is_file() and not path.is_symlink():
            archive.write(path, 'just-in-transit/' + path.relative_to(root).as_posix())
print(output)
