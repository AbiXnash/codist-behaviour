import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Calls the methods a fix changed on the old and the new version with the same inputs, and reports
 * the inputs whose results differ. The model writes no code here: inputs are standard edge cases per
 * type (approach A). Usage: java Harness <bug-dir> [inputs.json for approach B is not built yet]
 *
 * bug-dir holds old/ and new/, each with classes/ and cp.txt, and methods.txt with "pkg.Class#method"
 * lines. The old version is also run against itself, to catch results that change from run to run.
 */
public class Harness {
    static final int MAX_CALLS = 300, MAX_RECEIVERS = 6, DEPTH = 4;
    static final long TIMEOUT_MS = 2000;

    // ----- recipes: how to make a value, so the same input can be built in each version's loader -----

    interface Recipe { Object make(ClassLoader l) throws Exception; String show(); }

    record Lit(Object v) implements Recipe {
        public Object make(ClassLoader l) { return v instanceof Fresh f ? f.make() : v; }
        public String show() { return v instanceof Fresh f ? f.show() : render(v, 0); }
    }

    /** A mutable value made fresh for every call, such as an empty StringBuilder. */
    interface Fresh { Object make(); String show(); }

    record StaticField(String cls, String field) implements Recipe {
        public Object make(ClassLoader l) throws Exception {
            Field f = Class.forName(cls, true, l).getDeclaredField(field);
            f.setAccessible(true);
            return f.get(null);
        }
        public String show() { return simple(cls) + "." + field; }
    }

    record New(String cls, String[] params, Recipe[] args) implements Recipe {
        public Object make(ClassLoader l) throws Exception {
            Class<?> c = Class.forName(cls, true, l);
            Class<?>[] types = new Class<?>[params.length];
            for (int i = 0; i < params.length; i++) types[i] = load(params[i], l);
            Constructor<?> k = c.getDeclaredConstructor(types);
            k.setAccessible(true);
            Object[] vals = new Object[args.length];
            for (int i = 0; i < args.length; i++) vals[i] = args[i].make(l);
            return k.newInstance(vals);
        }
        public String show() {
            StringJoiner j = new StringJoiner(", ", "new " + simple(cls) + "(", ")");
            for (Recipe a : args) j.add(a.show());
            return j.toString();
        }
    }

    record Factory(String cls, String method) implements Recipe {
        public Object make(ClassLoader l) throws Exception {
            Method m = Class.forName(cls, true, l).getDeclaredMethod(method);
            m.setAccessible(true);
            return m.invoke(null);
        }
        public String show() { return simple(cls) + "." + method + "()"; }
    }

    static Class<?> load(String name, ClassLoader l) throws ClassNotFoundException {
        return switch (name) {
            case "int" -> int.class; case "long" -> long.class; case "double" -> double.class;
            case "float" -> float.class; case "boolean" -> boolean.class; case "char" -> char.class;
            case "byte" -> byte.class; case "short" -> short.class;
            default -> Class.forName(name, false, l);
        };
    }

    static String simple(String cls) { return cls.substring(cls.lastIndexOf('.') + 1); }

    // ----- edge cases per type -----

    static final String[] STRINGS = {"", " ", "a", "abc", "a,b", "\"a\"", "a\"b", "a\nb", "a\r\nb", "\ta\t", "#a", "\\",
        "  x  ", "1", "-1", "0.5", "http://example.com/a/../b?x=1#f", "User-agent: *\nDisallow: /private\nCrawl-delay: 5",
        "<p>hi</p>", "ÿé中", "a".repeat(300)};

    static final String[] DATES = {"1600-01-01", "1999-12-31", "2010-06-15", "2020-02-29", "2049-01-01", "2100-01-01", "2200-01-01", "3001-01-01"};

