package com.capability.pdfgeneration.service.service.pdfGenerationServices;

import com.capability.pdfgeneration.service.models.pdfGeneration.Report;
import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.template.BlockTemplate;
import com.deepoove.poi.template.ElementTemplate;
import com.deepoove.poi.template.MetaTemplate;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.ReflectiveMethodResolver;
import org.springframework.expression.spel.support.ReflectivePropertyAccessor;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class DocxTemplateValidatorService {

    // ---------- public API ----------

    /**
     * Validate a poi-tl template without rendering any data.
     * - Compiles the template (structural check)
     * - Parses all {{? …}} SpEL expressions (syntax check)
     * - Evaluates expressions against a safe auto-generated skeleton (no sample JSON)
     */
    public Report verify(byte[] docxBytes) {
        Report r = new Report();

        Configure cfg = Configure.builder()
                .useSpringEL(true)
                .build();

        try (XWPFTemplate tpl = XWPFTemplate.compile(new ByteArrayInputStream(docxBytes), cfg)) {

            for (MetaTemplate mt : tpl.getElementTemplates()) {
                if (mt instanceof ElementTemplate) {
                    ElementTemplate et = (ElementTemplate) mt;
                    Character sign = et.getSign();       // null for plain {{var}}
                    String tagBody   = et.getTagName();  // plain name or expression
                    String tagSource = et.getSource();   // original "{{...}}"

                    if (sign == null) {
                        // Plain {{var}}
                        validatePlainVar(r, tagBody, tagSource);

                    } else if (sign.charValue() == '?') {
                        // Conditional
                        validateConditional(r, tagBody, tagSource);

                    } else if (sign == '@' || sign == '#' || sign == '*' || sign == '+') {
                        // image/table/list/include – record name if present
                        if (tagBody != null && !tagBody.trim().isEmpty()) {
                            r.variables.add(tagBody);
                        }
                    } else if (sign.charValue() == '/') {
                        // end block – compile already verified pairing
                    } else {
                        r.warnings.add("Unknown tag kind: " + safe(tagSource));
                    }

                } else if (mt instanceof BlockTemplate) {
                    // compile verifies structure
                }
            }

            // Basic lint for variable names
            List<String> copy = new ArrayList<String>(r.variables);
            for (String v : copy) {
                if (v == null || v.trim().isEmpty()) {
                    r.errors.add("Empty variable name in a {{}} tag.");
                } else if (!PLAIN_IDENT.matcher(v).matches()) {
                    r.warnings.add("Variable name looks unusual: " + v);
                }
            }

        } catch (Exception e) {
            r.errors.add("Compile failed: " + e.getMessage());
            return r;
        }

        return r;
    }

    // ---------- conditional validation ----------

    private void validateConditional(Report r,
                                     @Nullable String exprText,
                                     @Nullable String src) {
        String expr = (exprText == null ? "" : exprText.trim());
        String source = (src == null ? ("{{? " + expr + "}}") : src);

        if (expr.isEmpty()) {
            r.errors.add("Empty conditional expression: " + safe(source));
            return;
        }

        // Disallow risky SpEL features
        if (DISALLOWED_T.matcher(expr).find()) {
            r.errors.add("Disallowed type reference T(...) in expression: " + safe(source));
        }
        if (DISALLOWED_NEW.matcher(expr).find()) {
            r.errors.add("Disallowed 'new' usage in expression: " + safe(source));
        }
        if (DISALLOWED_CLASS.matcher(expr).find()) {
            r.errors.add("Disallowed '.class' usage in expression: " + safe(source));
        }

        // Parse SpEL (syntax)
        Expression parsed;
        try {
            parsed = PARSER.parseExpression(expr);
        } catch (ParseException pe) {
            r.errors.add("Invalid SpEL syntax in " + safe(source) + " :: " + pe.getMessage());
            return;
        }

        if (hasHardErrors(r)) return;

        // Build a safe, typed default model from the expression itself
        Map<String, Object> modelForEval = buildSafeModelForExpression(expr);

        // Restricted evaluation context
        StandardEvaluationContext ctx = new StandardEvaluationContext(modelForEval);
        ctx.setTypeLocator(new org.springframework.expression.TypeLocator() {
            public Class<?> findType(String typeName) {
                throw new EvaluationException("Type references are disabled in validator");
            }
        });
        ctx.setConstructorResolvers(Collections.<org.springframework.expression.ConstructorResolver>emptyList());
        ctx.setBeanResolver(null);
        ctx.setPropertyAccessors(Arrays.asList(new MapAccessor(), new ReflectivePropertyAccessor()));
        ctx.setMethodResolvers(Arrays.asList(new ReflectiveMethodResolver()));

        try {
            Object val = parsed.getValue(ctx);
            if (!(val instanceof Boolean)) {
                r.warnings.add("Section expression does not evaluate to boolean: " + safe(source));
            }
        } catch (EvaluationException ee) {
            r.warnings.add("Expression failed to evaluate for " + safe(source) + " :: " + ee.getMessage());
        }

        r.sectionExprs.add(expr);
    }

    // ---------- helpers for plain vars ----------

    private void validatePlainVar(Report r, @Nullable String name, @Nullable String source) {
        if (name == null || name.trim().isEmpty()) {
            r.errors.add("Empty variable name in " + safe(source));
            return;
        }
        r.variables.add(name);
        if (!PLAIN_IDENT.matcher(name).matches()) {
            r.warnings.add("Variable name looks unusual: " + name);
        }
    }

    // ---------- safe model synthesis (no sample) ----------

    private Map<String, Object> buildSafeModelForExpression(String expr) {
        TypeFacts facts = inferTypesFromExpression(expr);
        return buildDefaultsFromFacts(facts);
    }

    // ---------- type inference ----------

    private enum ValType { BOOL, NUM, STR, LIST }

    private static final Pattern BRACKET_KEY    = Pattern.compile("\\[['\"]([^'\"\\]]+)['\"]\\]");
    private static final Pattern DOTTED_PATH    = Pattern.compile("\\b([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)+)\\b(?!\\s*\\()");

    private static final Pattern CMP_NUM_LEFT   = Pattern.compile("\\b([A-Za-z_][\\w\\.]*)\\s*(>=|<=|>|<)\\s*[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern CMP_NUM_RIGHT  = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?\\s*(>=|<=|>|<)\\s*\\b([A-Za-z_][\\w\\.]*)\\b");
    private static final Pattern EQ_NUM_LEFT    = Pattern.compile("\\b([A-Za-z_][\\w\\.]*)\\s*(==|!=)\\s*[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern EQ_NUM_RIGHT   = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?\\s*(==|!=)\\s*\\b([A-Za-z_][\\w\\.]*)\\b");

    private static final Pattern EQ_STR_LEFT    = Pattern.compile("\\b([A-Za-z_][\\w\\.]*)\\s*(==|!=)\\s*'[^']*'");
    private static final Pattern EQ_STR_RIGHT   = Pattern.compile("'[^']*'\\s*(==|!=)\\s*\\b([A-Za-z_][\\w\\.]*)\\b");
    private static final Pattern MATCHES_OP     = Pattern.compile("\\b([A-Za-z_][\\w\\.]*)\\s+matches\\s+'[^']*'");
    private static final Pattern STARTS_WITH    = Pattern.compile("\\b([A-Za-z_][\\w\\.]*)\\.startsWith\\s*\\(");
    private static final Pattern ENDS_WITH      = Pattern.compile("\\b([A-Za-z_][\\w\\.]*)\\.endsWith\\s*\\(");
    private static final Pattern CONTAINS       = Pattern.compile("\\b([A-Za-z_][\\w\\.]*)\\.contains\\s*\\(");
    private static final Pattern LOWER_UPPER    = Pattern.compile("\\b([A-Za-z_][\\w\\.]*)\\.(toLowerCase|toUpperCase)\\s*\\(");
    private static final Pattern LENGTH_CALL    = Pattern.compile("\\b([A-Za-z_][\\w\\.]*)\\.length\\s*\\(");
    private static final Pattern EQUALS_IC_LHS  = Pattern.compile("\\b([A-Za-z_][\\w\\.]*)\\.equalsIgnoreCase\\s*\\(");
    private static final Pattern EQUALS_IC_RHS  = Pattern.compile("'[^']*'\\s*\\.\\s*equalsIgnoreCase\\s*\\(\\s*([A-Za-z_][\\w\\.]*)\\s*\\)");

    private static final Pattern SIZE_CALL      = Pattern.compile("\\b([A-Za-z_][\\w\\.]*)\\.size\\s*\\(");
    private static final Pattern INDEXED_PATH   = Pattern.compile("\\b([A-Za-z_][\\w]*)\\s*\\[\\s*\\d+\\s*\\](?:\\.([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*))?");

    private static final Pattern SIMPLE_IDENT   = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern PLAIN_IDENT    = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private static final Pattern DISALLOWED_T     = Pattern.compile("\\bT\\s*\\(");
    private static final Pattern DISALLOWED_NEW   = Pattern.compile("\\bnew\\s+");
    private static final Pattern DISALLOWED_CLASS = Pattern.compile("\\.class\\b");

    private static boolean hasHardErrors(Report r) { return !r.errors.isEmpty(); }

    private static String safe(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }

    private static final Set<String> KEYWORDS;
    static {
        Set<String> ks = new HashSet<String>();
        ks.add("and"); ks.add("or"); ks.add("not");
        ks.add("true"); ks.add("false"); ks.add("null");
        ks.add("this"); ks.add("root"); ks.add("T"); ks.add("new");
        KEYWORDS = Collections.unmodifiableSet(ks);
    }

    // Facts collected from a single expression
    private static final class TypeFacts {
        final Map<String, ValType> leafTypes = new LinkedHashMap<String, ValType>(); // e.g. "user.active": BOOL, "score": NUM
        final Set<String> listPaths = new LinkedHashSet<String>();                   // e.g. "users[]", "items[]"
    }

    private TypeFacts inferTypesFromExpression(String expr) {
        TypeFacts f = new TypeFacts();

        // Numeric contexts
        inferType(expr, CMP_NUM_LEFT, 1, ValType.NUM, f.leafTypes);
        inferType(expr, CMP_NUM_RIGHT, 2, ValType.NUM, f.leafTypes);
        inferType(expr, EQ_NUM_LEFT, 1, ValType.NUM, f.leafTypes);
        inferType(expr, EQ_NUM_RIGHT, 2, ValType.NUM, f.leafTypes);

        // String contexts
        inferType(expr, EQ_STR_LEFT, 1, ValType.STR, f.leafTypes);
        inferType(expr, EQ_STR_RIGHT, 2, ValType.STR, f.leafTypes);
        inferType(expr, MATCHES_OP, 1, ValType.STR, f.leafTypes);
        inferType(expr, STARTS_WITH, 1, ValType.STR, f.leafTypes);
        inferType(expr, ENDS_WITH, 1, ValType.STR, f.leafTypes);
        inferType(expr, CONTAINS, 1, ValType.STR, f.leafTypes);
        inferType(expr, LOWER_UPPER, 1, ValType.STR, f.leafTypes);
        inferType(expr, LENGTH_CALL, 1, ValType.STR, f.leafTypes);
        inferType(expr, EQUALS_IC_LHS, 1, ValType.STR, f.leafTypes);
        inferType(expr, EQUALS_IC_RHS, 1, ValType.STR, f.leafTypes);

        // Lists
        inferType(expr, SIZE_CALL, 1, ValType.LIST, f.leafTypes);
        collectIndexedPaths(expr, f.listPaths, f.leafTypes);

        // Dotted paths anywhere → assume BOOL unless stronger type exists
        Matcher dp = DOTTED_PATH.matcher(expr);
        while (dp.find()) {
            String path = dp.group(1).trim();
            if (!path.isEmpty() && !KEYWORDS.contains(head(path).toLowerCase(Locale.ROOT))) {
                mergeType(f.leafTypes, normalizeIndexed(path), ValType.BOOL);
            }
        }

        // Bracket keys like #root['eligible'] → treat 'eligible' as BOOL unless typed otherwise
        Matcher bk = BRACKET_KEY.matcher(expr);
        while (bk.find()) {
            String key = bk.group(1).trim();
            if (!key.isEmpty()) mergeType(f.leafTypes, key, ValType.BOOL);
        }

        // Simple {{? flag }}
        String trimmed = expr.trim();
        if (SIMPLE_IDENT.matcher(trimmed).matches()) {
            mergeType(f.leafTypes, trimmed, ValType.BOOL);
        }

        return f;
    }

    private static void inferType(String expr, Pattern p, int groupIdx, ValType type, Map<String, ValType> out) {
        Matcher m = p.matcher(expr);
        while (m.find()) {
            String path = m.group(groupIdx);
            if (path != null && !path.trim().isEmpty()) {
                mergeType(out, normalizeIndexed(path.trim()), type);
            }
        }
    }

    private static String normalizeIndexed(String path) {
        return path.replaceAll("\\[\\s*\\d+\\s*\\]", "[]"); // users[0].active -> users[].active
    }

    private static void collectIndexedPaths(String expr, Set<String> listPaths, Map<String, ValType> leafTypes) {
        Matcher ix = INDEXED_PATH.matcher(expr);
        while (ix.find()) {
            String base = ix.group(1);      // users
            String tail = ix.group(2);      // active or active.score
            String listSeg = base + "[]";
            listPaths.add(listSeg);
            if (tail != null && !tail.trim().isEmpty()) {
                String normalized = listSeg + "." + tail.trim();
                mergeType(leafTypes, normalized, ValType.BOOL); // leaf under first element map
            }
        }
    }

    // Merge with priority: LIST > NUM/STR > BOOL
    private static void mergeType(Map<String, ValType> map, String path, ValType t) {
        ValType cur = map.get(path);
        if (cur == null) { map.put(path, t); return; }
        if (cur == ValType.LIST) return;
        if ((t == ValType.NUM || t == ValType.STR) && cur == ValType.BOOL) { map.put(path, t); return; }
        if (t == ValType.LIST) { map.put(path, t); }
    }

    private static String head(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    // Build nested defaults from collected facts
    private Map<String, Object> buildDefaultsFromFacts(TypeFacts facts) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();

        // Ensure list skeletons first so subsequent leaf defaults can nest inside
        for (String listPath : facts.listPaths) {
            ensureListSkeleton(root, listPath);
        }
        // Apply leaf defaults
        for (Map.Entry<String, ValType> e : facts.leafTypes.entrySet()) {
            setNestedDefault(root, e.getKey(), defaultFor(e.getValue()));
        }
        return root;
    }

    private static Object defaultFor(ValType t) {
        switch (t) {
            case BOOL: return Boolean.FALSE;
            case NUM:  return Integer.valueOf(0);
            case STR:  return "";
            case LIST: return new ArrayList<Object>();
            default:   return "";
        }
    }

    // Create list skeleton for e.g., "users[]" or "users[].active"
    @SuppressWarnings("unchecked")
    private void ensureListSkeleton(Map<String, Object> root, String listPath) {
        String[] parts = listPath.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i];
            boolean listSeg = seg.endsWith("[]");
            String key = listSeg ? seg.substring(0, seg.length() - 2) : seg;
            boolean last = (i == parts.length - 1);

            Object existing = cur.get(key);
            if (listSeg) {
                List<Object> list;
                if (!(existing instanceof List<?>)) {
                    list = new ArrayList<Object>();
                    cur.put(key, list);
                } else list = (List<Object>) existing;

                if (!last) {
                    if (list.isEmpty() || !(list.get(0) instanceof Map)) {
                        Map<String, Object> elem = new LinkedHashMap<String, Object>();
                        if (list.isEmpty()) list.add(elem); else list.set(0, elem);
                        cur = elem;
                    } else {
                        cur = (Map<String, Object>) list.get(0);
                    }
                }
            } else {
                if (!last) {
                    if (!(existing instanceof Map)) {
                        Map<String, Object> next = new LinkedHashMap<String, Object>();
                        cur.put(key, next);
                        cur = next;
                    } else {
                        cur = (Map<String, Object>) existing;
                    }
                } else {
                    if (existing == null) cur.put(key, new LinkedHashMap<String, Object>());
                }
            }
        }
    }

    // Set a nested default, supporting "users[].active.score"
    @SuppressWarnings("unchecked")
    private void setNestedDefault(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cur = root;

        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i];
            boolean listSeg = seg.endsWith("[]");
            String key = listSeg ? seg.substring(0, seg.length() - 2) : seg;
            boolean last = (i == parts.length - 1);

            Object existing = cur.get(key);

            if (listSeg) {
                List<Object> list;
                if (!(existing instanceof List<?>)) {
                    list = new ArrayList<Object>();
                    cur.put(key, list);
                } else list = (List<Object>) existing;

                if (last) {
                    // keep list empty so bare {{? items}} is false
                    return;
                }
                Map<String, Object> elem;
                if (list.isEmpty() || !(list.get(0) instanceof Map)) {
                    elem = new LinkedHashMap<String, Object>();
                    if (list.isEmpty()) list.add(elem); else list.set(0, elem);
                } else {
                    elem = (Map<String, Object>) list.get(0);
                }
                cur = elem;

            } else {
                if (last) {
                    if (existing == null) cur.put(key, value);
                } else {
                    if (!(existing instanceof Map)) {
                        Map<String, Object> next = new LinkedHashMap<String, Object>();
                        cur.put(key, next);
                        cur = next;
                    } else {
                        cur = (Map<String, Object>) existing;
                    }
                }
            }
        }
    }

    // ---------- (optional) read XML helper for debugging ----------
    @SuppressWarnings("unused")
    private static String readAllWordXml(byte[] docx) {
        StringBuilder sb = new StringBuilder(64_000);
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                String name = e.getName();
                if (name.equals("word/document.xml") ||
                        name.startsWith("word/header") ||
                        name.startsWith("word/footer")) {
                    sb.append(new String(zin.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
                }
            }
        } catch (Exception ignore) {}
        return sb.toString();
    }
}