"""Host-only review packaging checks; never build Android or contact a device."""
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / 'Package-Review.py'
AGP_METADATA = 'META-INF/com/android/build/gradle/app-metadata.properties'
SPEC = importlib.util.spec_from_file_location('bookcover_package_review', SCRIPT)
package_review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(package_review)


class PackageReviewTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='bookcover-package-tests-')
        self.addCleanup(self.temporary.cleanup)
        # Hosted Windows TEMP can use an 8.3 alias such as RUNNER~1. Match the
        # production module's resolved PROJECT before creating or recording paths.
        self.root = Path(self.temporary.name).resolve()
        self.project = self.root / 'checkout'
        self.project.mkdir()
        self.tools = self.root / 'tools'
        self.task_root = self.tools / 'tasks' / 'fixture-task'
        self.build = self.task_root / 'project-output'
        self.destination = self.root / 'review'
        self.snapshot_id = 'a' * 64
        self.context = {
            'SchemaVersion': 1, 'CheckoutRoot': str(self.project),
            'TaskId': 'fixture', 'TaskKey': 'fixture-task',
            'ToolRoot': str(self.tools), 'StateRoot': str(self.root / 'state'),
            'TaskRoot': str(self.task_root), 'BuildRoot': str(self.build),
            'ProjectCache': str(self.task_root / 'project-cache'),
            'GradleUserHome': str(self.task_root / 'gradle-user-home'),
            'ApkName': 'BookCover-fixture-task.apk',
            'ApkPath': str(self.project / 'output' / 'BookCover-fixture-task.apk'),
            'ChecksumPath': str(self.project / 'output' / 'BookCover-fixture-task.sha256'),
            'BuiltApkPath': str(self.build / 'app/outputs/apk/debug/app-debug.apk'),
            'UnitResultsPath': str(self.build / 'app/test-results/testQaUnitTest'),
            'LintDebugPath': str(self.build / 'app/reports/lint-results-debug.xml'),
            'LintQaPath': str(self.build / 'app/reports/lint-results-qa.xml'),
            'DeviceResultsPath': str(self.build / 'app/outputs/androidTest-results/connected/qa'),
        }
        self.source = self.write(self.project / 'app/src/main/java/Fixture.kt', b'package fixture\n')
        self.write(self.project / 'build.gradle.kts', b'// fixture build\n')
        self.write(self.project / 'settings.gradle.kts', b'rootProject.name = "Fixture"\n')
        self.write(self.project / 'gradle.properties', b'fixture=true\n')
        self.write(self.project / 'README.md', b'Fixture documentation\n')
        self.write(self.project / 'docs/review/RESULTS.md', b'Fixture historical commentary\n')
        source_paths = [self.source, self.project / 'build.gradle.kts',
                        self.project / 'settings.gradle.kts', self.project / 'gradle.properties']
        self.source_snapshot = {
            'SchemaVersion': 1, 'Id': self.snapshot_id, 'SourceSnapshotId': self.snapshot_id,
            'Files': [self.record(path, relative=True) for path in source_paths],
        }
        self.apk = Path(self.context['ApkPath'])
        self.apk.parent.mkdir(parents=True)
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('AndroidManifest.xml', '<manifest package="fixture"/>')
            archive.writestr(AGP_METADATA, 'appMetadataVersion=1\nandroidGradlePluginVersion=9.0.0\n')
        self.built_apk = self.write(Path(self.context['BuiltApkPath']), self.apk.read_bytes())
        self.checksum = self.write(Path(self.context['ChecksumPath']),
                                   f'{package_review.sha256(self.apk)}  {self.apk.name}\n'.encode())
        self.unit = self.write(Path(self.context['UnitResultsPath']) / 'TEST-fixture.xml',
                               b'<testsuite tests="1" failures="0" errors="0" skipped="0">'
                               b'<testcase name="fixture"/></testsuite>')
        self.lints = [self.write(Path(self.context[name]), b'<issues format="6"/>')
                      for name in ('LintDebugPath', 'LintQaPath')]
        self.receipt = self.write(self.task_root / 'receipts/build-fixture.json',
                                  json.dumps({'SchemaVersion': 2, 'Success': True,
                                              'SourceSnapshotId': self.snapshot_id}).encode())
        self.counts = {'Tests': 1, 'Failures': 0, 'Errors': 0, 'Skipped': 0,
                       'Files': [str(self.unit)]}
        self.inputs = {
            'ManifestPath': str(self.task_root / 'evidence/fixture.json'),
            'SourceSnapshotId': self.snapshot_id, 'Context': self.context,
            'BuildRoot': str(self.build), 'SourceSnapshot': self.source_snapshot,
            'ApkPath': str(self.apk), 'BuiltApkPath': str(self.built_apk),
            'ChecksumPath': str(self.checksum), 'UnitReports': [str(self.unit)],
            'LintReports': [str(path) for path in self.lints], 'DeviceReports': [],
            'UnitTests': self.counts, 'DeviceTests': None,
            'ReceiptPaths': [str(self.receipt)],
            'FileRecords': [self.record(path) for path in
                            [self.apk, self.built_apk, self.checksum, self.unit,
                             *self.lints, self.receipt]],
        }
        self.manifest = Path(self.inputs['ManifestPath'])
        self.write_manifest()
        self.lease = {'LeaseId': 'fixture-lease', 'Resource': 'BUILD', 'Owner': 'fixture-owner',
                      'CheckoutRoot': str(self.project), 'TaskKey': self.context['TaskKey']}
        self.requests = []
        self.project_patch = mock.patch.object(package_review, 'PROJECT', self.project)
        self.project_patch.start()
        self.addCleanup(self.project_patch.stop)

    @staticmethod
    def write(path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def record(self, path, relative=False):
        return {'Path': path.relative_to(self.project).as_posix() if relative else str(path),
                'Sha256': package_review.sha256(path), 'Bytes': path.stat().st_size}

    def write_manifest(self, **changes):
        manifest = {'SchemaVersion': 2, 'Context': self.context,
                    'SourceSnapshotId': self.snapshot_id, 'SourceSnapshot': self.source_snapshot,
                    'Files': self.inputs['FileRecords'], 'UnitTests': self.counts,
                    'DeviceTests': None, 'BuildReceiptPath': str(self.receipt)}
        manifest.update(changes)
        self.write(self.manifest, json.dumps(manifest).encode())

    def bridge(self, payload):
        self.requests.append(copy.deepcopy(payload))
        if payload['Operation'] == 'Acquire':
            return {'Lease': copy.deepcopy(self.lease), 'Inputs': copy.deepcopy(self.inputs)}
        if payload['Operation'] == 'Check':
            return {'Inputs': copy.deepcopy(self.inputs)}
        if payload['Operation'] == 'Release':
            return {}
        raise AssertionError(f'Unexpected bridge operation: {payload}')

    def run_main(self, *extra, bridge=None):
        arguments = ['--evidence-manifest', str(self.manifest), '--owner', 'fixture-owner', *extra]
        with mock.patch.object(package_review, 'powershell_request', side_effect=bridge or self.bridge):
            with contextlib.redirect_stdout(io.StringIO()):
                return package_review.main(arguments)

    def assert_released(self):
        self.assertEqual('Acquire', self.requests[0]['Operation'])
        self.assertEqual('Release', self.requests[-1]['Operation'])
        self.assertEqual(self.lease, self.requests[-1]['Lease'])
        self.assertTrue(all(request.get('Resource', 'BUILD') == 'BUILD' for request in self.requests))

    def test_manifest_is_required_even_when_legacy_apk_exists(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                package_review.main(['--validate-only'])

    def test_destination_is_required_for_packaging(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                package_review.main(['--evidence-manifest', str(self.manifest)])

    def test_removed_legacy_selection_arguments_are_rejected(self):
        for argument in ('--tool-root', '--folded-results'):
            with self.subTest(argument=argument), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    package_review.main(['--evidence-manifest', str(self.manifest), '--validate-only',
                                         argument, str(self.tools)])

    def test_validate_only_does_not_need_baseline_or_create_review_materials(self):
        before = set(self.root.rglob('*'))
        self.run_main('--validate-only')
        self.assertEqual(before, set(self.root.rglob('*')))
        self.assertIn('Check', [request['Operation'] for request in self.requests])
        self.assert_released()

    @unittest.skipUnless(os.name == 'nt', 'Windows short-path alias regression')
    def test_packaging_fixture_resolves_windows_short_path_alias(self):
        import ctypes

        temporary = tempfile.TemporaryDirectory(prefix='bookcover-package-alias-tests-')
        self.addCleanup(temporary.cleanup)
        canonical = Path(temporary.name).resolve()
        get_short_path = ctypes.WinDLL('kernel32', use_last_error=True).GetShortPathNameW
        get_short_path.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_uint32]
        get_short_path.restype = ctypes.c_uint32
        buffer = ctypes.create_unicode_buffer(32768)
        length = get_short_path(str(canonical), buffer, len(buffer))
        if not length or length >= len(buffer) or Path(buffer.value) == canonical:
            self.skipTest('The temporary volume does not provide a distinct Windows short-path alias.')
        self.assertTrue(Path(buffer.value).samefile(canonical))
        self.assertEqual(canonical, Path(buffer.value).resolve())
        aliased_temporary = mock.Mock()
        aliased_temporary.name = buffer.value
        aliased_temporary.cleanup = temporary.cleanup
        fixture = PackageReviewTests('test_success_packages_only_explicit_evidence_and_frozen_source')
        try:
            with mock.patch.object(tempfile, 'TemporaryDirectory', return_value=aliased_temporary):
                fixture.setUp()
            self.assertEqual(canonical, fixture.root)
            fixture.test_success_packages_only_explicit_evidence_and_frozen_source()
        finally:
            fixture.doCleanups()

    def test_success_packages_only_explicit_evidence_and_frozen_source(self):
        self.write(self.project / 'app/src/main/java/Unrecorded.kt', b'// newer source\n')
        self.write(self.project / 'gradlew', b'#!/bin/sh\necho unrecorded wrapper\n')
        self.write(self.project / 'Build BookCover.cmd', b'@echo unrecorded launcher\r\n')
        self.write(self.build / 'app/test-results/testQaUnitTest/TEST-unselected.xml', b'unselected report')
        self.write(self.tools / 'anything-folded/TEST-unselected.xml', b'unselected folded report')
        self.write(self.tools / 'final-review-build.log', b'unselected legacy log')
        self.run_main('--destination', str(self.destination))
        self.assertTrue(self.destination.is_dir())
        archive_path = Path(str(self.destination) + '.zip')
        self.assertTrue(archive_path.is_file())
        self.assertTrue(Path(str(archive_path) + '.sha256').is_file())
        with zipfile.ZipFile(archive_path) as archive:
            names = archive.namelist()
            self.assertTrue(any(name.endswith('/source/app/src/main/java/Fixture.kt') for name in names))
            self.assertTrue(any(name.endswith('/TEST-fixture.xml') for name in names))
            self.assertFalse(any('Unrecorded.kt' in name or 'unselected' in name or
                                 'final-review-build.log' in name for name in names))
            self.assertFalse(any(name.endswith('/source/gradlew') or
                                 name.endswith('/source/Build BookCover.cmd') for name in names))
        self.assertGreaterEqual([request['Operation'] for request in self.requests].count('Check'), 2)
        self.assert_released()
        self.assertIn('exit 0', self.requests[-1]['ReleaseEvidence'])

    def test_old_caller_asserted_schema_is_rejected(self):
        self.write_manifest(SchemaVersion=1)
        with self.assertRaises((ValueError, SystemExit)):
            self.run_main('--validate-only')
        self.assertFalse(self.destination.exists())

    def test_invalid_snapshot_identifier_is_rejected(self):
        self.write_manifest(SourceSnapshotId='b' * 40)
        with self.assertRaises((ValueError, SystemExit)):
            self.run_main('--validate-only')

    def test_manifest_from_a_different_checkout_is_rejected(self):
        other_context = dict(self.context, CheckoutRoot=str(self.root / 'other-checkout'))
        self.write_manifest(Context=other_context)
        with self.assertRaises((ValueError, SystemExit)):
            self.run_main('--validate-only')
        self.assertFalse(self.destination.exists())

    def test_valid_hash_report_outside_build_root_is_rejected(self):
        outside = self.write(self.tools / 'unrelated/TEST-fixture.xml', self.unit.read_bytes())
        self.inputs['UnitReports'] = [str(outside)]
        self.inputs['UnitTests']['Files'] = [str(outside)]
        self.inputs['FileRecords'] = [record for record in self.inputs['FileRecords']
                                      if record['Path'] != str(self.unit)] + [self.record(outside)]
        self.write_manifest()
        with self.assertRaises((ValueError, SystemExit)):
            self.run_main('--destination', str(self.destination))
        self.assertFalse(Path(str(self.destination) + '.zip').exists())
        self.assert_released()

    def test_apk_privacy_scan_rejects_personal_book_inside_apk(self):
        with zipfile.ZipFile(self.apk, 'a') as archive:
            archive.writestr('assets/personal.epub', b'personal book')
        self.built_apk.write_bytes(self.apk.read_bytes())
        self.checksum.write_text(f'{package_review.sha256(self.apk)}  {self.apk.name}\n', encoding='utf-8')
        self.inputs['FileRecords'] = [self.record(Path(record['Path']))
                                      for record in self.inputs['FileRecords']]
        self.write_manifest()
        with self.assertRaises((ValueError, SystemExit)):
            self.run_main('--destination', str(self.destination))
        self.assertFalse(Path(str(self.destination) + '.zip').exists())
        self.assert_released()

    def test_independent_artifact_hash_check_rejects_mutation_and_releases(self):
        self.unit.write_bytes(b'changed since evidence')
        with self.assertRaises((ValueError, SystemExit)):
            self.run_main('--destination', str(self.destination))
        self.assertFalse(Path(str(self.destination) + '.zip').exists())
        self.assert_released()

    def test_missing_explicit_report_does_not_fall_back_to_another_report(self):
        self.unit.unlink()
        self.write(self.unit.parent / 'TEST-unselected.xml', b'<testsuite/>')
        with self.assertRaises((ValueError, SystemExit, FileNotFoundError)):
            self.run_main('--destination', str(self.destination))
        self.assertFalse(Path(str(self.destination) + '.zip').exists())
        self.assert_released()

    def test_source_hash_check_rejects_mutation_and_releases(self):
        self.source.write_bytes(b'// changed after build\n')
        with self.assertRaises((ValueError, SystemExit)):
            self.run_main('--destination', str(self.destination))
        self.assertFalse(Path(str(self.destination) + '.zip').exists())
        self.assert_released()

    def test_helper_failure_after_acquire_releases_only_owned_lease(self):
        def fail_check(payload):
            if payload['Operation'] == 'Check':
                self.requests.append(copy.deepcopy(payload))
                raise ValueError('source changed during package')
            return self.bridge(payload)
        with self.assertRaises((ValueError, SystemExit)):
            self.run_main('--destination', str(self.destination), bridge=fail_check)
        self.assertFalse(Path(str(self.destination) + '.zip').exists())
        self.assert_released()
        self.assertIn('exit 1', self.requests[-1]['ReleaseEvidence'])

    def test_manifest_mutation_during_check_fails_and_releases(self):
        def change_manifest(payload):
            response = self.bridge(payload)
            if payload['Operation'] == 'Check':
                self.manifest.write_bytes(self.manifest.read_bytes() + b'\n')
            return response
        for options in (['--destination', str(self.destination)], ['--validate-only']):
            with self.subTest(options=options):
                self.requests.clear()
                with self.assertRaises((ValueError, SystemExit)):
                    self.run_main(*options, bridge=change_manifest)
                self.assertFalse(Path(str(self.destination) + '.zip').exists())
                self.assert_released()

    def test_failed_acquire_never_releases_someone_elses_lease(self):
        def fail_acquire(payload):
            self.requests.append(copy.deepcopy(payload))
            raise ValueError('BUILD is already reserved')
        with self.assertRaises((ValueError, SystemExit)):
            self.run_main('--validate-only', bridge=fail_acquire)
        self.assertEqual(['Acquire'], [request['Operation'] for request in self.requests])

    def test_existing_destination_is_preserved(self):
        marker = self.write(self.destination / 'owner-marker.txt', b'preserve')
        with self.assertRaises((ValueError, SystemExit)):
            self.run_main('--destination', str(self.destination))
        self.assertEqual(b'preserve', marker.read_bytes())
        self.assertFalse(Path(str(self.destination) + '.zip').exists())

    def test_personal_epubs_are_forbidden_even_with_generated_sample_name_elsewhere(self):
        for name in ('app/src/main/assets/samples/personal.epub', 'docs/lantern-keeper.epub',
                     'app/src/main/assets/personal/river-maps.epub', 'source/docs/personal.epub'):
            with self.subTest(name=name):
                self.assertTrue(package_review.forbidden(name))
        for name in ('app/src/main/assets/samples/lantern-keeper.epub',
                     'app/src/main/assets/samples/river-maps.epub'):
            with self.subTest(name=name):
                self.assertFalse(package_review.forbidden(name))

    def test_archive_rejects_traversal_secrets_and_personal_books(self):
        cases = [('traversal.zip', '../escape.txt', b'private'),
                 ('personal.zip', 'source/docs/personal.epub', b'book'),
                 ('secret.zip', 'docs/key.txt', b'-----BEGIN ' + b'PRIVATE KEY-----')]
        for filename, member, data in cases:
            with self.subTest(filename=filename):
                archive_path = self.root / filename
                with zipfile.ZipFile(archive_path, 'w') as archive:
                    archive.writestr(member, data)
                with self.assertRaises(ValueError):
                    package_review.inspect_archive(archive_path)

    def test_archive_rejects_symlink(self):
        archive_path = self.root / 'symlink.zip'
        info = zipfile.ZipInfo('docs/link')
        info.create_system = 3
        info.external_attr = 0o120777 << 16
        with zipfile.ZipFile(archive_path, 'w') as archive:
            archive.writestr(info, '../../private')
        with self.assertRaises(ValueError):
            package_review.inspect_archive(archive_path)

    def test_standard_agp_metadata_is_allowed_only_inside_apk(self):
        archive_path = self.root / 'metadata.apk'
        with zipfile.ZipFile(archive_path, 'w') as archive:
            archive.writestr(AGP_METADATA, 'appMetadataVersion=1\n')
        package_review.inspect_archive(archive_path, apk=True)
        with self.assertRaises(ValueError):
            package_review.inspect_archive(archive_path, apk=False)

    def test_agp_metadata_exception_does_not_allow_other_build_members(self):
        for member in ('build/private-source',
                       'META-INF/com/android/build/gradle/private-source.txt',
                       AGP_METADATA + '.bak',
                       'META-INF/com/android/build/gradle/APP-METADATA.properties'):
            with self.subTest(member=member):
                archive_path = self.root / 'other-build-member.apk'
                with zipfile.ZipFile(archive_path, 'w') as archive:
                    archive.writestr(AGP_METADATA, 'appMetadataVersion=1\n')
                    archive.writestr(member, 'private source')
                with self.assertRaises(ValueError):
                    package_review.inspect_archive(archive_path, apk=True)

    def test_standard_agp_metadata_still_checks_secret_text(self):
        archive_path = self.root / 'secret-metadata.apk'
        with zipfile.ZipFile(archive_path, 'w') as archive:
            archive.writestr(AGP_METADATA, b'-----BEGIN ' + b'PRIVATE KEY-----')
        with self.assertRaises(ValueError):
            package_review.inspect_archive(archive_path, apk=True)

    def test_standard_agp_metadata_still_rejects_symlink(self):
        archive_path = self.root / 'symlink-metadata.apk'
        info = zipfile.ZipInfo(AGP_METADATA)
        info.create_system = 3
        info.external_attr = 0o120777 << 16
        with zipfile.ZipFile(archive_path, 'w') as archive:
            archive.writestr(info, '../../private')
        with self.assertRaises(ValueError):
            package_review.inspect_archive(archive_path, apk=True)

    def test_powershell_bridge_uses_constant_code_and_json_stdin(self):
        payload = {'Operation': 'Acquire', 'ManifestPath': str(self.manifest),
                   'Owner': 'Owner \"; $(Write-Output injected); #',
                   'Project': str(self.project)}
        result = subprocess.CompletedProcess([], 0, stdout='{"Lease": {"LeaseId": "test"}}', stderr='')
        with mock.patch.object(package_review.subprocess, 'run', return_value=result) as run:
            package_review.powershell_request(payload)
        arguments, keywords = run.call_args
        command = arguments[0]
        self.assertIsInstance(command, list)
        self.assertIn('-NoProfile', command)
        self.assertIn('-NonInteractive', command)
        self.assertIn('-Command', command)
        code = command[command.index('-Command') + 1]
        self.assertNotIn(payload['Owner'], code)
        self.assertNotIn(str(self.manifest), code)
        self.assertEqual(payload, json.loads(keywords['input']))
        self.assertFalse(keywords.get('shell', False))
        self.assertIn('ReadToEnd', code)
        self.assertIn('-Resource BUILD', code)
        self.assertNotIn('-Resource PHONE', code)
        self.assertIn('-OperationStopped', code)
        self.assertIn('-ReleaseEvidence', code)


if __name__ == '__main__':
    unittest.main()
