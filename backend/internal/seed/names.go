package seed

import (
	"fmt"
	"math/rand"
)

// nameGen — генератор реалистичных имён/названий (Узбекистан/СНГ).
type nameGen struct{ r *rand.Rand }

func newNameGen(r *rand.Rand) *nameGen { return &nameGen{r: r} }

var (
	maleFirst = []string{"Алишер", "Botir", "Sardor", "Jasur", "Diyor", "Farrux", "Bekzod", "Aziz",
		"Otabek", "Shokhrukh", "Anvar", "Rustam", "Islom", "Davron", "Nodir", "Umid"}
	femaleFirst = []string{"Diyora", "Malika", "Nilufar", "Zarina", "Gulnora", "Shahnoza", "Kamola",
		"Feruza", "Sevara", "Madina", "Nigora", "Yulduz", "Dilnoza", "Ozoda"}
	lastNames = []string{"Karimov", "Yusupov", "Rashidov", "Tashkentov", "Bekmuratov", "Nazarov",
		"Ergashev", "Islomov", "Turdiev", "Xolmatov", "Saidov", "Umarov", "Aliev", "Nurmatov", "Sultonov"}
	patronymics = []string{"Botirovich", "Sardorovich", "Alisherovich", "Jasurovich", "Rustamovich",
		"Anvarovich", "Islomovich", "Farruxovich"}
	companyPrefixes = []string{"OOO", "MCHJ", "ЧП"}
	companyStems    = []string{"Barakat Trade", "Vega Import", "Tashkent Logistics", "Silk Road Group",
		"Osiyo Savdo", "Zarafshon Capital", "Chirchiq Textile", "Nur Business", "Ipak Yoli Trading",
		"Aral Trans", "Yashnobod Market", "Bukhara Export", "Registon Holding", "Fargona Agro",
		"Andijon Textile", "Namangan Trade", "Qashqadaryo Logistics", "Surxon Group", "Xorazm Trans", "Toshkent Invest"}
	cities = []string{"Ташкент", "Самарканд", "Бухара", "Наманган", "Андижан", "Фергана"}
)

func (n *nameGen) pick(list []string) string { return list[n.r.Intn(len(list))] }

func (n *nameGen) fullName(male bool) string {
	first := femaleFirst
	if male {
		first = maleFirst
	}
	return fmt.Sprintf("%s %s %s", n.pick(lastNames), n.pick(first), n.pick(patronymics))
}

func (n *nameGen) companyName(suffix int64) string {
	prefix := n.pick(companyPrefixes)
	stem := n.pick(companyStems)
	tail := ""
	if suffix%100 != 0 {
		tail = fmt.Sprintf("-%d", suffix)
	}
	return fmt.Sprintf("%s \"%s%s\"", prefix, stem, tail)
}

func (n *nameGen) phone() string {
	return fmt.Sprintf("+998%d", 900000000+n.r.Int63n(99999999))
}

func (n *nameGen) inn(seed int64) string {
	return fmt.Sprintf("3%013d", seed%10_000_000_000_000)
}

func (n *nameGen) deviceID(seed int64) string {
	return fmt.Sprintf("device-%x", uint64(seed)*2654435761)
}

func (n *nameGen) address() string {
	return fmt.Sprintf("%s, ул. %d", n.pick(cities), 1+n.r.Intn(200))
}
