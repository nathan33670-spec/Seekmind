#!/usr/bin/env python3
"""Generates testdata/sensitive_cases.tsv, the redaction reference set (docs/specs/confidentialite.md §5).

Each line gives the exact text expected after redaction. Only published test or example numbers are used: the test cards of the payment networks and the
example IBANs of the ISO 13616 registry. National insurance numbers are built from illustrative
bodies, their key computed here. Nothing belongs to a real person. Each line: kind, expected
(`masked` or `intact`), text, redacted text. Run from the repository root:

    python tools/testdata/gen_sensitive_cases.py
"""
import pathlib

OUT = pathlib.Path("testdata/sensitive_cases.tsv")

# Test card numbers published by the networks and payment processors.
TEST_CARDS = [
    "4111111111111111",  # Visa
    "4012888888881881",  # Visa
    "5555555555554444",  # Mastercard
    "5105105105105100",  # Mastercard
    "2223003122003222",  # Mastercard, 2-series
    "378282246310005",  # American Express
    "371449635398431",  # American Express
    "6011111111111117",  # Discover
    "6011000990139424",  # Discover
    "6200000000000005",  # UnionPay, 16 digits
    "6250941006528599",  # UnionPay
]

# Example IBANs of the ISO 13616 registry.
EXAMPLE_IBANS = [
    "MC5811222000010123456789030",
    "AT611904300234573201",
    "PL61109010140000071219812874",
    "FR1420041010050500013M02606",
    "DE89370400440532013000",
    "ES9121000418450200051332",
    "IT60X0542811101000000123456",
    "BE68539007547034",
    "NL91ABNA0417164300",
    "LU280019400644750000",
    "CH9300762011623852957",
    "GB29NWBK60161331926819",
    "PT50000201231234567890154",
]

# Illustrative bodies (sex, year, month, department, commune, order): the key is computed below.
NIR_BODIES = ["1840578006084", "2690549588157", "1551299123456", "287012A123456", "193072B050123"]


def luhn_ok(number: str) -> bool:
    total = 0
    for i, ch in enumerate(reversed(number)):
        d = int(ch)
        if i % 2 == 1:
            d = d * 2 - 9 if d * 2 > 9 else d * 2
        total += d
    return total % 10 == 0


def iban_ok(iban: str) -> bool:
    moved = iban[4:] + iban[:4]
    return int("".join(str(int(c, 36)) for c in moved)) % 97 == 1


def nir_key(body: str) -> int:
    digits = body.replace("2A", "19").replace("2B", "18")
    return 97 - int(digits) % 97


def grouped(number: str, size: int = 4, sep: str = " ") -> str:
    return sep.join(number[i : i + size] for i in range(0, len(number), size))


def bump_last(number: str) -> str:
    return number[:-1] + str((int(number[-1]) + 1) % 10)


MARK = "[•••]"  # PrivacyConfig.redactionMark


def masked(kind: str, before: str, number: str, after: str = "") -> tuple[str, str, str, str]:
    """A case whose [number] must be replaced by the mark, and nothing else."""
    return kind, "masked", before + number + after, before + MARK + after


def intact(kind: str, text: str) -> tuple[str, str, str, str]:
    return kind, "intact", text, text


def main() -> None:
    rows = []
    for card in TEST_CARDS:
        assert luhn_ok(card), card
        rows += [
            masked("card", "Carte ", card, " enregistrée"),
            masked("card", "Card number: ", grouped(card)),
            masked("card", "N° ", grouped(card, sep="-"), "."),
            intact("card", f"Carte {bump_last(card)} refusée"),  # Luhn fails
            # A number touching the card: the greedy candidate takes it in.
            masked("card", "Carte ", grouped(card), " 12/27"),
            masked("card", "1 ", grouped(card)),
            masked("card", "Qté 2 ", card),
        ]
    for iban in EXAMPLE_IBANS:
        assert iban_ok(iban), iban
        wrong = iban[:2] + f"{(int(iban[2:4]) + 1) % 100:02d}" + iban[4:]
        assert not iban_ok(wrong), wrong
        rows += [
            masked("iban", "IBAN : ", iban),
            masked("iban", "Virement vers ", grouped(iban), " EUR"),
            masked("iban", "iban ", iban.lower(), " ok"),
            intact("iban", f"IBAN : {wrong}"),  # check digits wrong
        ]
    for body in NIR_BODIES:
        assert len(body) == 13 and body[5:7] in {"2A", "2B"} | {f"{d:02d}" for d in range(1, 100)}, body
        key = nir_key(body)
        number = f"{body}{key:02d}"
        spaced = f"{body[0]} {body[1:3]} {body[3:5]} {body[5:7]} {body[7:10]} {body[10:13]} {key:02d}"
        rows += [
            masked("nir", "Sécurité sociale ", number),
            masked("nir", "N° SS : ", spaced),
            intact("nir", f"Sécurité sociale {body}{(key % 97) + 1:02d}"),  # wrong key
        ]
    # Numbers the product must keep (§5.4): tracking, orders, phones, promo codes, postcodes.
    for text in [
        "Colis 6A12345678901 en cours de livraison",
        "UPS 1Z999AA10123456784",
        "Suivi DHL 00340434161094042557",
        "Commande n° 4012-8888-8888-1882",  # Visa-like, Luhn fails
        "Commande 9234 5678 9012 3456 expédiée",  # no card prefix
        "Référence 1234567890123456",  # no card prefix
        "Appelez le +33 6 12 34 56 78",
        "Tél. 01 23 45 67 89",
        "Code promo SUMMER2026",
        "20000 Ajaccio",
        "Vol AF1234 le 12/11/2026 à 14:30",
        "Facture FR-2026-000123",
    ]:
        rows.append(intact("other", text))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8", newline="\n") as f:
        f.write("# Generated by tools/testdata/gen_sensitive_cases.py: do not edit by hand.\n")
        f.write("# kind\texpected\ttext\tredacted text\n")
        for kind, expected, text, redacted in rows:
            assert "\t" not in text and "\n" not in text
            f.write(f"{kind}\t{expected}\t{text}\t{redacted}\n")
    print(f"{OUT}: {len(rows)} cases")


if __name__ == "__main__":
    main()
