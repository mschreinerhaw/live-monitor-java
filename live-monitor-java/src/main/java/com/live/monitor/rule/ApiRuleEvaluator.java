package com.live.monitor.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
                Value target = containsTarget(args, name, context.body);
                String expected = requireText(args, args.size() > 1 ? 1 : 0, name);
                boolean matched = containsAny(target, expected, false);
                return booleanMatch(matched, containsDetail(target, "contains", expected), "contains not matched");
            }
            if ("icontains".equals(name)) {
                Value target = containsTarget(args, name, context.body);
                String expected = requireText(args, args.size() > 1 ? 1 : 0, name);
                boolean matched = containsAny(target, expected, true);
                return booleanMatch(matched, containsDetail(target, "icontains", expected), "icontains not matched");
            }
            if ("notcontains".equals(name)) {
                Value target = containsTarget(args, name, context.body);
                String expected = requireText(args, args.size() > 1 ? 1 : 0, name);
                boolean matched = !containsAny(target, expected, false);
                return booleanMatch(matched, containsDetail(target, "notContains", expected), "notContains not matched");
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
            if ("field".equals(name) || "column".equals(name)) {
                return fieldValue(requireText(args, 0, name));
            }
            if ("anyrow".equals(name)) {
                return rowConditions(args, true, name);
            }
            if ("allrows".equals(name)) {
                return rowConditions(args, false, name);
            }
            if ("allrowscompare".equals(name) || "allrowcompare".equals(name)) {
                return rowFieldCompare(args, false, name);
            }
            if ("anyrowscompare".equals(name) || "anyrowcompare".equals(name) || "rowcompare".equals(name)) {
                return rowFieldCompare(args, true, name);
            }
            if ("samevalues".equals(name) || "samevalue".equals(name)) {
                return sameValues(args, name);
            }
            if ("absdiff".equals(name) || "diff".equals(name)) {
                return numericDiff(args, false, name);
            }
            if ("pctdiff".equals(name) || "percentdiff".equals(name) || "percentagediff".equals(name)) {
                return numericDiff(args, true, name);
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
                Value tableValue = tableFieldValue(path);
                if (tableValue.value != null) {
                    return tableValue;
                }
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

        private Value fieldValue(String fieldName) {
            Value value = tableFieldValue(fieldName);
            if (value.value != null) {
                return value;
            }
            return jsonValue("$." + fieldName);
        }

        private Value tableFieldValue(String pathOrName) {
            SourceField sourceField = sourceField(pathOrName);
            if (sourceField != null) {
                List<String> values = tableFieldValues(sourceField.fieldName, tableRows(sourceField.sourceName));
                if (values.isEmpty()) {
                    return Value.nullValue();
                }
                return Value.list(values, sourceField.sourceName + "." + sourceField.fieldName
                    + " = [" + shortText(String.join(", ", values), 120) + "]");
            }
            String fieldName = fieldNameFromPath(pathOrName);
            if (!StringUtils.hasText(fieldName)) {
                return Value.nullValue();
            }
            List<String> values = tableFieldValues(fieldName);
            if (values.isEmpty()) {
                return Value.nullValue();
            }
            return Value.list(values, fieldName + " = [" + shortText(String.join(", ", values), 120) + "]");
        }

        private List<String> tableFieldValues(String fieldName) {
            SourceField sourceField = sourceField(fieldName);
            if (sourceField != null) {
                return tableFieldValues(sourceField.fieldName, tableRows(sourceField.sourceName));
            }
            return tableFieldValues(fieldName, tableRows());
        }

        private List<String> tableFieldValues(String fieldName, List<JsonNode> rows) {
            if (rows.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> values = new ArrayList<String>();
            for (JsonNode row : rows) {
                JsonNode value = rowField(row, fieldName);
                if (value != null && !value.isMissingNode() && !value.isNull()) {
                    values.add(value.isTextual() ? value.asText() : value.toString());
                }
            }
            return values;
        }

        private Value rowConditions(List<Value> args, boolean any, String function) {
            if (args.isEmpty() || args.size() % 3 != 0) {
                throw new IllegalArgumentException(function + " requires field/operator/value triples");
            }
            List<JsonNode> rows = tableRows();
            if (rows.isEmpty()) {
                return booleanMatch(false, null, "rows not found");
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                boolean rowMatched = true;
                for (int index = 0; index < args.size(); index += 3) {
                    String fieldName = textValue(args.get(index));
                    String operator = textValue(args.get(index + 1));
                    Value left = rowValue(rows.get(rowIndex), fieldName);
                    Value right = args.get(index + 2);
                    if (!compare(left, right, operator)) {
                        rowMatched = false;
                        if (!any) {
                            return booleanMatch(false, null, function + " row " + (rowIndex + 1) + " not matched: "
                                + fieldName + " " + operator + " " + textValue(right));
                        }
                        break;
                    }
                }
                if (rowMatched && any) {
                    return booleanMatch(true, function + " row " + (rowIndex + 1), function + " not matched");
                }
            }
            return booleanMatch(!any, function + " matched", function + " not matched");
        }

        private Value rowFieldCompare(List<Value> args, boolean any, String function) {
            if (args.size() != 3) {
                throw new IllegalArgumentException(function + " requires left field, operator and right field");
            }
            String leftField = textValue(args.get(0));
            String operator = textValue(args.get(1));
            String rightField = textValue(args.get(2));
            List<JsonNode> rows = tableRows();
            if (rows.isEmpty()) {
                return booleanMatch(false, null, "rows not found");
            }
            boolean matchedAny = false;
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Value left = rowValue(rows.get(rowIndex), leftField);
                Value right = rowValue(rows.get(rowIndex), rightField);
                boolean matched = compare(left, right, operator);
                if (any && matched) {
                    return booleanMatch(true, function + " row " + (rowIndex + 1) + ": "
                        + leftField + " " + operator + " " + rightField, function + " not matched");
                }
                if (!any && !matched) {
                    return booleanMatch(false, null, function + " row " + (rowIndex + 1) + " not matched: "
                        + leftField + "=" + textValue(left) + " " + operator + " " + rightField + "=" + textValue(right));
                }
                matchedAny = matchedAny || matched;
            }
            return booleanMatch(!any || matchedAny, function + " matched", function + " not matched");
        }

        private Value sameValues(List<Value> args, String function) {
            if (args.size() != 2) {
                throw new IllegalArgumentException(function + " requires two field names");
            }
            List<String> leftValues = tableFieldValues(textValue(args.get(0)));
            List<String> rightValues = tableFieldValues(textValue(args.get(1)));
            if (leftValues.isEmpty() || rightValues.isEmpty()) {
                return booleanMatch(false, null, function + " requires non-empty field values");
            }
            List<String> leftSorted = new ArrayList<String>(leftValues);
            List<String> rightSorted = new ArrayList<String>(rightValues);
            Collections.sort(leftSorted);
            Collections.sort(rightSorted);
            boolean matched = leftSorted.equals(rightSorted);
            return booleanMatch(
                matched,
                function + "(" + textValue(args.get(0)) + ", " + textValue(args.get(1)) + ")",
                function + " not matched"
            );
        }

        private Value numericDiff(List<Value> args, boolean percentage, String function) {
            if (args.size() != 2) {
                throw new IllegalArgumentException(function + " requires two numeric values");
            }
            double left = firstNumberValue(args.get(0));
            double right = firstNumberValue(args.get(1));
            double diff = Math.abs(left - right);
            if (percentage) {
                double denominator = Math.max(Math.abs(left), Math.abs(right));
                diff = denominator == 0D ? 0D : diff / denominator;
            }
            String detail = function + "(" + shortNumber(left) + ", " + shortNumber(right) + ") = " + shortNumber(diff);
            return Value.number(diff, detail);
        }

        private Value rowValue(JsonNode row, String fieldName) {
            JsonNode value = rowField(row, fieldName);
            if (value == null || value.isMissingNode() || value.isNull()) {
                return Value.nullValue();
            }
            String detail = fieldName + " = " + shortText(value.isTextual() ? value.asText() : value.toString(), 120);
            if (value.isNumber()) {
                return Value.number(value.asDouble(), detail);
            }
            if (value.isBoolean()) {
                return Value.bool(value.asBoolean(), detail);
            }
            return Value.string(value.isTextual() ? value.asText() : value.toString(), detail);
        }

        private JsonNode rowField(JsonNode row, String fieldName) {
            if (row == null || !StringUtils.hasText(fieldName)) {
                return null;
            }
            JsonNode value = row.get(fieldName);
            if (value != null) {
                return value;
            }
            String normalized = fieldName.trim().toLowerCase(Locale.ROOT);
            java.util.Iterator<String> names = row.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (normalized.equals(name.toLowerCase(Locale.ROOT))) {
                    return row.get(name);
                }
            }
            return null;
        }

        private List<JsonNode> tableRows() {
            return tableRows(null);
        }

        private List<JsonNode> tableRows(String sourceName) {
            JsonNode node = jsonBody();
            if (node == null) {
                return Collections.emptyList();
            }
            JsonNode source = null;
            if (StringUtils.hasText(sourceName)) {
                JsonNode sources = node.get("sources");
                source = sources == null ? null : sources.get(sourceName);
                if (source == null) {
                    source = node.get(sourceName);
                }
            }
            JsonNode rows = (source == null ? node : source).get("rows");
            if (rows == null || !rows.isArray()) {
                return Collections.emptyList();
            }
            List<JsonNode> result = new ArrayList<JsonNode>();
            for (JsonNode row : rows) {
                if (row != null && row.isObject()) {
                    result.add(row);
                }
            }
            return result;
        }

        private SourceField sourceField(String value) {
            if (!StringUtils.hasText(value)) {
                return null;
            }
            String text = value.trim();
            if (text.startsWith("$")) {
                return null;
            }
            int dot = text.indexOf('.');
            if (dot <= 0 || dot >= text.length() - 1 || text.indexOf('.', dot + 1) >= 0) {
                return null;
            }
            return new SourceField(text.substring(0, dot), text.substring(dot + 1));
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

    private static class SourceField {
        private final String sourceName;
        private final String fieldName;

        private SourceField(String sourceName, String fieldName) {
            this.sourceName = sourceName;
            this.fieldName = fieldName;
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
        if ("contains".equals(operator)) {
            return containsAny(left, textValue(right), false);
        }
        if ("icontains".equals(operator)) {
            return containsAny(left, textValue(right), true);
        }
        if ("notContains".equals(operator) || "notcontains".equals(operator)) {
            return !containsAny(left, textValue(right), false);
        }
        if (isCollectionValue(left) || isCollectionValue(right)) {
            return compareAny(values(left), values(right), operator);
        }
        if (("==".equals(operator) || "!=".equals(operator)) && right != null && right.value instanceof String) {
            boolean matched = textValue(left).equals(textValue(right));
            return "==".equals(operator) ? matched : !matched;
        }
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

    private static boolean compareAny(List<Value> leftValues, List<Value> rightValues, String operator) {
        if ("!=".equals(operator)) {
            for (Value left : leftValues) {
                for (Value right : rightValues) {
                    if (compare(left, right, "==")) {
                        return false;
                    }
                }
            }
            return true;
        }
        for (Value left : leftValues) {
            for (Value right : rightValues) {
                if (compare(left, right, operator)) {
                    return true;
                }
            }
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

    private static Value containsTarget(List<Value> args, String function, String body) {
        if (args.size() > 1) {
            return arg(args, 0, function);
        }
        arg(args, 0, function);
        return Value.string(body);
    }

    private static String containsDetail(Value target, String function, String expected) {
        String prefix = StringUtils.hasText(target.detail) ? target.detail + " " : "";
        return prefix + function + "(\"" + shortText(expected, 80) + "\")";
    }

    private static String textValue(Value value) {
        if (isCollectionValue(value)) {
            List<String> values = new ArrayList<String>();
            for (Value item : values(value)) {
                values.add(textValue(item));
            }
            return String.join(" | ", values);
        }
        return value == null || value.value == null ? "" : String.valueOf(value.value);
    }

    private static boolean containsAny(Value target, String expected, boolean ignoreCase) {
        String expectedText = ignoreCase ? expected.toLowerCase(Locale.ROOT) : expected;
        for (Value item : values(target)) {
            String actual = textValue(item);
            if (ignoreCase) {
                actual = actual.toLowerCase(Locale.ROOT);
            }
            if (actual.contains(expectedText)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCollectionValue(Value value) {
        return value != null && value.value instanceof Collection;
    }

    private static List<Value> values(Value value) {
        if (!isCollectionValue(value)) {
            return Collections.singletonList(value == null ? Value.nullValue() : value);
        }
        List<Value> result = new ArrayList<Value>();
        for (Object item : (Collection<?>) value.value) {
            result.add(Value.string(item == null ? "" : String.valueOf(item)));
        }
        return result;
    }

    private static String fieldNameFromPath(String pathOrName) {
        if (!StringUtils.hasText(pathOrName)) {
            return "";
        }
        String value = pathOrName.trim();
        if (value.startsWith("$.")) {
            value = value.substring(2);
        }
        if (value.indexOf('.') >= 0 || value.indexOf('[') >= 0 || value.indexOf(']') >= 0) {
            return "";
        }
        return value;
    }

    private static double numberValue(Value value) {
        Double number = optionalNumber(value);
        if (number == null) {
            throw new IllegalArgumentException("numeric value expected: " + textValue(value));
        }
        return number;
    }

    private static double firstNumberValue(Value value) {
        for (Value item : values(value)) {
            Double number = optionalNumber(item);
            if (number != null) {
                return number;
            }
        }
        throw new IllegalArgumentException("numeric value expected: " + textValue(value));
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

    private static String shortNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
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

        private static Value list(List<String> value, String detail) {
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
