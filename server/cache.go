package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"
)

// transcodeCache keeps Opus copies on disk, capped by total size. The least
// recently served copies are removed first.
type transcodeCache struct {
	dir string
	max int64
	sem chan struct{} // limits concurrent ffmpeg runs

	mu       sync.Mutex
	inflight map[string]chan struct{}
}

func newTranscodeCache(dir string, max int64) (*transcodeCache, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	// Leftovers from a run that was stopped mid-conversion.
	stale, _ := filepath.Glob(filepath.Join(dir, "*.tmp"))
	for _, f := range stale {
		os.Remove(f)
	}
	return &transcodeCache{
		dir:      dir,
		max:      max,
		sem:      make(chan struct{}, min(runtime.NumCPU(), 4)),
		inflight: map[string]chan struct{}{},
	}, nil
}

// get returns the path of an Opus copy of src at the given bitrate, making
// it first if needed. The key includes the source size and modified time, so
// a changed source gets a fresh copy.
func (c *transcodeCache) get(ctx context.Context, src string, st os.FileInfo, kbps int) (string, error) {
	sum := sha256.Sum256([]byte(fmt.Sprintf("%s|%d|%d|%d", src, st.Size(), st.ModTime().UnixNano(), kbps)))
	key := hex.EncodeToString(sum[:16])
	dst := filepath.Join(c.dir, key+".opus")

	for {
		c.mu.Lock()
		if wait, busy := c.inflight[key]; busy {
			c.mu.Unlock()
			select {
			case <-wait:
				continue
			case <-ctx.Done():
				return "", ctx.Err()
			}
		}
		if _, err := os.Stat(dst); err == nil {
			c.mu.Unlock()
			now := time.Now()
			os.Chtimes(dst, now, now) // marks it as recently used
			return dst, nil
		}
		done := make(chan struct{})
		c.inflight[key] = done
		c.mu.Unlock()

		err := c.transcode(src, dst, kbps)

		c.mu.Lock()
		delete(c.inflight, key)
		close(done)
		c.mu.Unlock()
		if err != nil {
			return "", err
		}
		go c.evict(dst)
		return dst, nil
	}
}

func (c *transcodeCache) transcode(src, dst string, kbps int) error {
	c.sem <- struct{}{}
	defer func() { <-c.sem }()

	// Not tied to the request: if the caller goes away, the finished copy
	// still lands in the cache for the retry.
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	tmp := dst + ".tmp"
	cmd := exec.CommandContext(ctx, "ffmpeg", "-nostdin", "-v", "error", "-y",
		"-i", src,
		"-map", "0:a:0", "-map_metadata", "0",
		"-c:a", "libopus", "-b:a", fmt.Sprintf("%dk", kbps), "-vbr", "on",
		"-f", "ogg", tmp)
	if out, err := cmd.CombinedOutput(); err != nil {
		os.Remove(tmp)
		return fmt.Errorf("ffmpeg: %w: %s", err, strings.TrimSpace(string(out)))
	}
	return os.Rename(tmp, dst)
}

// evict removes the least recently used copies until the cache fits its cap.
// keep is never removed, so a copy larger than the cap can still be served.
func (c *transcodeCache) evict(keep string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	entries, err := os.ReadDir(c.dir)
	if err != nil {
		return
	}
	type item struct {
		path string
		size int64
		used time.Time
	}
	var items []item
	var total int64
	for _, e := range entries {
		if !strings.HasSuffix(e.Name(), ".opus") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		items = append(items, item{filepath.Join(c.dir, e.Name()), info.Size(), info.ModTime()})
		total += info.Size()
	}
	sort.Slice(items, func(i, j int) bool { return items[i].used.Before(items[j].used) })
	for _, it := range items {
		if total <= c.max {
			return
		}
		if it.path == keep {
			continue
		}
		if err := os.Remove(it.path); err != nil {
			log.Printf("cache: could not remove %s: %v", it.path, err)
			continue
		}
		total -= it.size
	}
}
