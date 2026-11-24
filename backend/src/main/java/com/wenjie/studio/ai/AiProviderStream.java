package com.wenjie.studio.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** 单次供应商响应的有界解析器；暂态文字不等于通过用途及引用校验的候选。 */
final class AiProviderStream implements HttpResponse.BodySubscriber<byte[]> {
    static final int MAX_WIRE = 4 * 1024 * 1024;
    private static final int MAX_FRAME = 512 * 1024;
    private static final int MAX_TEXT = 256 * 1024;
    private final JsonMapper json;
    private final boolean responses;
    private final Consumer<String> preview;
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private final StringBuilder text = new StringBuilder();
    private final List<OutputItem> items = new ArrayList<>();
    private Flow.Subscription subscription;
    private int wireBytes, frameBytes, textBytes;
    private boolean firstLine = true, previousCr, terminal, assistant, chatFinished, inProgress, usageUncertain;
    private long sequence = -1;
    private String eventName, id, failureCode, finishReason;
    private JsonNode usage, response;
    private OutputItem message;

    AiProviderStream(JsonMapper json, boolean responses, Consumer<String> preview) {
        this.json = json;
        this.responses = responses;
        this.preview = preview;
    }

    @Override public CompletionStage<byte[]> getBody() { return body; }

    @Override public synchronized void onSubscribe(Flow.Subscription incoming) {
        if (subscription != null || body.isDone()) { incoming.cancel(); return; }
        subscription = incoming;
        incoming.request(1);
    }

