#!/usr/bin/env python3
"""Get a Nous inference token for FriendlyBot without pasting secrets around.

Order of operations:
  1. --key KEY          use the key you give (e.g. from portal API-keys page)
  2. ~/.hermes/auth.json reuse the credential `hermes login` already stored
  3. otherwise          print exactly where to click to create a free key

Then the key is validated (GET /v1/models) and written to a file
(default ./friendlybot-token.txt, mode 0600). Upload that file to your
server's world/serverconfig/ folder (Seedloaf: File Manager) and restart --
the mod reads it automatically, so nobody ever types the key in chat.
The key itself is never printed, only its last 4 characters.

Usage:
  python3 get-token.py
  python3 get-token.py --key sk-... --out my-token.txt
"""

import argparse
import json
import os
import stat
import sys
import urllib.request

BASE_URL = "https://inference-api.nousresearch.com/v1"
HERMES_AUTH = os.path.expanduser("~/.hermes/auth.json")


def mask(key):
    return "..." + key[-4:] if len(key) > 4 else "(too short?)"


def try_hermes_auth():
    """Best-effort read of the credential `hermes login` stored."""
    try:
        with open(HERMES_AUTH) as handle:
            data = json.load(handle)
    except (OSError, ValueError):
        return None
    if isinstance(data, dict):
        for key in ("token", "access_token", "api_key", "bearer"):
            value = data.get(key)
            if isinstance(value, str) and len(value) > 20:
                return value
        for section in data.values():
            if isinstance(section, dict):
                for key in ("token", "access_token", "api_key", "bearer"):
                    value = section.get(key)
                    if isinstance(value, str) and len(value) > 20:
                        return value
    return None


def validate(base_url, key):
    request = urllib.request.Request(
        base_url.rstrip("/") + "/models",
        headers={"Authorization": "Bearer " + key},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status == 200, ""
    except Exception as error:  # noqa: BLE001 - report, don't crash
        return False, str(error)


def main():
    parser = argparse.ArgumentParser(description="Fetch a Nous token for FriendlyBot.")
    parser.add_argument("--key", default="", help="API key (sk-...) if you have one")
    parser.add_argument("--out", default="friendlybot-token.txt", help="output file")
    parser.add_argument("--base-url", default=BASE_URL, help="inference base URL")
    args = parser.parse_args()

    key = args.key.strip()
    source = "command line"
    if not key:
        key = try_hermes_auth() or ""
        source = "~/.hermes/auth.json"
    if not key:
        print("No key found.")
        print("1. Log in free at https://portal.nousresearch.com (Google/GitHub/Discord all work).")
        print("2. Open API Keys and create one (free tier included).")
        print("3. Re-run:  python3 get-token.py --key sk-...")
        return 1

    ok, error = validate(args.base_url, key)
    if not ok:
        print(f"That key ({source}) was rejected: {error or 'unauthorized'}")
        print("Create a fresh one at https://portal.nousresearch.com/api-keys and retry with --key.")
        return 1

    with open(args.out, "w") as handle:
        handle.write(key.strip() + "\n")
    os.chmod(args.out, stat.S_IRUSR | stat.S_IWUSR)
    print(f"Token ending {mask(key)} from {source} works.")
    print(f"Wrote {args.out} (readable only by you).")
    print("Upload it to world/serverconfig/ on your server and restart. Done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
