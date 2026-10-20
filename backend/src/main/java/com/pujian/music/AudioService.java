package com.pujian.music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AudioService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AiSettings settings;
    private final String endpoint;
    private final String apiKey;
    private final Path audioDir;
    private final SongStore songs;
    private final AgentStudioService agents;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final java.util.concurrent.ConcurrentHashMap<String, String> progress = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> generating = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean localGenerating = new java.util.concurrent.atomic.AtomicBoolean();

    public String providerName() {
        if (settings != null && settings.current().provider().equals("acestep")) return "ACE-Step 本地服务";
        return ElevenLabsMusicClient.isOfficial(settings == null ? endpoint : settings.current().musicEndpoint()) ? "ElevenLabs Music" : "自定义音乐服务";
    }
    public String providerId() { return settings == null ? "generic" : settings.current().provider(); }
    public int defaultDurationSeconds() { return settings == null ? 30 : settings.current().durationSeconds(); }
    public Map<String, Object> generationStatus(String id) {
        songs.find(id);
        return Map.of("running", generating.contains(id), "message", progress.getOrDefault(id, ""));
    }

    public AudioService(@Value("${studio.music-endpoint}") String endpoint,
                        @Value("${studio.music-api-key}") String apiKey,
                        @Value("${studio.data-dir}") String dataDir,
                        SongStore songs, AgentStudioService agents, ObjectMapper mapper) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.audioDir = Path.of(dataDir).toAbsolutePath().normalize().resolve("audio");
        this.songs = songs;
        this.agents = agents;
        this.mapper = mapper;
    }

    public boolean isReady() {
        var config = settings == null ? null : settings.current();
        String url = config == null ? endpoint : config.musicEndpoint();
        String key = config == null ? apiKey : config.musicApiKey();
        return url != null && !url.isBlank() && (!ElevenLabsMusicClient.isOfficial(url) || (key != null && !key.isBlank()));
    }

    public Song generate(String id) {
        return generate(id, null);
    }
    public Song generate(String id, Integer durationSeconds) {
        if (durationSeconds != null && (durationSeconds < 10 || durationSeconds > 600))
            throw new ApiException(HttpStatus.BAD_REQUEST, "生成时长必须为 10 到 600 秒");
        songs.find(id);
        if (!generating.add(id)) throw new ApiException(HttpStatus.CONFLICT, "这首歌曲正在生成，请等待完成");
        progress.put(id, "正在准备歌曲生成…");
        try {
            var result = generateInternal(id, durationSeconds);
            progress.put(id, "歌曲已生成");
            return result;
        } catch (RuntimeException error) {
            progress.put(id, "生成失败，请查看错误提示");
            throw error;
        } finally { generating.remove(id); }
    }

    private Song generateInternal(String id, Integer durationSeconds) {
        var song = songs.find(id);
        String configuredEndpoint = settings == null ? endpoint : settings.current().musicEndpoint();
        if (configuredEndpoint != null && !configuredEndpoint.isBlank()) {
            String host = null;
            try {
                host = URI.create(configuredEndpoint).getHost();
            } catch (Exception ignored) {
                // 非法 URL 由后续请求阶段暴露，这里不要直接抛 500。
            }
            if ("api.deepseek.com".equalsIgnoreCase(host))
                throw new ApiException(HttpStatus.BAD_REQUEST, "DeepSeek 请配置在歌词模型中。歌曲音频需要单独接入音乐生成服务。");
        }
        if (song.lyrics().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "请先写好歌词");
        if (!isReady()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "请在 AI 模型配置中选择音乐服务并保存；ElevenLabs 需要 API Key，ACE-Step 需要启动本地服务。");
        try {
            var config = settings == null ? null : settings.current();
            var endpoint = config == null ? this.endpoint : config.musicEndpoint();
            var apiKey = config == null ? this.apiKey : config.musicApiKey();
            if (config != null && config.provider().equals("acestep")) {
                if (!localGenerating.compareAndSet(false, true))
                    throw new ApiException(HttpStatus.CONFLICT, "本地模型正在生成其他歌曲，请稍后再试");
                Path temp = null;
                try {
                    Files.createDirectories(audioDir);
                    temp = Files.createTempFile(audioDir, "generation-", ".wav");
                    new AceStepMusicClient(mapper).generateToFile(song, endpoint, config.musicModel(), apiKey,
                        durationSeconds == null ? config.durationSeconds() : durationSeconds,
                        temp, message -> progress.put(id, message));
                    Files.move(temp, audioDir.resolve(id + ".wav"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    // Remove old formats only after the new WAV has been saved successfully.
                    for (var ext : new String[] { "mp3", "ogg", "m4a" }) Files.deleteIfExists(audioDir.resolve(id + "." + ext));
                    return songs.setAudio(id, "/api/songs/" + id + "/audio?v=" + System.currentTimeMillis());
                } finally {
                    localGenerating.set(false);
                    if (temp != null) Files.deleteIfExists(temp);
                }
            }
            progress.put(id, "正在编曲并生成歌曲…");
            var prompt = agents.buildArrangement(song);
            if (ElevenLabsMusicClient.isOfficial(endpoint)) {
                var musicModel = config == null || config.musicModel().isBlank() ? "music_v2_5" : config.musicModel();
                return saveAudio(id, new ElevenLabsMusicClient(mapper).generate(song, musicModel, apiKey, prompt), "mp3");
            }
            var payload = mapper.writeValueAsString(Map.of(
                "title", song.title(), "style", song.style(), "mood", song.mood(),
                "theme", song.theme(), "description", song.description(),
                "lyrics", song.lyrics(), "prompt", prompt, "model", config == null ? "" : config.musicModel()));
            var builder = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofMinutes(4))
                .header("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
            var response = client.send(builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                if (response.statusCode() == 404)
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "歌曲生成接口不存在（404）。请填写音乐服务的完整生成地址，而非模型平台的 Base URL。");
                else
                throw new ApiException(HttpStatus.BAD_GATEWAY, "歌曲服务返回错误 (" + response.statusCode() + ")");
            var body = mapper.readTree(response.body());
            var encoded = body.path("audioBase64").asText("");
            if (!encoded.isBlank()) {
                if (encoded.startsWith("data:")) encoded = encoded.substring(encoded.indexOf(',') + 1);
                var bytes = Base64.getDecoder().decode(encoded);
                if (bytes.length > 30 * 1024 * 1024) throw new ApiException(HttpStatus.BAD_GATEWAY, "生成的音频超过 30 MB");
                return saveAudio(song.id(), bytes, "mp3");
            }
            var url = body.path("audioUrl").asText("");
            if (url.startsWith("https://") || url.startsWith("http://")) return songs.setAudio(id, url);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "歌曲服务没有返回 audioBase64 或 audioUrl");
        } catch (ApiException error) { throw error; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new ApiException(HttpStatus.BAD_GATEWAY, "歌曲生成已中断"); }
        catch (Exception error) { throw new ApiException(HttpStatus.BAD_GATEWAY, "歌曲生成失败，请检查服务接口配置"); }
    }

    public Song upload(String id, MultipartFile file) {
        songs.find(id);
        if (file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "请选择音频文件");
        if (file.getSize() > 30L * 1024 * 1024) throw new ApiException(HttpStatus.BAD_REQUEST, "音频文件不能超过 30 MB");
        var type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        var ext = switch (type) {
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav", "audio/x-wav", "audio/wave" -> "wav";
            case "audio/ogg" -> "ogg";
            case "audio/mp4", "audio/x-m4a" -> "m4a";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "仅支持 MP3、WAV、OGG 或 M4A 音频");
        };
        try { return saveAudio(id, file.getBytes(), ext); }
        catch (IOException error) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "保存音频失败"); }
    }

    public AudioFile read(String id) {
        var file = resource(id);
        try { return new AudioFile(Files.readAllBytes(file.path()), file.extension()); }
        catch (IOException error) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "读取音频失败"); }
    }

    public AudioResource resource(String id) {
        var song = songs.find(id);
        if (song.audioUrl() == null || !song.audioUrl().startsWith("/api/songs/"))
            throw new ApiException(HttpStatus.NOT_FOUND, "本地音频不存在");
        for (var ext : new String[] { "mp3", "wav", "ogg", "m4a" }) {
            var file = audioDir.resolve(id + "." + ext);
            if (Files.exists(file)) {
                return new AudioResource(file, ext);
            }
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "本地音频不存在");
    }

    public void delete(String id) {
        for (var ext : new String[] { "mp3", "wav", "ogg", "m4a" }) {
            try { Files.deleteIfExists(audioDir.resolve(id + "." + ext)); }
            catch (IOException ignored) { }
        }
    }

    private Song saveAudio(String id, byte[] bytes, String ext) {
        try {
            Files.createDirectories(audioDir);
            delete(id);
            Files.write(audioDir.resolve(id + "." + ext), bytes);
            return songs.setAudio(id, "/api/songs/" + id + "/audio?v=" + System.currentTimeMillis());
        } catch (IOException error) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "保存音频失败"); }
    }

    public record AudioFile(byte[] bytes, String extension) {}
    public record AudioResource(Path path, String extension) {}
}
