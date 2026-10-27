"""Assemble the self-contained BookCover review archive using Python's standard library.

Requires an explicit task evidence manifest from Build.ps1. Existing destinations
are never replaced. --validate-only checks evidence without making review files.
"""
from pathlib import Path, PurePosixPath
import argparse
import difflib
import getpass
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import tempfile
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
DOCUMENTATION_ROOT_FILES = ['PHONE-TEST-EPUB.md', 'PHONE-TEST-READER.md', 'README.md', 'START-HERE.md']
EXCLUDED_PARTS = {'.git', '.gradle', '.kotlin', '.idea', 'build', '__pycache__', 'node_modules',
                  '.tools', 'captures', 'review-deliverables', 'output',
                  'private-recovery', 'private-phone-backups'}
SENSITIVE_SUFFIXES = {'.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.der', '.hprof', '.pyc', '.iml'}
SAMPLE_NAMES = {'lantern-keeper.epub', 'river-maps.epub'}
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
            or (path.suffix.lower() == '.epub' and not
                (name in SAMPLE_NAMES and '/'.join(part.lower() for part in path.parts[-6:-1])
                 == 'app/src/main/assets/samples'))
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


def inspect_archive(path, apk=False, depth=0):
    if depth > 3:
        raise ValueError('Review archive nesting is unexpectedly deep.')
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            safe_archive_name(info.filename)
            if (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError(f'Symlink archive member is not permitted: {info.filename}')
            privacy_name = info.filename
            if apk and privacy_name.replace('\\', '/').startswith('assets/samples/'):
                privacy_name = 'app/src/main/' + privacy_name
            # AGP writes this public metadata under a directory named "build".
            # The exception applies only to this exact member inside an APK.
            agp_metadata = (apk and info.filename ==
                            'META-INF/com/android/build/gradle/app-metadata.properties')
            if forbidden(privacy_name) and not agp_metadata:
                raise ValueError(f'Forbidden archive member: {info.filename}')
            if not info.is_dir() and info.file_size <= 8 * 1024 * 1024:
                check_text(archive.read(info), info.filename)
            suffix = PurePosixPath(info.filename).suffix.lower()
            if not info.is_dir() and suffix in {'.apk', '.zip'}:
                if info.file_size > 256 * 1024 * 1024:
                    raise ValueError(f'Nested archive is unexpectedly large: {info.filename}')
                inspect_archive(io.BytesIO(archive.read(info)), apk=suffix == '.apk', depth=depth + 1)
        corrupt = archive.testzip()
        if corrupt:
            raise ValueError(f'Archive integrity check failed: {corrupt}')


def copy_checked(source, destination, sanitizer=None, expected_digest=None):
    reject_symlinks(source)
    data = source.read_bytes()
    if expected_digest and hashlib.sha256(data).hexdigest() != expected_digest.lower():
        raise ValueError(f'File changed while copying: {source}')
    check_text(data, source.name)
    destination.parent.mkdir(parents=True, exist_ok=True)
    if sanitizer:
        text = data.decode('utf-8-sig', errors='replace')
        destination.write_text(sanitizer(text), encoding='utf-8')
    else:
        destination.write_bytes(data)
        shutil.copystat(source, destination)


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


# The executable code is constant. Every value comes from JSON stdin; the trusted
# checkout root is supplied separately by this module, never interpolated as code.
POWERSHELL_BRIDGE = r'''
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$lease = $null
$request = $null
$context = $null
try {
    $project = [IO.Path]::GetFullPath($env:BOOKCOVER_PACKAGE_PROJECT).TrimEnd([char[]]'\/')
    . (Join-Path $project 'scripts\Task-Resources.ps1')
    $request = [Console]::In.ReadToEnd() | ConvertFrom-Json -ErrorAction Stop
    if ($request.Operation -ceq 'Acquire') {
        $manifest = Read-BCJson $request.ManifestPath
        if ($manifest.SchemaVersion -ne 2) { throw 'Packaging requires schema version 2 build evidence.' }
        $recorded = $manifest.Context
    } else { $recorded = $request.Context }
    if ([string]::IsNullOrWhiteSpace($recorded.CheckoutRoot) -or
        (Get-BCFullPath $recorded.CheckoutRoot) -ine $project) {
        throw 'Evidence checkout does not match this packaging checkout.'
    }
    $context = Get-BookCoverTaskContext -CheckoutRoot $project -TaskId $recorded.TaskId -ToolRoot $recorded.ToolRoot -StateRoot $recorded.StateRoot
    foreach ($property in $context.PSObject.Properties) {
        if ($null -eq $recorded.PSObject.Properties[$property.Name] -or
            [string]$recorded.($property.Name) -cne [string]$property.Value) {
            throw "Evidence task context differs: $($property.Name)"
        }
    }
    switch -CaseSensitive ($request.Operation) {
        'Acquire' {
            $lease = Enter-BookCoverResource -Context $context -Resource BUILD -Owner $request.Owner -Purpose 'Review packaging / evidence validation'
            $inputs = Get-BookCoverPackageInputs -Context $context -ManifestPath $request.ManifestPath -SourceSnapshotId $manifest.SourceSnapshotId
            $result = @{ Lease = $lease; Inputs = $inputs }
        }
        'Check' {
            $null = Assert-BookCoverResource -Context $context -Lease $request.Lease
            $inputs = Get-BookCoverPackageInputs -Context $context -ManifestPath $request.ManifestPath -SourceSnapshotId $request.SourceSnapshotId
            $result = @{ Inputs = $inputs }
        }
        'Release' {
            Exit-BookCoverResource -Context $context -Lease $request.Lease -OperationStopped -ReleaseEvidence $request.ReleaseEvidence
            $result = @{}
        }
        default { throw 'Unknown packaging resource operation.' }
    }
    $json = ConvertTo-Json -InputObject $result -Depth 40 -Compress
    [Console]::Out.WriteLine($json)
} catch {
    $failure = $_
    if ($null -ne $request -and $request.Operation -ceq 'Acquire' -and $null -ne $lease) {
        try {
            Exit-BookCoverResource -Context $context -Lease $lease -OperationStopped -ReleaseEvidence ('Packaging acquisition failed after validation ended: ' + $failure.Exception.Message)
        } catch {
            [Console]::Error.WriteLine('Packaging could not release its own lease: ' + $_.Exception.Message)
        }
    }
    [Console]::Error.WriteLine($failure.Exception.Message)
    exit 1
}
'''


def powershell_request(payload):
    environment = os.environ.copy()
    environment['BOOKCOVER_PACKAGE_PROJECT'] = str(PROJECT.resolve())
    result = subprocess.run(
        ['powershell', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
         '-Command', POWERSHELL_BRIDGE], input=json.dumps(payload),
        cwd=PROJECT, env=environment, capture_output=True, text=True,
        encoding='utf-8', errors='replace', check=False)
    if result.returncode:
        raise ValueError('Task evidence/resource check failed: ' +
                         (result.stderr.strip() or result.stdout.strip() or str(result.returncode)))
    try:
        response = json.loads(result.stdout.lstrip('\ufeff'))
    except (ValueError, TypeError) as error:
        raise ValueError('Task helper returned invalid JSON; no evidence was selected.') from error
    if not isinstance(response, dict):
        raise ValueError('Task helper returned an invalid response.')
    return response


def field(obj, name):
    if not isinstance(obj, dict):
        raise ValueError(f'Missing evidence object for {name}')
    matches = [value for key, value in obj.items() if key.lower() == name.lower()]
    if len(matches) != 1:
        raise ValueError(f'Missing or ambiguous evidence property: {name}')
    return matches[0]


def list_field(obj, name):
    value = field(obj, name)
    if not isinstance(value, list):
        raise ValueError(f'Evidence {name} must be an explicit list.')
    return value


def reject_symlinks(path):
    path = Path(path).absolute()
    if any(parent.is_symlink() or getattr(parent, 'is_junction', lambda: False)()
           for parent in [path, *path.parents]):
        raise ValueError(f'Symlink is not copied into review: {path}')


def checked_path(value):
    path = Path(value)
    if not path.is_absolute():
        raise ValueError(f'Evidence requires an absolute path: {value}')
    reject_symlinks(path)
    return path.resolve()


def within(path, root):
    try:
        return path.relative_to(root)
    except ValueError as error:
        raise ValueError(f'Evidence path is outside its recorded root: {path}') from error


def digest_record(record, path):
    digest = field(record, 'Sha256')
    if not isinstance(digest, str) or not re.fullmatch('[0-9a-fA-F]{64}', digest):
        raise ValueError(f'Invalid file digest: {path}')
    if not path.is_file() or sha256(path) != digest.lower():
        raise ValueError(f'Evidence file changed or is missing: {path}')
    if any(key.lower() == 'bytes' for key in record):
        if field(record, 'Bytes') != path.stat().st_size:
            raise ValueError(f'Evidence file size changed: {path}')
    return digest.lower()


def validate_inputs(inputs, manifest, expected_snapshot):
    context = field(inputs, 'Context')
    recorded = field(manifest, 'Context')
    if checked_path(field(context, 'CheckoutRoot')) != PROJECT.resolve():
        raise ValueError('Evidence belongs to another checkout.')
    for name in ('CheckoutRoot', 'TaskId', 'TaskKey', 'ToolRoot', 'StateRoot', 'BuildRoot'):
        if field(context, name) != field(recorded, name):
            raise ValueError(f'Evidence context changed: {name}')
    if field(inputs, 'SourceSnapshotId') != expected_snapshot:
        raise ValueError('Evidence source snapshot changed.')
    build_root = checked_path(field(inputs, 'BuildRoot'))
    if build_root != checked_path(field(context, 'BuildRoot')):
        raise ValueError('Evidence build root changed.')
    records = {}
    for record in list_field(inputs, 'FileRecords'):
        path = checked_path(field(record, 'Path'))
        if path in records:
            raise ValueError(f'Duplicate evidence file: {path}')
        records[path] = digest_record(record, path)
    selected = {}
    for name in ('ApkPath', 'BuiltApkPath', 'ChecksumPath'):
        path = checked_path(field(inputs, name))
        if path != checked_path(field(context, name)):
            raise ValueError(f'Selected {name} differs from its task context.')
        if path not in records:
            raise ValueError(f'Selected file lacks a validated digest: {path}')
        selected[name] = path
    if records[selected['ApkPath']] != records[selected['BuiltApkPath']]:
        raise ValueError('Output APK does not match the built APK.')
    checksum = selected['ChecksumPath'].read_text(encoding='utf-8-sig').strip()
    if not re.fullmatch(re.escape(records[selected['ApkPath']]) + r'\s+\*?' +
                        re.escape(selected['ApkPath'].name), checksum, flags=re.IGNORECASE):
        raise ValueError('Selected APK checksum is invalid.')
    inspect_archive(selected['ApkPath'], apk=True)
    roots = {'UnitReports': build_root / 'app/test-results/testQaUnitTest',
             'LintReports': build_root / 'app/reports',
             'DeviceReports': build_root / 'app/outputs/androidTest-results/connected/qa'}
    for name, root in roots.items():
        paths = [checked_path(path) for path in list_field(inputs, name)]
        if len(set(paths)) != len(paths) or (name != 'DeviceReports' and not paths):
            raise ValueError(f'Empty or duplicate selected {name}.')
        for path in paths:
            within(path, root)
            if path.suffix.lower() != '.xml' or path not in records:
                raise ValueError(f'Selected report lacks a validated XML digest: {path}')
        if name == 'LintReports' and set(paths) != {
                root / 'lint-results-debug.xml', root / 'lint-results-qa.xml'}:
            raise ValueError('Both exact debug/QA lint reports are required.')
        selected[name] = paths
    receipts = [checked_path(path) for path in list_field(inputs, 'ReceiptPaths')]
    if not receipts or any(path not in records for path in receipts):
        raise ValueError('Successful build receipt digests are required.')
    for path in receipts:
        within(path, checked_path(field(context, 'TaskRoot')))
    selected['ReceiptPaths'] = receipts
    source = []
    snapshot = field(inputs, 'SourceSnapshot')
    for record in list_field(snapshot, 'Files'):
        name = field(record, 'Path')
        raw = Path(name)
        if not raw.is_absolute():
            safe_archive_name(name)
            raw = PROJECT / raw
        path = checked_path(str(raw))
        relative = within(path, PROJECT.resolve())
        if forbidden(relative):
            raise ValueError(f'Forbidden frozen source path: {relative}')
        if any(existing[0] == path for existing in source):
            raise ValueError(f'Duplicate frozen source: {relative}')
        source.append((path, digest_record(record, path)))
    if not source:
        raise ValueError('The frozen source snapshot is empty.')
    selected['SourceFiles'] = sorted(source)
    selected['Records'] = records
    selected['Context'] = context
    selected['SourceSnapshot'] = snapshot
    return selected


def current_documentation(frozen):
    files = [PROJECT / name for name in DOCUMENTATION_ROOT_FILES if (PROJECT / name).is_file()]
    files += [p for pattern in ('LICENSE*', 'NOTICE*') for p in PROJECT.glob(pattern) if p.is_file()]
    if (PROJECT / 'docs').is_dir():
        files += [p for p in (PROJECT / 'docs').rglob('*') if p.is_file()]
    frozen_paths = {path for path, _ in frozen}
    return sorted({path for path in files if path not in frozen_paths and
                   not forbidden(path.relative_to(PROJECT))})


def build_package(destination, selected, inputs, manifest_path, manifest_digest, check):
    archive_path = Path(str(destination) + '.zip')
    baseline = PROJECT / 'review-deliverables/2026-10-26/BookCover-baseline-0.3.0'
    baseline_zip = Path(str(baseline) + '.zip')
    have_baseline = baseline_zip.is_file() and (baseline / 'source').is_dir()
    if have_baseline:
        inspect_archive(baseline_zip)
    destination.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=destination.name + '.packaging-', dir=destination.parent))
    temporary_zip = staging.parent / (staging.name + '.zip')
    try:
        check()
        context = selected['Context']
        replacements = [(PROJECT, '{PROJECT}'),
                        (Path(field(context, 'CheckoutRoot')), '{PROJECT}'),
                        (Path(field(context, 'ToolRoot')), '{TOOL_ROOT}'),
                        (Path(field(context, 'StateRoot')), '{STATE_ROOT}')]

        def sanitize(text):
            for path, label in replacements:
                for spelling in {str(path), path.as_posix(), path.as_posix().replace(' ', '%20'),
                                 json.dumps(str(path))[1:-1]}:
                    text = text.replace(spelling, label)
            return text

        for path, digest in selected['SourceFiles']:
            copy_checked(path, staging / 'source' / path.relative_to(PROJECT), expected_digest=digest)
        for path in current_documentation(selected['SourceFiles']):
            copy_checked(path, staging / 'source' / path.relative_to(PROJECT))
        reviews = PROJECT / 'docs/review'
        for path in sorted(reviews.iterdir()) if reviews.is_dir() else []:
            if path.is_file() and path.suffix.lower() in {'.md', '.txt'} and not forbidden(path.name):
                copy_checked(path, staging / 'docs' / path.name, sanitize)
        final_apk = staging / 'builds' / selected['ApkPath'].name
        copy_checked(selected['ApkPath'], final_apk,
                     expected_digest=selected['Records'][selected['ApkPath']])
        final_apk.with_suffix('.sha256').write_text(
            f'{sha256(final_apk)}  {final_apk.name}\n', encoding='utf-8')
        for category, label in [('UnitReports', 'unit'), ('LintReports', 'lint'), ('DeviceReports', 'native-qa')]:
            for index, path in enumerate(selected[category]):
                relative = path.relative_to(Path(field(context, 'BuildRoot')))
                copy_checked(path, staging / 'tests' / label / relative, sanitize,
                             selected['Records'][path])
        for index, path in enumerate(selected['ReceiptPaths']):
            copy_checked(path, staging / 'tests/receipts' / f'{index + 1}-{path.name}', sanitize,
                         selected['Records'][path])
        copy_checked(manifest_path, staging / 'tests/EVIDENCE-MANIFEST.json', sanitize, manifest_digest)
        materials = {'SourceSnapshotId': field(inputs, 'SourceSnapshotId'),
                     'Context': context, 'UnitTests': field(inputs, 'UnitTests'),
                     'DeviceTests': field(inputs, 'DeviceTests'),
                     'only_explicit_evidence_selected': True,
                     'report_paths_sanitized': True, 'current_documentation_bound_to_build': False}
        (staging / 'tests/MATERIALS.json').write_text(
            sanitize(json.dumps(materials, indent=2)), encoding='utf-8')
        (staging / 'BUILD-SOURCE-SNAPSHOT.json').write_text(
            json.dumps(selected['SourceSnapshot'], indent=2), encoding='utf-8')
        changes = None
        if have_baseline:
            copy_checked(baseline_zip, staging / 'baseline' / baseline_zip.name)
            changes = compare_sources(baseline / 'source', staging / 'source', staging / 'changes')
        (staging / 'SOURCE-MANIFEST.json').write_text(
            json.dumps(inventory(staging / 'source'), indent=2), encoding='utf-8')
        (staging / 'README.md').write_text('''# BookCover review package

`builds/` contains the explicit task APK and its SHA-256 checksum. `tests/` contains
only the unit, lint and optional QA device reports selected by the evidence manifest,
plus its successful operation receipts. No historical or folded-report search is used.

`BUILD-SOURCE-SNAPSHOT.json` identifies the frozen build inputs copied into `source/`.
Current documentation is also included in `source/` and `docs/`; it may describe older
work and is not evidence that its observations apply to this APK. `tests/MATERIALS.json`
records the selected automated test counts. Device results are present only if explicitly
recorded; physical outside-cover behavior still requires the owner's observation.

A baseline comparison is included only when the preserved local baseline was available.
SDK/JDK binaries, caches, local.properties, signing keys, credentials, output folders and
personal books are excluded. Only the two generated repository sample EPUBs are allowed.
Report paths are normalized; frozen source bytes are unchanged. SOURCE-MANIFEST.json
inventories all exported source and current documentation. FILES-SHA256SUMS.txt inventories
package files except itself; the adjacent ZIP checksum verifies the complete archive.
''', encoding='utf-8')
        check()
        for item in inventory(staging):
            relative = item['path']
            if forbidden(relative):
                raise ValueError(f'Forbidden packaged path: {relative}')
            check_text((staging / relative).read_bytes(), relative)
        manifest = ''.join(f'{item["sha256"]}  {item["path"]}\n' for item in inventory(staging))
        (staging / 'FILES-SHA256SUMS.txt').write_text(manifest, encoding='utf-8')
        with zipfile.ZipFile(temporary_zip, 'x', zipfile.ZIP_DEFLATED, compresslevel=7) as archive:
            for path in sorted(staging.rglob('*')):
                if path.is_file():
                    archive.write(path, Path(destination.name) / path.relative_to(staging))
        inspect_archive(temporary_zip)
        archive_hash = sha256(temporary_zip)
        check()
        if any(path.exists() for path in (destination, archive_path, Path(str(archive_path) + '.sha256'))):
            raise ValueError('Destination appeared during packaging; existing materials were preserved.')
        staging.rename(destination)
        temporary_zip.rename(archive_path)
        Path(str(archive_path) + '.sha256').write_text(
            f'{archive_hash}  {archive_path.name}\n', encoding='utf-8')
        return {'folder': str(destination), 'zip': str(archive_path), 'zip_sha256': archive_hash,
                'apk': str(destination / final_apk.relative_to(staging)),
                'apk_sha256': selected['Records'][selected['ApkPath']],
                'SourceSnapshotId': field(inputs, 'SourceSnapshotId'),
                'changed_source_files': changes, 'package_files': len(inventory(destination))}
    finally:
        # These paths were allocated by this operation. No existing destination is removed.
        if staging.exists():
            shutil.rmtree(staging)
        if temporary_zip.exists():
            temporary_zip.unlink()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-manifest', type=Path, required=True,
                        help='Explicit schema version 2 manifest produced by the task build scripts')
    parser.add_argument('--destination', type=Path,
                        help='New review folder; required unless --validate-only is used')
    parser.add_argument('--validate-only', action='store_true',
                        help='Check real task evidence and frozen source without creating review materials')
    parser.add_argument('--owner', default=os.environ.get('USERNAME') or getpass.getuser())
    args = parser.parse_args(argv)
    if not args.validate_only and args.destination is None:
        parser.error('--destination is required for packaging; use --validate-only for evidence checks only')
    if not args.owner.strip():
        parser.error('--owner must be nonempty')
    manifest_path = checked_path(str(args.evidence_manifest.absolute()))
    manifest_data = manifest_path.read_bytes()
    manifest_digest = hashlib.sha256(manifest_data).hexdigest()
    manifest = json.loads(manifest_data.decode('utf-8-sig'))
    if field(manifest, 'SchemaVersion') != 2:
        raise ValueError('Packaging requires schema version 2 evidence with automatic build receipts; rebuild using Build.ps1.')
    snapshot_id = field(manifest, 'SourceSnapshotId')
    if not isinstance(snapshot_id, str) or not re.fullmatch('[a-f0-9]{64}', snapshot_id):
        raise ValueError('Evidence requires an automatic 64-character source snapshot digest.')
    if checked_path(field(field(manifest, 'Context'), 'CheckoutRoot')) != PROJECT.resolve():
        raise ValueError('Evidence belongs to another checkout.')
    if args.destination is not None:
        reject_symlinks(args.destination.absolute())
    destination = args.destination.resolve() if args.destination is not None else None
    if destination is not None:
        reject_symlinks(destination)
        if any(path.exists() for path in (destination, Path(str(destination) + '.zip'),
                                         Path(str(destination) + '.zip.sha256'))):
            raise ValueError('Destination or ZIP already exists. Preserve it and use a new --destination.')
    acquired = powershell_request({'Operation': 'Acquire', 'ManifestPath': str(manifest_path),
                                  'Owner': args.owner})
    lease = field(acquired, 'Lease')
    # Keep this exact acquired lease for release, even if later validation fails.
    context = field(manifest, 'Context')
    outcome = 'failed (exit 1)'
    try:
        inputs = field(acquired, 'Inputs')
        selected = validate_inputs(inputs, manifest, snapshot_id)
        if field(lease, 'Resource') != 'BUILD':
            raise ValueError('Packaging did not acquire a BUILD lease.')

        def check():
            if sha256(manifest_path) != manifest_digest:
                raise ValueError('The selected evidence manifest changed during packaging.')
            current = powershell_request({'Operation': 'Check', 'ManifestPath': str(manifest_path),
                                          'Context': context, 'Lease': lease, 'SourceSnapshotId': snapshot_id})
            if sha256(manifest_path) != manifest_digest:
                raise ValueError('The selected evidence manifest changed during packaging.')
            current_selection = validate_inputs(field(current, 'Inputs'), manifest, snapshot_id)
            if current_selection != selected:
                raise ValueError('Selected evidence or frozen source changed during packaging.')

        check()
        if args.validate_only:
            result = {'validated': True, 'SourceSnapshotId': snapshot_id,
                      'apk': str(selected['ApkPath']), 'UnitTests': field(inputs, 'UnitTests'),
                      'DeviceTests': field(inputs, 'DeviceTests')}
        else:
            result = build_package(destination, selected, inputs, manifest_path, manifest_digest, check)
        outcome = 'completed (exit 0)'
    except BaseException as error:
        outcome = f'failed (exit 1): {type(error).__name__}: {error}'
        raise
    finally:
        # subprocess.run is synchronous; every helper/copy/archive operation has ended.
        powershell_request({'Operation': 'Release', 'Context': context, 'Lease': lease,
                            'ReleaseEvidence': 'Review packaging / validation operation ' + outcome})
    print(json.dumps(result, indent=2))
    return 0


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error))
