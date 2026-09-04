#!/usr/bin/env python3
"""Independent probe for the original KTV song URL API. Exits non-zero unless a real URL is returned."""
import argparse, hashlib, json, time
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError

APP_ID = "d4eeacc6cec3434fbc8c41608a3056a0"
APP_KEY = "024210cba40d4385a93e6c2d3249bfb5"
SDK_KEY = "19042303a8374f67ae3fe1e25c97936f"
HOST = "http://mm.kk456.top"
VN = "4.1.3.03161025"

def md5(value): return hashlib.md5(value.encode()).hexdigest()
def get(url):
    try:
        with urlopen(Request(url, headers={"Accept": "*/*"}), timeout=10) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")

def parse_json(body):
    start = body.find("{")
    if start < 0:
        raise json.JSONDecodeError("JSON object not found", body, 0)
    return json.loads(body[start:])

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("music_no")
    ap.add_argument("--mac", default="9658dd1eafa73a55")
    ap.add_argument("--sn", default="0000037bb640")
    ap.add_argument("--resolution", default="720")
    args = ap.parse_args()
    now = str(int(time.time()))
    token_raw = f"appid={APP_ID}&mac={args.mac}_{args.sn}&sn={args.sn}&time={now}&ver=2.0&vn={VN}"
    status, body = get(f"{HOST}/i.php?{token_raw}&sign={md5(token_raw + APP_KEY)}")
    print("token:", status, body)
    token = parse_json(body).get("token") if status == 200 else None
    if not token: raise SystemExit(2)
    now = str(int(time.time()))
    song_raw = (f"appid={APP_ID}&device={args.mac}_{args.sn}&ish265=0&ls=1&"
                f"musicno={args.music_no}&resolution={args.resolution}&sn={args.sn}&time={now}&token={token}")
    status, body = get(f"{HOST}/music/do.php?{song_raw}&sign={md5(song_raw + SDK_KEY)}")
    print("song:", status, body)
    try: url = parse_json(body).get("data", "")
    except json.JSONDecodeError: url = ""
    if status != 200 or not url.startswith(("http://", "https://")): raise SystemExit(3)
    if "wb66" in url.lower() or "demo" in url.lower(): raise SystemExit(4)
    print("real_url:", url)

if __name__ == "__main__": main()
