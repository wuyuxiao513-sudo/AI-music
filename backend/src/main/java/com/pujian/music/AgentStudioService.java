package com.pujian.music;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgentStudioService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AiSettings settings;

    private ReActAgent.Builder configuredBuilder() {
        if (settings == null) return ReActAgent.builder().model(model);
        var config = settings.current();
        return ReActAgent.builder().model(io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
            .modelName(config.textModel()).baseUrl(config.textBaseUrl())
            .apiKey(config.textApiKey().isBlank() ? "local" : config.textApiKey()).build());
    }
    private final String model;

    public AgentStudioService(@Value("${studio.text-model}") String model) { this.model = model; }

    public boolean isReady() {
        if (settings != null) {
            var config = settings.current();
            String host;
            try {
                host = java.net.URI.create(config.textBaseUrl()).getHost();
            } catch (Exception ignored) {
                // 用户手填的 Base URL 非法时，不应让 /api/status 直接 500。
                return false;
            }
            return !config.textModel().isBlank() && (!config.textApiKey().isBlank()
                || "localhost".equals(host) || "127.0.0.1".equals(host));
        }
        if (model == null || model.isBlank()) return false;
        var provider = model.split(":", 2)[0];
        if (provider.equals("ollama")) return true;
        var keyName = switch (provider) {
            case "dashscope" -> "DASHSCOPE_API_KEY";
            case "openai" -> "OPENAI_API_KEY";
            default -> null;
        };
        return keyName != null && System.getenv(keyName) != null && !System.getenv(keyName).isBlank();
    }

    public LyricsResult generateLyrics(LyricsRequest request) {
        if (!isReady()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "歌词模型尚未配置，请打开左侧 AI 模型配置填写模型、接口地址和密钥。");
        var agent = configuredBuilder()
            .name("lyricist")
            .sysPrompt("你是一位中文原创歌曲作词人。写出自然、可演唱、有画面感的歌词；不要模仿现有歌曲，不要引用受版权保护的歌词。只返回指定格式，不要解释。")
            .build();
        try (agent) {
            var prompt = "为下列歌曲创作歌词。主题：" + request.theme() + "。风格：" + safe(request.style())
                + "。情绪：" + safe(request.mood()) + "。补充想法：" + safe(request.description())
                + "。请输出第一行“标题：歌名”，然后输出至少两段主歌和一段副歌，段落以[主歌1]、[副歌]等标记。总长度约 200 到 400 个汉字。";
            var result = agent.call(new UserMessage(prompt)).block();
            if (result == null || result.getTextContent() == null || result.getTextContent().isBlank())
                throw new ApiException(HttpStatus.BAD_GATEWAY, "歌词模型没有返回内容，请稍后重试");
            return parseLyrics(result.getTextContent(), request.theme());
        } catch (ApiException error) { throw error; }
        catch (Exception error) { throw new ApiException(HttpStatus.BAD_GATEWAY, "歌词生成失败，请检查模型配置或稍后重试"); }
    }

    public String buildArrangement(Song song) {
        var base = "风格：" + song.style() + "；情绪：" + song.mood() + "；主题：" + song.theme()
            + "；补充：" + song.description() + "；编曲要求：" + song.prompt();
        if (!isReady()) return base;
        var agent = configuredBuilder()
            .name("producer")
            .sysPrompt("你是一位音乐制作人。把歌曲要求整理成一句适合音乐生成模型的编曲提示词，包含曲风、速度、乐器、人声与情绪。只输出提示词，不要解释。")
            .build();
        try (agent) {
            var result = agent.call(new UserMessage(base)).block();
            return result == null || result.getTextContent() == null || result.getTextContent().isBlank()
                ? base : result.getTextContent().trim();
        } catch (Exception ignored) { return base; }
    }

    static LyricsResult parseLyrics(String text, String fallbackTitle) {
        var cleaned = text.trim().replaceFirst("^```(?:text)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        var firstBreak = cleaned.indexOf('\n');
        var first = firstBreak < 0 ? cleaned : cleaned.substring(0, firstBreak).trim();
        if (first.startsWith("标题：") || first.startsWith("标题:")) {
            var title = first.substring(3).trim();
            var lyrics = firstBreak < 0 ? "" : cleaned.substring(firstBreak + 1).trim();
            return new LyricsResult(title.isBlank() ? fallbackTitle : title, lyrics);
        }
        return new LyricsResult(fallbackTitle, cleaned);
    }

    private String safe(String value) { return value == null ? "" : value; }
}
