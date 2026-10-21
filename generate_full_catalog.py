import json
import re
import os

with open('C:/Users/Giovanni/.gemini/antigravity/scratch/berserk_reader/manga_index.json', 'r', encoding='utf-8') as f:
    raw_volumes = json.load(f)

# Also check downloaded folders to get actual pages_count if present
manga_dir = 'C:/Users/Giovanni/.gemini/antigravity/scratch/berserk_reader/Berserk_Manga'

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

full_catalog = []

for vol in raw_volumes:
    vol_title = sanitize_filename(vol['volume'])
    vol_num_match = re.search(r'\d+', vol_title)
    vol_num = int(vol_num_match.group(0)) if vol_num_match else 1
    vol_str = f"{vol_num:02d}"

    chapters = []
    vol_disk_dir = os.path.join(manga_dir, vol_title)

    for ch in vol['chapters']:
        ch_title = sanitize_filename(ch['title'])
        if "disponibile ??" in ch_title.lower():
            continue
        raw_cap, formatted_cap = get_formatted_chapter(ch['href'])

        # Check pages count on disk if available
        pages_count = 0
        if os.path.exists(vol_disk_dir):
            ch_disk_dir = os.path.join(vol_disk_dir, ch_title)
            if os.path.exists(ch_disk_dir):
                pages_count = len([f for f in os.listdir(ch_disk_dir) if f.lower().endswith(('.jpg', '.png'))])

        # If not on disk, provide known count for early chapters
        if pages_count == 0:
            if ch['href'] == 'reader/000-01': pages_count = 97
            elif ch['href'] == 'reader/000-02': pages_count = 70
            elif ch['href'] == 'reader/000-03': pages_count = 57
            elif ch['href'] == 'reader/000-04': pages_count = 127
            elif ch['href'] == 'reader/000-05': pages_count = 109
            elif '000-' in ch['href']: pages_count = 45
            else: pages_count = 22

        chapters.append({
            'title': ch['title'],
            'folder_name': ch_title,
            'slug': ch['href'],
            'raw_cap': raw_cap,
            'cdn_cap': formatted_cap,
            'cdn_vol': vol_str,
            'pages_count': pages_count
        })

    full_catalog.append({
        'volume_number': vol_num,
        'volume_title': vol_title,
        'cdn_vol': vol_str,
        'chapters_count': len(chapters),
        'chapters': chapters
    })

out_path = 'C:/Users/Giovanni/.gemini/antigravity/scratch/berserk_reader/full_catalog.json'
with open(out_path, 'w', encoding='utf-8') as f:
    json.dump(full_catalog, f, ensure_ascii=False, indent=2)

# Copy to Berserk_Manga/berserk_catalog.json
with open(os.path.join(manga_dir, 'berserk_catalog.json'), 'w', encoding='utf-8') as f:
    json.dump(full_catalog, f, ensure_ascii=False, indent=2)

# Copy to Android assets
assets_dir = 'C:/Users/Giovanni/.gemini/antigravity/scratch/berserk_reader/BerserkReaderApp/app/src/main/assets'
os.makedirs(assets_dir, exist_ok=True)
with open(os.path.join(assets_dir, 'berserk_catalog.json'), 'w', encoding='utf-8') as f:
    json.dump(full_catalog, f, ensure_ascii=False, indent=2)

print(f"Generated {out_path} and synced to assets ({len(full_catalog)} volumes, {sum(v['chapters_count'] for v in full_catalog)} chapters).")