    static List<Recipe> values(Class<?> t, Type generic, ClassLoader l, int depth) {
        List<Recipe> out = new ArrayList<>();
        if (t == int.class || t == Integer.class) for (int v : new int[]{0, 1, -1, 2, 10, Integer.MAX_VALUE, Integer.MIN_VALUE}) out.add(new Lit(v));
        else if (t == long.class || t == Long.class) for (long v : new long[]{0, 1, -1, 1000, Long.MAX_VALUE, Long.MIN_VALUE}) out.add(new Lit(v));
        else if (t == double.class || t == Double.class) for (double v : new double[]{0, 1.5, -1, 1999.5, 2023.25, 1e10, Double.NaN, Double.POSITIVE_INFINITY}) out.add(new Lit(v));
        else if (t == float.class || t == Float.class) for (float v : new float[]{0, 1.5f, -1, Float.NaN}) out.add(new Lit(v));
        else if (t == boolean.class || t == Boolean.class) { out.add(new Lit(true)); out.add(new Lit(false)); }
        else if (t == char.class || t == Character.class) for (char v : new char[]{'a', ',', '"', '\n', '\\', '\0'}) out.add(new Lit(v));
        else if (t == byte.class || t == Byte.class) for (byte v : new byte[]{0, 1, -1}) out.add(new Lit(v));
        else if (t == short.class || t == Short.class) for (short v : new short[]{0, 1, -1}) out.add(new Lit(v));
        else if (t == String.class || t == CharSequence.class || t == Object.class) for (String s : STRINGS) out.add(new Lit(s));
        else if (t == byte[].class) for (String s : STRINGS) out.add(new Lit(new FreshBytes(s)));
        else if (t == StringBuilder.class || t == Appendable.class || t == StringBuffer.class) out.add(new Lit(new FreshBuilder()));
        else if (t == Reader.class) for (String s : new String[]{"", "a,b\n1,2\n", "h1,h2\nx,y\n", "\"a\nb\",c\n"}) out.add(new Lit(new FreshReader(s)));
        else if (t == InputStream.class) for (String s : STRINGS) out.add(new Lit(new FreshStream(s)));
        else if (t == URL.class) for (String s : new String[]{"http://example.com/", "http://example.com/robots.txt", "https://a.b/c?d=e"}) out.add(new Lit(url(s)));
        else if (t == URI.class) for (String s : new String[]{"http://example.com/", "file:///tmp/x", "a/b"}) out.add(new Lit(URI.create(s)));
        else if (t == File.class) for (String s : new String[]{"", "missing.txt", "/tmp"}) out.add(new Lit(new File(s)));
        else if (t == Path.class) for (String s : new String[]{"missing.txt", "/tmp"}) out.add(new Lit(Path.of(s)));
        else if (t == java.time.LocalDate.class) for (String d : DATES) out.add(new Lit(java.time.LocalDate.parse(d)));
        else if (t == java.time.LocalDateTime.class) for (String d : DATES) out.add(new Lit(java.time.LocalDate.parse(d).atTime(12, 0)));
        else if (t == java.time.ZonedDateTime.class) for (String d : DATES) out.add(new Lit(java.time.LocalDate.parse(d).atTime(12, 0).atZone(java.time.ZoneOffset.UTC)));
        else if (t == java.time.Instant.class) for (String d : DATES) out.add(new Lit(java.time.LocalDate.parse(d).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        else if (t == Date.class) for (String d : DATES) out.add(new Lit(new FreshDate(d)));
        else if (t == Calendar.class || t == GregorianCalendar.class) for (String d : DATES) out.add(new Lit(new FreshCalendar(d)));
        else if (t == java.time.Duration.class) for (long sec : new long[]{0, 1, 3600, -1}) out.add(new Lit(java.time.Duration.ofSeconds(sec)));
        else if (t.isEnum()) for (Object c : t.getEnumConstants()) out.add(new StaticField(t.getName(), ((Enum<?>) c).name()));
        else if (t.isArray() && t.getComponentType() == String.class) {
            out.add(new Lit(new FreshArray(new String[0]))); out.add(new Lit(new FreshArray(new String[]{"a"})));
            out.add(new Lit(new FreshArray(new String[]{"a", "b"}))); out.add(new Lit(new FreshArray(new String[]{"-a", "--b", "c"})));
            out.add(new Lit(new FreshArray(new String[]{"", " "})));
        }
        else if (List.class.isAssignableFrom(t) || t == Collection.class || t == Iterable.class) {
            out.add(new Lit(new FreshList(List.of()))); out.add(new Lit(new FreshList(List.of("a")))); out.add(new Lit(new FreshList(List.of("a", "b"))));
        }
        else if (Set.class.isAssignableFrom(t)) { out.add(new Lit(new FreshSet(Set.of()))); out.add(new Lit(new FreshSet(Set.of("a")))); }
        else if (Map.class.isAssignableFrom(t)) { out.add(new Lit(new FreshMap(Map.of()))); out.add(new Lit(new FreshMap(Map.of("a", "1")))); }
        else if (depth > 0 && t.getClassLoader() == l) out.addAll(instances(t, l, depth - 1));
        if (!t.isPrimitive()) out.add(new Lit(null));
        return out;
    }

    /** Ways to get objects of a project class: its constants, a no-argument constructor, factories, constructors with edge cases. */
    static List<Recipe> instances(Class<?> c, ClassLoader l, int depth) {
        List<Recipe> out = new ArrayList<>();
        if (Modifier.isAbstract(c.getModifiers()) || c.isInterface()) {
            for (String name : CLASSES.getOrDefault(l, List.of())) {
                try {
                    Class<?> k = Class.forName(name, false, l);
                    if (k != c && c.isAssignableFrom(k) && !k.isInterface() && !Modifier.isAbstract(k.getModifiers()) && !k.isAnonymousClass())
                        out.addAll(instances(k, l, depth));
                } catch (Throwable ignored) { }
                if (out.size() >= MAX_RECEIVERS) break;
            }
            return out;
        }
        for (Field f : c.getDeclaredFields())
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == c) out.add(new StaticField(c.getName(), f.getName()));
        for (Method m : c.getDeclaredMethods())
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == c && m.getParameterCount() == 0 && !m.isSynthetic())
                out.add(new Factory(c.getName(), m.getName()));
        Constructor<?>[] ks = c.getDeclaredConstructors();
        Arrays.sort(ks, Comparator.comparingInt(Constructor::getParameterCount));
        for (Constructor<?> k : ks) {
            if (k.isSynthetic()) continue;
            Class<?>[] ps = k.getParameterTypes();
            String[] names = Arrays.stream(ps).map(Class::getName).toArray(String[]::new);
            if (ps.length == 0) { out.add(new New(c.getName(), names, new Recipe[0])); continue; }
            List<List<Recipe>> choices = new ArrayList<>();
            boolean ok = true;
            for (int i = 0; i < ps.length; i++) {
                List<Recipe> vs = values(ps[i], k.getGenericParameterTypes().length == ps.length ? k.getGenericParameterTypes()[i] : ps[i], l, depth);
                if (vs.isEmpty()) { ok = false; break; }
                choices.add(vs);
            }
            if (!ok) continue;
            for (Recipe[] args : combos(choices, 6)) out.add(new New(c.getName(), names, args));
            if (out.size() >= MAX_RECEIVERS * 2) break;
        }
        return out;
    }

