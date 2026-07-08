package uz.caseintel.seed;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Выдаёт последовательные id для batch-инсертов, где мы САМИ указываем
 * id (а не полагаемся на BIGSERIAL/автоинкремент) — это нужно, чтобы
 * сразу знать id клиента/компании/счёта и использовать их для связей
 * (accounts.owner_id, transactions.from_account, relationships.source_id
 * и т.п.) без похода в БД за "какой id только что сгенерировался".
 *
 * Один allocator на таблицу — id начинаются с 1 и не пересекаются
 * с последовательностями Postgres (мы вставляем явные id, поэтому
 * ВАЖНО после сидирования подвинуть SEQUENCE каждой таблицы вперёд,
 * см. SeedRunner.fixSequencesAfterSeed(), иначе следующий обычный
 * INSERT через приложение (не seed) столкнётся с конфликтом id).
 */
public class SeedIdAllocator {
    private final AtomicLong counter = new AtomicLong(0);

    public long next() {
        return counter.incrementAndGet();
    }
}
