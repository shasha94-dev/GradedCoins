"""NGC parsing reference.

Runtime barcode recognition is performed by Android ML Kit/ZXing in NgcScanner.kt. The parsing
rules below mirror the original Python barcode/certificate URL logic.
"""
import re


def grade_to_string(grade, details_reason="00"):
    if grade in ("87", "88", "89"):
        return "NGCDetails"
    if details_reason != "00":
        return "NGCDetails"
    try:
        value = int(grade)
    except (TypeError, ValueError):
        return None
    return grade if 1 <= value <= 70 else None


def get_ngc_url_from_barcode_text(raw):
    digits = "".join(c for c in raw if c.isdigit())
    if len(digits) < 20:
        return None
    coin_number = digits[:6]
    grade = grade_to_string(digits[6:8], digits[8:10])
    if not grade:
        return None
    cert10 = digits[10:20]
    cert = f"{cert10[:7]}-{cert10[7:]}"
    return {"coin_number": coin_number, "cert_number": cert, "grade": grade, "url": f"https://www.ngccoin.uk/certlookup/{cert}/{grade}/"}
