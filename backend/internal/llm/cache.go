package llm

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
)

// ResponseCache — файловый кэш ответов LLM для офлайн-демо.
// Ключ: SHA-256(provider|model|system|user). Значение: ./llm-cache/{hash}.txt.
type ResponseCache struct {
	dir     string
	enabled bool
}

func NewResponseCache(dir string, enabled bool) *ResponseCache {
	return &ResponseCache{dir: dir, enabled: enabled}
}

func (c *ResponseCache) key(provider, model, system, user string) string {
	h := sha256.Sum256([]byte(provider + "|" + model + "|" + system + "|" + user))
	return hex.EncodeToString(h[:])
}

func (c *ResponseCache) Get(provider, model, system, user string) (string, bool) {
	if !c.enabled {
		return "", false
	}
	b, err := os.ReadFile(filepath.Join(c.dir, c.key(provider, model, system, user)+".txt"))
	if err != nil {
		return "", false
	}
	return string(b), true
}

func (c *ResponseCache) Put(provider, model, system, user, response string) {
	if !c.enabled {
		return
	}
	if err := os.MkdirAll(c.dir, 0o755); err != nil {
		return
	}
	_ = os.WriteFile(filepath.Join(c.dir, c.key(provider, model, system, user)+".txt"), []byte(response), 0o644)
}
