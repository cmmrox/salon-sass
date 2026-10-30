"""Command line for the work spine. Run it through the wrapper: qa/scripts/sdlc <command>."""
import argparse
import sys
import unittest

from . import flow, generate, integration, model, runner, validate


def cmd_validate(_args):
    bundle = model.load()
    findings = validate.validate(bundle, generate.render(bundle))
    for finding in findings:
        print(f"FAIL {finding}")
    if findings:
        print(f"{len(findings)} finding(s)")
        return 1
    print(
        f"OK {len(bundle['requirements'])} requirements, {len(bundle['features'])} features, "
        f"{len(bundle['stages'])} stages, {len(bundle['skills'])} skills"
    )
    return 0


def cmd_generate(args):
    bundle = model.load()
    stale = 0
    for path, content in generate.render(bundle).items():
        if bundle["generated"].get(path) == content:
            continue
        stale += 1
        if args.check:
            print(f"STALE {path}")
        else:
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(content)
            print(f"wrote {path}")
    if not stale:
        print("generated documents are up to date")
    return 1 if (args.check and stale) else 0


def cmd_status(_args):
    bundle = model.load()
    for feature in bundle["features"]:
        stages = [s for s in bundle["stages"] if s.get("_feature_dir") == feature["_dir"]]
        if feature.get("status") == "backlog":
            continue
        role, action = flow.feature_turn(feature, stages, bundle["features"])
        print(f"{feature.get('id')} {feature.get('status'):12} next: {role}: {action}")
        for stage in sorted(stages, key=lambda s: (s.get("sequence") or 0, s.get("id") or "")):
            role, action = flow.stage_turn(stage)
            print(f"  {stage.get('id')} {stage.get('status'):12} qa={str(stage.get('qa_status')):8} next: {role}: {action}")
    return 0


def cmd_run(args):
    bundle = model.load()
    try:
        if args.release:
            return runner.run_release(bundle, args.release, args.dry_run)
        entries = runner.select(bundle, stage=args.stage, feature=args.feature, suite=args.suite)
        return runner.run(entries, args.stage or args.feature or args.suite, args.dry_run)[0]
    except (runner.SelectionError, OSError) as error:
        print(f"refusing: {error}")
        return 2


def cmd_selftest(_args):
    suite = unittest.defaultTestLoader.discover("qa/scripts/tests", top_level_dir="qa/scripts")
    return 0 if unittest.TextTestRunner(verbosity=1).run(suite).wasSuccessful() else 1


def cmd_integration(args):
    try:
        bundle = model.load()
        if args.command == "pr-check":
            findings = validate.validate(bundle, generate.render(bundle))
            if findings:
                raise integration.Refusal("run validate and fix its findings before submitting")
            integration.pr_check(bundle, args.feature)
            print(f"OK {args.feature}: feature QA and revision gates passed; owner merges the PR")
        elif args.command == "start-check":
            integration.start_check(bundle, args.feature)
            print(f"OK {args.feature}: earlier feature merges verified against GitHub and origin/main")
        elif args.command == "merge-sync":
            record = integration.merge_sync(bundle, args.feature, args.check)
            if not args.check:
                cmd_generate(argparse.Namespace(check=False))
            print(f"OK {args.feature}: GitHub merge {record['merged_commit']} at {record['merged_at']}")
        else:
            import json
            print(json.dumps(integration.cleanup(args.branch, args.apply), indent=2))
            if not args.apply:
                print("PREVIEW only; use --apply after checking the explicit targets")
        return 0
    except (integration.Refusal, OSError, ValueError) as error:
        print(f"refusing: {error}")
        return 2


def main(argv=None):
    parser = argparse.ArgumentParser(prog="qa/scripts/sdlc")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("validate", help="check the whole work spine; exit 1 on any finding").set_defaults(func=cmd_validate)
    gen = commands.add_parser("generate", help="rewrite features/README.md and qa/coverage.md")
    gen.add_argument("--check", action="store_true", help="report stale documents without writing")
    gen.set_defaults(func=cmd_generate)
    commands.add_parser("status", help="print whose turn it is for every feature in flight").set_defaults(func=cmd_status)
    run = commands.add_parser("run", help="run QA automation for exactly one scope")
    run.add_argument("--stage", help="stage id, for example F07-S02")
    run.add_argument("--feature", help="feature id, for example F07")
    run.add_argument("--suite", help="smoke, regression or release: closed work tagged with that suite")
    run.add_argument("--release", help="release version, for example v0.1.0: runs every gate of its manifest")
    run.add_argument("--dry-run", action="store_true", help="print what would run and stop")
    run.set_defaults(func=cmd_run)
    commands.add_parser("selftest", help="run the unit tests of this tooling").set_defaults(func=cmd_selftest)
    for name, help_text in (("pr-check", "check closed feature and tested revision before its PR"),
                            ("start-check", "verify previous feature merges live before starting"),
                            ("merge-sync", "record a confirmed GitHub merge; never merges a PR")):
        command = commands.add_parser(name, help=help_text)
        command.add_argument("feature", help="feature id, for example F07")
        if name == "merge-sync":
            command.add_argument("--check", action="store_true", help="verify only; do not edit feature.yaml")
        command.set_defaults(func=cmd_integration)
    clean = commands.add_parser("cleanup", help="preview cleanup of explicit branches already merged into origin/main")
    clean.add_argument("--branch", action="append", required=True, help="exact related branch; repeat for each target")
    clean.add_argument("--apply", action="store_true", help="archive QA evidence and remove the verified targets")
    clean.set_defaults(func=cmd_integration)
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
