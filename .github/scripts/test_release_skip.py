"""Unit tests for release_skip.py. Run: python3 -m unittest discover -s .github/scripts -p "test_*.py" """

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import release_skip  # noqa: E402

TREE = "a" * 40
OTHER_TREE = "b" * 40
REPO = "owner/repo"
GROUPS = {"web": ["web", "docker (web)"], "site": ["site"]}
PR_INTO_MASTER = {"GITHUB_EVENT_NAME": "pull_request", "GITHUB_BASE_REF": "master", "GITHUB_REF": "refs/pull/7/merge", "GITHUB_REPOSITORY": REPO}
PUSH_TO_MASTER = {"GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/master", "GITHUB_REPOSITORY": REPO}


def run(run_id, tree=TREE, status="completed", conclusion="success"):
    return {
        "id": run_id,
        "head_sha": ("%d" % run_id) * 40,
        "html_url": "https://github.com/%s/actions/runs/%d" % (REPO, run_id),
        "status": status,
        "conclusion": conclusion,
        "head_commit": {"tree_id": tree},
    }


def jobs(**conclusions):
    return {"jobs": [{"name": name.replace("_", " ").replace("docker web", "docker (web)"), "conclusion": c} for name, c in conclusions.items()]}


ALL_GREEN = jobs(web="success", docker_web="success", site="success", ci="success")


class FakeApi:
    """Serves a sequence of run listings (one per poll) and fixed job lists; records calls."""

    def __init__(self, listings, job_lists):
        self.listings = list(listings)
        self.job_lists = job_lists
        self.calls = []

    def __call__(self, path):
        self.calls.append(path)
        if "/jobs?" in path:
            run_id = int(path.split("/runs/")[1].split("/")[0])
            return self.job_lists[run_id]
        listing = self.listings.pop(0) if len(self.listings) > 1 else self.listings[0]
        return {"workflow_runs": listing}


def decide(env, api, tree=TREE, wait_seconds=0, clock=None):
    clock = clock or [0.0]

    def sleep(seconds):
        clock[0] += seconds

    return release_skip.decide(env, api, tree, GROUPS, "ci.yml", wait_seconds, sleep=sleep, now=lambda: clock[0])


class IsReleaseTest(unittest.TestCase):
    def test_release_events(self):
        self.assertTrue(release_skip.is_release("pull_request", "refs/pull/1/merge", "master"))
        self.assertTrue(release_skip.is_release("push", "refs/heads/master", ""))

    def test_everything_else_is_not_a_release(self):
        self.assertFalse(release_skip.is_release("pull_request", "refs/pull/1/merge", "dev"))
        self.assertFalse(release_skip.is_release("push", "refs/heads/dev", ""))
        self.assertFalse(release_skip.is_release("workflow_dispatch", "refs/heads/master", ""))


class ParseGroupsTest(unittest.TestCase):
    def test_parses_names_with_spaces(self):
        self.assertEqual(release_skip.parse_groups(["web=web, docker (web)", "site=site"]), {"web": ["web", "docker (web)"], "site": ["site"]})

    def test_rejects_bad_specs(self):
        for spec in ["web", "=web", "web=", "web= , "]:
            with self.assertRaises(ValueError):
                release_skip.parse_groups([spec])


