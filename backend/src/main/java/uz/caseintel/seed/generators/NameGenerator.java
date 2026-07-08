package uz.caseintel.seed.generators;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Генератор реалистичных имён/названий для синтетического датасета
 * (Узбекистан/СНГ контекст, см. ARCHITECTURE.md §16).
 */
public class NameGenerator {

    private static final List<String> MALE_FIRST_NAMES = List.of(
            "Алишер", "Botir", "Sardor", "Jasur", "Diyor", "Farrux", "Bekzod", "Aziz",
            "Otabek", "Shokhrukh", "Anvar", "Rustam", "Islom", "Davron", "Nodir", "Umid"
    );
    private static final List<String> FEMALE_FIRST_NAMES = List.of(
            "Diyora", "Malika", "Nilufar", "Zarina", "Gulnora", "Shahnoza", "Kamola",
            "Feruza", "Sevara", "Madina", "Nigora", "Yulduz", "Dilnoza", "Ozoda"
    );
    private static final List<String> LAST_NAMES = List.of(
            "Karimov", "Yusupov", "Rashidov", "Tashkentov", "Bekmuratov", "Nazarov",
            "Ergashev", "Islomov", "Turdiev", "Xolmatov", "Saidov", "Umarov", "Aliev",
            "Nurmatov", "Sultonov"
    );
    private static final List<String> PATRONYMICS = List.of(
            "Botirovich", "Sardorovich", "Alisherovich", "Jasurovich", "Rustamovich",
            "Anvarovich", "Islomovich", "Farruxovich"
    );

    private static final List<String> COMPANY_PREFIXES = List.of(
            "OOO", "MCHJ", "ЧП"
    );
    private static final List<String> COMPANY_NAME_STEMS = List.of(
            "Barakat Trade", "Vega Import", "Tashkent Logistics", "Silk Road Group",
            "Osiyo Savdo", "Zarafshon Capital", "Chirchiq Textile", "Nur Business",
            "Ipak Yoli Trading", "Aral Trans", "Yashnobod Market", "Bukhara Export",
            "Registon Holding", "Fargona Agro", "Andijon Textile", "Namangan Trade",
            "Qashqadaryo Logistics", "Surxon Group", "Xorazm Trans", "Toshkent Invest"
    );

    private final RandomGenerator random;

    public NameGenerator(RandomGenerator random) {
        this.random = random;
    }

    public String fullName(boolean male) {
        String last = pick(LAST_NAMES);
        String first = male ? pick(MALE_FIRST_NAMES) : pick(FEMALE_FIRST_NAMES);
        String patronymic = pick(PATRONYMICS);
        return "%s %s %s".formatted(last, first, patronymic);
    }

    /** Название компании — вариативность через постфикс, чтобы компании не выглядели все одинаковыми. */
    public String companyName(long uniqueSuffix) {
        String prefix = pick(COMPANY_PREFIXES);
        String stem = pick(COMPANY_NAME_STEMS);
        return "%s \"%s%s\"".formatted(prefix, stem, uniqueSuffix % 100 == 0 ? "" : "-" + uniqueSuffix);
    }

    public String phone() {
        return "+998%d".formatted(900000000L + (long) (random.nextDouble() * 99999999L));
    }

    public String inn(long uniqueSeed) {
        return "3%013d".formatted(uniqueSeed % 10_000_000_000_000L);
    }

    public String deviceId(long uniqueSeed) {
        return "device-" + Long.toHexString(uniqueSeed * 2654435761L);
    }

    public String address() {
        List<String> cities = List.of("Ташкент", "Самарканд", "Бухара", "Наманган", "Андижан", "Фергана");
        return pick(cities) + ", ул. " + (1 + random.nextInt(200));
    }

    private <T> T pick(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }
}
