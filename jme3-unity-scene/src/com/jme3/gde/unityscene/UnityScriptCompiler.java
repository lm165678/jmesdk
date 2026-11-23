package com.jme3.gde.unityscene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;

/** Parses a deliberately small C# grammar and emits Java from checked expressions. */
final class UnityScriptCompiler {
    private static final Pattern TOKEN = Pattern.compile(
            "\\s+|//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/|[A-Za-z_]\\w*|(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?[fF]?|"
            + "==|!=|>=|<=|&&|\\|\\||[\\[\\]{}();:,.<>+=*/!-]");
    private static final Set<String> MESSAGES = Set.of("Awake", "OnEnable", "Start",
            "Update", "LateUpdate", "FixedUpdate", "OnDisable", "OnDestroy");
    private static final Set<String> TIMED = Set.of("Update", "LateUpdate", "FixedUpdate");

    record Result(String className, String java) {
    }

    private record Field(String type, String fallback) {
    }

    private record Expression(String type, String java) {
    }

    private record Method(String name, List<String> body) {
    }

    private final List<String> tokens;
    private final Map<String, Field> fields = new LinkedHashMap<>();
    private final Set<String> methods = new java.util.HashSet<>();
    private int cursor;
    private String message;
    private int translateId;

    private UnityScriptCompiler(List<String> tokens) {
        this.tokens = tokens;
    }

    static Result compile(String source, String packageName, String guid) {
        if (!SourceVersion.isName(packageName) || !guid.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("Invalid generated package or script GUID");
        }
        List<String> tokens = new ArrayList<>();
        var matcher = TOKEN.matcher(source);
        int offset = 0;
        while (offset < source.length()) {
            matcher.region(offset, source.length());
            if (!matcher.lookingAt()) {
                throw new IllegalArgumentException("Unsupported C# token at character " + offset);
            }
            String token = matcher.group();
            if (!token.isBlank() && !token.startsWith("//") && !token.startsWith("/*")) {
                tokens.add(token);
            }
            offset = matcher.end();
        }
        return new UnityScriptCompiler(tokens).generate(packageName, guid);
    }

    private Result generate(String packageName, String guid) {
        while (take("using")) {
            expect("UnityEngine");
            expect(";");
        }
        take("public");
        expect("class");
        String className = identifier() + "Control_" + guid.toLowerCase(java.util.Locale.ROOT);
        expect(":");
        expect("MonoBehaviour");
        expect("{");
        List<Method> bodies = new ArrayList<>();
        while (!take("}")) {
            boolean serialize = take("[");
            if (serialize) {
                expect("SerializeField");
                expect("]");
            }
            boolean publicField = take("public");
            if (!publicField) {
                take("private");
            }
            String type = next();
            String name = identifier();
            if ("void".equals(type)) {
                if (serialize || !MESSAGES.contains(name) || !methods.add(name)) {
                    throw error("Unsupported or duplicate method " + name);
                }
                expect("(");
                expect(")");
                int start = cursor;
                expect("{");
                int depth = 1;
                while (depth > 0) {
                    String token = next();
                    if ("{".equals(token)) {
                        depth++;
                    } else if ("}".equals(token)) {
                        depth--;
                    }
                }
                bodies.add(new Method(name, List.copyOf(tokens.subList(start, cursor))));
            } else {
                if (!(publicField || serialize) || !Set.of("float", "int", "bool", "Transform", "GameObject").contains(type)) {
                    throw error("Only serialized fields of supported types can be converted");
                }
                String fallback = fieldDefault(type);
                if (fields.putIfAbsent(name, new Field(type, fallback)) != null) {
                    throw error("Duplicate field " + name);
                }
                expect(";");
            }
        }
        if (cursor != tokens.size()) {
            throw error("Unexpected content after the MonoBehaviour class");
        }
        StringBuilder java = new StringBuilder("package ").append(packageName).append(";\n\n")
                .append("import com.jme3.unity.runtime.UnityBehaviourControl;\n")
                .append("import com.jme3.math.Vector3f;\n")
                .append("import com.jme3.scene.Spatial;\n\n")
                .append("public final class ").append(className).append(" extends UnityBehaviourControl {\n");
        for (Method method : bodies) {
            UnityScriptCompiler body = new UnityScriptCompiler(method.body());
            body.fields.putAll(fields);
            body.message = method.name();
            String callback = method.name().startsWith("On")
                    ? "on" + method.name().substring(2) : "on" + method.name();
            java.append("    @Override\n    protected void ").append(callback).append("(")
                    .append(TIMED.contains(method.name()) ? "float seconds" : "").append(") ");
            body.block(java, 1);
        }
        return new Result(packageName + "." + className, java.append("}\n").toString());
    }

