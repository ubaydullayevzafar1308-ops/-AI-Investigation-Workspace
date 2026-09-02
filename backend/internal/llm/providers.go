package llm

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/tiredjon/cbu/backend/internal/config"
)

// Adapter — единый интерфейс провайдера LLM. Выбор реализации только
// конфигом (LLM_PROVIDER), без изменения кода.
type Adapter interface {
	Complete(ctx context.Context, systemPrompt, userPrompt string) (string, error)
	ProviderName() string
	ModelName() string
}

// NewAdapter выбирает адаптер по конфигу.
func NewAdapter(cfg config.LLMConfig) (Adapter, error) {
	switch cfg.Provider {
	case "claude":
		return &claudeAdapter{cfg: cfg, client: httpClient(cfg.Timeout)}, nil
	case "openai":
		return &openAIAdapter{cfg: cfg, client: httpClient(cfg.Timeout)}, nil
	case "gemini":
		return &geminiAdapter{cfg: cfg, client: httpClient(cfg.Timeout)}, nil
	case "local":
		if strings.TrimSpace(cfg.BaseURL) == "" {
			return nil, fmt.Errorf("LLM_BASE_URL must be set when LLM_PROVIDER=local (e.g. http://localhost:11434/v1)")
		}
		return &localAdapter{cfg: cfg, client: httpClient(cfg.Timeout)}, nil
	case "stub", "":
		return stubAdapter{}, nil
	default:
		return nil, fmt.Errorf("unknown LLM provider: %s", cfg.Provider)
	}
}

func httpClient(timeout time.Duration) *http.Client {
	if timeout <= 0 {
		timeout = 60 * time.Second
	}
	return &http.Client{Timeout: timeout}
}

func doJSON(ctx context.Context, client *http.Client, method, url string, headers map[string]string, body any) ([]byte, error) {
	buf, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, method, url, bytes.NewReader(buf))
	if err != nil {
		return nil, err
	}
	req.Header.Set("content-type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("LLM API status %d: %s", resp.StatusCode, truncate(string(raw), 300))
	}
	return raw, nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}

// ── stub ────────────────────────────────────────────────────────────

type stubAdapter struct{}

func (stubAdapter) ProviderName() string { return "stub" }
func (stubAdapter) ModelName() string    { return "template-v1.0" }

func (stubAdapter) Complete(_ context.Context, _ string, userPrompt string) (string, error) {
	switch {
	case strings.Contains(userPrompt, "Структура отчёта") || strings.Contains(userPrompt, "заголовков"):
		return "1. Сводка по кейсу\n" +
			"Открыт кейс на основе алерта о подозрительной активности. " +
			"Итоговый Risk Score: HIGH. Рекомендуется эскалировать.\n\n" +
			"2. Описание подозрительной активности\n" +
			"Выявлены признаки многостадийной схемы отмывания средств с использованием цепочки подконтрольных компаний. " +
			"Обнаружены: круговые денежные потоки между участниками, дробление сумм для ухода от контроля, связи с лицами из чёрного списка.\n\n" +
			"3. Выявленные связи\n" +
			"Клиент имеет связи (общее устройство входа, структурные отношения) с лицами и организациями, помеченными в чёрном списке. " +
			"Это может указывать на скоординированную деятельность.\n\n" +
			"4. Оценка риска и её обоснование\n" +
			"На основе структурированного анализа система рекомендует классификацию как HIGH RISK. " +
			"Совокупность паттернов (циклы, дробление, чёрные списки) указывает на намеренную схему. Доверие к оценке: высокое.\n\n" +
			"5. Рекомендация\n" +
			"Эскалировать дело в подразделение комплаенса и регулятора для ручной проверки и возможного взаимодействия с правоохранительными органами.", nil
	case strings.Contains(userPrompt, "краткое объяснение") || strings.Contains(userPrompt, "почему"):
		return "Уровень риска: HIGH. Система оценила его как высокий на основе обнаруженных паттернов. " +
			"Главный фактор: круговая схема денежных переводов (+25 баллов) между клиентом и двумя связанными компаниями. " +
			"Дополнительно: дробление сумм на значения чуть ниже порога контроля (+20), связь с лицами из чёрного списка (+20), " +
			"история прошлых алертов (+10). Все указывает на скоординированную схему отмывания. " +
			"Рекомендация: эскалировать в комплаенс.", nil
	default:
		return "AI-ассистент будет доступен после подключения LLM-провайдера. " +
			"Структурированные данные кейса и детальный анализ доступны в таблице Evidence выше.", nil
	}
}

// ── claude ──────────────────────────────────────────────────────────

