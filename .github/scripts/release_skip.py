#!/usr/bin/env python3
"""Decide whether a release run (a pull request into master, or the push to master after it merges) may skip the heavy CI jobs.

A group of jobs is skipped only when the commit's tree (`git rev-parse HEAD^{tree}`; for a pull request that is the test merge commit)
is identical to the tree of a dev commit whose own CI run (the push to dev) concluded success AND ran every job of that group. Same tree
means same files, workflow included, so that run already proved this exact code. Anything else runs everything: a hotfix straight to
master, a dev that was never green, a tree that differs, or any error while asking the GitHub API (fail closed).

Outputs (to $GITHUB_OUTPUT): one `<group>=run|skip` per --group, and `reason`, a one-line explanation the `ci` gate prints.
Stdlib only, so it runs on a bare runner. The decision logic is pure and covered by test_release_skip.py in the same folder.

Usage: release_skip.py --workflow ci.yml --group "app=job name,other job" [--group ...] [--wait-minutes 20]
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

DEV_BRANCH = "dev"
RELEASE_BRANCH = "master"
POLL_SECONDS = 30


def is_release(event, ref, base_ref):
    """True for a pull request into master and for a push to master; everything else keeps its usual jobs."""
    if event == "pull_request":
        return base_ref == RELEASE_BRANCH
    if event == "push":
        return ref == "refs/heads/" + RELEASE_BRANCH
    return False


def parse_groups(specs):
    """["app=job a,job b"] -> {"app": ["job a", "job b"]}."""
    groups = {}
    for spec in specs:
        name, sep, jobs = spec.partition("=")
        names = [j.strip() for j in jobs.split(",") if j.strip()]
        if not sep or not name.strip() or not names:
            raise ValueError("bad --group %r, expected name=job,job" % spec)
        groups[name.strip()] = names
    return groups


def evaluate(tree, runs, fetch_jobs, groups):
    """Match dev runs against the tree.

    Returns (covered, pending): covered maps each group that a green run with the same tree fully ran to that run; pending lists runs
    with the same tree that have not finished yet (worth waiting for).
    """
    covered = {}
    pending = []
    for run in runs:
        head = run.get("head_commit") or {}
        if head.get("tree_id") != tree:
            continue
        if run.get("status") != "completed":
            pending.append(run)
            continue
        if run.get("conclusion") != "success":
            continue
        passed = {job.get("name") for job in fetch_jobs(run["id"]) if job.get("conclusion") == "success"}
        for group, names in groups.items():
            if group not in covered and all(name in passed for name in names):
                covered[group] = run
    return covered, pending


def describe(tree, groups, covered):
    """One line per group, joined: why each group runs or is skipped."""
    parts = []
    for group in groups:
        run = covered.get(group)
        if run:
            parts.append(
                "skip %s: tree %s matches dev commit %s, CI run %s green"
                % (group, tree[:12], run.get("head_sha", "?")[:12], run.get("html_url", "?"))
            )
        else:
            parts.append(
                "run %s: no green CI run of a dev commit with tree %s ran all of its jobs" % (group, tree[:12])
            )
    return "; ".join(parts)


def decide(env, api, tree, groups, workflow, wait_seconds, sleep=time.sleep, now=time.monotonic):
    """Returns (decisions {group: "run"|"skip"}, reason). Never raises: any error runs everything."""
    run_all = {group: "run" for group in groups}
    event = env.get("GITHUB_EVENT_NAME", "")
    if not is_release(event, env.get("GITHUB_REF", ""), env.get("GITHUB_BASE_REF", "")):
        target = env.get("GITHUB_BASE_REF") if event == "pull_request" else env.get("GITHUB_REF", "")
        return run_all, "not a release run (%s, %s): running the usual jobs" % (event or "?", target or "?")
    try:
        if not tree:
            raise ValueError("no tree id")
        repo = env["GITHUB_REPOSITORY"]
        runs_path = "repos/%s/actions/workflows/%s/runs?branch=%s&event=push&per_page=50" % (repo, workflow, DEV_BRANCH)

        def fetch_jobs(run_id):
            return api("repos/%s/actions/runs/%s/jobs?filter=latest&per_page=100" % (repo, run_id)).get("jobs", [])

        deadline = now() + wait_seconds
        while True:
            covered, pending = evaluate(tree, api(runs_path).get("workflow_runs", []), fetch_jobs, groups)
            if len(covered) == len(groups) or not pending or now() >= deadline:
                break
            print("dev run %s with the same tree is still %s; checking again in %ss" % (pending[0].get("html_url", "?"), pending[0].get("status"), POLL_SECONDS))
            sleep(POLL_SECONDS)
        decisions = {group: ("skip" if group in covered else "run") for group in groups}
        return decisions, describe(tree, groups, covered)
    except Exception as error:  # fail closed: a lookup we can't trust never skips anything
        return run_all, "run everything: the GitHub API lookup failed (%s: %s)" % (type(error).__name__, error)


def github_api(token, base_url):
    def call(path):
        request = urllib.request.Request(
            base_url.rstrip("/") + "/" + path,
            headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"},
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)

    return call


def head_tree():
    try:
        return subprocess.run(["git", "rev-parse", "HEAD^{tree}"], capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return ""


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--workflow", required=True, help="workflow file name, e.g. ci.yml")
    parser.add_argument("--group", action="append", required=True, help='"name=job,job": jobs skipped together')
    parser.add_argument("--wait-minutes", type=float, default=20, help="how long to wait for a dev run with the same tree that is still running")
    args = parser.parse_args(argv)
    groups = parse_groups(args.group)

    env = dict(os.environ)
    api = github_api(env.get("GITHUB_TOKEN", ""), env.get("GITHUB_API_URL", "https://api.github.com"))
    decisions, reason = decide(env, api, head_tree(), groups, args.workflow, args.wait_minutes * 60)

    print(reason)
    lines = ["%s=%s" % (group, decision) for group, decision in decisions.items()] + ["reason=" + reason.replace("\n", " ")]
    for name in ("GITHUB_OUTPUT", "GITHUB_STEP_SUMMARY"):
        path = env.get(name)
        if path:
            with open(path, "a", encoding="utf-8") as handle:
                if name == "GITHUB_OUTPUT":
                    handle.write("\n".join(lines) + "\n")
                else:
                    handle.write("### Release CI decision\n\n" + "\n".join("- " + part for part in reason.split("; ")) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
