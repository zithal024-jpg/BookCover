"""Assemble the self-contained BookCover review archive using Python's standard library.

Run only after final build/results are ready. Existing destinations are never replaced.
"""
from pathlib import Path, PurePosixPath
import argparse
import difflib
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
DEFAULT_DESTINATION = PROJECT / 'review-deliverables/2026-10-26/BookCover-0.4.0-product-review'
ROOT_FILES = ['.gitignore', 'Build BookCover.cmd', 'PHONE-TEST-EPUB.md', 'PHONE-TEST-READER.md',
              'README.md', 'START-HERE.md', 'build.gradle.kts', 'gradle.properties', 'gradlew',
              'gradlew.bat', 'settings.gradle.kts']
SOURCE_DIRECTORIES = ['app', 'docs', 'scripts', 'gradle']
EXCLUDED_PARTS = {'.git', '.gradle', '.kotlin', '.idea', 'build', '__pycache__', 'node_modules',
                  '.tools', 'captures', 'review-deliverables', 'output',
                  'private-recovery', 'private-phone-backups'}
SENSITIVE_SUFFIXES = {'.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.der', '.hprof', '.pyc', '.iml'}
LOGS = ['baseline-0.3.0-review-build.log', 'typography-stage-build.log',
        'navigation-theme-stage-build.log', 'final-review-build.log',
        'native-reader-device-tests.log', 'native-reader-folded-tests.log',
        'review-extracted-build.log', 'apk-verification.log',
        'library-theme-phone-tests.txt', 'physical-confirmation.txt', 'review-resolved-dependencies.log']
SECRET_PATTERNS = [
    re.compile('-----BEGIN ' + r'(?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?' + 'PRIVATE KEY-----'),
    re.compile(r'\bsk-[A-Za-z0-9_-]{32,}\b'),
    re.compile(r'\bgh[pousr]_[A-Za-z0-9]{30,}\b'),
    re.compile(r'\bgithub_pat_[A-Za-z0-9_]{50,}\b'),
    re.compile(r'\bAIza[0-9A-Za-z_-]{35}\b'),
    re.compile(r'\bAKIA[0-9A-Z]{16}\b'),
]


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def forbidden(relative):
    path = PurePosixPath(str(relative).replace('\\', '/'))
    name = path.name.lower()
    return (any(part.lower() in EXCLUDED_PARTS for part in path.parts)
            or path.suffix.lower() in SENSITIVE_SUFFIXES
            or name in {'local.properties', '.env', '.npmrc', 'id_rsa', 'id_ed25519',
                        'keystore.properties', 'signing.properties', 'auth.json', 'google-services.json',
                        '.ds_store', 'thumbs.db', 'desktop.ini'}
            or name.startswith('.env.')
            or (path.suffix.lower() == '.json' and any(word in name for word in ('credential', 'oauth', 'token'))))


def check_text(data, label):
    if b'\0' in data[:8192]:
        return
    try:
        text = data.decode('utf-8-sig')
    except UnicodeDecodeError:
        return
    if any(pattern.search(text) for pattern in SECRET_PATTERNS):
        raise ValueError(f'Potential credential/private-key material in {label}; review before packaging')


def safe_archive_name(name):
    parts = PurePosixPath(name.replace('\\', '/'))
    if parts.is_absolute() or '..' in parts.parts or any(':' in part for part in parts.parts):
        raise ValueError(f'Unsafe archive path: {name}')


