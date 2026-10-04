package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	errUnauthorized = errors.New("plex rejected the token")
	errNotFound     = errors.New("track not found")
)

// track is what Plex knows about one track's source file.
type track struct {
	File      string
	Container string
	Codec     string
	Bitrate   int // kbps
	// Loudness correction in dB from Plex's own analysis. Nil when Plex has
	// not analysed the track.
	Gain      *float64
	AlbumGain *float64
}

// looseFloat reads a number that Plex may send bare or as a string.
type looseFloat struct{ v *float64 }

func (l *looseFloat) UnmarshalJSON(b []byte) error {
	s := strings.Trim(string(b), `"`)
	if f, err := strconv.ParseFloat(s, 64); err == nil {
		l.v = &f
	}
	return nil
}

type plexMetadata struct {
	MediaContainer struct {
		Metadata []struct {
			Type  string `json:"type"`
			Media []struct {
				Bitrate    int    `json:"bitrate"`
				AudioCodec string `json:"audioCodec"`
				Container  string `json:"container"`
				Part       []struct {
					File   string `json:"file"`
					Stream []struct {
						StreamType int        `json:"streamType"`
						Gain       looseFloat `json:"gain"`
						AlbumGain  looseFloat `json:"albumGain"`
					} `json:"Stream"`
				} `json:"Part"`
			} `json:"Media"`
		} `json:"Metadata"`
	} `json:"MediaContainer"`
}

type cachedTrack struct {
	t       *track
	expires time.Time
}

// plexClient looks tracks up with the caller's own token, so a caller can
// only reach what Plex lets them see. Answers are cached per token.
type plexClient struct {
	base string
	http *http.Client
	ttl  time.Duration

	mu    sync.Mutex
	cache map[string]cachedTrack
}

func newPlexClient(base string) *plexClient {
	return &plexClient{
		base:  base,
		http:  &http.Client{Timeout: 15 * time.Second},
		ttl:   5 * time.Minute,
		cache: map[string]cachedTrack{},
	}
}

func cacheKey(token, id string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:]) + "/" + id
}

func (p *plexClient) lookup(ctx context.Context, token, id string) (*track, error) {
	if token == "" {
		return nil, errUnauthorized
	}
	key := cacheKey(token, id)
	now := time.Now()
	p.mu.Lock()
	if c, ok := p.cache[key]; ok && now.Before(c.expires) {
		p.mu.Unlock()
		return c.t, nil
	}
	p.mu.Unlock()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, p.base+"/library/metadata/"+id, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("X-Plex-Token", token)
	resp, err := p.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	switch {
	case resp.StatusCode == http.StatusUnauthorized, resp.StatusCode == http.StatusForbidden:
		return nil, errUnauthorized
	case resp.StatusCode == http.StatusNotFound:
		return nil, errNotFound
	case resp.StatusCode != http.StatusOK:
		return nil, fmt.Errorf("plex returned %s", resp.Status)
	}
	var md plexMetadata
	if err := json.NewDecoder(resp.Body).Decode(&md); err != nil {
		return nil, fmt.Errorf("plex response: %w", err)
	}
	items := md.MediaContainer.Metadata
	if len(items) == 0 || items[0].Type != "track" {
		return nil, errNotFound
	}
	var t *track
	for _, m := range items[0].Media {
		if len(m.Part) > 0 && m.Part[0].File != "" {
			t = &track{File: m.Part[0].File, Container: m.Container, Codec: m.AudioCodec, Bitrate: m.Bitrate}
			for _, st := range m.Part[0].Stream {
				if st.StreamType == 2 { // audio
					t.Gain, t.AlbumGain = st.Gain.v, st.AlbumGain.v
					break
				}
			}
			break
		}
	}
	if t == nil {
		return nil, errNotFound
	}

	p.mu.Lock()
	if len(p.cache) > 20000 {
		for k, c := range p.cache {
			if now.After(c.expires) {
				delete(p.cache, k)
			}
		}
	}
	p.cache[key] = cachedTrack{t: t, expires: now.Add(p.ttl)}
	p.mu.Unlock()
	return t, nil
}
