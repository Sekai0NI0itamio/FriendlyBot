#!/usr/bin/env python3
"""Mint a Nous inference token for FriendlyBot from your browser login.

You never need an API key: log in once in the browser with
`hermes auth add nous` (device code you approve on the Portal site).
Hermes stores that login in ~/.hermes/auth.json. This script then:

  1. Reuses the stored access token if it is still fresh, else
  2. Redeems the stored refresh token for a new access token, writing the
     ROTATED tokens back to auth.json exactly like Hermes does
     (Nous refresh tokens are single-use -- redeeming without persisting
     the rotation revokes the whole login, so this step is careful), then
  3. Validates against the inference API and writes friendlybot-token.txt
     (mode 0600, key never printed) for upload to world/serverconfig/.

Usage:
  python3 get-token.py
  python3 get-token.py --key sk-... --out my-token.txt   # static key path
"""

import argparse
import base64
import json
import os
import stat
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone

AUTH_FILE = os.path.expanduser("~/.hermes/auth.json")
PORTAL_URL = "https://portal.nousresearch.com"
CLIENT_ID = "hermes-cli"
SKEW_SECONDS = 120


def fail(message, hint=""):
    print("FAILED: " + message)
    if hint:
        print(hint)
    return 1


def jwt_expires_at(token):
    try:
        parts = token.split(".")
        if len(parts) != 3:
            return None
        padded = parts[1] + "=" * (-len(parts[1]) % 4)
        return json.loads(base64.urlsafe_b64decode(padded))["exp"]
    except Exception:  # noqa: BLE001 - unparsable means unknown
        return None


def fresh(access_token, expires_at):
    if not access_token:
        return False
    exp = None
    if expires_at:
        try:
            exp = datetime.fromisoformat(str(expires_at)).timestamp()
        except ValueError:
            exp = None
    if exp is None:
        exp = jwt_expires_at(access_token)
    if exp is None:
        return False
    import time
    return exp > time.time() + SKEW_SECONDS


def http_post_form(url, headers, fields):
    data = urllib.parse.urlencode(fields).encode()
    request = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, dict(response.headers), response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read().decode()


def http_get(url, token):
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except Exception:  # noqa: BLE001 - network down etc.
        return 0


def main():
    parser = argparse.ArgumentParser(description="Mint a Nous token for FriendlyBot.")
    parser.add_argument("--key", default="", help="static API key, skips browser-login path")
    parser.add_argument("--out", default="friendlybot-token.txt", help="output file")
    parser.add_argument("--base-url", default="", help="override inference base URL")
    args = parser.parse_args()

    if args.key.strip():
        return emit(args.key.strip(), args, args.base_url
                    or "https://inference-api.nousresearch.com/v1", "command line")

    try:
        with open(AUTH_FILE) as handle:
            store = json.load(handle)
    except (OSError, ValueError):
        store = {}
    nous = (store.get("providers") or {}).get("nous") or {}
    if not isinstance(nous, dict) or not nous.get("refresh_token"):
        print("No Hermes browser login found.")
        print("1. Install the Hermes CLI, then run:  hermes auth add nous")
        print("2. Approve the shown code in your browser (no key involved).")
        print("3. Re-run this script.")
        print("Or create a free static key at https://portal.nousresearch.com/api-keys")
        print("and run:  python3 get-token.py --key sk-...")
        return 1

    portal = (nous.get("portal_base_url") or PORTAL_URL).rstrip("/")
    inference = (args.base_url or nous.get("inference_base_url")
                 or "https://inference-api.nousresearch.com/v1").rstrip("/")

    access = nous.get("access_token") or ""
    if fresh(access, nous.get("expires_at")):
        return emit(access, args, inference, "stored access token")

    status, headers, body = http_post_form(
        portal + "/api/oauth/token",
        {"x-nous-refresh-token": nous["refresh_token"]},
        {"grant_type": "refresh_token", "client_id": nous.get("client_id") or CLIENT_ID},
    )
    if status in {403, 429} and headers.get("x-vercel-mitigated"):
        return fail("Portal edge firewall challenged this machine; credentials untouched.",
                    "Wait a minute and re-run. Do not delete ~/.hermes/auth.json.")
    if status != 200:
        try:
            code = json.loads(body).get("error", "")
        except ValueError:
            code = ""
        if status in {401, 403} or code == "invalid_grant":
            return fail("Login expired or revoked.",
                        "Re-login in the browser with:  hermes auth add nous")
        return fail(f"Refresh failed (HTTP {status}).", "Try again shortly.")

    try:
        payload = json.loads(body)
        new_access = payload["access_token"]
    except (ValueError, KeyError):
        return fail("Refresh gave no access token.", "Try again shortly.")

    # Persist the rotation exactly like Hermes (keys keep their order).
    from datetime import timedelta
    now = datetime.now(timezone.utc)
    nous["access_token"] = new_access
    if payload.get("refresh_token"):
        nous["refresh_token"] = payload["refresh_token"]
    nous["obtained_at"] = now.isoformat()
    try:
        ttl = int(payload.get("expires_in") or 0)
    except (TypeError, ValueError):
        ttl = 0
    if ttl > 0:
        nous["expires_in"] = ttl
        nous["expires_at"] = (now + timedelta(seconds=ttl)).isoformat()
    try:
        with open(AUTH_FILE, "w") as handle:
            json.dump(store, handle, indent=2)
    except OSError as error:
        return fail(f"Could not write rotated tokens back ({error}).",
                    "Your old token may still work once; re-login if not: hermes auth add nous")
    return emit(new_access, args, inference, "fresh browser-login token")


def emit(key, args, inference, source):
    if http_get(inference + "/models", key) != 200:
        return fail("Token minted but the inference API rejected it.",
                    "Check the Portal status page, then re-run.")
    with open(args.out, "w") as handle:
        handle.write(key.strip() + "\n")
    os.chmod(args.out, stat.S_IRUSR | stat.S_IWUSR)
    print(f"Token ending ...{key.strip()[-4:]} from {source} works.")
    print(f"Wrote {args.out} (readable only by you, key never printed).")
    print("Upload it to world/serverconfig/ on your server and restart. Done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
