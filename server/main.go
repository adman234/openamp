// Command openamp-files serves audio files from a music share by Plex track id.
//
// The caller never supplies a path. Each request carries a Plex token, the
// service asks Plex where that track's file lives, maps the path onto its own
// read-only mount, and sends the file or an Opus copy of it.
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path"
	"strconv"
	"strings"
	"sync"
	"time"
)

type pathMap struct {
	from string // folder as Plex sees it
	to   string // folder as this container sees it
}

type config struct {
	plexURL  string
	maps     []pathMap
	cacheDir string
	cacheMax int64 // bytes
	listen   string
	kbps     map[string]int
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envInt(key string, def int) (int, error) {
	v := os.Getenv(key)
	if v == "" {
		return def, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil || n <= 0 {
		return 0, fmt.Errorf("%s must be a positive number, got %q", key, v)
	}
	return n, nil
}

// parsePathMaps reads "plexpath=localpath" pairs separated by commas.
func parsePathMaps(s string) ([]pathMap, error) {
	var maps []pathMap
	for _, pair := range strings.Split(s, ",") {
		pair = strings.TrimSpace(pair)
		if pair == "" {
			continue
		}
		from, to, ok := strings.Cut(pair, "=")
		if !ok || !strings.HasPrefix(from, "/") || !strings.HasPrefix(to, "/") {
			return nil, fmt.Errorf("PATH_MAP entry %q must look like /plex/path=/container/path", pair)
		}
		maps = append(maps, pathMap{from: path.Clean(from), to: path.Clean(to)})
	}
	if len(maps) == 0 {
		return nil, errors.New("PATH_MAP is required, for example /data/music=/music")
	}
	return maps, nil
}

func loadConfig() (config, error) {
	c := config{
		plexURL:  strings.TrimRight(os.Getenv("PLEX_URL"), "/"),
		cacheDir: envOr("CACHE_DIR", "/cache"),
		listen:   envOr("LISTEN", ":8080"),
	}
	if c.plexURL == "" {
		return c, errors.New("PLEX_URL is required, for example http://192.168.1.10:32400")
	}
	var err error
	if c.maps, err = parsePathMaps(os.Getenv("PATH_MAP")); err != nil {
		return c, err
	}
	maxMB, err := envInt("CACHE_MAX_MB", 10240)
	if err != nil {
		return c, err
	}
	c.cacheMax = int64(maxMB) << 20
	c.kbps = map[string]int{}
	for q, def := range map[string]int{"high": 192, "medium": 128, "low": 96} {
		if c.kbps[q], err = envInt("OPUS_"+strings.ToUpper(q)+"_KBPS", def); err != nil {
			return c, err
		}
	}
	return c, nil
}

var errOutsideShare = errors.New("file is outside the mapped music folders")

// resolve maps a path reported by Plex onto the local mount. It rejects
// anything that does not land inside a mapped folder.
func (c config) resolve(plexFile string) (string, error) {
	for _, m := range c.maps {
		if !strings.HasPrefix(plexFile, strings.TrimSuffix(m.from, "/")+"/") {
			continue
		}
		local := path.Join(m.to, plexFile[len(m.from):])
		if !strings.HasPrefix(local, strings.TrimSuffix(m.to, "/")+"/") {
			return "", errOutsideShare
		}
		return local, nil
	}
	return "", errOutsideShare
}

var losslessCodecs = map[string]bool{
	"flac": true, "alac": true, "ape": true, "wavpack": true, "tta": true,
	"wav": true, "aiff": true, "dsd": true, "truehd": true, "mlp": true,
}

// needsTranscode reports whether a source should be converted to reach the
// target bitrate. A lossy source already at or below the target is sent as
// is, since converting it again would only lose quality.
func needsTranscode(codec string, bitrate, target int) bool {
	codec = strings.ToLower(codec)
	if losslessCodecs[codec] || strings.HasPrefix(codec, "pcm") || strings.HasPrefix(codec, "dsd") {
		return true
	}
	return bitrate <= 0 || bitrate > target
}

var contentTypes = map[string]string{
	"opus": "audio/ogg", "ogg": "audio/ogg", "oga": "audio/ogg",
	"flac": "audio/flac", "mp3": "audio/mpeg", "m4a": "audio/mp4",
	"aac": "audio/aac", "wav": "audio/wav", "aiff": "audio/aiff",
	"wma": "audio/x-ms-wma", "ape": "audio/x-ape", "wv": "audio/x-wavpack",
}

type server struct {
	cfg   config
	plex  *plexClient
	cache *transcodeCache
}

func validID(id string) bool {
	if id == "" || len(id) > 20 {
		return false
	}
	for _, r := range id {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func writeLookupError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, errUnauthorized):
		http.Error(w, "Plex rejected the token", http.StatusUnauthorized)
	case errors.Is(err, errNotFound):
		http.Error(w, "no such track", http.StatusNotFound)
	case errors.Is(err, errOutsideShare):
		http.Error(w, err.Error(), http.StatusNotFound)
	default:
		log.Printf("lookup failed: %v", err)
		http.Error(w, "could not reach Plex", http.StatusBadGateway)
	}
}

// locate asks Plex for the track and finds its file on the local mount.
func (s *server) locate(r *http.Request, id string) (*track, string, os.FileInfo, error) {
	t, err := s.plex.lookup(r.Context(), r.Header.Get("X-Plex-Token"), id)
	if err != nil {
		return nil, "", nil, err
	}
	local, err := s.cfg.resolve(t.File)
	if err != nil {
		return nil, "", nil, err
	}
	st, err := os.Stat(local)
	if err != nil || st.IsDir() {
		return nil, "", nil, fmt.Errorf("%w: %s is not readable on the mount", errNotFound, local)
	}
	return t, local, st, nil
}

func (s *server) handleFile(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		http.Error(w, "track id must be a number", http.StatusBadRequest)
		return
	}
	quality := r.URL.Query().Get("quality")
	if quality == "" {
		quality = "original"
	}
	target, lossy := s.cfg.kbps[quality]
	if !lossy && quality != "original" {
		http.Error(w, "quality must be original, high, medium or low", http.StatusBadRequest)
		return
	}
	t, local, st, err := s.locate(r, id)
	if err != nil {
		writeLookupError(w, err)
		return
	}

	servePath, modTime := local, st.ModTime()
	ext := strings.ToLower(strings.TrimPrefix(path.Ext(local), "."))
	transcoded := false
	if lossy && needsTranscode(t.Codec, t.Bitrate, target) {
		servePath, err = s.cache.get(r.Context(), local, st, target)
		if err != nil {
			log.Printf("transcode of track %s failed: %v", id, err)
			http.Error(w, "could not convert the track", http.StatusInternalServerError)
			return
		}
		ext, transcoded = "opus", true
	}

	f, err := os.Open(servePath)
	if err != nil {
		http.Error(w, "could not open the track", http.StatusInternalServerError)
		return
	}
	defer f.Close()

	ctype := contentTypes[ext]
	if ctype == "" {
		ctype = "application/octet-stream"
	}
	h := w.Header()
	h.Set("Content-Type", ctype)
	h.Set("X-OpenAmp-Ext", ext)
	h.Set("X-OpenAmp-Transcoded", strconv.FormatBool(transcoded))
	h.Set("X-OpenAmp-Source-Size", strconv.FormatInt(st.Size(), 10))
	h.Set("X-OpenAmp-Source-Mtime", strconv.FormatInt(st.ModTime().Unix(), 10))
	// ServeContent handles Range and If-Range, which gives resume support.
	http.ServeContent(w, r, "", modTime, f)
}

