package com.capability.pdfgeneration.service.service.pdfGenerationServices;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.template.ElementTemplate;
import com.deepoove.poi.template.MetaTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class DocxVariableExtractorService {

    private final Configure cfg = Configure.builder().useSpringEL().build();

    // ---- Regex helpers ----
    private static final Pattern STRING_LIT =
            Pattern.compile("(?s)(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')");

    private static final Pattern BRACKET_KEY =
            Pattern.compile("\\[['\"]([^'\"\\]]+)['\"]\\]");

    private static final Pattern PROP_CHAIN =
            Pattern.compile("\\b([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\b(?!\\s*\\()");

    private static final Pattern BARE_IDENT =
            Pattern.compile("\\b([A-Za-z_][\\w]*)\\b(?!\\s*\\()");

    private static final Pattern INDEXED_BASE =
            Pattern.compile("\\b([A-Za-z_][\\w]*)\\s*\\[\\s*\\d+\\s*\\]");

    private static final Pattern PLAIN_VAR =
            Pattern.compile("\\{\\{\\s*(?!\\?)\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    private static final Pattern IF_EXPR =
            Pattern.compile("\\{\\{\\s*\\?\\s*(.+?)\\s*}}", Pattern.DOTALL);

    private static final Set<String> KEYWORDS;
    static {
        Set<String> k = new HashSet<String>();
        Collections.addAll(k, "and","or","not","true","false","null","this","root","T","new","class");
        Collections.addAll(k, "size","length","matches","startsWith","endsWith","contains",
                "toLowerCase","toUpperCase","equalsIgnoreCase");
        KEYWORDS = Collections.unmodifiableSet(k);
    }

    private enum Cat { OPTIONAL, REQUIRED }

    /**
     * Returns a JSON-serializable map like:
     * {"variable1":"optional","variable2":"required"}
     */
    public Map<String, String> extractVariableNames(byte[] docxBytes) {
        // Work map that tracks the strongest category seen per variable
        Map<String, Cat> cats = new LinkedHashMap<String, Cat>();

        // Pass 1: poi-tl (fast) — good for plain {{var}}; may miss complex conditionals
        try (ByteArrayInputStream in = new ByteArrayInputStream(docxBytes);
             XWPFTemplate tpl = XWPFTemplate.compile(in, cfg)) {

            for (MetaTemplate mt : tpl.getElementTemplates()) {
                if (!(mt instanceof ElementTemplate)) continue;
                ElementTemplate et = (ElementTemplate) mt;
                Character sign = et.getSign();      // null (plain), '?', etc.
                String body = et.getTagName();      // for '?' may be null when complex
                String src  = et.getSource();

                if (sign == null) {
                    addPlain(cats, body);
                } else if (sign.charValue() == '?') {
                    collectFromExpr(body, cats, true);
                    collectFromExpr(src,  cats, true);
                } else {
                    addPlain(cats, body);
                }
            }
        } catch (Exception ignore) {
            // We’ll still do a raw XML scan below.
        }

        // Pass 2: raw XML (robust) — catches {{? ...}} across split runs
        try {
            String text = readWordText(docxBytes); // strip XML tags → contiguous text
            // a) conditionals
            Matcher ce = IF_EXPR.matcher(text);
            while (ce.find()) {
                String expr = ce.group(1);
                collectFromExpr(expr, cats, true);     // REQUIRED
            }
            // b) plain vars missed by poi-tl (rare)
            Matcher pv = PLAIN_VAR.matcher(text);
            while (pv.find()) {
                String name = pv.group(1);
                addPlain(cats, name);                  // OPTIONAL
            }
        } catch (Exception ignore) { }

        // Produce a sorted, JSON-ready map {"var":"optional"|"required"}
        List<String> keys = new ArrayList<String>(cats.keySet());
        Collections.sort(keys);
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String k : keys) {
            out.put(k, cats.get(k) == Cat.REQUIRED ? "required" : "optional");
        }
        return out;
    }

    // ---- Core mining ----

    private static void addPlain(Map<String, Cat> out, String s) {
        if (s == null) return;
        String t = s.trim();
        if (t.isEmpty()) return;
        if (t.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            upgrade(out, t, Cat.OPTIONAL);
        }
    }

    private static void collectFromExpr(String raw, Map<String, Cat> out, boolean required) {
        if (raw == null) return;
        String expr = stripBracesIfPresent(raw);
        if (expr.isEmpty()) return;

        Cat cat = required ? Cat.REQUIRED : Cat.OPTIONAL;

        // 1) literal map keys: #root['eligible'] → eligible
        Matcher bk = BRACKET_KEY.matcher(expr);
        while (bk.find()) {
            String key = bk.group(1);
            if (key != null) {
                String t = key.trim();
                if (!t.isEmpty() && !isKeyword(t)) upgrade(out, t, cat);
            }
        }

        // 2) remove quoted strings so "owner" isn’t treated as variable
        String noStrings = STRING_LIT.matcher(expr).replaceAll(" ");

        // 3) indexed bases: users[0] → users
        Matcher ib = INDEXED_BASE.matcher(noStrings);
        while (ib.find()) {
            String base = ib.group(1);
            if (base != null && !base.isEmpty() && !isKeyword(base)) upgrade(out, base, cat);
        }

        // 4) dotted chains and single names, excluding method calls
        Matcher pc = PROP_CHAIN.matcher(noStrings);
        while (pc.find()) {
            String chain = pc.group(1);
            if (chain == null || chain.isEmpty()) continue;
            String head = headOf(chain);
            if (isKeyword(chain) || isKeyword(head)) continue;
            upgrade(out, chain, cat);
        }

        // 5) bare identifiers (safety net when dots get split)
        Matcher bi = BARE_IDENT.matcher(noStrings);
        while (bi.find()) {
            String id = bi.group(1);
            if (id == null || id.isEmpty()) continue;
            if (isKeyword(id)) continue;
            if (hasChainStartingWith(out.keySet(), id)) continue;
            upgrade(out, id, cat);
        }
    }

    private static void upgrade(Map<String, Cat> map, String key, Cat incoming) {
        Cat cur = map.get(key);
        if (cur == null) {
            map.put(key, incoming);
            return;
        }
        // REQUIRED overrides OPTIONAL; never downgrade REQUIRED
        if (cur == Cat.OPTIONAL && incoming == Cat.REQUIRED) {
            map.put(key, Cat.REQUIRED);
        }
    }

    private static String stripBracesIfPresent(String s) {
        String t = s.trim();
        if (t.startsWith("{{")) t = t.substring(2);
        if (t.endsWith("}}")) t = t.substring(0, t.length() - 2);
        if (t.startsWith("?"))  t = t.substring(1);
        return t.trim();
    }

    private static boolean hasChainStartingWith(Set<String> set, String head) {
        for (String s : set) {
            if (s.equals(head)) return true;
            if (s.startsWith(head + ".")) return true;
        }
        return false;
    }

    private static boolean isKeyword(String s) {
        return s != null && KEYWORDS.contains(s.toLowerCase(Locale.ROOT));
    }

    private static String headOf(String chain) {
        int dot = chain.indexOf('.');
        return dot < 0 ? chain : chain.substring(0, dot);
    }

    // ---- DOCX XML → plain text (merge across runs) ----

    private static String readWordText(byte[] docx) throws Exception {
        StringBuilder sb = new StringBuilder(131072);
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(docx));
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if ("word/document.xml".equals(name) ||
                        name.startsWith("word/header") ||
                        name.startsWith("word/footer")) {

                    String xml = new String(readAll(zin), StandardCharsets.UTF_8);
                    // remove XML tags; keep raw text so {{?…}} is contiguous
                    String text = xml.replaceAll("<[^>]+>", "");
                    sb.append(text).append('\n');
                }
            }
        } finally {
            try { zin.close(); } catch (Exception ignore) {}
        }
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}