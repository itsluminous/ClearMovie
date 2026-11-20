#!/usr/bin/env python3
"""Point the website's Download buttons straight at the latest release's APK.

Run by .github/workflows/pages.yml on the checked-out copy of site/index.html,
just before the site is uploaded. Every Download button in the page carries a
`data-latest-apk` marker next to its committed href, the latest-release page:

    <a ... href="https://github.com/OWNER/REPO/releases/latest" data-latest-apk>

When the latest release has exactly one .apk asset, and GitHub answers for that
asset's download URL, those hrefs are rewritten to it, so a click downloads the
APK. In every other case (the API lookup fails, there are zero or several .apk
assets, the URL is not the shape expected, the asset does not answer) the page
is left as committed, with a warning, so it still links the release page. This
script never fails the deploy for that: it exits 0 unless it was called wrongly.

The page stays static: the lookup happens here, at deploy time, never in the
visitor's browser.

Usage:
    site-apk-link.py HTML_FILE [--release-json FILE] [--no-check]

GITHUB_REPOSITORY (owner/repo) defaults to itsluminous/ClearMovie, and
GITHUB_TOKEN, when set, authenticates the API call. --release-json reads the
release from a file instead of the API (for testing), and --no-check skips the
request that confirms the asset answers.
"""

import argparse
import json
import os
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

MARKER = "data-latest-apk"
SAFE = re.compile(r"[A-Za-z0-9._+-]+")
TIMEOUT = 20


def warn(message):
    # A GitHub Actions annotation; plain text anywhere else.
    print(f"::warning title=Download link::{message}", flush=True)


def fetch_release(repo, token):
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/releases/latest",
        headers={
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "clearmovie-pages",
        },
    )
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        return json.load(response)


def pick_apk_url(release, repo):
    """The single .apk asset's download URL, or None with a warning."""
    if not isinstance(release, dict):
        warn("The release answer is not a JSON object.")
        return None
    if release.get("draft") or release.get("prerelease"):
        warn("The latest release is a draft or a pre-release.")
        return None
    tag = release.get("tag_name")
    assets = release.get("assets")
    if not isinstance(tag, str) or not isinstance(assets, list):
        warn("The release has no tag name or no asset list.")
        return None
    apks = [
        a for a in assets
        if isinstance(a, dict) and str(a.get("name", "")).lower().endswith(".apk")
    ]
    if len(apks) != 1:
        names = ", ".join(str(a.get("name")) for a in apks) or "none"
        warn(f"Release {tag} has {len(apks)} .apk assets ({names}); expected exactly one.")
        return None
    apk = apks[0]
    name = apk.get("name")
    url = apk.get("browser_download_url")
    if apk.get("state") != "uploaded":
        warn(f"Asset {name} of {tag} is not fully uploaded.")
        return None
    if not SAFE.fullmatch(tag) or not SAFE.fullmatch(name):
        warn(f"Release tag or asset name has unexpected characters: {tag!r} {name!r}.")
        return None
    # Only the exact URL GitHub gives a release asset of this repository: no
    # other host, and nothing in it that needs escaping inside an HTML attribute.
    expected = f"https://github.com/{repo}/releases/download/{tag}/{name}"
    if url != expected:
        warn(f"Asset URL {url!r} is not the expected {expected!r}.")
        return None
    return url


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def asset_answers(url):
    """True when GitHub serves the asset: 200, or a redirect to its storage."""
    opener = urllib.request.build_opener(_NoRedirect)
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "clearmovie-pages"})
    try:
        with opener.open(request, timeout=TIMEOUT) as response:
            status = response.status
    except urllib.error.HTTPError as error:
        status = error.code
    except (urllib.error.URLError, OSError) as error:
        warn(f"Could not reach {url}: {error}.")
        return False
    if status in (200, 301, 302, 303, 307, 308):
        return True
    warn(f"{url} answered HTTP {status}.")
    return False


def rewrite(html_path, repo, url):
    """Swap the marked fallback hrefs for url. Returns how many were swapped."""
    with open(html_path, encoding="utf-8") as f:
        html = f.read()
    fallback = f'href="https://github.com/{repo}/releases/latest" {MARKER}'
    count = html.count(fallback)
    marked = html.count(MARKER)
    if count == 0:
        warn(f"No Download button in {html_path} carries {MARKER} with the release-page href.")
        return 0
    if count != marked:
        warn(f"{marked} {MARKER} markers but only {count} with the release-page href; "
             "rewriting the ones that match.")
    html = html.replace(fallback, f'href="{url}" {MARKER}')
    directory = os.path.dirname(os.path.abspath(html_path))
    # Write beside the page, then swap it in, so a failed write never leaves a
    # half-written page (or a stray temporary file in the deployed folder).
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=directory, delete=False) as f:
        temp = f.name
    try:
        with open(temp, "w", encoding="utf-8") as f:
            f.write(html)
        os.chmod(temp, os.stat(html_path).st_mode & 0o777)
        os.replace(temp, html_path)
    finally:
        if os.path.exists(temp):
            os.remove(temp)
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("html")
    parser.add_argument("--release-json")
    parser.add_argument("--no-check", action="store_true")
    args = parser.parse_args()

    if not os.path.isfile(args.html):
        print(f"error: {args.html} is not a file", file=sys.stderr)
        return 2
    repo = os.environ.get("GITHUB_REPOSITORY") or "itsluminous/ClearMovie"
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        print(f"error: unexpected GITHUB_REPOSITORY {repo!r}", file=sys.stderr)
        return 2

    try:
        if args.release_json:
            with open(args.release_json, encoding="utf-8") as f:
                release = json.load(f)
        else:
            release = fetch_release(repo, os.environ.get("GITHUB_TOKEN"))
        url = pick_apk_url(release, repo)
        if url and not args.no_check and not asset_answers(url):
            url = None
        if not url:
            print("Download buttons keep the release-page link.")
            return 0
        count = rewrite(args.html, repo, url)
    except Exception as error:  # Fail soft: the committed page still works.
        warn(f"Lookup failed ({type(error).__name__}: {error}); keeping the release-page link.")
        return 0
    if count:
        print(f"Pointed {count} Download button(s) at {url}")
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a", encoding="utf-8") as f:
                f.write(f"Download buttons point at {url}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