    private String fieldDefault(String type) {
        boolean assigned = take("=");
        if (type.equals("Transform") || type.equals("GameObject")) {
            if (assigned) {
                expect("null");
            }
            return "null";
        }
        if (type.equals("bool")) {
            String value = assigned ? next() : "false";
            if (!Set.of("false", "true").contains(value)) {
                throw error("Expected bool literal");
            }
            return value;
        }
        String value = assigned ? (take("-") ? "-" : "") + next() : "0";
        try {
            return type.equals("int") ? Integer.toString(Integer.parseInt(value))
                    : floatLiteral(value);
        } catch (NumberFormatException exception) {
            throw error("Expected " + type + " literal");
        }
    }

    private void block(StringBuilder java, int indent) {
        expect("{");
        java.append("{\n");
        while (!take("}")) {
            statement(java, indent + 1);
        }
        java.append("    ".repeat(indent)).append("}\n");
    }

    private void statement(StringBuilder java, int indent) {
        String prefix = "    ".repeat(indent);
        if (take("if")) {
            expect("(");
            Expression condition = expression(0);
            require(condition, "bool");
            expect(")");
            java.append(prefix).append("if (").append(condition.java()).append(") ");
            block(java, indent);
            if (take("else")) {
                java.append(prefix).append("else ");
                block(java, indent);
            }
            return;
        }
        String name = next();
        if (take("=")) {
            Field field = fields.get(name);
            if (field == null || !Set.of("bool", "int", "float").contains(field.type())) {
                throw error("Unsupported assignment to " + name);
            }
            Expression value = expression(0);
            require(value, field.type());
            java.append(prefix).append("setField(\"").append(name).append("\", String.valueOf(")
                    .append(value.java()).append("));\n");
        } else {
            String target = object(name);
            expect(".");
            if (take("transform")) {
                expect(".");
            }
            expect("Translate");
            expect("(");
            Expression x = expression(0);
            expect(",");
            Expression y = expression(0);
            expect(",");
            Expression z = expression(0);
            expect(")");
            require(x, "float");
            require(y, "float");
            require(z, "float");
            String local = "target" + translateId++;
            java.append(prefix).append("Spatial ").append(local).append(" = ").append(target).append(";\n")
                    .append(prefix).append("if (").append(local).append(" != null) {\n")
                    .append(prefix).append("    Vector3f direction = ").append(local)
                    .append(".getWorldRotation().mult(new Vector3f(").append(x.java()).append(", ")
                    .append(y.java()).append(", -(").append(z.java()).append(")));\n")
                    .append(prefix).append("    if (").append(local).append(".getParent() != null) {\n")
                    .append(prefix).append("        Spatial parent = ").append(local).append(".getParent();\n")
                    .append(prefix).append("        direction = parent.worldToLocal(parent.getWorldTranslation().add(direction), null);\n")
                    .append(prefix).append("    }\n")
                    .append(prefix).append("    ").append(local).append(".move(direction);\n")
                    .append(prefix).append("}\n");
        }
        expect(";");
    }

