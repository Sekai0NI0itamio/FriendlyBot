#!/usr/bin/env python3
"""One-command Nous login + token for FriendlyBot. No API key needed.

  python3 get-token.py --setup

does everything: installs the Hermes CLI if missing, walks you through the
browser login (`hermes auth add nous` shows a code you approve on the Portal
site), mints an inference token from that login, validates it, and writes
friendlybot-token.txt (mode 0600, key never printed) ready to upload to
world/serverconfig/ on your server.

Other modes:
  python3 get-token.py                    # mint only, needs an existing login
  python3 get-token.py --key sk-...       # static key path instead of login
  python3 get-token.py --setup --yes      # no prompts, full auto
"""

import argparse
import base64
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

AUTH_FILE = os.path.expanduser("~/.hermes/auth.json")
HERMES_BIN_DIR = os.path.expanduser("~/.hermes/bin")
INSTALL_URL = "https://hermes-agent.nousresearch.com/install.sh"
PORTAL_URL = "https://portal.nousresearch.com"
CLIENT_ID = "hermes-cli"
SKEW_SECONDS = 120


def fail(message, hint=""):
    print("FAILED: " + message)
    if hint:
        print(hint)
    return 1


def ask(text, auto_yes):
    if auto_yes:
        return True
    try:
        return input(text + " [Y/n] ").strip().lower() in ("", "y", "yes")
    except EOFError:
        return False


def hermes_bin():
    found = shutil.which("hermes")
    if found:
        return found
    candidate = os.path.join(HERMES_BIN_DIR, "hermes")
    return candidate if os.path.isfile(candidate) and os.access(candidate, os.X_OK) else ""


def install_hermes():
    print("Downloading the official Hermes installer...")
    with tempfile.NamedTemporaryFile(suffix=".sh", delete=False) as tmp:
        try:
            with urllib.request.urlopen(INSTALL_URL, timeout=60) as response:
                tmp.write(response.read())
        except Exception as error:  # noqa: BLE001 - report, don't crash
            return fail(f"Could not download installer ({error}).",
                        "Install manually from https://hermes-agent.nousresearch.com and re-run.")
        script = tmp.name
    print("Running the installer (this takes a few minutes)...")
    try:
        result = subprocess.run(["bash", script])
    finally:
        try:
            os.unlink(script)
        except OSError:
            pass
    if result.returncode != 0:
        return fail("Installer exited with an error.", "See above and retry.")
    os.environ["PATH"] = HERMES_BIN_DIR + os.pathsep + os.environ.get("PATH", "")
    if not hermes_bin():
        return fail("Installed but `hermes` is not on PATH.",
                    f"Run: export PATH=\"{HERMES_BIN_DIR}:$PATH\" (or restart your terminal) and re-run.")
    print("Hermes CLI installed.")
    return 0


def browser_login(auto_yes):
    binary = hermes_bin()
    print("Opening the Portal login: approve the shown code in your browser.")
    result = subprocess.run([binary, "auth", "add", "nous"])
    if result.returncode != 0:
        return fail("Login did not complete.", "Re-run with --setup to try again.")
    return 0


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


def load_store():
    try:
        with open(AUTH_FILE) as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return {}


def mint_from_login(args):
    store = load_store()
    nous = (store.get("providers") or {}).get("nous") or {}
    if not isinstance(nous, dict) or not nous.get("refresh_token"):
        return None
    portal = (nous.get("portal_base_url") or PORTAL_URL).rstrip("/")
    inference = (args.base_url or nous.get("inference_base_url")
                 or "https://inference-api.nousresearch.com/v1").rstrip("/")

    access = nous.get("access_token") or ""
    if fresh(access, nous.get("expires_at")):
        return access, inference, "stored access token"

    status, headers, body = http_post_form(
        portal + "/api/oauth/token",
        {"x-nous-refresh-token": nous["refresh_token"]},
        {"grant_type": "refresh_token", "client_id": nous.get("client_id") or CLIENT_ID},
    )
    if status in {403, 429} and headers.get("x-vercel-mitigated"):
        print("FAILED: Portal edge firewall challenged this machine; credentials untouched.")
        print("Wait a minute and re-run.")
        return False
    if status != 200:
        try:
            code = json.loads(body).get("error", "")
        except ValueError:
            code = ""
        if status in {401, 403} or code == "invalid_grant":
            print("FAILED: Login expired or revoked.")
            print("Re-login in the browser with:  hermes auth add nous")
        else:
            print(f"FAILED: Refresh failed (HTTP {status}). Try again shortly.")
        return False

    try:
        payload = json.loads(body)
        new_access = payload["access_token"]
    except (ValueError, KeyError):
        print("FAILED: Refresh gave no access token. Try again shortly.")
        return False

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
        print(f"FAILED: Could not write rotated tokens back ({error}).")
        print("Your old token may still work once; re-login if not: hermes auth add nous")
        return False
    return new_access, inference, "fresh browser-login token"


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


def main():
    parser = argparse.ArgumentParser(description="Install Hermes, log in via browser, mint a token.")
    parser.add_argument("--key", default="", help="static API key, skips login flow")
    parser.add_argument("--out", default="friendlybot-token.txt", help="output file")
    parser.add_argument("--base-url", default="", help="override inference base URL")
    parser.add_argument("--setup", action="store_true", help="auto-install CLI and drive browser login")
    parser.add_argument("--yes", action="store_true", help="answer yes to all prompts")
    args = parser.parse_args()

    if args.key.strip():
        return emit(args.key.strip(), args,
                    args.base_url or "https://inference-api.nousresearch.com/v1",
                    "command line")

    if not hermes_bin():
        if not args.setup and not ask("Hermes CLI is missing. Install it now?", args.yes):
            return fail("Cannot continue without the CLI.",
                        "Re-run with --setup to auto-install.")
        code = install_hermes()
        if code != 0:
            return code

    result = mint_from_login(args)
    if result is None:
        if not args.setup and not ask("No browser login found. Log in now (opens a code to approve)?", args.yes):
            print("Then run:  hermes auth add nous")
            print("Approve the code in your browser, then re-run this script.")
            return 1
        code = browser_login(args.yes)
        if code != 0:
            return code
        result = mint_from_login(args)
        if result is None:
            return fail("Still no login found.", "Run: hermes auth add nous")
        if result is False:
            return 1
    elif result is False:
        return 1
    key, inference, source = result
    return emit(key, args, inference, source)


if __name__ == "__main__":
    sys.exit(main())