    @Override public synchronized void onNext(List<ByteBuffer> chunks) {
        if (body.isDone()) return;
        try {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > MAX_WIRE - wireBytes) throw rejected("AI_RESPONSE_TOO_LARGE");
                wireBytes += chunk.remaining();
                while (chunk.hasRemaining()) {
                    int value = Byte.toUnsignedInt(chunk.get());
                    if (previousCr && value == '\n') { previousCr = false; continue; }
                    previousCr = false;
                    if (value == '\r' || value == '\n') {
                        acceptLine();
                        previousCr = value == '\r';
                    } else {
                        if (line.size() >= MAX_FRAME) throw rejected("AI_RESPONSE_TOO_LARGE");
                        line.write(value);
                    }
                }
            }
            subscription.request(1);
        } catch (StreamRejected exception) { fail(exception.code); }
        catch (RuntimeException exception) { fail("AI_STREAM_INVALID"); }
    }

    @Override public synchronized void onError(Throwable error) { fail("AI_NETWORK_UNKNOWN"); }

    @Override public synchronized void onComplete() {
        if (body.isDone()) return;
        // EOF 不补造事件分隔符或终态；截断正文永远不能进入候选校验。
        if (!terminal || line.size() != 0 || data.length() != 0 || eventName != null) {
            fail("AI_STREAM_INCOMPLETE");
            return;
        }
        try { body.complete(json.writeValueAsBytes(envelope())); }
        catch (RuntimeException exception) { fail("AI_STREAM_INVALID"); }
    }

    synchronized String failureCode() { return failureCode; }

    synchronized JsonNode accountingSnapshot() {
        ObjectNode snapshot = json.createObjectNode();
        if (id != null) snapshot.put("id", id);
        if (usage != null && !usageUncertain) snapshot.set("usage", usage);
        if (responses) {
            var output = snapshot.putArray("output");
            if (!items.isEmpty() || !text.isEmpty()) output.addObject().put("type", "message");
        } else {
            snapshot.putArray("choices").addObject().putObject("message").put("content", text.toString());
        }
        return snapshot;
    }

    synchronized void cancel() {
        body.cancel(true);
        if (subscription != null) subscription.cancel();
    }

    private void fail(String code) {
        if (body.isDone()) return;
        if (failureCode == null) failureCode = code;
        if (subscription != null) subscription.cancel();
        body.completeExceptionally(new IllegalStateException(code));
    }

    private void acceptLine() {
        byte[] raw = line.toByteArray();
        line.reset();
        String value;
        try {
            // 换行字节不会出现在 UTF-8 多字节字符内部；整行解码保留跨网络块字符并拒绝坏编码。
            value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) { throw rejected("AI_STREAM_INVALID"); }
        if (firstLine && value.startsWith("\uFEFF")) value = value.substring(1);
        firstLine = false;
        if (value.isEmpty()) {
            if (!data.isEmpty()) acceptEvent();
            else if (eventName != null) throw rejected("AI_STREAM_INVALID");
            data.setLength(0);
            eventName = null;
            frameBytes = 0;
            return;
        }
        if (raw.length + 1 > MAX_FRAME - frameBytes) throw rejected("AI_RESPONSE_TOO_LARGE");
        frameBytes += raw.length + 1;
        if (value.startsWith(":")) return;
        int separator = value.indexOf(':');
        String name = separator < 0 ? value : value.substring(0, separator);
        String content = separator < 0 ? "" : value.substring(separator + 1);
        if (content.startsWith(" ")) content = content.substring(1);
        switch (name) {
            case "data" -> {
                data.append(content).append('\n');
            }
            case "event" -> {
                if (eventName != null || content.isEmpty() || content.length() > 100) throw rejected("AI_STREAM_INVALID");
                eventName = content;
            }
            case "id" -> {
                if (content.length() > 200 || content.indexOf('\0') >= 0) throw rejected("AI_STREAM_INVALID");
            }
            case "retry" -> {
                if (!content.matches("[0-9]{1,9}")) throw rejected("AI_STREAM_INVALID");
            }
            default -> throw rejected("AI_STREAM_INVALID");
        }
    }

    private void acceptEvent() {
        if (terminal) throw rejected("AI_STREAM_INVALID");
        String value = data.substring(0, data.length() - 1);
        if (!responses && value.equals("[DONE]")) {
            if (eventName != null && !eventName.equals("message") || id == null || !assistant || !chatFinished) {
                throw rejected("AI_STREAM_INVALID");
            }
            terminal = true;
            return;
        }
        JsonNode root = json.readTree(value);
        object(root);
        if (responses) acceptResponse(root);
        else acceptChat(root);
    }

    private void acceptChat(JsonNode root) {
        if (eventName != null && !eventName.equals("message")) throw rejected("AI_STREAM_INVALID");
        if (present(root, "error")) throw rejected("AI_PROVIDER_ERROR");
        String nextId = identifier(root.get("id"));
        if (id == null) id = nextId;
        if (!id.equals(nextId) || !"chat.completion.chunk".equals(string(root.get("object")))) throw rejected("AI_STREAM_INVALID");
        JsonNode choices = root.get("choices");
        array(choices, 0, 1);
        if (choices.isEmpty()) {
            if (!chatFinished || !present(root, "usage")) throw rejected("AI_STREAM_INVALID");
            captureUsage(root.get("usage"));
            return;
        }
        if (chatFinished) throw rejected("AI_STREAM_INVALID");
        JsonNode choice = choices.get(0);
        object(choice);
        if (integer(choice.get("index")) != 0) throw rejected("AI_STREAM_INVALID");
        JsonNode delta = choice.get("delta");
        object(delta);
        if (present(delta, "role")) {
            if (!"assistant".equals(string(delta.get("role")))) throw rejected("AI_STREAM_INVALID");
            assistant = true;
        }
        if (present(delta, "tool_calls") || present(delta, "function_call")) rejectOutput("AI_TOOL_CALL_REJECTED");
        if (present(delta, "refusal")) rejectOutput("AI_OUTPUT_REFUSED");
        if (delta.has("content") && !delta.get("content").isNull()) {
            if (!assistant) throw rejected("AI_STREAM_INVALID");
            append(string(delta.get("content")));
        }
        if (present(choice, "finish_reason")) {
            if (!assistant) throw rejected("AI_STREAM_INVALID");
            finishReason = string(choice.get("finish_reason"));
            chatFinished = true;
            if (!"stop".equals(finishReason)) rejectOutput("length".equals(finishReason) ? "AI_OUTPUT_TRUNCATED" : "AI_OUTPUT_INCOMPLETE");
        }
        // 某些兼容端点把最终用量放在结束块；身份与结束条件核对后仍可保留真实账单。
        if (present(root, "usage")) {
            if (!chatFinished) throw rejected("AI_STREAM_INVALID");
            captureUsage(root.get("usage"));
        }
    }

    private void acceptResponse(JsonNode root) {
        String type = string(root.get("type"));
        if (eventName != null && !eventName.equals(type)) throw rejected("AI_STREAM_INVALID");
        long next = sequence(root.get("sequence_number"));
        if (next <= sequence) throw rejected("AI_STREAM_INVALID");
        sequence = next;
        if (type.equals("response.created")) {
            if (id != null) throw rejected("AI_STREAM_INVALID");
            JsonNode created = root.get("response");
            object(created);
            id = identifier(created.get("id"));
            String status = string(created.get("status"));
            if (!status.equals("queued") && !status.equals("in_progress")) throw rejected("AI_STREAM_INVALID");
            if (created.has("output")) array(created.get("output"), 0, 0);
            return;
        }
        if (id == null) throw rejected("AI_STREAM_INVALID");
        if (root.has("response_id") && !id.equals(identifier(root.get("response_id")))) throw rejected("AI_STREAM_INVALID");
        switch (type) {
            case "response.in_progress" -> {
                if (inProgress || !items.isEmpty()) throw rejected("AI_STREAM_INVALID");
                JsonNode active = root.get("response");
                responseIdentity(active);
                if (!"in_progress".equals(string(active.get("status")))) throw rejected("AI_STREAM_INVALID");
                inProgress = true;
            }
            case "response.output_item.added" -> addItem(root);
            case "response.content_part.added" -> addPart(root);
            case "response.output_text.delta" -> {
                OutputPart part = part(root);
                if (!part.type.equals("output_text") || part.textDone || part.done) throw rejected("AI_STREAM_INVALID");
                String delta = string(root.get("delta"));
                append(delta);
                part.text.append(delta);
            }
            case "response.output_text.done" -> {
                OutputPart part = part(root);
                if (!part.type.equals("output_text") || part.textDone || part.done
                        || !part.text.toString().equals(string(root.get("text")))) throw rejected("AI_STREAM_INVALID");
                part.textDone = true;
            }
            case "response.content_part.done" -> {
                OutputPart part = part(root);
                if (!part.textDone || part.done) throw rejected("AI_STREAM_INVALID");
                checkPart(root.get("part"), part);
                part.done = true;
            }
            case "response.output_item.done" -> {
                OutputItem item = item(root, false);
                if (item.done) throw rejected("AI_STREAM_INVALID");
                checkItem(root.get("item"), item);
                item.done = true;
            }
            case "response.reasoning_summary_part.added", "response.reasoning_summary_part.done",
                    "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done",
                    "response.reasoning_text.delta", "response.reasoning_text.done" -> {
                OutputItem item = item(root, true);
                if (!item.type.equals("reasoning") || item.done) throw rejected("AI_STREAM_INVALID");
                // 推理片段只核绑定，不写缓冲、候选或日志。
                if (root.has("summary_index")) integer(root.get("summary_index"));
                if (root.has("content_index")) integer(root.get("content_index"));
                if (type.endsWith(".delta")) string(root.get("delta"));
                else if (type.endsWith("_text.done")) string(root.get("text"));
                else object(root.get("part"));
            }
            case "response.completed", "response.incomplete", "response.failed" -> finishResponse(root, type);
            case "response.refusal.delta" -> {
                OutputPart part = part(root);
                if (!part.type.equals("refusal") || part.textDone || part.done) throw rejected("AI_STREAM_INVALID");
                String fragment = string(root.get("delta"));
                if (fragment.length() > MAX_TEXT - part.text.length()) throw rejected("AI_RESPONSE_TOO_LARGE");
                part.text.append(fragment);
            }
            case "response.refusal.done" -> {
                OutputPart part = part(root);
                if (!part.type.equals("refusal") || part.textDone || part.done
                        || !part.text.toString().equals(string(root.get("refusal")))) throw rejected("AI_STREAM_INVALID");
                part.textDone = true;
            }
            case "error" -> { rejectOutput("AI_PROVIDER_ERROR"); terminal = true; }
            default -> throw rejected("AI_STREAM_INVALID");
        }
    }

    private void addItem(JsonNode root) {
        int index = integer(root.get("output_index"));
        if (index != items.size() || index >= 16) throw rejected("AI_STREAM_INVALID");
        JsonNode value = root.get("item");
        object(value);
        String itemId = identifier(value.get("id")), type = string(value.get("type"));
        if (items.stream().anyMatch(item -> item.id.equals(itemId))) throw rejected("AI_STREAM_INVALID");
        OutputItem item = new OutputItem(itemId, type);
        if (type.equals("message")) {
            if (message != null || !"assistant".equals(string(value.get("role")))
                    || !"in_progress".equals(string(value.get("status")))) throw rejected("AI_STREAM_INVALID");
            array(value.get("content"), 0, 0);
            message = item;
        } else if (!type.equals("reasoning")) throw rejected("AI_TOOL_CALL_REJECTED");
        items.add(item);
    }

    private void addPart(JsonNode root) {
        OutputItem item = item(root, true);
        if (item != message || item.done || item.parts.size() >= 16
                || integer(root.get("content_index")) != item.parts.size()
                || !item.parts.isEmpty() && !item.parts.getLast().done) throw rejected("AI_STREAM_INVALID");
        JsonNode part = root.get("part");
        object(part);
        String type = string(part.get("type"));
        if (type.equals("refusal")) rejectOutput("AI_OUTPUT_REFUSED");
        else if (!type.equals("output_text")) throw rejected("AI_STREAM_INVALID");
        if (!"".equals(string(part.get(type.equals("refusal") ? "refusal" : "text")))) throw rejected("AI_STREAM_INVALID");
        item.parts.add(new OutputPart(type));
    }

    private OutputItem item(JsonNode root, boolean itemIdRequired) {
        int index = integer(root.get("output_index"));
        if (index >= items.size()) throw rejected("AI_STREAM_INVALID");
        OutputItem item = items.get(index);
        if (itemIdRequired && !item.id.equals(identifier(root.get("item_id")))) throw rejected("AI_STREAM_INVALID");
        return item;
    }

    private OutputPart part(JsonNode root) {
        OutputItem item = item(root, true);
        int index = integer(root.get("content_index"));
        if (item != message || item.done || index >= item.parts.size() || index != item.parts.size() - 1) throw rejected("AI_STREAM_INVALID");
        return item.parts.get(index);
    }

    private void finishResponse(JsonNode root, String type) {
        JsonNode completed = root.get("response");
        responseIdentity(completed);
        if (present(completed, "usage")) captureUsage(completed.get("usage"));
        String status = string(completed.get("status"));
        if (!type.equals("response." + status)) throw rejected("AI_STREAM_INVALID");
        if (!status.equals("completed")) {
            rejectOutput(status.equals("failed") ? "AI_PROVIDER_ERROR"
                    : "max_output_tokens".equals(nullableString(completed.path("incomplete_details").get("reason")))
                        ? "AI_OUTPUT_TRUNCATED" : "AI_OUTPUT_INCOMPLETE");
        } else {
            JsonNode output = completed.get("output");
            array(output, 1, 16);
            if (message == null || output.size() != items.size()) throw rejected("AI_STREAM_INVALID");
            for (int index = 0; index < items.size(); index++) {
                OutputItem item = items.get(index);
                if (!item.done) throw rejected("AI_STREAM_INVALID");
                checkItem(output.get(index), item);
            }
        }
        // 完整响应只留在本次解析生命周期；发布仍经过网关原有全部用途及引用校验。
        response = completed;
        terminal = true;
    }

    private void responseIdentity(JsonNode value) {
        object(value);
        if (!id.equals(identifier(value.get("id")))) throw rejected("AI_STREAM_INVALID");
    }

    private void checkItem(JsonNode value, OutputItem item) {
        object(value);
        if (!item.id.equals(identifier(value.get("id"))) || !item.type.equals(string(value.get("type")))) throw rejected("AI_STREAM_INVALID");
        if (item.type.equals("reasoning")) return;
        if (!"assistant".equals(string(value.get("role"))) || !"completed".equals(string(value.get("status")))) throw rejected("AI_STREAM_INVALID");
        JsonNode parts = value.get("content");
        array(parts, 1, 16);
        if (parts.size() != item.parts.size()) throw rejected("AI_STREAM_INVALID");
        for (int index = 0; index < parts.size(); index++) {
            OutputPart part = item.parts.get(index);
            if (!part.done || !part.textDone) throw rejected("AI_STREAM_INVALID");
            checkPart(parts.get(index), part);
        }
    }

    private void checkPart(JsonNode value, OutputPart part) {
        object(value);
        if (!part.type.equals(string(value.get("type")))
                || !part.text.toString().equals(string(value.get(part.type.equals("refusal") ? "refusal" : "text")))) {
            throw rejected("AI_STREAM_INVALID");
        }
    }

    private void captureUsage(JsonNode value) {
        if (usageUncertain || usage != null && !usage.equals(value)) {
            // 互相矛盾的用量不能挑一份结算，后续回执也不能恢复本次自动核账资格。
            usageUncertain = true;
            usage = null;
            throw rejected("AI_STREAM_INVALID");
        }
        object(value);
        usage = value;
    }

    private void append(String fragment) {
        int length = fragment.getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_TEXT - textBytes) throw rejected("AI_RESPONSE_TOO_LARGE");
        textBytes += length;
        text.append(fragment);
        if (!fragment.isEmpty() && failureCode == null) {
            try { preview.accept(fragment); }
            catch (RuntimeException exception) { throw rejected("AI_STREAM_INVALID"); }
        }
    }

    private void rejectOutput(String code) { if (failureCode == null) failureCode = code; }

    private JsonNode envelope() {
        if (responses && response != null) return response;
        ObjectNode root = json.createObjectNode();
        if (id != null) root.put("id", id);
        if (usage != null) root.set("usage", usage);
        if (!responses) {
            var choice = root.putArray("choices").addObject();
            choice.put("index", 0);
            choice.put("finish_reason", finishReason);
            choice.putObject("message").put("role", "assistant").put("content", text.toString());
        }
        return root;
    }

    private static void object(JsonNode value) { if (value == null || !value.isObject()) throw rejected("AI_STREAM_INVALID"); }
    private static void array(JsonNode value, int min, int max) {
        if (value == null || !value.isArray() || value.size() < min || value.size() > max) throw rejected("AI_STREAM_INVALID");
    }
    private static int integer(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw rejected("AI_STREAM_INVALID");
        return value.intValue();
    }
    private static long sequence(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0 || value.longValue() > 9_007_199_254_740_991L) throw rejected("AI_STREAM_INVALID");
        return value.longValue();
    }
    private static String identifier(JsonNode value) {
        String id = string(value);
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw rejected("AI_STREAM_INVALID");
        return id;
    }
    private static String nullableString(JsonNode value) { return value != null && value.isTextual() ? value.stringValue() : null; }
    private static String string(JsonNode value) {
        String text = nullableString(value);
        if (text == null) throw rejected("AI_STREAM_INVALID");
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index == text.length() || !Character.isLowSurrogate(text.charAt(index))) throw rejected("AI_STREAM_INVALID");
            } else if (Character.isLowSurrogate(current)) throw rejected("AI_STREAM_INVALID");
        }
        return text;
    }
    private static boolean present(JsonNode value, String name) {
        JsonNode field = value.get(name);
        return field != null && !field.isNull() && !(field.isArray() && field.isEmpty());
    }
    private static StreamRejected rejected(String code) { return new StreamRejected(code); }
    private static final class StreamRejected extends RuntimeException {
        private final String code;
        private StreamRejected(String code) { super(code, null, false, false); this.code = code; }
    }
    private static final class OutputItem {
        private final String id, type;
        private final List<OutputPart> parts = new ArrayList<>();
        private boolean done;
        private OutputItem(String id, String type) { this.id = id; this.type = type; }
    }
    private static final class OutputPart {
        private final String type;
        private final StringBuilder text = new StringBuilder();
        private boolean textDone, done;
        private OutputPart(String type) { this.type = type; }
    }
}
