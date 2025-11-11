package com.example.asr.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LlmService {
    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.llm.model:gpt-3.5-turbo}")
    private String model;

    @Value("${openai.base-url}")
    private String baseUrl;

    @Autowired
    private TtsSynthesisService ttsService;

    // OpenAI 客户端（延迟初始化）
    private OpenAIClient openAIClient;

    // 会话上下文管理：存储每个会话的历史消息（使用 ChatCompletionMessageParam 以便支持用户和助手消息）
    private final Map<String, List<ChatCompletionMessageParam>> sessionContexts = new ConcurrentHashMap<>();

    /**
     * 获取或创建 OpenAI 客户端
     */
    private OpenAIClient getClient() {
        if (openAIClient == null) {
            synchronized (this) {
                if (openAIClient == null) {
                    openAIClient = OpenAIOkHttpClient.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .build();
                    log.info("OpenAI client initialized");
                }
            }
        }
        return openAIClient;
    }

    public String classifyIntent(String text) {
        try {
            OpenAIClient client = getClient();
            
            String intentPrompt = "你是一个意图识别助手。请判断用户的意图是「闲聊」还是「下单」。" +
                    "如果用户想要购买、订购商品，请回复「下单」；否则回复「闲聊」。\n\n" +
                    "用户输入：" + text + "\n\n请只回复「闲聊」或「下单」，不要有其他内容。";
            
            ChatCompletionCreateParams createParams = ChatCompletionCreateParams.builder()
                    .model(model)  // 直接使用字符串，或者使用 ChatModel.of(model)
                    .addSystemMessage("你是一个精准的意图识别系统。")
                    .addUserMessage(intentPrompt)
                    .maxCompletionTokens(50)
                    .build();
            
            ChatCompletion result = client.chat().completions().create(createParams);
            String intent = result.choices().stream()
                    .flatMap(choice -> choice.message().content().stream())
                    .collect(Collectors.joining())
                    .trim();
            
            log.info("Intent classification for '{}': {}", text, intent);
            
            // 简单规范化
            if (intent.contains("下单")) {
                return "order";
            } else {
                return "chitchat";
            }
        } catch (Exception e) {
            log.error("Intent classification failed", e);
            return "chitchat"; // 默认闲聊
        }
    }

    public void streamGenerate(String sessionId, String prompt, Consumer<String> onTextChunk) {
        try {
            OpenAIClient client = getClient();
            
            // 获取或初始化会话上下文
            List<ChatCompletionMessageParam> messages = sessionContexts.computeIfAbsent(sessionId, k -> new ArrayList<>());
            
            // 构建请求参数
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(model)  // 直接使用字符串，或者使用 ChatModel.of(model)
                    .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
                    .maxCompletionTokens(2048);
            
            // 添加历史消息
            messages.forEach(paramsBuilder::addMessage);
            
            // 创建并添加新的用户消息
            ChatCompletionUserMessageParam userMessage = ChatCompletionUserMessageParam.builder()
                    .content(ChatCompletionUserMessageParam.Content.ofText(prompt))
                    .build();
            paramsBuilder.addUserMessage(prompt);
            
            ChatCompletionCreateParams createParams = paramsBuilder.build();
            
            log.info("Starting LLM stream generation for session: {}", sessionId);
            
            // 使用 ChatCompletionAccumulator 来累积流式响应
            ChatCompletionAccumulator accumulator = ChatCompletionAccumulator.create();
            
            // 流式调用
            try (StreamResponse<ChatCompletionChunk> streamResponse = 
                    client.chat().completions().createStreaming(createParams)) {
                
                streamResponse.stream()
                        .peek(accumulator::accumulate)  // 累积每个 chunk
                        .flatMap(completion -> completion.choices().stream())
                        .flatMap(choice -> choice.delta().content().stream())
                        .forEach(text -> {
                            if (text != null && !text.isEmpty()) {
                                if (onTextChunk != null) {
                                    onTextChunk.accept(text);
                                }
                                log.debug("LLM chunk: {}", text);
                            }
                        });
            }
            
            // 获取完整的 ChatCompletion
            ChatCompletion chatCompletion = accumulator.chatCompletion();
            
            // 保存用户消息和助手消息到上下文
            // 1. 保存用户消息
            messages.add(ChatCompletionMessageParam.ofUser(userMessage));
            
            // 2. 保存助手消息（从 ChatCompletion 中获取）
            if (chatCompletion != null && !chatCompletion.choices().isEmpty()) {
                ChatCompletionMessage assistantMessage = chatCompletion.choices().get(0).message();
                // 使用 toParam() 转换为 ChatCompletionAssistantMessageParam，然后包装为 ChatCompletionMessageParam
                messages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage.toParam()));
            }
            
            log.info("LLM generation completed for session: {}", sessionId);
            
        } catch (Exception e) {
            log.error("LLM stream generation failed for session: {}", sessionId, e);
            if (onTextChunk != null) {
                onTextChunk.accept("抱歉，我遇到了一些问题，请稍后再试。");
            }
        }
    }

    public void clearContext(String sessionId) {
        sessionContexts.remove(sessionId);
        log.info("Cleared context for session: {}", sessionId);
    }
}
