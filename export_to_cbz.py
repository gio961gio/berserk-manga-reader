#!/usr/bin/env python3
"""
Berserk Manga CBZ Exporter
Packages downloaded manga volumes or chapters into .cbz (Comic Book Zip) files.
"""

import os
import sys
import zipfile
import argparse

def create_cbz_for_volume(vol_path, output_dir=None):
    if not os.path.isdir(vol_path):
        return None

    vol_name = os.path.basename(vol_path)
    if output_dir is None:
        output_dir = os.path.dirname(vol_path)
    os.makedirs(output_dir, exist_ok=True)

    cbz_filename = f"{vol_name}.cbz"
    cbz_path = os.path.join(output_dir, cbz_filename)

    print(f"Creating CBZ for {vol_name} -> {cbz_filename}...")
    image_count = 0

    with zipfile.ZipFile(cbz_path, 'w', zipfile.ZIP_DEFLATED) as cbz:
        for root, dirs, files in os.walk(vol_path):
            dirs.sort()
            files.sort()
            for f in files:
                if f.lower().endswith(('.jpg', '.jpeg', '.png', '.webp')):
                    full_path = os.path.join(root, f)
                    arcname = os.path.relpath(full_path, vol_path)
                    cbz.write(full_path, arcname)
                    image_count += 1

    print(f"  Added {image_count} images into {cbz_filename} ({os.path.getsize(cbz_path) / (1024*1024):.1f} MB)")
    return cbz_path

def main():
    parser = argparse.ArgumentParser(description="Export Berserk volumes to CBZ files")
    parser.add_argument('--input', default='C:/Users/Giovanni/.gemini/antigravity/scratch/berserk_reader/Berserk_Manga', help="Path to Berserk_Manga folder")
    parser.add_argument('--output', default='C:/Users/Giovanni/.gemini/antigravity/scratch/berserk_reader/CBZ_Volumes', help="Output folder for CBZ files")
    parser.add_argument('--volume', default=None, help="Specific volume to convert (e.g. 'Volume 01' or '1')")

    args = parser.parse_args()

    if not os.path.exists(args.input):
        print(f"Error: {args.input} does not exist!")
        return

    os.makedirs(args.output, exist_ok=True)

    volumes = [d for d in os.listdir(args.input) if os.path.isdir(os.path.join(args.input, d)) and d.lower().startswith("volume")]
    volumes.sort()

    if args.volume:
        vol_str = f"Volume {int(args.volume):02d}" if args.volume.isdigit() else args.volume
        volumes = [d for d in volumes if d.lower() == vol_str.lower()]

    print(f"Found {len(volumes)} volumes to export to CBZ.")
    for v in volumes:
        v_path = os.path.join(args.input, v)
        create_cbz_for_volume(v_path, args.output)

    print("CBZ export finished!")

if __name__ == '__main__':
    main()