class DecideTest(unittest.TestCase):
    def test_push_to_dev_runs_everything_without_asking_the_api(self):
        api = FakeApi([[run(1)]], {1: ALL_GREEN})
        decisions, reason = decide({"GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/dev", "GITHUB_REPOSITORY": REPO}, api)
        self.assertEqual(decisions, {"web": "run", "site": "run"})
        self.assertIn("not a release run", reason)
        self.assertEqual(api.calls, [])

    def test_pr_into_dev_runs_everything(self):
        decisions, _ = decide(dict(PR_INTO_MASTER, GITHUB_BASE_REF="dev"), FakeApi([[run(1)]], {1: ALL_GREEN}))
        self.assertEqual(decisions, {"web": "run", "site": "run"})

    def test_matching_green_dev_run_skips_everything(self):
        for env in (PR_INTO_MASTER, PUSH_TO_MASTER):
            api = FakeApi([[run(2, tree=OTHER_TREE), run(1)]], {1: ALL_GREEN})
            decisions, reason = decide(env, api)
            self.assertEqual(decisions, {"web": "skip", "site": "skip"})
            self.assertIn("tree %s matches dev commit %s" % (TREE[:12], "1" * 12), reason)
            self.assertIn("https://github.com/owner/repo/actions/runs/1 green", reason)
            self.assertIn("branch=dev&event=push", api.calls[0])

    def test_hotfix_tree_differs_runs_everything(self):
        decisions, reason = decide(PR_INTO_MASTER, FakeApi([[run(1, tree=OTHER_TREE)]], {1: ALL_GREEN}))
        self.assertEqual(decisions, {"web": "run", "site": "run"})
        self.assertIn("run web: no green CI run", reason)

    def test_dev_never_green_runs_everything(self):
        decisions, _ = decide(PR_INTO_MASTER, FakeApi([[run(1, conclusion="failure"), run(2, conclusion="cancelled")]], {}))
        self.assertEqual(decisions, {"web": "run", "site": "run"})

    def test_no_dev_runs_at_all_runs_everything(self):
        decisions, _ = decide(PR_INTO_MASTER, FakeApi([[]], {}))
        self.assertEqual(decisions, {"web": "run", "site": "run"})

    def test_job_skipped_by_a_path_filter_on_dev_does_not_count(self):
        # The dev run was green, but `site` was skipped there, so nothing proved `site` at this tree.
        api = FakeApi([[run(1)]], {1: jobs(web="success", docker_web="success", site="skipped", ci="success")})
        decisions, reason = decide(PR_INTO_MASTER, api)
        self.assertEqual(decisions, {"web": "skip", "site": "run"})
        self.assertIn("skip web", reason)
        self.assertIn("run site", reason)

    def test_a_group_needs_every_one_of_its_jobs(self):
        api = FakeApi([[run(1)]], {1: jobs(web="success", site="success")})  # docker (web) missing
        decisions, _ = decide(PR_INTO_MASTER, api)
        self.assertEqual(decisions, {"web": "run", "site": "skip"})

    def test_groups_can_be_covered_by_different_runs_with_the_same_tree(self):
        api = FakeApi([[run(2), run(1)]], {2: jobs(web="success", docker_web="success"), 1: jobs(site="success")})
        decisions, _ = decide(PR_INTO_MASTER, api)
        self.assertEqual(decisions, {"web": "skip", "site": "skip"})

    def test_api_error_fails_closed(self):
        def broken(path):
            raise OSError("HTTP 502")

        decisions, reason = decide(PR_INTO_MASTER, broken)
        self.assertEqual(decisions, {"web": "run", "site": "run"})
        self.assertIn("lookup failed (OSError: HTTP 502)", reason)

    def test_malformed_api_response_fails_closed(self):
        decisions, reason = decide(PR_INTO_MASTER, lambda path: {"workflow_runs": [{"head_commit": {"tree_id": TREE}, "status": "completed", "conclusion": "success"}]})
        self.assertEqual(decisions, {"web": "run", "site": "run"})
        self.assertIn("lookup failed", reason)

    def test_missing_tree_fails_closed(self):
        decisions, reason = decide(PR_INTO_MASTER, FakeApi([[run(1)]], {1: ALL_GREEN}), tree="")
        self.assertEqual(decisions, {"web": "run", "site": "run"})
        self.assertIn("lookup failed", reason)

    def test_waits_for_a_running_dev_run_with_the_same_tree(self):
        api = FakeApi([[run(1, status="in_progress", conclusion=None)], [run(1, status="in_progress", conclusion=None)], [run(1)]], {1: ALL_GREEN})
        clock = [0.0]
        decisions, _ = decide(PR_INTO_MASTER, api, wait_seconds=600, clock=clock)
        self.assertEqual(decisions, {"web": "skip", "site": "skip"})
        self.assertEqual(clock[0], 2 * release_skip.POLL_SECONDS)

    def test_gives_up_waiting_at_the_deadline_and_runs_everything(self):
        api = FakeApi([[run(1, status="queued", conclusion=None)]], {})
        clock = [0.0]
        decisions, _ = decide(PR_INTO_MASTER, api, wait_seconds=90, clock=clock)
        self.assertEqual(decisions, {"web": "run", "site": "run"})
        self.assertGreaterEqual(clock[0], 90)

    def test_does_not_wait_when_nothing_with_the_same_tree_is_running(self):
        clock = [0.0]
        decide(PR_INTO_MASTER, FakeApi([[run(1, tree=OTHER_TREE, status="in_progress", conclusion=None)]], {}), wait_seconds=600, clock=clock)
        self.assertEqual(clock[0], 0)


class MainTest(unittest.TestCase):
    def test_writes_outputs_for_a_non_release_run(self):
        with tempfile.TemporaryDirectory() as folder:
            output = os.path.join(folder, "out")
            summary = os.path.join(folder, "summary")
            saved = dict(os.environ)
            try:
                os.environ.update({"GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/dev", "GITHUB_OUTPUT": output, "GITHUB_STEP_SUMMARY": summary})
                self.assertEqual(release_skip.main(["--workflow", "ci.yml", "--group", "web=web,docker (web)", "--group", "site=site"]), 0)
            finally:
                os.environ.clear()
                os.environ.update(saved)
            with open(output, encoding="utf-8") as handle:
                lines = handle.read().splitlines()
            self.assertEqual(lines[:2], ["web=run", "site=run"])
            self.assertTrue(lines[2].startswith("reason=not a release run"))
            with open(summary, encoding="utf-8") as handle:
                self.assertIn("Release CI decision", handle.read())


if __name__ == "__main__":
    unittest.main()
