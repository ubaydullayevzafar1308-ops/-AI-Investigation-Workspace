package api

import (
	_ "embed"
	"net/http"
)

//go:embed openapi.json
var openAPISpec []byte

// swaggerUIPage — Swagger UI из CDN (cdnjs). Для полностью офлайн-демо
// сам JSON доступен на /api/openapi.json без интернета.
const swaggerUIPage = `<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>AI Case Intelligence API</title>
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/swagger-ui/5.17.14/swagger-ui.min.css">
</head>
<body>
  <div id="swagger-ui"></div>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/swagger-ui/5.17.14/swagger-ui-bundle.min.js"></script>
  <script>
    window.ui = SwaggerUIBundle({ url: '/api/openapi.json', dom_id: '#swagger-ui', deepLinking: true });
  </script>
</body>
</html>`

func (h *Handler) openAPIJSON(w http.ResponseWriter, r *http.Request) error {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_, _ = w.Write(openAPISpec)
	return nil
}

func (h *Handler) swaggerUI(w http.ResponseWriter, r *http.Request) error {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write([]byte(swaggerUIPage))
	return nil
}
