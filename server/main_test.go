package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestResolve(t *testing.T) {
	c := config{maps: []pathMap{{from: "/data/music", to: "/music"}}}
	cases := []struct {
		in, want string
		ok       bool
	}{
		{"/data/music/Artist/Album/01 Song.flac", "/music/Artist/Album/01 Song.flac", true},
		{"/data/music/../../etc/passwd", "", false},
		{"/data/musicals/x.flac", "", false},
		{"/etc/passwd", "", false},
		{"/data/music", "", false},
	}
	for _, tc := range cases {
		got, err := c.resolve(tc.in)
		if (err == nil) != tc.ok || got != tc.want {
			t.Errorf("resolve(%q) = %q, %v; want %q, ok=%v", tc.in, got, err, tc.want, tc.ok)
		}
	}
}

func TestNeedsTranscode(t *testing.T) {
	cases := []struct {
		codec   string
		bitrate int
		target  int
		want    bool
	}{
		{"flac", 900, 128, true},
		{"FLAC", 0, 128, true},
		{"pcm_s16le", 1411, 192, true},
		{"mp3", 320, 128, true},
		{"mp3", 128, 128, false},
		{"opus", 96, 192, false},
		{"aac", 0, 128, true},
	}
	for _, tc := range cases {
		if got := needsTranscode(tc.codec, tc.bitrate, tc.target); got != tc.want {
			t.Errorf("needsTranscode(%q, %d, %d) = %v, want %v", tc.codec, tc.bitrate, tc.target, got, tc.want)
		}
	}
}

func TestParsePathMaps(t *testing.T) {
	maps, err := parsePathMaps("/data/music/=/music, /data/more=/more")
	if err != nil || len(maps) != 2 || maps[0] != (pathMap{"/data/music", "/music"}) {
		t.Fatalf("parsePathMaps = %v, %v", maps, err)
	}
	for _, bad := range []string{"", "music=/music", "/data/music"} {
		if _, err := parsePathMaps(bad); err == nil {
			t.Errorf("parsePathMaps(%q) should fail", bad)
		}
	}
}

// fakePlex serves track 1 as a file under plexRoot and rejects any token but "good".
func fakePlex(t *testing.T, plexRoot string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Plex-Token") != "good" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if r.URL.Path != "/library/metadata/1" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		fmt.Fprintf(w, `{"MediaContainer":{"Metadata":[{"type":"track","Media":[{"bitrate":128,"audioCodec":"mp3","container":"mp3","Part":[{"file":%q,"Stream":[{"streamType":2,"gain":-7.25,"albumGain":"-6.5"}]}]}]}]}}`,
			plexRoot+"/Artist/Album/01 Song.mp3")
	}))
}

func newTestServer(t *testing.T) (*server, []byte) {
	t.Helper()
	share := filepath.ToSlash(t.TempDir())
	body := []byte("not really an mp3, but twenty-nine bytes")
	dir := filepath.Join(share, "Artist", "Album")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "01 Song.mp3"), body, 0o644); err != nil {
		t.Fatal(err)
	}
	plex := fakePlex(t, "/data/music")
	t.Cleanup(plex.Close)
	cache, err := newTranscodeCache(t.TempDir(), 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	cfg := config{
		plexURL: plex.URL,
		maps:    []pathMap{{from: "/data/music", to: share}},
		kbps:    map[string]int{"high": 192, "medium": 128, "low": 96},
	}
	return &server{cfg: cfg, plex: newPlexClient(plex.URL), cache: cache}, body
}

func do(s *server, target, token string, hdr ...string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, target, nil)
	if token != "" {
		req.Header.Set("X-Plex-Token", token)
	}
	for i := 0; i+1 < len(hdr); i += 2 {
		req.Header.Set(hdr[i], hdr[i+1])
	}
	rec := httptest.NewRecorder()
	s.routes().ServeHTTP(rec, req)
	return rec
}

func TestFile(t *testing.T) {
	if filepath.Separator != '/' {
		t.Skip("path mapping assumes a Linux container")
	}
	s, body := newTestServer(t)

	if rec := do(s, "/v1/tracks/1/file", ""); rec.Code != http.StatusUnauthorized {
		t.Errorf("no token: got %d", rec.Code)
	}
	if rec := do(s, "/v1/tracks/1/file", "bad"); rec.Code != http.StatusUnauthorized {
		t.Errorf("bad token: got %d", rec.Code)
	}
	if rec := do(s, "/v1/tracks/2/file", "good"); rec.Code != http.StatusNotFound {
		t.Errorf("unknown track: got %d", rec.Code)
	}
	if rec := do(s, "/v1/tracks/abc/file", "good"); rec.Code != http.StatusBadRequest {
		t.Errorf("non-numeric id: got %d", rec.Code)
	}
	if rec := do(s, "/v1/tracks/1/file?quality=ultra", "good"); rec.Code != http.StatusBadRequest {
		t.Errorf("bad quality: got %d", rec.Code)
	}

	rec := do(s, "/v1/tracks/1/file", "good")
	if rec.Code != http.StatusOK || rec.Body.String() != string(body) {
		t.Fatalf("original: got %d %q", rec.Code, rec.Body.String())
	}
	if rec.Header().Get("X-OpenAmp-Gain") != "-7.25" || rec.Header().Get("X-OpenAmp-Album-Gain") != "-6.50" {
		t.Errorf("gain headers: %v", rec.Header())
	}
	if rec.Header().Get("X-OpenAmp-Ext") != "mp3" || rec.Header().Get("X-OpenAmp-Transcoded") != "false" {
		t.Errorf("original headers: %v", rec.Header())
	}

	// A 128 kbps mp3 asked for at Medium (128) is sent as is, without ffmpeg.
	rec = do(s, "/v1/tracks/1/file?quality=medium", "good")
	if rec.Code != http.StatusOK || rec.Header().Get("X-OpenAmp-Transcoded") != "false" {
		t.Errorf("medium passthrough: got %d %v", rec.Code, rec.Header())
	}

	rec = do(s, "/v1/tracks/1/file", "good", "Range", "bytes=4-9")
	if rec.Code != http.StatusPartialContent || rec.Body.String() != string(body[4:10]) {
		t.Errorf("range: got %d %q", rec.Code, rec.Body.String())
	}
}

func TestInfo(t *testing.T) {
	if filepath.Separator != '/' {
		t.Skip("path mapping assumes a Linux container")
	}
	s, body := newTestServer(t)

	if rec := do(s, "/v1/tracks/info?ids=1", "bad"); rec.Code != http.StatusUnauthorized {
		t.Errorf("bad token: got %d", rec.Code)
	}
	rec := do(s, "/v1/tracks/info?ids=1,2", "good")
	if rec.Code != http.StatusOK {
		t.Fatalf("info: got %d %s", rec.Code, rec.Body.String())
	}
	var out struct {
		Tracks []infoEntry `json:"tracks"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	if len(out.Tracks) != 2 {
		t.Fatalf("info: got %d entries", len(out.Tracks))
	}
	if got := out.Tracks[0]; got.ID != "1" || got.Size != int64(len(body)) || got.Codec != "mp3" || got.Bitrate != 128 || got.Mtime == 0 {
		t.Errorf("track 1: %+v", got)
	}
	if got := out.Tracks[1]; got.ID != "2" || got.Error != "not_found" {
		t.Errorf("track 2: %+v", got)
	}
}
