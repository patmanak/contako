"""Validate invented regression data or create an offline, non-destructive import pack."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import struct
import zlib

ROOT = Path(__file__).resolve().parent
APP = ROOT.parent.parent


def source_file(relative: str) -> Path:
    path = (ROOT / relative).resolve()
    if not path.is_relative_to(ROOT) or not path.is_file():
        raise ValueError(f"Invalid fixture path: {relative}")
    return path


def validate() -> dict:
    index = json.loads((ROOT / "catalog.json").read_text(encoding="utf-8"))
    assert index["schemaVersion"] == 1
    assert 1 <= len(index["datasets"]) <= 3
    datasets = [json.loads(source_file(name).read_text(encoding="utf-8")) for name in index["datasets"]]
    assert all(d["schemaVersion"] == 1 for d in datasets)
    assert len({d["id"] for d in datasets}) == len(datasets)
    groups = {}
    for dataset in datasets:
        for group in dataset["groups"]:
            previous = groups.setdefault(group["id"], {**group, "members": []})
            assert (previous["name"], previous["color"]) == (group["name"], group["color"])
            for edge in group["members"]:
                if edge not in previous["members"]:
                    previous["members"].append(edge)
    data = {"datasets": datasets, "groups": list(groups.values()),
            "contacts": [c for d in datasets for c in d["contacts"]],
            "negative": [n for d in datasets for n in d["negative"]],
            "loadProfiles": [p for d in datasets for p in d.get("loadProfiles", [])]}
    contacts = {c["id"]: c for c in data["contacts"]}
    assert len(contacts) == len(data["contacts"])
    group_ids = [g["id"] for g in data["groups"]]
    assert len(set(group_ids)) == len(group_ids)
    plans = APP.parent / "docs"
    definitions = set()
    for name in ("UNIT_TEST_PLAN.md", "SOFTWARE_TEST_PLAN.md", "FUNCTIONAL_TEST_PLAN.md"):
        definitions.update(re.findall(r"^\| ((?:UT|SW|FT)-\d+) \|", (plans / name).read_text(encoding="utf-8"), re.M))
    for c in data["contacts"]:
        assert c["displayName"].startswith("CTK-QA " + c["id"] + " ")
        assert c["cases"] and set(c["cases"]) <= definitions, c["id"]
        for email in c["expected"]["emails"]:
            assert email.endswith("@example.test")
        for card in c["cards"]:
            assert card["kind"] in {"Signed", "ClearText", "Encrypted", "EncryptedAndSigned"}
            text = card["vCard"]
            assert text.count("BEGIN:VCARD") == text.count("END:VCARD") == 1
            assert "VERSION:4.0" in text and "UID:ctk-qa-" + c["id"].lower() in text
            assert all(mail.endswith("@example.test") for mail in re.findall(r"[\w.+-]+@[\w.-]+", text))
            assert "PRIVATE KEY" not in text and "Authorization:" not in text
    for g in data["groups"]:
        assert g["name"].startswith("CTK-QA ") and g["color"] == "#8080FF"
        assert len({tuple(edge) for edge in g["members"]}) == len(g["members"])
        for contact_id, email in g["members"]:
            assert email in contacts[contact_id]["expected"]["emails"]
    for negative in data["negative"]:
        assert negative["vCard"].startswith("BEGIN:VCARD\n")
        assert negative["expected"] == "reject" and set(negative["cases"]) <= definitions
    return data


def fold(line: str) -> str:
    parts, current, size = [], "", 0
    for char in line:
        length = len(char.encode("utf-8"))
        if size + length > 75:
            parts.append(current)
            current, size = " ", 1
        current += char
        size += length
    return "\r\n".join(parts + [current])


def vcard(lines: list[str]) -> str:
    return "\r\n".join(fold(line) for line in lines) + "\r\n"


def merged(contact: dict) -> str:
    lines = ["BEGIN:VCARD", "VERSION:4.0", "UID:ctk-qa-" + contact["id"].lower()]
    for card in contact["cards"]:
        for line in card["vCard"].splitlines():
            prop = line.split(":", 1)[0].split(";", 1)[0].split(".")[-1]
            if prop not in {"BEGIN", "END", "VERSION", "UID", "CATEGORIES"}:
                lines.append(line)
    return vcard(lines + ["END:VCARD"])


def png(width: int, height: int, color: tuple[int, int, int], transparent=False) -> bytes:
    def chunk(kind, payload):
        return struct.pack("!I", len(payload)) + kind + payload + struct.pack("!I", zlib.crc32(kind + payload))
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            active = (x * 8 // width + y * 8 // height) % 2
            raw.extend(color if active else (245, 245, 245))
            raw.append(255 if active or not transparent else 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack("!2I5B", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def prepare(data: dict, profile: str, output: Path) -> None:
    output = output.resolve()
    build = (APP / "build").resolve()
    if output == build or not output.is_relative_to(build):
        raise ValueError("Output must be a new directory below app/build")
    if output.exists() and any(output.iterdir()):
        raise ValueError("Refusing to overwrite a nonempty output directory")
    output.mkdir(parents=True, exist_ok=True)
    groups = data["groups"]
    if profile == "core":
        cards = [merged(c) for c in data["contacts"] if c["liveImport"]]
        for dataset in data["datasets"]:
            content = "".join(merged(c) for c in dataset["contacts"] if c["liveImport"])
            if content:
                (output / (dataset["id"] + ".vcf")).write_bytes(content.encode("utf-8"))
        native = "".join(merged(c) for c in data["contacts"] if not c["liveImport"])
        (output / "native-only.vcf").write_bytes(native.encode("utf-8"))
    else:
        sizes = next(p for p in data["loadProfiles"] if p["id"] == profile)
        groups = [{"id": f"L{i:03d}", "name": f"CTK-QA LOAD Group {i:03d}", "color": "#8080FF", "members": []}
                  for i in range(sizes["groups"])]
        cards = []
        for i in range(sizes["contacts"]):
            identity = f"LOAD{i:05d}"
            email = f"load{i:05d}@example.test"
            rich = i % 5 == 0
            fields = ["BEGIN:VCARD", "VERSION:4.0", f"UID:ctk-qa-load-{i:05d}",
                      f"FN:CTK-QA {identity} Élodie 東京" if rich else f"FN:CTK-QA {identity}",
                      f"N:Load{i:05d};Élodie-Marie;;;" if rich else f"N:Load{i:05d};Case;;;",
                      f"EMAIL;PREF=1:{email}"]
            if rich:
                fields += [f"EMAIL;TYPE=work:load{i:05d}.work@example.test",
                           "ORG:Atelier fictif Écureuil;Qualité", "BDAY:20000229",
                           "ADR;TYPE=home:;Bâtiment β;12 rue Imaginaire;Cité Test;Région QA;00000;Pays fictif",
                           "NOTE:" + ("Été 東京 🐙 — note fictive\\nDeuxième ligne\\; virgule\\, " * 12)]
                # Avoid an artificial thousand-contact Android aggregation through one phone.
                if i < 500:
                    fields.append(f"TEL;TYPE=cell:+120255501{i // 5:02d}")
            cards.append(vcard(fields + ["END:VCARD"]))
            groups[i % len(groups)]["members"].append([identity, email])
        (output / "contacts.vcf").write_bytes("".join(cards).encode("utf-8"))
    (output / "groups.json").write_text(json.dumps(groups, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    images = [("A-landscape", 1200, 900, (210, 40, 40), False), ("B-portrait", 900, 1200, (30, 160, 60), False),
              ("C-square", 900, 900, (40, 70, 220), False), ("logo-transparent", 320, 240, (130, 50, 190), True),
              ("small", 48, 32, (200, 130, 20), False), ("panorama", 3000, 100, (20, 140, 180), False)]
    for name, width, height, color, transparent in images:
        (output / (name + ".png")).write_bytes(png(width, height, color, transparent))
    (output / "pack.json").write_text(json.dumps({"schemaVersion": 1, "profile": profile,
                                                "contacts": len(cards), "groups": len(groups)}, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(cards)} synthetic contacts / {len(groups)} groups. No remote changes.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--profile", choices=("core", "load-300", "load-5000"), default="core")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    catalog = validate()
    if args.output:
        prepare(catalog, args.profile, args.output)
    elif not args.check:
        parser.error("Specify --check or --output")
    print("Dataset validation PASS")
