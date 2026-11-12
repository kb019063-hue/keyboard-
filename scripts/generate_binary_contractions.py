#!/usr/bin/env python3
"""
Binary Contraction Generator

Converts contraction JSON files to optimized binary format for fast loading.

Binary Format V2:
-----------------
Header (16 bytes, little-endian):
  - Magic number: b'CTRB' (4 bytes)
  - Format version: uint32 (4 bytes) = 2
  - Non-paired count: uint32 (4 bytes)
  - Paired base count: uint32 (4 bytes)

Non-Paired Section:
  - For each non-paired mapping (apostrophe-free -> contraction):
    - Key length: uint16 (2 bytes)
    - Key bytes: UTF-8 encoded string
    - Value length: uint16 (2 bytes)
    - Value bytes: UTF-8 encoded string

Paired Section (V2 carries the pairing STRUCTURE, not a flat set):
  - For each paired base, in the JSON file's order:
    - Base length: uint16 (2 bytes)
    - Base bytes: UTF-8 encoded string
    - Variant count: uint16 (2 bytes)
    - For each variant, in the JSON file's order:
      - Variant length: uint16 (2 bytes)
      - Variant bytes: UTF-8 encoded string
      - Frequency: int32 (4 bytes), 0xFFFFFFFF when the entry carries none

Why V2 (2026-11-12): V1 carried the paired contractions as a FLAT SET of forms, with no
base and no frequency. The app therefore had to parse `contraction_pairings.json`
(154 KB, 1,748 bases) with org.json ON THE MAIN THREAD at every cold start, just to
recover the pairing structure. That parse was the slowest step of `onCreate` in three
CI runs running (79, 85 and 40 ms of a 191-224 ms set-up), and it is paid on the
critical path to the first picture. V2 carries the structure itself, so the parse is
gone. The loader still accepts V1: an old asset then falls back to the JSON exactly as
before.

The JSON stays the SOURCE OF TRUTH. This script only projects it. Regenerate the
binary after changing `contraction_pairings.json`:

    python3 scripts/generate_binary_contractions.py \
        src/main/assets/dictionaries/contractions_non_paired.json \
        src/main/assets/dictionaries/contraction_pairings.json \
        src/main/assets/dictionaries/contractions.bin

`ContractionBinaryPairingsTest` fails if the shipped binary and the shipped JSON
disagree, so a stale binary cannot reach a release.

Benefits:
  - No JSON parsing overhead
  - Faster loading at startup
  - Compact binary representation
  - Direct memory loading
"""

import json
import struct
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

# Binary format constants
MAGIC = b'CTRB'
VERSION = 2
HEADER_SIZE = 16

# Sentinel for "this pairing carries no frequency". org.json's `has("frequency")`
# check is the JSON-side equivalent, and the two must agree exactly.
NO_FREQUENCY = -1