    /** One value per parameter at a time: a base call, then each parameter through its values, then seeded random mixes. */
    static List<Recipe[]> combos(List<List<Recipe>> choices, int max) {
        List<Recipe[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Recipe[] base = new Recipe[choices.size()];
        for (int i = 0; i < base.length; i++) base[i] = firstNonNull(choices.get(i));
        addCombo(out, seen, base.clone());
        for (int i = 0; i < base.length && out.size() < max; i++)
            for (Recipe v : choices.get(i)) {
                Recipe[] c = base.clone();
                c[i] = v;
                addCombo(out, seen, c);
                if (out.size() >= max) break;
            }
        Random r = new Random(42);
        for (int n = 0; n < max * 3 && out.size() < max; n++) {
            Recipe[] c = new Recipe[base.length];
            for (int i = 0; i < c.length; i++) c[i] = choices.get(i).get(r.nextInt(choices.get(i).size()));
            addCombo(out, seen, c);
        }
        return out;
    }

    /**
     * The offset-and-length convention: a text parameter followed by int parameters is called with the
     * whole text, offset 0 and its length, which edge cases one at a time never line up.
     */
    static List<Recipe[]> spans(Class<?>[] ps, List<List<Recipe>> choices) {
        List<Recipe[]> out = new ArrayList<>();
        for (int i = 0; i < ps.length; i++) {
            if (ps[i] != String.class && ps[i] != CharSequence.class) continue;
            List<Integer> ints = new ArrayList<>();
            for (int j = i + 1; j < ps.length && ints.size() < 2; j++) if (ps[j] == int.class) ints.add(j);
            if (ints.isEmpty()) continue;
            for (Recipe v : choices.get(i)) {
                if (!(v instanceof Lit lit) || !(lit.v() instanceof String text)) continue;
                Recipe[] c = new Recipe[ps.length];
                for (int k = 0; k < c.length; k++) c[k] = firstNonNull(choices.get(k));
                c[i] = v;
                if (ints.size() == 2) { c[ints.get(0)] = new Lit(0); c[ints.get(1)] = new Lit(text.length()); }
                else c[ints.get(0)] = new Lit(text.length());
                out.add(c);
            }
        }
        return out;
    }

    static void addCombo(List<Recipe[]> out, Set<String> seen, Recipe[] c) {
        StringBuilder k = new StringBuilder();
        for (Recipe r : c) k.append(r.show()).append('\u0001');
        if (seen.add(k.toString())) out.add(c);
    }

    static Recipe firstNonNull(List<Recipe> vs) {
        for (Recipe v : vs) if (!(v instanceof Lit lit && lit.v() == null)) return v;
        return vs.get(0);
    }

    // ----- running -----

    record Outcome(String result, String error, String args, String state) {
        String key() { return seen() + " | state " + state; }
        /** What a caller can see: the result or the exception, and the arguments after the call. */
        String seen() { return error != null ? "throws " + error : result + " | args " + args; }
    }

    static Outcome call(ClassLoader l, Member m, Recipe receiver, Recipe[] args) {
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        try {
            Future<Outcome> f = ex.submit(() -> {
                Thread.currentThread().setContextClassLoader(l);
                Object recv = null;
                try {
                    recv = receiver == null ? null : receiver.make(l);
                } catch (Throwable e) {
                    return new Outcome(null, "cannot make receiver: " + cause(e), "", "");
                }
                Object[] vals = new Object[args.length];
                try {
                    for (int i = 0; i < args.length; i++) vals[i] = args[i].make(l);
                } catch (Throwable e) {
                    return new Outcome(null, "cannot make input: " + cause(e), "", "");
                }
                Member target = relink(m, l);
                try {
                    Object res = target instanceof Method mm ? mm.invoke(recv, vals) : ((Constructor<?>) target).newInstance(vals);
                    return new Outcome(render(res, 0), null, render(vals, 0), render(recv, 0));
                } catch (InvocationTargetException e) {
                    return new Outcome(null, cause(e.getCause()), render(vals, 0), "");
                }
            });
            return f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new Outcome(null, "timeout", "", "");
        } catch (Throwable e) {
            return new Outcome(null, "harness: " + cause(e), "", "");
        } finally {
            ex.shutdownNow();
        }
    }

    static String cause(Throwable e) {
        while (e instanceof InvocationTargetException || e instanceof ExecutionException) e = e.getCause();
        return e == null ? "null" : e.getClass().getName();
    }

    /** The same method, found again in another version's loader. */
    static Member relink(Member m, ClassLoader l) throws Exception {
        Class<?> c = Class.forName(m.getDeclaringClass().getName(), true, l);
        Class<?>[] ps = m instanceof Method mm ? mm.getParameterTypes() : ((Constructor<?>) m).getParameterTypes();
        Class<?>[] types = new Class<?>[ps.length];
        for (int i = 0; i < ps.length; i++) types[i] = load(ps[i].getName(), l);
        if (m instanceof Method) {
            Method r = c.getDeclaredMethod(m.getName(), types);
            r.setAccessible(true);
            return r;
        }
        Constructor<?> r = c.getDeclaredConstructor(types);
        r.setAccessible(true);
        return r;
    }

    /** A value as text, comparable between versions: no identity hashes, project objects by their fields. */
    static String render(Object v, int depth) {
        if (v == null) return "null";
        Class<?> c = v.getClass();
        if (v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character || v instanceof Enum<?>)
            return v instanceof String s ? '"' + s + '"' : String.valueOf(v);
        if (v instanceof CharSequence cs) return '"' + cs.toString() + '"';
        if (c.isArray()) {
            StringJoiner j = new StringJoiner(", ", "[", "]");
            for (int i = 0; i < Array.getLength(v) && i < 50; i++) j.add(render(Array.get(v, i), depth + 1));
            return j.toString();
        }
        if (depth > DEPTH) return c.getSimpleName();
        if (v instanceof Map<?, ?> m) {
            List<String> es = new ArrayList<>();
            for (Map.Entry<?, ?> e : m.entrySet()) es.add(render(e.getKey(), depth + 1) + "=" + render(e.getValue(), depth + 1));
            if (!(v instanceof SortedMap) && !(v instanceof LinkedHashMap)) Collections.sort(es);
            return "{" + String.join(", ", es) + "}";
        }
        if (v instanceof Iterable<?> it) {
            List<String> es = new ArrayList<>();
            for (Object o : it) { es.add(render(o, depth + 1)); if (es.size() > 50) break; }
            if (v instanceof Set && !(v instanceof SortedSet) && !(v instanceof LinkedHashSet)) Collections.sort(es);
            return "[" + String.join(", ", es) + "]";
        }
        if (c.getClassLoader() instanceof URLClassLoader) { // a project object: its fields
            StringJoiner j = new StringJoiner(", ", c.getSimpleName() + "{", "}");
            for (Class<?> k = c; k != null && k.getClassLoader() instanceof URLClassLoader; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || !inBoth(k, f.getName())) continue;
                    try {
                        f.setAccessible(true);
                        j.add(f.getName() + "=" + render(f.get(v), depth + 1));
                    } catch (Throwable e) {
                        j.add(f.getName() + "=?");
                    }
                }
            return j.toString();
        }
        try {
            if (c.getMethod("toString").getDeclaringClass() != Object.class) return v.toString();
        } catch (Throwable ignored) { }
        return c.getSimpleName();
    }