type claudeAdapter struct {
	cfg    config.LLMConfig
	client *http.Client
}

func (a *claudeAdapter) ProviderName() string { return "claude" }
func (a *claudeAdapter) ModelName() string    { return a.cfg.Model }

func (a *claudeAdapter) Complete(ctx context.Context, systemPrompt, userPrompt string) (string, error) {
	body := map[string]any{
		"model":      a.cfg.Model,
		"max_tokens": 2000,
		"system":     systemPrompt,
		"messages":   []map[string]any{{"role": "user", "content": userPrompt}},
	}
	raw, err := doJSON(ctx, a.client, http.MethodPost, "https://api.anthropic.com/v1/messages", map[string]string{
		"x-api-key":         a.cfg.APIKey,
		"anthropic-version": "2023-06-01",
	}, body)
	if err != nil {
		return "", fmt.Errorf("claude API call failed: %w", err)
	}
	var parsed struct {
		Content []struct {
			Text string `json:"text"`
		} `json:"content"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return "", fmt.Errorf("failed to parse Claude response: %w", err)
	}
	if len(parsed.Content) == 0 {
		return "", nil
	}
	return parsed.Content[0].Text, nil
}

// ── openai ──────────────────────────────────────────────────────────

type openAIAdapter struct {
	cfg    config.LLMConfig
	client *http.Client
}

func (a *openAIAdapter) ProviderName() string { return "openai" }
func (a *openAIAdapter) ModelName() string    { return a.cfg.Model }

func (a *openAIAdapter) Complete(ctx context.Context, systemPrompt, userPrompt string) (string, error) {
	return openAIChatCompletions(ctx, a.client, "https://api.openai.com/v1/chat/completions",
		map[string]string{"Authorization": "Bearer " + a.cfg.APIKey}, a.cfg.Model, systemPrompt, userPrompt, "OpenAI")
}

// ── local (OpenAI-совместимый, Ollama / vLLM) ───────────────────────

type localAdapter struct {
	cfg    config.LLMConfig
	client *http.Client
}

func (a *localAdapter) ProviderName() string { return "local" }
func (a *localAdapter) ModelName() string    { return a.cfg.Model }

func (a *localAdapter) Complete(ctx context.Context, systemPrompt, userPrompt string) (string, error) {
	url := strings.TrimRight(a.cfg.BaseURL, "/") + "/chat/completions"
	return openAIChatCompletions(ctx, a.client, url, nil, a.cfg.Model, systemPrompt, userPrompt, "Local LLM (Ollama/vLLM)")
}

func openAIChatCompletions(ctx context.Context, client *http.Client, url string, headers map[string]string, model, systemPrompt, userPrompt, label string) (string, error) {
	body := map[string]any{
		"model": model,
		"messages": []map[string]any{
			{"role": "system", "content": systemPrompt},
			{"role": "user", "content": userPrompt},
		},
	}
	raw, err := doJSON(ctx, client, http.MethodPost, url, headers, body)
	if err != nil {
		return "", fmt.Errorf("%s call failed: %w", label, err)
	}
	var parsed struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return "", fmt.Errorf("failed to parse %s response: %w", label, err)
	}
	if len(parsed.Choices) == 0 {
		return "", nil
	}
	return parsed.Choices[0].Message.Content, nil
}

// ── gemini ──────────────────────────────────────────────────────────

type geminiAdapter struct {
	cfg    config.LLMConfig
	client *http.Client
}

func (a *geminiAdapter) ProviderName() string { return "gemini" }
func (a *geminiAdapter) ModelName() string    { return a.cfg.Model }

func (a *geminiAdapter) Complete(ctx context.Context, systemPrompt, userPrompt string) (string, error) {
	url := fmt.Sprintf("https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s", a.cfg.Model, a.cfg.APIKey)
	body := map[string]any{
		"contents":          []map[string]any{{"parts": []map[string]any{{"text": userPrompt}}}},
		"systemInstruction": map[string]any{"parts": []map[string]any{{"text": systemPrompt}}},
	}
	raw, err := doJSON(ctx, a.client, http.MethodPost, url, nil, body)
	if err != nil {
		return "", fmt.Errorf("gemini API call failed: %w", err)
	}
	var parsed struct {
		Candidates []struct {
			Content struct {
				Parts []struct {
					Text string `json:"text"`
				} `json:"parts"`
			} `json:"content"`
		} `json:"candidates"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return "", fmt.Errorf("failed to parse Gemini response: %w", err)
	}
	if len(parsed.Candidates) == 0 || len(parsed.Candidates[0].Content.Parts) == 0 {
		return "", nil
	}
	return parsed.Candidates[0].Content.Parts[0].Text, nil
}
