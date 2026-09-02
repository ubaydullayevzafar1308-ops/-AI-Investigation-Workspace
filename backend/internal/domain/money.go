package domain

import (
	"strings"

	"github.com/shopspring/decimal"
)

// FormatAmount форматирует сумму как "100 000 000 UZS" — целое число с
// пробелами-разделителями тысяч. Аналог Java "%,.0f UZS".replace(",", " ").
func FormatAmount(d decimal.Decimal) string {
	neg := d.IsNegative()
	intPart := d.Abs().Truncate(0).String() // без дробной части

	var b strings.Builder
	n := len(intPart)
	for i, ch := range intPart {
		if i > 0 && (n-i)%3 == 0 {
			b.WriteByte(' ')
		}
		b.WriteRune(ch)
	}
	res := b.String()
	if neg {
		res = "-" + res
	}
	return res + " UZS"
}