    // ----- fresh mutable values -----

    record FreshDate(String d) implements Fresh { public Object make() { return Date.from(java.time.LocalDate.parse(d).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()); } public String show() { return "date(" + d + ")"; } }
    record FreshCalendar(String d) implements Fresh {
        public Object make() { GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC")); c.setTime(Date.from(java.time.LocalDate.parse(d).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())); return c; }
        public String show() { return "calendar(" + d + ")"; }
    }
    record FreshBuilder() implements Fresh { public Object make() { return new StringBuilder(); } public String show() { return "new StringBuilder()"; } }
    record FreshBytes(String s) implements Fresh { public Object make() { return s.getBytes(StandardCharsets.UTF_8); } public String show() { return "bytes(" + render(s, 0) + ")"; } }
    record FreshReader(String s) implements Fresh { public Object make() { return new StringReader(s); } public String show() { return "reader(" + render(s, 0) + ")"; } }
    record FreshStream(String s) implements Fresh { public Object make() { return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)); } public String show() { return "stream(" + render(s, 0) + ")"; } }
    record FreshArray(String[] a) implements Fresh { public Object make() { return a.clone(); } public String show() { return render(a, 0); } }
    record FreshList(List<String> a) implements Fresh { public Object make() { return new ArrayList<>(a); } public String show() { return render(a, 0); } }
    record FreshSet(Set<String> a) implements Fresh { public Object make() { return new LinkedHashSet<>(a); } public String show() { return render(a, 0); } }
    record FreshMap(Map<String, String> a) implements Fresh { public Object make() { return new LinkedHashMap<>(a); } public String show() { return render(a, 0); } }

    static URL url(String s) { try { return URI.create(s).toURL(); } catch (Exception e) { throw new RuntimeException(e); } }

    /** Each version's loader and the loader it is compared with: a field only one version has is not compared. */
    static final Map<ClassLoader, ClassLoader> PAIR = new HashMap<>();

    static boolean inBoth(Class<?> k, String field) {
        ClassLoader other = PAIR.get(k.getClassLoader());
        if (other == null) return true;
        try {
            Class.forName(k.getName(), false, other).getDeclaredField(field);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** The project's own class names, per version, to find subclasses of abstract types. */
    static final Map<ClassLoader, List<String>> CLASSES = new HashMap<>();

    static URLClassLoader loader(Path version) throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(version.resolve("classes").toUri().toURL());
        Path cp = version.resolve("cp.txt");
        if (Files.exists(cp)) for (String p : Files.readString(cp).trim().split(File.pathSeparator)) if (!p.isBlank()) urls.add(Path.of(p).toUri().toURL());
        URLClassLoader l = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        Path classes = version.resolve("classes");
        try (var files = Files.walk(classes)) {
            CLASSES.put(l, files.map(p -> classes.relativize(p).toString()).filter(p -> p.endsWith(".class"))
                .map(p -> p.substring(0, p.length() - 6).replace(File.separatorChar, '.')).sorted().toList());
        }
        return l;
    }

    // ----- approach B: values the model suggested, as JSON -----

    /** A small JSON reader: objects, arrays, strings, numbers, booleans and null. */
    static final class Json {
        final String s; int i;
        Json(String s) { this.s = s; }
        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) { ws(); String k = (String) value(); ws(); i++; m.put(k, value()); ws(); if (s.charAt(i++) == '}') return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(value()); ws(); if (s.charAt(i++) == ']') return l; } }
            if (c == '"') { StringBuilder b = new StringBuilder(); i++;
                while (s.charAt(i) != '"') {
                    char ch = s.charAt(i++);
                    if (ch == '\\') { char e = s.charAt(i++);
                        switch (e) { case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r'); case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                            case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; } default -> b.append(e); } }
                    else b.append(ch); }
                i++; return b.toString(); }
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i);
            return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
        }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    }

    static final Object NO = new Object(); // a value that does not fit the parameter

    /** One suggested value as a recipe for parameter type t, NO if it does not fit, or null to use approach A's value. */
    static Object fit(Object v, Class<?> t) {
        try {
            if (v == null) return t.isPrimitive() ? NO : new Lit(null);
            boolean str = v instanceof String, num = v instanceof Number;
            if (t == String.class || t == CharSequence.class) return str ? new Lit(v) : NO;
            if (t == Object.class) return v instanceof List ? NO : new Lit(v instanceof Long l ? (Object) l.intValue() : v);
            if (t == int.class || t == Integer.class) return num ? new Lit(((Number) v).intValue()) : str ? new Lit(Integer.parseInt((String) v)) : NO;
            if (t == long.class || t == Long.class) return num ? new Lit(((Number) v).longValue()) : NO;
            if (t == double.class || t == Double.class) return num ? new Lit(((Number) v).doubleValue()) : NO;
            if (t == float.class || t == Float.class) return num ? new Lit(((Number) v).floatValue()) : NO;
            if (t == short.class || t == Short.class) return num ? new Lit(((Number) v).shortValue()) : NO;
            if (t == byte.class || t == Byte.class) return num ? new Lit(((Number) v).byteValue()) : NO;
            if (t == boolean.class || t == Boolean.class) return v instanceof Boolean ? new Lit(v) : NO;
            if (t == char.class || t == Character.class) return str && ((String) v).length() == 1 ? new Lit(((String) v).charAt(0)) : NO;
            if (t == byte[].class) return str ? new Lit(new FreshBytes((String) v)) : NO;
            if (t == InputStream.class) return str ? new Lit(new FreshStream((String) v)) : NO;
            if (t == Reader.class) return str ? new Lit(new FreshReader((String) v)) : NO;
            if (t == StringBuilder.class || t == Appendable.class || t == StringBuffer.class) return new Lit(new FreshBuilder());
            if (t == URL.class) return str ? new Lit(url((String) v)) : NO;
            if (t == URI.class) return str ? new Lit(URI.create((String) v)) : NO;
            if (t == File.class) return str ? new Lit(new File((String) v)) : NO;
            if (t == Path.class) return str ? new Lit(Path.of((String) v)) : NO;
            if (t == java.time.LocalDate.class) return str ? new Lit(java.time.LocalDate.parse((String) v)) : NO;
            if (t == Date.class) return str ? new Lit(new FreshDate((String) v)) : NO;
            if (t == Calendar.class || t == GregorianCalendar.class) return str ? new Lit(new FreshCalendar((String) v)) : NO;
            if (t.isEnum()) { if (!str) return NO; for (Object c : t.getEnumConstants()) if (((Enum<?>) c).name().equals(v)) return new StaticField(t.getName(), (String) v); return NO; }
            if (t.isArray() && t.getComponentType() == String.class) return v instanceof List<?> l && l.stream().allMatch(x -> x instanceof String) ? new Lit(new FreshArray(l.toArray(String[]::new))) : NO;
            if (List.class.isAssignableFrom(t) || t == Collection.class || t == Iterable.class) return v instanceof List<?> l ? new Lit(new FreshList(l.stream().map(String::valueOf).toList())) : NO;
            if (Set.class.isAssignableFrom(t)) return v instanceof List<?> l ? new Lit(new FreshSet(new LinkedHashSet<>(l.stream().map(String::valueOf).toList()))) : NO;
        } catch (RuntimeException e) {
            return NO;
        }
        return null; // a project object: approach A builds it
    }

    @SuppressWarnings("unchecked")
    static List<Recipe[]> suggested(Path bug, String key, Class<?>[] ps, List<List<Recipe>> choices, int[] dropped) throws IOException {
        Path f = bug.resolve("inputs.json");
        if (!Files.exists(f)) return List.of();
        Map<String, Object> all = (Map<String, Object>) new Json(Files.readString(f)).value();
        List<Recipe[]> out = new ArrayList<>();
        for (Object call : (List<Object>) all.getOrDefault(key, List.of())) {
            if (!(call instanceof List<?> given)) { dropped[0]++; continue; }
            List<Object> args = new ArrayList<>(given);
            if (ps.length == 0) { out.add(new Recipe[0]); continue; } // a method with no parameters: any call is the call
            // One array or list parameter: the model's list of strings is the array itself.
            if (ps.length == 1 && (ps[0].isArray() || Collection.class.isAssignableFrom(ps[0])) && !(args.size() == 1 && args.get(0) instanceof List))
                args = new ArrayList<>(List.of(args));
            Recipe[] r = new Recipe[ps.length];
            boolean ok = true;
            if (args.size() == ps.length) {
                for (int i = 0; i < ps.length && ok; i++) {
                    Object v = fit(args.get(i), ps[i]);
                    if (v == NO) ok = false;
                    else r[i] = v == null ? firstNonNull(choices.get(i)) : (Recipe) v;
                }
            } else if (args.size() < ps.length) {
                // Fewer values than parameters: each goes to the next parameter it fits; approach A fills the rest.
                int next = 0;
                for (Object v : args) {
                    while (next < ps.length && (fit(v, ps[next]) == NO || fit(v, ps[next]) == null)) next++;
                    if (next == ps.length) { ok = false; break; }
                    r[next] = (Recipe) fit(v, ps[next]);
                    next++;
                }
                for (int i = 0; i < ps.length; i++) if (r[i] == null) r[i] = firstNonNull(choices.get(i));
            } else ok = false;
            if (ok) out.add(r); else dropped[0]++;
        }
        return out;
    }

    public static void main(String[] a) throws Exception {
        Path bug = Path.of(a[0]);
        boolean modelInputs = Arrays.asList(a).contains("B"), json = Arrays.asList(a).contains("--json");
        List<String[]> found = new ArrayList<>(); // method, call, old, new
        List<String> notes = new ArrayList<>();
        int[] dropped = {0};
        ClassLoader oldL = loader(bug.resolve("old")), newL = loader(bug.resolve("new")), oldL2 = loader(bug.resolve("old"));
        PAIR.put(oldL, newL); PAIR.put(newL, oldL); PAIR.put(oldL2, newL);
        int calls = 0, ran = 0, diffs = 0, flaky = 0, stateOnly = 0, nullOnly = 0;
        List<String> examples = new ArrayList<>();
        PrintStream realOut = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); // the code under test may print
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        for (String line : Files.readAllLines(bug.resolve("methods.txt"))) {
            String[] parts = line.trim().split("#");
            if (parts.length != 2 || parts[1].startsWith("<")) continue;
            Class<?> c;
            try { c = Class.forName(parts[0], false, oldL); } catch (Throwable e) { note(examples, notes, "no class " + parts[0]); continue; }
            List<Member> targets = new ArrayList<>();
            if (parts[1].equals(c.getSimpleName())) targets.addAll(Arrays.asList(c.getDeclaredConstructors()));
            else for (Method m : c.getDeclaredMethods()) if (m.getName().equals(parts[1]) && !m.isSynthetic()) targets.add(m);
            if (targets.isEmpty()) note(examples, notes, parts[1] + ": not in the old code");
            for (Member m : targets) {
                if (m.getName().equals("main")) { note(examples, notes, "main: skipped, it may exit"); continue; }
                try { relink(m, newL); } catch (Throwable e) { note(examples, notes, parts[1] + ": signature changed, cannot compare"); continue; }
                Class<?>[] ps = m instanceof Method mm ? mm.getParameterTypes() : ((Constructor<?>) m).getParameterTypes();
                Type[] gs = m instanceof Method mm ? mm.getGenericParameterTypes() : ((Constructor<?>) m).getGenericParameterTypes();
                List<List<Recipe>> choices = new ArrayList<>();
                for (int i = 0; i < ps.length; i++) choices.add(values(ps[i], gs.length == ps.length ? gs[i] : ps[i], oldL, 1));
                if (choices.stream().anyMatch(List::isEmpty)) { note(examples, notes, parts[1] + ": no inputs for its parameters"); continue; }
                List<Recipe> receivers = new ArrayList<>();
                if (m instanceof Method mm && !Modifier.isStatic(mm.getModifiers())) {
                    receivers.addAll(instances(c, oldL, 1));
                    if (receivers.size() > MAX_RECEIVERS) receivers = receivers.subList(0, MAX_RECEIVERS);
                    if (receivers.isEmpty()) { note(examples, notes, parts[1] + ": no way to make a " + c.getSimpleName()); continue; }
                } else receivers.add(null);
                List<Recipe[]> inputs;
                if (modelInputs) inputs = suggested(bug, line.trim(), ps, choices, dropped);
                else {
                    inputs = new ArrayList<>(ps.length == 0 ? List.<Recipe[]>of(new Recipe[0]) : combos(choices, MAX_CALLS / receivers.size()));
                    inputs.addAll(0, spans(ps, choices));
                }
                for (Recipe recv : receivers)
                    for (Recipe[] in : inputs) {
                        calls++;
                        Outcome o = call(oldL, m, recv, in), n = call(newL, m, recv, in), o2 = call(oldL2, m, recv, in);
                        if (o.error() != null && (o.error().startsWith("cannot make") || o.error().startsWith("harness"))) continue;
                        ran++;
                        if (!o.key().equals(o2.key())) { flaky++; continue; }
                        if (o.seen().equals(n.seen())) {
                            if (!o.key().equals(n.key())) stateOnly++; // only private fields differ: not shown as a difference
                            continue;
                        }
                        StringBuilder shown = new StringBuilder(recv == null ? "" : recv.show());
                        for (Recipe r : in) shown.append(' ').append(r.show());
                        boolean npe = "java.lang.NullPointerException".equals(o.error()) || "java.lang.NullPointerException".equals(n.error());
                        if (npe && NULL.matcher(shown).find()) { nullOnly++; continue; } // only because the harness passed null
                        {
                            diffs++;
                            StringJoiner j = new StringJoiner(", ", parts[1] + "(", ")");
                            for (Recipe r : in) j.add(r.show());
                            String shownCall = (recv == null ? "" : recv.show() + ".") + j;
                            if (found.size() < 20) found.add(new String[]{parts[0] + "#" + parts[1], shownCall, cut(o.seen()), cut(n.seen())});
                            if (examples.size() < 6) examples.add(shownCall + "\n      old: " + cut(o.key()) + "\n      new: " + cut(n.key()));
                        }
                    }
            }
        }
        System.setOut(realOut);
        if (json) {
            StringBuilder b = new StringBuilder();
            b.append(String.format("{\"calls\":%d,\"ran\":%d,\"differ\":%d,\"flaky\":%d,\"stateOnly\":%d,\"nullOnly\":%d,\"differences\":[",
                calls, ran, diffs, flaky, stateOnly, nullOnly));
            for (int i = 0; i < found.size(); i++) {
                String[] f = found.get(i);
                b.append(i > 0 ? "," : "").append("{\"method\":").append(quote(f[0])).append(",\"call\":").append(quote(f[1]))
                    .append(",\"old\":").append(quote(f[2])).append(",\"new\":").append(quote(f[3])).append('}');
            }
            b.append("],\"notes\":[");
            for (int i = 0; i < notes.size(); i++) b.append(i > 0 ? "," : "").append(quote(notes.get(i)));
            realOut.println(b.append("]}"));
            System.exit(0);
        }
        realOut.printf("calls=%d ran=%d differ=%d flaky=%d state-only=%d null-only=%d%s%n", calls, ran, diffs, flaky, stateOnly, nullOnly,
            modelInputs ? " dropped=" + dropped[0] : "");
        for (String e : examples) realOut.println("  " + e);
        System.exit(0);
    }

    static final java.util.regex.Pattern NULL = java.util.regex.Pattern.compile("(^|[\\s(,\\[])null([\\s),\\]]|$)");

    static void note(List<String> examples, List<String> notes, String text) {
        examples.add(text);
        if (!notes.contains(text)) notes.add(text);
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    static String cut(String s) { return s.length() > 220 ? s.substring(0, 220) + "…" : s; }
}
