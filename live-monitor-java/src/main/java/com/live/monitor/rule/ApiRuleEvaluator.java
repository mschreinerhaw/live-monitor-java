package com.live.monitor.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ApiRuleEvaluator {
    private static final int MAX_RULE_LENGTH = 1000;
    private static final int MAX_BODY_PARSE_CHARS = 262144;
    private static final int MAX_JSON_PATH_DEPTH = 32;
    private static final int MAX_REGEX_PATTERN_LENGTH = 300;
    private static final int MAX_REGEX_INPUT_CHARS = 8192;
    private static final long MAX_EVALUATION_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final long MAX_REGEX_MILLIS = 100;
    private static final ExecutorService REGEX_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "api-rule-regex");
            thread.setDaemon(true);
            return thread;
        }
    });

    private final ObjectMapper objectMapper;

    public ApiRuleEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Evaluation evaluate(String expression, ResponseContext context) {
        if (!StringUtils.hasText(expression)) {
            return Evaluation.matched("no api assertion configured");
        }
        if (expression.length() > MAX_RULE_LENGTH) {
            return Evaluation.failed("api assertion error: rule length exceeds " + MAX_RULE_LENGTH);
        }
        if (context != null && context.body.length() > MAX_BODY_PARSE_CHARS) {
            return Evaluation.failed("api assertion error: response body exceeds " + MAX_BODY_PARSE_CHARS + " chars");
        }
        long started = System.nanoTime();
        try {
            ApiRuleLexer lexer = new ApiRuleLexer(CharStreams.fromString(expression));
            lexer.removeErrorListeners();
            lexer.addErrorListener(ThrowingErrorListener.INSTANCE);
            ApiRuleParser parser = new ApiRuleParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            parser.addErrorListener(ThrowingErrorListener.INSTANCE);
            Visitor visitor = new Visitor(context, started + MAX_EVALUATION_NANOS);
            Value value = visitor.visit(parser.parse());
            boolean matched = truthy(value);
            return new Evaluation(
                matched,
                matched ? "api assertion matched" : "api assertion failed: " + expression,
                matched ? firstText(value.detail, visitor.hitContent) : null,
                matched ? null : firstText(visitor.failureReason, "expression returned false"),
                elapsedMs(started)
            );
        } catch (Exception ex) {
            return Evaluation.failed("api assertion error: " + ex.getMessage(), elapsedMs(started));
        }
    }

    public static class ResponseContext {
        private final int statusCode;
        private final int responseTimeMs;
        private final String body;
        private JsonNode jsonBody;
        private boolean jsonParsed;

        public ResponseContext(int statusCode, int responseTimeMs, String body) {
            this.statusCode = statusCode;
            this.responseTimeMs = responseTimeMs;
            this.body = body == null ? "" : body;
        }
    }

    public static class Evaluation {
        public final boolean matched;
        public final String message;
        public final String hitContent;
        public final String failureReason;
        public final long executionTimeMs;

        private Evaluation(boolean matched, String message, String hitContent, String failureReason, long executionTimeMs) {
            this.matched = matched;
            this.message = message;
            this.hitContent = hitContent;
            this.failureReason = failureReason;
            this.executionTimeMs = executionTimeMs;
        }

        private static Evaluation matched(String message) {
            return new Evaluation(true, message, null, null, 0L);
        }

        private static Evaluation failed(String message) {
            return new Evaluation(false, message, null, message, 0L);
        }

        private static Evaluation failed(String message, long executionTimeMs) {
            return new Evaluation(false, message, null, message, executionTimeMs);
        }
    }

    private class Visitor extends ApiRuleBaseVisitor<Value> {
        private final ResponseContext context;
        private final long deadlineNanos;
        private String hitContent;
        private String failureReason;

        private Visitor(ResponseContext context, long deadlineNanos) {
            this.context = context == null ? new ResponseContext(0, 0, "") : context;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public Value visitParse(ApiRuleParser.ParseContext ctx) {
            return visit(ctx.expression());
        }

        @Override
        public Value visitOrExpression(ApiRuleParser.OrExpressionContext ctx) {
            checkBudget();
            Value result = visit(ctx.andExpression(0));
            for (int i = 1; i < ctx.andExpression().size(); i++) {
                if (truthy(result)) {
                    return Value.bool(true);
                }
                result = Value.bool(truthy(visit(ctx.andExpression(i))));
            }
            return result;
        }

        @Override
        public Value visitAndExpression(ApiRuleParser.AndExpressionContext ctx) {
            checkBudget();
            Value result = visit(ctx.unaryExpression(0));
            for (int i = 1; i < ctx.unaryExpression().size(); i++) {
                if (!truthy(result)) {
                    return Value.bool(false);
                }
                result = Value.bool(truthy(visit(ctx.unaryExpression(i))));
            }
            return result;
        }

        @Override
        public Value visitUnaryExpression(ApiRuleParser.UnaryExpressionContext ctx) {
            checkBudget();
            if (ctx.NOT() != null) {
                return Value.bool(!truthy(visit(ctx.unaryExpression())));
            }
            return visit(ctx.comparisonExpression());
        }

        @Override
        public Value visitComparisonExpression(ApiRuleParser.ComparisonExpressionContext ctx) {
            checkBudget();
            Value left = visit(ctx.primary(0));
            if (ctx.primary().size() == 1) {
                return left;
            }
            Value right = visit(ctx.primary(1));
            String operator = ctx.getChild(1).getText();
            boolean matched = compare(left, right, operator);
            if (matched) {
                String detail = firstText(left.detail, textValue(left)) + " " + operator + " " + textValue(right);
                hitContent = detail;
                return Value.bool(true, detail);
            }
            failureReason = firstText(left.detail, textValue(left)) + " " + operator + " " + textValue(right) + " not matched";
            return Value.bool(false);
        }

        @Override
        public Value visitPrimary(ApiRuleParser.PrimaryContext ctx) {
            if (ctx.literal() != null) {
                return visit(ctx.literal());
            }
            if (ctx.functionCall() != null) {
                return visit(ctx.functionCall());
            }
            return visit(ctx.expression());
        }

        @Override
        public Value visitLiteral(ApiRuleParser.LiteralContext ctx) {
            if (ctx.NUMBER() != null) {
                return Value.number(Double.valueOf(ctx.NUMBER().getText()));
            }
            if (ctx.STRING() != null) {
                return Value.string(unquote(ctx.STRING().getText()));
            }
            if (ctx.TRUE() != null) {
                return Value.bool(true);
            }
            if (ctx.FALSE() != null) {
                return Value.bool(false);
            }
            return Value.nullValue();
        }

        @Override
        public Value visitFunctionCall(ApiRuleParser.FunctionCallContext ctx) {
            String name = ctx.IDENT().getText().toLowerCase(Locale.ROOT);
            List<Value> args = new ArrayList<Value>();
            if (ctx.argumentList() != null) {
                for (ApiRuleParser.ExpressionContext expression : ctx.argumentList().expression()) {
                    args.add(visit(expression));
                }
            }
            if ("contains".equals(name)) {
                String expected = requireText(args, 0, name);
                boolean matched = context.body.contains(expected);
                return booleanMatch(matched, "contains(\"" + shortText(expected, 80) + "\")", "contains not matched");
            }
            if ("icontains".equals(name)) {
                String expected = requireText(args, 0, name);
                boolean matched = context.body.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
                return booleanMatch(matched, "icontains(\"" + shortText(expected, 80) + "\")", "icontains not matched");
            }
            if ("notcontains".equals(name)) {
                String expected = requireText(args, 0, name);
                boolean matched = !context.body.contains(expected);
                return booleanMatch(matched, "notContains(\"" + shortText(expected, 80) + "\")", "notContains not matched");
            }
            if ("matches".equals(name)) {
                RegexResult result = regexFind(requireText(args, 0, name));
                return booleanMatch(result.matched, result.detail, result.failureReason);
            }
            if ("regex".equals(name)) {
                RegexResult result = regexFind(requireText(args, 0, name));
                if (!result.matched) {
                    failureReason = result.failureReason;
                    return Value.nullValue();
                }
                return Value.string(result.value, result.detail);
            }
            if ("json".equals(name)) {
                return jsonValue(requireText(args, 0, name));
            }
            if ("number".equals(name)) {
                return Value.number(numberValue(arg(args, 0, name)));
            }
            if ("string".equals(name)) {
                return Value.string(textValue(arg(args, 0, name)));
            }
            if ("exists".equals(name)) {
                Value value = arg(args, 0, name);
                boolean matched = value.value != null && StringUtils.hasText(textValue(value));
                return booleanMatch(matched, firstText(value.detail, "exists"), "value does not exist");
            }
            if ("body".equals(name)) {
                return Value.string(context.body);
            }
            if ("status".equals(name)) {
                return Value.number((double) context.statusCode);
            }
            if ("time".equals(name) || "responsems".equals(name)) {
                return Value.number((double) context.responseTimeMs);
            }
            throw new IllegalArgumentException("unknown function: " + name);
        }

        private Value booleanMatch(boolean matched, String detail, String failed) {
            if (matched) {
                hitContent = detail;
                return Value.bool(true, detail);
            }
            failureReason = failed;
            return Value.bool(false);
        }

        private RegexResult regexFind(String pattern) {
            checkBudget();
            if (pattern.length() > MAX_REGEX_PATTERN_LENGTH) {
                throw new IllegalArgumentException("regex pattern length exceeds " + MAX_REGEX_PATTERN_LENGTH);
            }
            String input = context.body.length() > MAX_REGEX_INPUT_CHARS
                ? context.body.substring(0, MAX_REGEX_INPUT_CHARS)
                : context.body;
            Future<RegexResult> future = REGEX_EXECUTOR.submit(new Callable<RegexResult>() {
                @Override
                public RegexResult call() {
                    Matcher matcher = Pattern.compile(pattern, Pattern.DOTALL).matcher(input);
                    if (!matcher.find()) {
                        return RegexResult.failed("regex not matched");
                    }
                    String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                    return RegexResult.matched(value, "regex(\"" + shortText(pattern, 80) + "\") = " + shortText(value, 80));
                }
            });
            try {
                return future.get(MAX_REGEX_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                future.cancel(true);
                throw new IllegalArgumentException("regex match timeout after " + MAX_REGEX_MILLIS + "ms");
            } catch (Exception ex) {
                future.cancel(true);
                throw new IllegalArgumentException("regex error: " + ex.getMessage());
            }
        }

        private Value jsonValue(String path) {
            checkBudget();
            JsonNode node = jsonBody();
            if (node == null) {
                return Value.nullValue();
            }
            JsonNode selected = selectJsonPath(node, path);
            if (selected == null || selected.isMissingNode() || selected.isNull()) {
                failureReason = path + " not found";
                return Value.nullValue();
            }
            String detail = path + " = " + shortText(selected.isTextual() ? selected.asText() : selected.toString(), 120);
            if (selected.isNumber()) {
                return Value.number(selected.asDouble(), detail);
            }
            if (selected.isBoolean()) {
                return Value.bool(selected.asBoolean(), detail);
            }
            if (selected.isTextual()) {
                return Value.string(selected.asText(), detail);
            }
            return Value.string(selected.toString(), detail);
        }

        private JsonNode jsonBody() {
            if (!context.jsonParsed) {
                context.jsonParsed = true;
                try {
                    context.jsonBody = objectMapper.readTree(context.body);
                } catch (Exception ignored) {
                    context.jsonBody = null;
                }
            }
            return context.jsonBody;
        }

        private void checkBudget() {
            if (System.nanoTime() > deadlineNanos) {
                throw new IllegalArgumentException("rule execution timeout after 200ms");
            }
        }
    }

    private JsonNode selectJsonPath(JsonNode root, String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        String normalized = path.trim();
        if ("$".equals(normalized)) {
            return root;
        }
        if (jsonPathDepth(normalized) > MAX_JSON_PATH_DEPTH) {
            throw new IllegalArgumentException("json path depth exceeds " + MAX_JSON_PATH_DEPTH);
        }
        int depth = 0;
        int index = normalized.startsWith("$") ? 1 : 0;
        JsonNode node = root;
        while (index < normalized.length() && node != null) {
            if (++depth > MAX_JSON_PATH_DEPTH) {
                throw new IllegalArgumentException("json path depth exceeds " + MAX_JSON_PATH_DEPTH);
            }
            char ch = normalized.charAt(index);
            if (ch == '.') {
                index++;
                continue;
            }
            if (ch == '[') {
                int close = normalized.indexOf(']', index);
                if (close < 0) {
                    return null;
                }
                String item = normalized.substring(index + 1, close).trim();
                if ((item.startsWith("\"") && item.endsWith("\"")) || (item.startsWith("'") && item.endsWith("'"))) {
                    node = node.get(unquote(item));
                } else {
                    try {
                        node = node.get(Integer.parseInt(item));
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                }
                index = close + 1;
                continue;
            }
            int nextDot = normalized.indexOf('.', index);
            int nextBracket = normalized.indexOf('[', index);
            int next = nextDot < 0 ? nextBracket : nextBracket < 0 ? nextDot : Math.min(nextDot, nextBracket);
            if (next < 0) {
                next = normalized.length();
            }
            node = node.get(normalized.substring(index, next));
            index = next;
        }
        return node;
    }

    private int jsonPathDepth(String path) {
        int depth = 0;
        for (int index = path.startsWith("$") ? 1 : 0; index < path.length(); index++) {
            char ch = path.charAt(index);
            if (ch == '.') {
                int next = index + 1;
                if (next < path.length() && path.charAt(next) != '[' && path.charAt(next) != '.') {
                    depth++;
                }
            } else if (ch == '[') {
                depth++;
            }
        }
        return depth;
    }

    private static boolean compare(Value left, Value right, String operator) {
        Double leftNumber = optionalNumber(left);
        Double rightNumber = optionalNumber(right);
        int compared;
        if (leftNumber != null && rightNumber != null) {
            compared = Double.compare(leftNumber, rightNumber);
        } else {
            compared = textValue(left).compareTo(textValue(right));
        }
        if (">".equals(operator)) {
            return compared > 0;
        }
        if (">=".equals(operator)) {
            return compared >= 0;
        }
        if ("<".equals(operator)) {
            return compared < 0;
        }
        if ("<=".equals(operator)) {
            return compared <= 0;
        }
        if ("==".equals(operator)) {
            return compared == 0;
        }
        if ("!=".equals(operator)) {
            return compared != 0;
        }
        return false;
    }

    private static boolean truthy(Value value) {
        if (value == null || value.value == null) {
            return false;
        }
        if (value.value instanceof Boolean) {
            return (Boolean) value.value;
        }
        if (value.value instanceof Number) {
            return ((Number) value.value).doubleValue() != 0D;
        }
        return StringUtils.hasText(String.valueOf(value.value));
    }

    private static Value arg(List<Value> args, int index, String function) {
        if (args.size() <= index) {
            throw new IllegalArgumentException(function + " requires argument " + (index + 1));
        }
        return args.get(index);
    }

    private static String requireText(List<Value> args, int index, String function) {
        return textValue(arg(args, index, function));
    }

    private static String textValue(Value value) {
        return value == null || value.value == null ? "" : String.valueOf(value.value);
    }

    private static double numberValue(Value value) {
        Double number = optionalNumber(value);
        if (number == null) {
            throw new IllegalArgumentException("numeric value expected: " + textValue(value));
        }
        return number;
    }

    private static Double optionalNumber(Value value) {
        if (value == null || value.value == null) {
            return null;
        }
        if (value.value instanceof Number) {
            return ((Number) value.value).doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(value.value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String unquote(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        String raw = text.substring(1, text.length() - 1);
        StringBuilder result = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch != '\\' || i + 1 >= raw.length()) {
                result.append(ch);
                continue;
            }
            char escaped = raw.charAt(++i);
            if (escaped == 'n') {
                result.append('\n');
            } else if (escaped == 'r') {
                result.append('\r');
            } else if (escaped == 't') {
                result.append('\t');
            } else if (escaped == 'b') {
                result.append('\b');
            } else if (escaped == 'f') {
                result.append('\f');
            } else if (escaped == 'u' && i + 4 < raw.length()) {
                result.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                i += 4;
            } else {
                result.append(escaped);
            }
        }
        return result.toString();
    }

    private static class Value {
        private final Object value;
        private final String detail;

        private static Value bool(boolean value) {
            return new Value(value, null);
        }

        private static Value bool(boolean value, String detail) {
            return new Value(value, detail);
        }

        private static Value number(double value) {
            return new Value(value, null);
        }

        private static Value number(double value, String detail) {
            return new Value(value, detail);
        }

        private static Value string(String value) {
            return new Value(value, null);
        }

        private static Value string(String value, String detail) {
            return new Value(value, detail);
        }

        private static Value nullValue() {
            return new Value(null, null);
        }

        private Value(Object value, String detail) {
            this.value = value;
            this.detail = detail;
        }
    }

    private static class RegexResult {
        private final boolean matched;
        private final String value;
        private final String detail;
        private final String failureReason;

        private RegexResult(boolean matched, String value, String detail, String failureReason) {
            this.matched = matched;
            this.value = value;
            this.detail = detail;
            this.failureReason = failureReason;
        }

        private static RegexResult matched(String value, String detail) {
            return new RegexResult(true, value, detail, null);
        }

        private static RegexResult failed(String failureReason) {
            return new RegexResult(false, null, null, failureReason);
        }
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static String shortText(String value, int limit) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit - 3) + "...";
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static class ThrowingErrorListener extends BaseErrorListener {
        private static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();

        @Override
        public void syntaxError(
            Recognizer<?, ?> recognizer,
            Object offendingSymbol,
            int line,
            int charPositionInLine,
            String msg,
            RecognitionException e
        ) {
            throw new IllegalArgumentException("line " + line + ":" + charPositionInLine + " " + msg);
        }
    }
}
