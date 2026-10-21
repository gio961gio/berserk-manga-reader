#!/usr/bin/env python3
"""
Berserk Manga Downloader
Downloads all chapters and volumes from onepiecepower.com
Organizes files cleanly:
Berserk_Manga/
  Volume 01/
    Capitolo 000-01 - Il guerriero nero/
      01.jpg
      02.jpg
      ...
"""

import os
import sys
import re
import json
import time
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

# Default headers matching a standard desktop browser
HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8',
    'Accept-Language': 'it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7',
    'Referer': 'https://onepiecepower.com/manga8/berserk/lista-capitoli'
}

def create_session():
    session = requests.Session()
    retries = Retry(
        total=5,
        backoff_factor=1,
        status_forcelist=[429, 500, 502, 503, 504],
        allowed_methods=["HEAD", "GET", "OPTIONS"]
    )
    adapter = HTTPAdapter(max_retries=retries, pool_connections=20, pool_maxsize=20)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    session.headers.update(HEADERS)
    return session

def sanitize_filename(name):
    clean = re.sub(r'[\\/*?:"<>|]', "", name)
    clean = re.sub(r'\s+', ' ', clean).strip('. ')
    return clean

def get_formatted_chapter(slug):
    raw_cap = slug.split("reader/")[1].strip()
    if "-" in raw_cap:
        formatted_cap = re.sub(r'^0', '', raw_cap)
    else:
        try:
            val = float(raw_cap)
            formatted_cap = raw_cap[1:] if val < 100 else raw_cap
        except ValueError:
            formatted_cap = raw_cap
    return raw_cap, formatted_cap

def download_page(session, url, target_path):
    if os.path.exists(target_path) and os.path.getsize(target_path) > 1000:
        return True, 200, os.path.getsize(target_path)

    try:
        resp = session.get(url, timeout=15)
        if resp.status_code == 200:
            if len(resp.content) > 500:
                with open(target_path, 'wb') as f:
                    f.write(resp.content)
                return True, 200, len(resp.content)
        return False, resp.status_code, 0
    except Exception:
        return False, -1, 0

def download_chapter(session, vol_str, chapter_info, chapter_dir, max_workers=4):
    raw_cap, formatted_cap = get_formatted_chapter(chapter_info['href'])
    marker_path = os.path.join(chapter_dir, ".completed")

    if os.path.exists(marker_path):
        existing_pages = [f for f in os.listdir(chapter_dir) if f.lower().endswith(('.jpg', '.jpeg', '.png', '.webp'))]
        return len(existing_pages), True

    os.makedirs(chapter_dir, exist_ok=True)

    page_start = 1
    clean_eof = False
    workers = max(1, max_workers)

    with ThreadPoolExecutor(max_workers=workers) as executor:
        while True:
            batch_pages = list(range(page_start, page_start + workers))
            future_to_page = {}
            for p in batch_pages:
                page_str = f"{p:02d}"
                target_file = os.path.join(chapter_dir, f"{page_str}.jpg")
                url = f"https://onepiecepower.com/manga8/berserk/volume{vol_str}/capitolo{formatted_cap}/{page_str}.jpg"
                future = executor.submit(download_page, session, url, target_file)
                future_to_page[future] = p

            batch_results = {}
            for future in as_completed(future_to_page):
                p = future_to_page[future]
                try:
                    success, status, size = future.result()
                    batch_results[p] = (success, status, size)
                except Exception:
                    batch_results[p] = (False, -1, 0)

            hit_404 = False
            error_on_page = False

            for p in sorted(batch_pages):
                success, status, size = batch_results.get(p, (False, -1, 0))
                if success:
                    continue
                elif status == 404:
                    hit_404 = True
                    # Clean up any phantom files from higher numbers in this batch
                    for phantom_p in range(p + 1, page_start + workers):
                        phantom_file = os.path.join(chapter_dir, f"{phantom_p:02d}.jpg")
                        if os.path.exists(phantom_file):
                            try: os.remove(phantom_file)
                            except Exception: pass
                    break
                else:
                    # Retry this single page with backoff
                    retry_success = False
                    for retry in range(1, 4):
                        time.sleep(0.5 * retry)
                        page_str = f"{p:02d}"
                        target_file = os.path.join(chapter_dir, f"{page_str}.jpg")
                        url = f"https://onepiecepower.com/manga8/berserk/volume{vol_str}/capitolo{formatted_cap}/{page_str}.jpg"
                        s_ok, s_stat, _ = download_page(session, url, target_file)
                        if s_ok:
                            retry_success = True
                            break
                        elif s_stat == 404:
                            hit_404 = True
                            break

                    if not retry_success and not hit_404:
                        error_on_page = True
                        break

            if hit_404:
                clean_eof = True
                break

            if error_on_page:
                clean_eof = False
                break

            page_start += workers

    existing_pages = [f for f in os.listdir(chapter_dir) if f.lower().endswith(('.jpg', '.jpeg', '.png', '.webp'))]
    pages_count = len(existing_pages)

    if clean_eof and pages_count > 0:
        with open(marker_path, 'w', encoding='utf-8') as f:
            f.write(f"pages={pages_count}\n")

    return pages_count, False

