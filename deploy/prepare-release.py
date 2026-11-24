"""Expand only the verified application dependencies/migrations into a new release."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import zipfile


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


root = Path(sys.argv[1])
if not root.is_absolute() or root.is_symlink() or root.parent != Path("/opt/interview-studio/releases"):
    raise SystemExit("Expected a plain, absolute interview-studio release directory")
expected = json.loads((root / "checksums.json").read_text(encoding="utf-8"))
jar = root / "application.jar"
if sha(jar) != expected["jar"]:
    raise SystemExit("Application checksum mismatch")
for item in expected["web"]:
    relative = PurePosixPath(item["path"])
    if relative.is_absolute() or ".." in relative.parts:
        raise SystemExit("Unsafe web path")
    file = root / "web" / item["path"]
    if file.is_symlink() or not file.is_file():
        raise SystemExit("Invalid web file")
    if sha(file) != item["sha256"]:
        raise SystemExit("Web checksum mismatch: " + item["path"])

for name, digest in expected["deployment"].items():
    if sha(root / name) != digest:
        raise SystemExit("Deployment checksum mismatch: " + name)

migration = root / "migration"
migration.mkdir(mode=0o755)
(migration / "lib").mkdir(mode=0o755)
(migration / "sql").mkdir(mode=0o755)
(migration / "classes").mkdir(mode=0o755)
with (root / "Migrate.class").open("rb") as source, (migration / "classes" / "Migrate.class").open("xb") as target:
    target.write(source.read())

libraries = 0
sql = []
with zipfile.ZipFile(jar) as archive:
    for entry in archive.infolist():
        name = entry.filename
        destination = None
        if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
            tail = name[len("BOOT-INF/lib/"):]
            if "/" in tail or "\\" in tail or tail in ("", ".", ".."):
                raise SystemExit("Unexpected dependency path")
            destination = migration / "lib" / tail
            libraries += 1
        elif name.startswith("BOOT-INF/classes/db/migration/") and name.endswith(".sql"):
            tail = name[len("BOOT-INF/classes/db/migration/"):]
            if "/" in tail or "\\" in tail or tail in ("", ".", ".."):
                raise SystemExit("Unexpected migration path")
            destination = migration / "sql" / tail
            sql.append(tail)
        if destination is not None:
            with destination.open("xb") as target:
                target.write(archive.read(entry))
            destination.chmod(0o644)

versions = sorted(int(name[1:name.index("__")]) for name in sql)
if versions != list(range(1, 92)):
    raise SystemExit("Expected exactly V1 through V91; no migration was executed")
print(json.dumps({"verifiedWebFiles": len(expected["web"]), "libraries": libraries,
                  "migrationVersions": versions, "applicationSha256": expected["jar"]}))
