import subprocess
import os
import shutil
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse, FileResponse
from typing import List
from pydantic import BaseModel

app = FastAPI(title="Modern Media Server")

# Configuration - Change these to your media paths
MEDIA_ROOT = os.environ.get("MEDIA_ROOT", "./media")
CACHE_DIR = os.path.join(MEDIA_ROOT, ".cache/thumbs")

os.makedirs(CACHE_DIR, exist_ok=True)

class MediaItem(BaseModel):
    name: str
    path: str
    type: str # "folder", "video", "music", "image"
    size: int = 0

def get_media_type(filename: str) -> str:
    ext = os.path.splitext(filename)[1].lower()
    if ext in ['.mp4', '.mkv', '.avi', '.mov']: return "video"
    if ext in ['.mp3', '.flac', '.wav', '.m4a']: return "music"
    if ext in ['.jpg', '.jpeg', '.png', '.webp']: return "image"
    return "unknown"

@app.get("/api/browse", response_model=List[MediaItem])
async def browse(path: str = ""):
    full_path = os.path.join(MEDIA_ROOT, path.lstrip("/"))
    if not os.path.exists(full_path):
        raise HTTPException(status_code=404, detail="Path not found")
    
    items = []
    for entry in os.scandir(full_path):
        if entry.name.startswith("."): continue # Skip hidden files/cache
        rel_path = os.path.relpath(entry.path, MEDIA_ROOT)
        if entry.is_dir():
            items.append(MediaItem(name=entry.name, path=rel_path, type="folder"))
        else:
            m_type = get_media_type(entry.name)
            if m_type != "unknown":
                items.append(MediaItem(
                    name=entry.name, 
                    path=rel_path, 
                    type=m_type, 
                    size=entry.stat().st_size
                ))
    return items

@app.get("/api/search", response_model=List[MediaItem])
async def search(query: str):
    results = []
    for root, dirs, files in os.walk(MEDIA_ROOT):
        if ".cache" in root: continue
        for name in files + dirs:
            if query.lower() in name.lower():
                full_path = os.path.join(root, name)
                rel_path = os.path.relpath(full_path, MEDIA_ROOT)
                is_dir = os.path.isdir(full_path)
                m_type = "folder" if is_dir else get_media_type(name)
                if m_type != "unknown":
                    results.append(MediaItem(
                        name=name,
                        path=rel_path,
                        type=m_type,
                        size=0 if is_dir else os.path.getsize(full_path)
                    ))
    return results

@app.get("/api/stream.m3u8")
async def stream_hls(path: str):
    # ... existing implementation ...
    pass

@app.get("/api/thumb")
async def get_thumbnail(path: str):
    import hashlib
    full_path = os.path.join(MEDIA_ROOT, path.lstrip("/"))
    
    # Generate a unique cache filename based on the file path and modification time
    file_stat = os.stat(full_path)
    cache_key = hashlib.md5(f"{path}_{file_stat.st_mtime}".encode()).hexdigest()
    cache_path = os.path.join(CACHE_DIR, f"{cache_key}.jpg")

    if os.path.exists(cache_path):
        return FileResponse(cache_path)

    # Simple thumbnail extraction
    m_type = get_media_type(path)
    if m_type == "video":
        subprocess.run([
            "ffmpeg", "-y", "-ss", "00:00:05", "-i", full_path,
            "-vframes", "1", "-q:v", "4", "-vf", "scale=320:-1", cache_path
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    elif m_type == "image":
        subprocess.run([
            "ffmpeg", "-y", "-i", full_path,
            "-vf", "scale=320:-1", cache_path
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    
    if os.path.exists(cache_path):
        return FileResponse(cache_path)
    raise HTTPException(status_code=500, detail="Thumbnail generation failed")

if __name__ == "__main__":
    import uvicorn
    # Create dummy media folder for testing
    os.makedirs(MEDIA_ROOT, exist_ok=True)
    uvicorn.run(app, host="0.0.0.0", port=8000)