def load_non_paired_contractions(json_path: Path) -> Dict[str, str]:
    """
    Load non-paired contractions from JSON file.

    Format: {"dont": "don't", "cant": "can't", ...}

    Returns:
        Dict mapping apostrophe-free forms to contractions
    """
    with open(json_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    # Ensure all keys and values are lowercase
    contractions = {k.lower(): v.lower() for k, v in data.items()}
    print(f"Loaded {len(contractions)} non-paired contractions from {json_path.name}")
    return contractions


def load_paired_structure(
    json_path: Path
) -> List[Tuple[str, List[Tuple[str, Optional[int]]]]]:
    """
    Load the paired contractions from JSON file, WITH their bases and frequencies.

    Format: {"well": [{"contraction": "we'll", "frequency": 243}], ...}

    Returns:
        List of (base, [(contraction, frequency or None), ...]) in FILE ORDER.

    The file order matters and is preserved: `ContractionManager.parsePairings` builds
    a LinkedHashMap in that order, and the order decides which variant a suggestion
    bar offers first. Bases and variants are lowercased exactly as the Kotlin parser
    lowercases them, and two JSON keys that differ only in case are MERGED into one
    base (the parser's `getOrPut` on the lowercased key), with the variants appended
    in file order. The shipped file has no such collision; the merge is here so that
    the binary can never disagree with the parser it replaces.
    """
    with open(json_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    order: List[str] = []
    merged: Dict[str, List[Tuple[str, Optional[int]]]] = {}
    for base_word, variants in data.items():
        lower_base = base_word.lower()
        if lower_base not in merged:
            merged[lower_base] = []
            order.append(lower_base)
        bucket = merged[lower_base]
        for variant in variants:
            contraction = variant['contraction'].lower()
            frequency = variant.get('frequency')
            bucket.append((contraction, frequency))

    total = sum(len(v) for v in merged.values())
    print(f"Loaded {len(merged)} paired bases / {total} variants from {json_path.name}")
    return [(base, merged[base]) for base in order]


def load_paired_contractions(json_path: Path) -> set:
    """
    Load paired contractions from JSON file.

    Format: {"well": [{"contraction": "we'll", "frequency": 243}], ...}

    Returns:
        Set of all contraction forms (with apostrophes)

    Kept for the V1 writer and for the checks below; V2 derives the same set from
    the pairing structure.
    """
    with open(json_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    contractions = set()
    for base_word, variants in data.items():
        for variant in variants:
            contraction = variant['contraction'].lower()
            contractions.add(contraction)

    print(f"Loaded {len(contractions)} paired contractions from {json_path.name}")
    return contractions


def write_binary_contractions(
    output_path: Path,
    non_paired: Dict[str, str],
    paired: List[Tuple[str, List[Tuple[str, Optional[int]]]]]
):
    """
    Write contractions to binary format (V2).

    Args:
        output_path: Output file path for binary contractions
        non_paired: Dict mapping apostrophe-free forms to contractions
        paired: List of (base, [(contraction, frequency or None), ...]) in file order
    """
    with open(output_path, 'wb') as f:
        # Write header
        header = struct.pack(
            '<4sIII',
            MAGIC,              # Magic number
            VERSION,            # Format version
            len(non_paired),    # Non-paired count
            len(paired)         # Paired BASE count
        )
        f.write(header)

        # Write non-paired section (sorted for consistency)
        for key, value in sorted(non_paired.items()):
            key_bytes = key.encode('utf-8')
            value_bytes = value.encode('utf-8')

            f.write(struct.pack('<H', len(key_bytes)))
            f.write(key_bytes)
            f.write(struct.pack('<H', len(value_bytes)))
            f.write(value_bytes)

        # Write paired section — base, then its variants, in the JSON's file order
        for base, variants in paired:
            base_bytes = base.encode('utf-8')
            f.write(struct.pack('<H', len(base_bytes)))
            f.write(base_bytes)
            f.write(struct.pack('<H', len(variants)))
            for contraction, frequency in variants:
                contraction_bytes = contraction.encode('utf-8')
                f.write(struct.pack('<H', len(contraction_bytes)))
                f.write(contraction_bytes)
                f.write(struct.pack('<i', NO_FREQUENCY if frequency is None else frequency))

    file_size = output_path.stat().st_size

    print(f"\nWritten binary contractions: {output_path.name}")
    print(f"  Non-paired: {len(non_paired)}")
    print(f"  Paired bases: {len(paired)}")
    print(f"  Paired variants: {sum(len(variants) for _, variants in paired)}")
    print(f"  Distinct paired forms: {len({c for _, variants in paired for c, _ in variants})}")
    print(f"  Total known: {len(non_paired) + len({c for _, variants in paired for c, _ in variants})}")
    print(f"  File size: {file_size:,} bytes")


def main():
    if len(sys.argv) < 3:
        print("Usage: python generate_binary_contractions.py <non_paired_json> <paired_json> [output_bin]")
        print("\nExample:")
        print("  python scripts/generate_binary_contractions.py \\")
        print("    assets/dictionaries/contractions_non_paired.json \\")
        print("    assets/dictionaries/contraction_pairings.json \\")
        print("    assets/dictionaries/contractions.bin")
        sys.exit(1)

    non_paired_path = Path(sys.argv[1])
    paired_path = Path(sys.argv[2])

    if not non_paired_path.exists():
        print(f"Error: Non-paired file not found: {non_paired_path}")
        sys.exit(1)

    if not paired_path.exists():
        print(f"Error: Paired file not found: {paired_path}")
        sys.exit(1)

    # Default output: contractions.bin in same directory
    if len(sys.argv) >= 4:
        output_path = Path(sys.argv[3])
    else:
        output_path = non_paired_path.parent / 'contractions.bin'

    print("Converting contractions to binary format...")
    non_paired = load_non_paired_contractions(non_paired_path)
    paired = load_paired_structure(paired_path)

    # The known set the loader derives from the binary must be the same set the flat
    # V1 writer produced, or the guessed pairing bases change.
    flat = load_paired_contractions(paired_path)
    from_structure = {c for _, variants in paired for c, _ in variants}
    if flat != from_structure:
        print("Error: the paired form set from the structure differs from the flat set")
        sys.exit(1)

    write_binary_contractions(output_path, non_paired, paired)
    print(f"\n✓ Binary contractions generated successfully!")


if __name__ == '__main__':
    main()