def inspect_archive(path):
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            safe_archive_name(info.filename)
            if (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError(f'Symlink archive member is not permitted: {info.filename}')
            if forbidden(info.filename):
                raise ValueError(f'Forbidden archive member: {info.filename}')
            if not info.is_dir() and info.file_size <= 8 * 1024 * 1024:
                check_text(archive.read(info), info.filename)
        corrupt = archive.testzip()
        if corrupt:
            raise ValueError(f'Archive integrity check failed: {corrupt}')


def source_files():
    files = [PROJECT / name for name in ROOT_FILES if (PROJECT / name).is_file()]
    files += [p for p in PROJECT.glob('LICENSE*') if p.is_file()]
    files += [p for p in PROJECT.glob('NOTICE*') if p.is_file()]
    for directory in SOURCE_DIRECTORIES:
        files.extend(p for p in (PROJECT / directory).rglob('*')
                     if p.is_file() and not forbidden(p.relative_to(PROJECT)))
    return sorted(set(files))


def copy_checked(source, destination, sanitizer=None):
    if source.is_symlink():
        raise ValueError(f'Symlink is not copied into review: {source}')
    data = source.read_bytes()
    check_text(data, source.name)
    destination.parent.mkdir(parents=True, exist_ok=True)
    if sanitizer:
        text = data.decode('utf-8-sig', errors='replace')
        destination.write_text(sanitizer(text), encoding='utf-8')
    else:
        shutil.copy2(source, destination)


def inventory(root):
    return [{'path': p.relative_to(root).as_posix(), 'bytes': p.stat().st_size, 'sha256': sha256(p)}
            for p in sorted(root.rglob('*')) if p.is_file()]


def compare_sources(baseline, current, output):
    before = {item['path']: item for item in inventory(baseline)}
    after = {item['path']: item for item in inventory(current)}
    changes, patch = [], []
    for name in sorted(before.keys() | after.keys()):
        old, new = before.get(name), after.get(name)
        if old and new and old['sha256'] == new['sha256']:
            continue
        kind = 'added' if old is None else 'removed' if new is None else 'modified'
        changes.append({'path': name, 'change': kind, 'baseline': old, 'final': new})
        try:
            a = (baseline / name).read_bytes() if old else b''
            b = (current / name).read_bytes() if new else b''
            if b'\0' in a or b'\0' in b:
                raise UnicodeDecodeError('utf-8', b'\0', 0, 1, 'binary')
            patch.extend(difflib.unified_diff(a.decode('utf-8-sig').splitlines(True),
                         b.decode('utf-8-sig').splitlines(True), 'baseline/' + name, 'final/' + name))
        except UnicodeDecodeError:
            patch.append(f'Binary file {kind}: {name}; see SHA-256 change inventory.\n')
    output.mkdir(parents=True, exist_ok=True)
    (output / 'SOURCE-CHANGES.json').write_text(json.dumps(changes, indent=2), encoding='utf-8')
    (output / 'SOURCE-DIFF.patch').write_text(''.join(patch), encoding='utf-8')
    summary = ['# Baseline-to-final source changes\n',
               'Compared with the immutable 0.3.0 snapshot captured before implementation. '
               'This is a file comparison, not an invented Git commit history.\n',
               f'Changed files: {len(changes)}. Unchanged files: {len(before.keys() & after.keys()) - sum(c["change"] == "modified" for c in changes)}.\n',
               '| Change | File |\n|---|---|']
    summary += [f'| {item["change"]} | `{item["path"]}` |' for item in changes]
    summary += ['\nSOURCE-CHANGES.json records before/after sizes and hashes. SOURCE-DIFF.patch provides '
                'readable text differences; binary differences use inventory hashes. The complete baseline '
                'is in baseline/BookCover-baseline-0.3.0.zip. See docs/ for explanations and validation.\n']
    (output / 'SOURCE-CHANGES.md').write_text('\n'.join(summary), encoding='utf-8')
    return len(changes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', type=Path, default=DEFAULT_DESTINATION)
    parser.add_argument('--tool-root', type=Path, default=Path(os.environ.get('LOCALAPPDATA', Path.home() / '.local/share')) / 'BookCoverBuild')
    parser.add_argument('--folded-results', type=Path, action='append', default=[])
    args = parser.parse_args()
    destination = args.destination.resolve()
    archive_path = Path(str(destination) + '.zip')
    if destination.exists() or archive_path.exists():
        raise SystemExit('Destination or ZIP already exists. Preserve it and use --destination with a new name.')
    baseline = PROJECT / 'review-deliverables/2026-10-26/BookCover-baseline-0.3.0'
    baseline_zip = Path(str(baseline) + '.zip')
    apk = PROJECT / 'output/BookCover-prototype.apk'
    required = [baseline_zip, baseline / 'source', apk, PROJECT / 'docs/review/RESULTS.md']
    if missing := [str(p) for p in required if not p.exists()]:
        raise SystemExit('Required finalized material missing: ' + ', '.join(missing))
    inspect_archive(baseline_zip)
    destination.mkdir(parents=True)
    for path in source_files():
        relative = path.relative_to(PROJECT)
        if not forbidden(relative):
            copy_checked(path, destination / 'source' / relative)
    copy_checked(baseline_zip, destination / 'baseline' / baseline_zip.name)
    final_apk = destination / 'builds/BookCover-0.4.0-prototype.apk'
    copy_checked(apk, final_apk)
    final_apk.with_suffix('.sha256').write_text(f'{sha256(final_apk)}  {final_apk.name}\n', encoding='utf-8')

    def sanitize(text):
        for path, label in [(PROJECT, '{PROJECT}'), (args.tool_root.resolve(), '{TOOL_ROOT}')]:
            for spelling in {str(path), path.as_posix(), path.as_posix().replace(' ', '%20')}:
                text = text.replace(spelling, label)
        return text

    for path in sorted((PROJECT / 'docs/review').iterdir()):
        if path.is_file() and path.suffix.lower() in {'.md', '.txt'}:
            copy_checked(path, destination / 'docs' / path.name, sanitize)
    missing_reports = []
    for name in LOGS:
        path = args.tool_root / name
        if path.is_file():
            copy_checked(path, destination / 'tests/logs' / name, sanitize)
        else:
            missing_reports.append(name)
    app_output = args.tool_root / 'project-output/app'
    report_sets = [(app_output / 'test-results/testQaUnitTest', 'unit', '*.xml'),
                   (app_output / 'outputs/androidTest-results/connected/debug', 'native-debug-historical', '*.xml'),
                   (app_output / 'outputs/androidTest-results/connected/qa', 'native-qa', '*.xml')]
    folded = args.folded_results + [p for p in args.tool_root.glob('*folded*') if p.is_dir()]
    report_sets += [(path, f'folded-{index + 1}', '*.xml') for index, path in enumerate(dict.fromkeys(folded))]
    for directory, label, pattern in report_sets:
        for path in sorted(directory.rglob(pattern)) if directory.exists() else []:
            copy_checked(path, destination / 'tests' / label / path.relative_to(directory), sanitize)
    for variant in ['debug', 'qa']:
        for suffix in ['txt', 'html', 'xml']:
            name = f'lint-results-{variant}.{suffix}'
            path = app_output / 'reports' / name
            if suffix == 'txt':
                intermediate = app_output / f'intermediates/lint_intermediate_text_report/{variant}/lintReport{variant.capitalize()}' / name
                if intermediate.is_file():
                    path = intermediate
            if path.is_file():
                copy_checked(path, destination / 'tests/lint' / name, sanitize)
    (destination / 'tests').mkdir(exist_ok=True)
    (destination / 'tests/MATERIALS.json').write_text(json.dumps({'omitted_missing_optional_logs': missing_reports,
        'report_paths_sanitized': True, 'source_files_unchanged': True}, indent=2), encoding='utf-8')
    changes = compare_sources(baseline / 'source', destination / 'source', destination / 'changes')
    (destination / 'SOURCE-MANIFEST.json').write_text(json.dumps(inventory(destination / 'source'), indent=2), encoding='utf-8')
    try:
        status = subprocess.run(['git', 'status', '--short'], cwd=PROJECT, capture_output=True, text=True, check=False)
        history = subprocess.run(['git', 'log', '--oneline', '-20'], cwd=PROJECT, capture_output=True, text=True, check=False)
        git_record = 'Git state captured for review. Baseline began with no commits and untracked project files.\n\n' + status.stdout
        git_record += '\nRecent commits:\n' + (history.stdout if history.returncode == 0 else 'No Git commit history is available.\n')
    except FileNotFoundError:
        git_record = 'Git unavailable during packaging. Baseline DEVELOPMENT-STATE.txt records the initial no-commit state.\n'
    (destination / 'changes/GIT-STATE.txt').write_text(sanitize(git_record), encoding='utf-8')
    (destination / 'README.md').write_text('''# BookCover 0.4.0 product review

Start with [the overview](docs/OVERVIEW.md), [final validation results](docs/RESULTS.md),
[build/install instructions](docs/BUILD-AND-INSTALL.md), and [known limitations](docs/KNOWN-LIMITATIONS.md).

- `source/`: complete project source, assets, tests, Gradle wrapper/configuration and build helpers.
- `builds/`: final debug test APK and SHA-256. APKs are for evaluation, not production distribution.
- `tests/`: selected build/device logs, unit/native XML and lint reports; missing optional logs are listed in MATERIALS.json.
- `docs/`: independent-review documentation and concrete acceptance criteria. Historical development records remain inside source/docs/.
- `changes/`: baseline-to-final file inventory, unified text diff and available Git state.
- `baseline/BookCover-baseline-0.3.0.zip`: preserved pre-implementation source and prior APK.

Read RESULTS.md to distinguish automated checks, physical observations and pending work.
The 0.3.0 user-confirmed outside-cover result is not automatically proof of 0.4.0 behavior.

SDK/JDK binaries, Gradle distributions, dependency caches, local.properties, private signing keys,
credentials, phone library files and personal screenshots are omitted. Obtain tools/dependencies
as described in the build guide. A reviewer-generated debug key normally differs from the supplied
APK's key; use a separate test device/variant instead of deleting an existing user's data.

SOURCE-MANIFEST.json inventories exact project-source bytes. FILES-SHA256SUMS.txt inventories
every package file except itself. The adjacent ZIP checksum verifies the complete archive.
Report paths are normalized to {PROJECT}/{TOOL_ROOT}; source bytes are preserved unchanged.
''', encoding='utf-8')
    for item in inventory(destination):
        relative = item['path']
        if forbidden(relative):
            raise ValueError(f'Forbidden packaged path: {relative}')
        check_text((destination / relative).read_bytes(), relative)
    manifest = ''.join(f'{item["sha256"]}  {item["path"]}\n' for item in inventory(destination))
    (destination / 'FILES-SHA256SUMS.txt').write_text(manifest, encoding='utf-8')
    with zipfile.ZipFile(archive_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=7) as archive:
        for path in sorted(destination.rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(destination.parent))
    inspect_archive(archive_path)
    archive_hash = sha256(archive_path)
    Path(str(archive_path) + '.sha256').write_text(f'{archive_hash}  {archive_path.name}\n', encoding='utf-8')
    print(json.dumps({'folder': str(destination), 'zip': str(archive_path), 'zip_sha256': archive_hash,
        'apk': str(final_apk), 'apk_sha256': sha256(final_apk), 'changed_source_files': changes,
        'package_files': len(inventory(destination)), 'missing_optional_logs': missing_reports}, indent=2))


if __name__ == '__main__':
    main()