    private String object(String name) {
        if (name.equals("GetComponent")) {
            expect("<");
            expect("Transform");
            expect(">");
            expect("(");
            expect(")");
            return "getSpatial()";
        }
        if (name.equals("transform") || name.equals("gameObject")) {
            return "getSpatial()";
        }
        Field field = fields.get(name);
        if (field != null && Set.of("Transform", "GameObject").contains(field.type())) {
            return "objectField(\"" + name + "\")";
        }
        throw error("Unsupported object " + name);
    }

    private Expression expression(int level) {
        Expression left = atom();
        while (cursor < tokens.size()) {
            String operator = tokens.get(cursor);
            int priority = precedence(operator);
            if (priority == 0 || priority <= level) {
                break;
            }
            cursor++;
            Expression right = expression(priority);
            boolean logical = operator.equals("&&") || operator.equals("||");
            boolean equality = operator.equals("==") || operator.equals("!=");
            boolean numeric = !logical && !(equality && left.type().equals(right.type()));
            if (logical) {
                require(left, "bool");
                require(right, "bool");
            } else if (numeric) {
                require(left, "float");
                require(right, "float");
            }
            String type = priority <= 4 ? "bool"
                    : left.type().equals("float") || right.type().equals("float") ? "float" : "int";
            left = new Expression(type, "(" + left.java() + " " + operator + " " + right.java() + ")");
        }
        return left;
    }

    private Expression atom() {
        String token = next();
        if (token.equals("(")) {
            Expression inner = expression(0);
            expect(")");
            return inner;
        }
        if (token.equals("-") || token.equals("!")) {
            Expression inner = atom();
            require(inner, token.equals("!") ? "bool" : "float");
            return new Expression(inner.type(), "(" + token + inner.java() + ")");
        }
        if (token.equals("true") || token.equals("false")) {
            return new Expression("bool", token);
        }
        if (token.equals("Time")) {
            expect(".");
            String member = next();
            if (!TIMED.contains(message) || !member.equals("deltaTime")
                    && !(message.equals("FixedUpdate") && member.equals("fixedDeltaTime"))) {
                throw error("Unsupported Time access in " + message);
            }
            return new Expression("float", "seconds");
        }
        Field field = fields.get(token);
        if (field != null) {
            return switch (field.type()) {
                case "bool" -> new Expression("bool", "booleanField(\"" + token + "\", " + field.fallback() + ")");
                case "int", "float" -> new Expression(field.type(),
                        field.type() + "Field(\"" + token + "\", " + field.fallback() + ")");
                default -> new Expression("object", "objectField(\"" + token + "\")");
            };
        }
        if (token.equals("null")) {
            return new Expression("object", "null");
        }
        try {
            return token.matches("\\d+") ? new Expression("int", Integer.toString(Integer.parseInt(token)))
                    : new Expression("float", floatLiteral(token));
        } catch (NumberFormatException exception) {
            throw error("Unsupported expression " + token);
        }
    }

    private static int precedence(String operator) {
        return switch (operator) {
            case "||" -> 1;
            case "&&" -> 2;
            case "==", "!=" -> 3;
            case "<", ">", "<=", ">=" -> 4;
            case "+", "-" -> 5;
            case "*", "/" -> 6;
            default -> 0;
        };
    }

    private static String floatLiteral(String token) {
        float value = Float.parseFloat(token);
        if (!Float.isFinite(value)) {
            throw new NumberFormatException(token);
        }
        return Float.toString(value) + "f";
    }

    private void require(Expression expression, String type) {
        if (!expression.type().equals(type) && !(type.equals("float") && expression.type().equals("int"))) {
            throw error("Expected " + type + " expression");
        }
    }

    private String identifier() {
        String name = next();
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
            throw error("Invalid identifier " + name);
        }
        return name;
    }

    private boolean take(String value) {
        if (cursor < tokens.size() && tokens.get(cursor).equals(value)) {
            cursor++;
            return true;
        }
        return false;
    }

    private void expect(String value) {
        if (!take(value)) {
            throw error("Expected " + value);
        }
    }

    private String next() {
        if (cursor == tokens.size()) {
            throw error("Unexpected end of script");
        }
        return tokens.get(cursor++);
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " near token " + cursor);
    }
}
