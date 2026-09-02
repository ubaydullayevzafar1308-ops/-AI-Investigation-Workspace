package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/tiredjon/cbu/backend/internal/repo"
)

// apiError — явная ошибка с HTTP-статусом (аналог ResponseStatusException).
type apiError struct {
	status int
	msg    string
}

func (e *apiError) Error() string { return e.msg }

func errStatus(status int, msg string) *apiError { return &apiError{status: status, msg: msg} }
func notFound(msg string) *apiError              { return &apiError{status: http.StatusNotFound, msg: msg} }
func badRequest(msg string) *apiError            { return &apiError{status: http.StatusBadRequest, msg: msg} }

// errorResponse — единый формат ошибки {timestamp, status, message}.
type errorResponse struct {
	Timestamp time.Time `json:"timestamp"`
	Status    int       `json:"status"`
	Message   string    `json:"message"`
}

type handlerFunc func(w http.ResponseWriter, r *http.Request) error

// handle оборачивает хендлер: конвертирует возвращённую ошибку в
// {timestamp, status, message}. 404 для repo.ErrNotFound, 400 для
// apiError с этим статусом, иначе 500.
func handle(fn handlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		err := fn(w, r)
		if err == nil {
			return
		}
		status := http.StatusInternalServerError
		msg := err.Error()

		var ae *apiError
		switch {
		case errors.As(err, &ae):
			status = ae.status
		case errors.Is(err, repo.ErrNotFound):
			status = http.StatusNotFound
		}
		writeJSON(w, status, errorResponse{Timestamp: time.Now(), Status: status, Message: msg})
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func decodeJSON(r *http.Request, v any) error {
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		return badRequest("invalid request body: " + err.Error())
	}
	return nil
}

// ── пагинация в стиле Spring Data Page<T> ────────────────────────────

type page struct {
	Content          any   `json:"content"`
	TotalElements    int64 `json:"totalElements"`
	TotalPages       int   `json:"totalPages"`
	Number           int   `json:"number"`
	Size             int   `json:"size"`
	NumberOfElements int   `json:"numberOfElements"`
	First            bool  `json:"first"`
	Last             bool  `json:"last"`
	Empty            bool  `json:"empty"`
}

func newPage(content any, count int, total int64, pageNum, size int) page {
	totalPages := 0
	if size > 0 {
		totalPages = int((total + int64(size) - 1) / int64(size))
	}
	return page{
		Content:          content,
		TotalElements:    total,
		TotalPages:       totalPages,
		Number:           pageNum,
		Size:             size,
		NumberOfElements: count,
		First:            pageNum == 0,
		Last:             pageNum >= totalPages-1,
		Empty:            count == 0,
	}
}

func pageParams(r *http.Request) (pageNum, size, limit, offset int) {
	pageNum = queryInt(r, "page", 0)
	size = queryInt(r, "size", 20)
	if pageNum < 0 {
		pageNum = 0
	}
	if size <= 0 {
		size = 20
	}
	return pageNum, size, size, pageNum * size
}

func queryInt(r *http.Request, key string, def int) int {
	v := r.URL.Query().Get(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

func optionalQuery(r *http.Request, key string) *string {
	v := r.URL.Query().Get(key)
	if v == "" {
		return nil
	}
	return &v
}

func pathInt64(r *http.Request, key string) (int64, error) {
	v := chiURLParam(r, key)
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		return 0, badRequest("invalid path parameter " + key)
	}
	return n, nil
}
