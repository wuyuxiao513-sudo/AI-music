package com.pujian.music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;

/** Submit a local ACE-Step job, poll its result, and retrieve a WAV. */
public class AceStepMusicClient {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Duration timeout;
    private final Duration interval;

    public AceStepMusicClient(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
            Duration.ofMinutes(60), Duration.ofSeconds(2));
    }
    AceStepMusicClient(ObjectMapper mapper, HttpClient client, Duration timeout, Duration interval) {
        this.mapper = mapper; this.client = client; this.timeout = timeout; this.interval = interval;
    }

    public byte[] generate(Song song, String endpoint, String model, String key, int seconds, Consumer<String> progress) {
        Path temp = null;
        try {
            temp = Files.createTempFile("acestep-", ".wav");
            generateToFile(song, endpoint, model, key, seconds, temp, progress);
            return Files.readAllBytes(temp);
        } catch (IOException error) { throw failure("无法保存 ACE-Step 音频"); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {} }
    }

    public Path generateToFile(Song song, String endpoint, String model, String key, int seconds, Path destination, Consumer<String> progress) {
        if (seconds < 10 || seconds > 600)
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACE-Step 单次生成时长为 10 到 600 秒");
        String base = endpoint.replaceAll("/+$", "");
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            progress.accept("正在向 ACE-Step 提交任务…");
            var data = post(base + "/release_task", Map.ofEntries(
                Map.entry("model", model), Map.entry("prompt", prompt(song)), Map.entry("lyrics", song.lyrics()),
                Map.entry("audio_duration", seconds), Map.entry("audio_format", "wav"),
                Map.entry("batch_size", 1), Map.entry("inference_steps", 8), Map.entry("thinking", false),
                Map.entry("sample_mode", false), Map.entry("use_format", false), Map.entry("use_cot_caption", false),
                Map.entry("use_cot_language", false), Map.entry("vocal_language", "zh"),
                Map.entry("bpm", 100), Map.entry("key_scale", "C Major"), Map.entry("time_signature", "4")
            ), key, deadline);
            String taskId = data.path("task_id").asText("");
            if (taskId.isBlank()) throw failure("ACE-Step 没有返回任务编号，请检查服务日志");
            progress.accept("ACE-Step 排队或生成中，请稍候…");
            while (System.nanoTime() < deadline) {
                var rows = post(base + "/query_result", Map.of("task_id_list", java.util.List.of(taskId)), key, deadline);
                if (!rows.isArray() || rows.isEmpty()) throw failure("ACE-Step 返回的任务状态无效");
                var row = rows.get(0);
                int state = row.path("status").asInt(-1);
                if (state == 2) throw failure("ACE-Step 生成失败，请检查本地服务日志及可用内存");
                if (state == 1) {
                    JsonNode result = row.path("result");
                    if (result.isTextual()) result = mapper.readTree(result.asText());
                    if (!result.isArray() || result.isEmpty()) throw failure("ACE-Step 没有返回音频文件");
                    URI uri = audioUri(base, result.get(0).path("file").asText(""));
                    progress.accept("正在下载并保存生成的音频…");
                    var response = client.send(request(uri, key, deadline).GET().build(), HttpResponse.BodyHandlers.ofFile(destination));
                    checkHttp(response.statusCode());
                    if (Files.size(destination) > 256L * 1024 * 1024) throw failure("生成音频超过单次 10 分钟 WAV 的文件大小范围");
                    byte[] bytes;
                    try (var input = Files.newInputStream(destination)) { bytes = input.readNBytes(12); }
                    if (bytes.length < 12 || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
                        || bytes[8] != 'W' || bytes[9] != 'A' || bytes[10] != 'V' || bytes[11] != 'E')
                        throw failure("ACE-Step 返回的文件不是有效 WAV 音频");
                    return destination;
                }
                if (state != 0) throw failure("ACE-Step 返回了未知任务状态");
                Thread.sleep(Math.min(interval.toMillis(), Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
            }
            throw timedOut();
        } catch (ApiException error) { throw error; }
        catch (HttpTimeoutException error) { throw timedOut(); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw failure("ACE-Step 请求已中断，本地任务可能仍在运行");
        } catch (IOException error) {
            throw failure("无法连接或读取 ACE-Step 服务，请先运行 D:\\ACE-Step-1.5\\start-local-api.bat，并检查服务日志");
        }
    }

    private JsonNode post(String url, Object payload, String key, long deadline) throws IOException, InterruptedException {
        var response = client.send(request(URI.create(url), key, deadline).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofString());
        checkHttp(response.statusCode());
        var body = mapper.readTree(response.body());
        if (body == null
            || body.path("code").asInt(200) != 200
            || (!body.path("error").isMissingNode() && !body.path("error").isNull()))
            throw failure("ACE-Step 请求失败，请查看本地服务日志");
        return body.path("data");
    }
    private HttpRequest.Builder request(URI uri, String key, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw timedOut();
        // Uvicorn does not support Java HttpClient's cleartext HTTP/2 upgrade.
        var builder = HttpRequest.newBuilder(uri).version(HttpClient.Version.HTTP_1_1)
            .timeout(Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(60).toNanos())));
        if (key != null && !key.isBlank()) builder.header("Authorization", "Bearer " + key);
        return builder;
    }
    static URI audioUri(String base, String path) {
        try {
            var origin = URI.create(base);
            var uri = origin.resolve(path);
            if (path.isBlank() || !origin.getScheme().equalsIgnoreCase(uri.getScheme())
                || !origin.getAuthority().equalsIgnoreCase(uri.getAuthority()) || uri.getUserInfo() != null
                || !"/v1/audio".equals(uri.getPath())) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException error) { throw failure("ACE-Step 返回了无效的音频下载地址"); }
    }
    private static String prompt(Song song) {
        String style = switch (song.style()) {
            case "流行" -> "pop"; case "说唱" -> "hip hop, rap"; case "古典" -> "classical";
            case "民谣" -> "folk"; case "电子" -> "electronic"; case "爵士" -> "jazz"; default -> song.style();
        };
        return style + ", Mandarin vocals, " + song.mood() + ", " + song.description() + ", " + song.prompt();
    }
    private static void checkHttp(int status) {
        if (status >= 200 && status < 300) return;
        if (status == 401 || status == 403) throw failure("ACE-Step 鉴权失败，请检查本地服务的 API Key");
        if (status == 404) throw failure("ACE-Step 接口不存在，请填写服务根地址，例如 http://127.0.0.1:8001");
        throw failure("ACE-Step 服务返回错误（" + status + "），请检查服务日志");
    }
    private static ApiException timedOut() { return failure("ACE-Step 等待超时，任务可能仍在本机运行，请查看服务日志后再重试"); }
    private static ApiException failure(String message) { return new ApiException(HttpStatus.BAD_GATEWAY, message); }
}