type infoEntry struct {
	ID        string `json:"id"`
	Size      int64  `json:"size,omitempty"`
	Mtime     int64  `json:"mtime,omitempty"`
	Container string `json:"container,omitempty"`
	Codec     string `json:"codec,omitempty"`
	Bitrate   int    `json:"bitrate,omitempty"`
	Error     string `json:"error,omitempty"`
}

const maxInfoIDs = 500

func (s *server) handleInfo(w http.ResponseWriter, r *http.Request) {
	var ids []string
	for _, id := range strings.Split(r.URL.Query().Get("ids"), ",") {
		if id = strings.TrimSpace(id); id != "" {
			ids = append(ids, id)
		}
	}
	if len(ids) == 0 || len(ids) > maxInfoIDs {
		http.Error(w, fmt.Sprintf("ids must list 1 to %d track ids", maxInfoIDs), http.StatusBadRequest)
		return
	}
	for _, id := range ids {
		if !validID(id) {
			http.Error(w, "track id must be a number", http.StatusBadRequest)
			return
		}
	}

	out := make([]infoEntry, len(ids))
	var unauthorized bool
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 8)
	for i, id := range ids {
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			e := infoEntry{ID: id}
			t, _, st, err := s.locate(r, id)
			switch {
			case errors.Is(err, errUnauthorized):
				mu.Lock()
				unauthorized = true
				mu.Unlock()
			case errors.Is(err, errNotFound), errors.Is(err, errOutsideShare):
				e.Error = "not_found"
			case err != nil:
				log.Printf("info for track %s failed: %v", id, err)
				e.Error = "unavailable"
			default:
				e.Size, e.Mtime = st.Size(), st.ModTime().Unix()
				e.Container, e.Codec, e.Bitrate = t.Container, t.Codec, t.Bitrate
			}
			out[i] = e
		}()
	}
	wg.Wait()
	if unauthorized {
		http.Error(w, "Plex rejected the token", http.StatusUnauthorized)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"tracks": out})
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte("ok\n"))
	})
	mux.HandleFunc("GET /v1/tracks/info", s.handleInfo)
	mux.HandleFunc("GET /v1/tracks/{id}/file", s.handleFile)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		mux.ServeHTTP(w, r)
		if r.URL.Path != "/healthz" {
			// The token travels in a header, so the logged URL holds no secret.
			log.Printf("%s %s %s", r.Method, r.URL.RequestURI(), time.Since(start).Round(time.Millisecond))
		}
	})
}

func main() {
	cfg, err := loadConfig()
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	cache, err := newTranscodeCache(cfg.cacheDir, cfg.cacheMax)
	if err != nil {
		log.Fatalf("cache: %v", err)
	}
	s := &server{cfg: cfg, plex: newPlexClient(cfg.plexURL), cache: cache}
	log.Printf("openamp-files listening on %s, Plex at %s", cfg.listen, cfg.plexURL)
	srv := &http.Server{Addr: cfg.listen, Handler: s.routes(), ReadHeaderTimeout: 10 * time.Second}
	log.Fatal(srv.ListenAndServe())
}
