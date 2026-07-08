package uz.caseintel.llm.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Файловый кэш ответов LLM — на демо хакатона интернета может не быть.
 * См. AI_LAYER_ARCHITECTURE.md §5.
 *
 * Ключ: SHA-256 от (provider + model + systemPrompt + userPrompt).
 * Значение: ответ LLM, файл ./llm-cache/{hash}.txt.
 * Перед демо кэш "прогревается" на golden case — вся система работает
 * без сети.
 */
@Component
public class LlmResponseCache {

    private static final Path CACHE_DIR = Path.of("./llm-cache");

    public Optional<String> get(String provider, String model, String systemPrompt, String userPrompt) {
        Path file = cacheFile(provider, model, systemPrompt, userPrompt);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void put(String provider, String model, String systemPrompt, String userPrompt, String response) {
        Path file = cacheFile(provider, model, systemPrompt, userPrompt);
        try {
            Files.createDirectories(CACHE_DIR);
            Files.writeString(file, response, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Не удалось записать кэш (нет прав на диск и т.п.) — не критично,
            // просто следующий вызов снова пойдёт в реальный API.
        }
    }

    private Path cacheFile(String provider, String model, String systemPrompt, String userPrompt) {
        String hash = sha256(provider + "|" + model + "|" + systemPrompt + "|" + userPrompt);
        return CACHE_DIR.resolve(hash + ".txt");
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
