#!/usr/bin/env python3
"""
update_adult_domains.py — Download and merge adult-content domain blocklists.

Sources:
  1. OISD Small (broad blocklist including adult domains)
     https://small.oisd.nl/
  2. StevenBlack porn-only hosts file
     https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts
  3. ut1 adult domains list
     https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/adult/domains

Output:
  app/src/main/assets/adult_domains.txt  (one domain per line, deduplicated)

Run:
  python3 scripts/update_adult_domains.py
  (from the project root)
"""

import urllib.request
import re
import os
import sys
import time

SOURCES = [
    (
        "OISD Small",
        "https://small.oisd.nl/",
        "adblock",
    ),
    (
        "StevenBlack porn-only",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
        "hosts",
    ),
    (
        "ut1 adult domains",
        "https://raw.githubusercontent.com/nicowillis/ut1-blacklists/master/blacklists/adult/domains",
        "plain",
    ),
]

OUTPUT_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "adult_domains.txt",
)

DOMAIN_RE = re.compile(
    r'^[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?'
    r'(\.[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)+$'
)


def fetch(url: str, name: str) -> str:
    print(f"  Downloading {name} …", end=" ", flush=True)
    req = urllib.request.Request(url, headers={"User-Agent": "detox-domain-updater/1.0"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = resp.read().decode("utf-8", errors="replace")
            print(f"OK ({len(data):,} bytes)")
            return data
        except Exception as exc:
            if attempt < 2:
                print(f"retry ({exc}) …", end=" ", flush=True)
                time.sleep(2)
            else:
                print(f"FAILED: {exc}")
                return ""
    return ""


def parse_adblock(text: str) -> set:
    """Parse AdBlock Plus filter syntax: ||domain.com^ per line."""
    domains = set()
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("!") or line.startswith("["):
            continue
        # Typical OISD entry: ||domain.com^
        if line.startswith("||") and line.endswith("^"):
            domain = line[2:-1].lower().rstrip(".")
            if DOMAIN_RE.match(domain) and "." in domain:
                domains.add(domain)
    return domains


def parse_hosts(text: str) -> set:
    """Parse a /etc/hosts-style file: '0.0.0.0 domain.com' or '127.0.0.1 domain.com'."""
    domains = set()
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[0] in ("0.0.0.0", "127.0.0.1"):
            domain = parts[1].lower().rstrip(".")
            if domain in ("localhost", "0.0.0.0", "broadcasthost"):
                continue
            if DOMAIN_RE.match(domain) and "." in domain:
                domains.add(domain)
    return domains


def parse_plain(text: str) -> set:
    """Parse a plain list: one domain per line, may have IPs/comments."""
    domains = set()
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        # Strip leading IP if present
        parts = line.split()
        candidate = parts[-1].lower().rstrip(".")
        if DOMAIN_RE.match(candidate) and "." in candidate and not candidate[0].isdigit():
            domains.add(candidate)
    return domains


PARSERS = {
    "adblock": parse_adblock,
    "hosts": parse_hosts,
    "plain": parse_plain,
}


def main():
    print("=" * 60)
    print("Detox — Adult Domain Blocklist Updater")
    print("=" * 60)

    all_domains: set = set()

    for name, url, fmt in SOURCES:
        print(f"\n[{name}]")
        text = fetch(url, name)
        if not text:
            print(f"  WARNING: {name} skipped (download failed)")
            continue
        parser = PARSERS[fmt]
        domains = parser(text)
        print(f"  Parsed {len(domains):,} domains")
        all_domains |= domains

    print(f"\nTotal unique domains before dedup: {len(all_domains):,}")

    # Remove obviously invalid entries
    valid = {d for d in all_domains if DOMAIN_RE.match(d) and "." in d and " " not in d}
    print(f"Valid domains after filter: {len(valid):,}")

    if len(valid) < 10_000:
        print(
            f"\nWARNING: Only {len(valid):,} domains collected — expected 50,000+.\n"
            "Check network connectivity or source URLs."
        )

    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)

    sorted_domains = sorted(valid)
    with open(OUTPUT_PATH, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Adult content domain blocklist — auto-generated\n")
        f.write(f"# Generated: {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}\n")
        f.write(f"# Domains: {len(sorted_domains):,}\n")
        f.write("# Sources: OISD Small, StevenBlack porn-only, ut1 adult\n")
        f.write("# Format: one apex domain per line (subdomains matched automatically)\n")
        f.write("#\n")
        for domain in sorted_domains:
            f.write(domain + "\n")

    print(f"\nSaved {len(sorted_domains):,} domains to:\n  {OUTPUT_PATH}")
    print("\nDone.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