def load_index(index_path):
    with open(index_path, 'r', encoding='utf-8') as f:
        return json.load(f)

def run_download(index_path, output_dir, volume_filter=None, max_workers=4):
    volumes_data = load_index(index_path)
    os.makedirs(output_dir, exist_ok=True)
    session = create_session()

    print("==================================================")
    print("  BERSERK MANGA DOWNLOADER (Concurrent Batches)")
    print(f"  Output folder: {output_dir}")
    print(f"  Parallel workers: {max_workers}")
    print("==================================================")

    selected_volumes = []
    for vol in volumes_data:
        vol_name = vol['volume']
        vol_num_match = re.search(r'\d+', vol_name)
        vol_num = int(vol_num_match.group(0)) if vol_num_match else 0

        if volume_filter is not None:
            if isinstance(volume_filter, list) and vol_num not in volume_filter:
                continue

        selected_volumes.append(vol)

    total_vols = len(selected_volumes)
    print(f"Selected {total_vols} volumes to process.\n")

    catalog_path = os.path.join(output_dir, "berserk_catalog.json")
    existing_catalog_dict = {}
    if os.path.exists(catalog_path):
        try:
            with open(catalog_path, 'r', encoding='utf-8') as f:
                prev_list = json.load(f)
                for item in prev_list:
                    if 'volume_number' in item:
                        existing_catalog_dict[item['volume_number']] = item
        except Exception as e:
            print(f"Warning: could not read existing catalog ({e}), will re-create.")

    for v_idx, vol in enumerate(selected_volumes, 1):
        vol_name = vol['volume']
        vol_num_str = re.search(r'\d+', vol_name).group(0)
        vol_num = int(vol_num_str)
        clean_vol_name = sanitize_filename(vol_name)
        vol_dir = os.path.join(output_dir, clean_vol_name)
        os.makedirs(vol_dir, exist_ok=True)

        chapters = vol['chapters']
        valid_chapters = [c for c in chapters if "disponibile ??" not in c.get('title', '').lower()]
        print(f"[{v_idx}/{total_vols}] {clean_vol_name} ({len(valid_chapters)} chapters)...")

        vol_catalog = {
            'volume_number': vol_num,
            'volume_title': clean_vol_name,
            'cdn_vol': f"{vol_num:02d}",
            'chapters_count': len(valid_chapters),
            'chapters': []
        }

        for c_idx, ch in enumerate(valid_chapters, 1):
            ch_title = sanitize_filename(ch['title'])
            raw_cap, formatted_cap = get_formatted_chapter(ch['href'])
            ch_dir = os.path.join(vol_dir, ch_title)

            pages_count, already_done = download_chapter(session, vol_catalog['cdn_vol'], ch, ch_dir, max_workers)
            status_str = "cached" if already_done else f"{pages_count} pages downloaded"
            print(f"    [{c_idx}/{len(valid_chapters)}] {ch_title} -> {status_str}")

            vol_catalog['chapters'].append({
                'title': ch['title'],
                'folder_name': ch_title,
                'slug': ch['href'],
                'raw_cap': raw_cap,
                'cdn_cap': formatted_cap,
                'cdn_vol': vol_catalog['cdn_vol'],
                'pages_count': pages_count
            })

            if not already_done:
                time.sleep(0.1)

        existing_catalog_dict[vol_num] = vol_catalog

    final_catalog = [existing_catalog_dict[k] for k in sorted(existing_catalog_dict.keys())]
    with open(catalog_path, 'w', encoding='utf-8') as f:
        json.dump(final_catalog, f, ensure_ascii=False, indent=2)

    app_assets_catalog = os.path.join(os.path.dirname(__file__), "BerserkReaderApp", "app", "src", "main", "assets", "berserk_catalog.json")
    if os.path.exists(os.path.dirname(app_assets_catalog)):
        try:
            with open(app_assets_catalog, 'w', encoding='utf-8') as f:
                json.dump(final_catalog, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    print(f"\n[DONE] All requested volumes processed! Catalog saved to: {catalog_path}")

def parse_range(range_str):
    if '-' in range_str:
        start, end = map(int, range_str.split('-'))
        return list(range(start, end + 1))
    elif ',' in range_str:
        return [int(x.strip()) for x in range_str.split(',')]
    else:
        return [int(range_str)]

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Download Berserk manga chapters organized by volume")
    parser.add_argument('--index', default=os.path.join(os.path.dirname(__file__), 'manga_index.json'), help="Path to manga_index.json")
    parser.add_argument('--output', default=os.path.join(os.path.dirname(__file__), 'Berserk_Manga'), help="Output directory")
    parser.add_argument('--volume', default=None, help="Volume number or range (e.g. '1', '1-5', '1,2,3')")
    parser.add_argument('--all', action='store_true', help="Download all 44 volumes")
    parser.add_argument('--workers', type=int, default=4, help="Number of concurrent download threads")

    args = parser.parse_args()

    vol_filter = None
    if args.volume:
        vol_filter = parse_range(args.volume)
    elif not args.all:
        print("Note: No volume specified and --all not set. Defaulting to Volume 1 for test.")
        vol_filter = [1]

    run_download(args.index, args.output, volume_filter=vol_filter, max_workers=args.workers)
