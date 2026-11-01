import java.io.File;
import java.nio.file.*;
import java.util.*;

public class DBAppCheck {
    private static final Random random = new Random(42);
    private static final String[] NAMES = {"Ahmed", "Dalia", "John", "Zaky", "Mona", "Omar"};

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("dbapp-check");
        System.setProperty("dbapp.home", home.toString());
        try {
            typesAndValidation();
            precedenceExample();
            indexedMatchesUnindexed(home);
            System.out.println("ALL CHECKS PASSED");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(home)) {
                files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition)
            throw new AssertionError(what);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void rejects(String what, Action action) throws Exception {
        try {
            action.run();
        } catch (DBAppException e) {
            return;
        }
        throw new AssertionError("Should have been rejected: " + what);
    }

    private static Hashtable<String, Object> row(Object... pairs) {
        Hashtable<String, Object> row = new Hashtable<>();
        for (int i = 0; i < pairs.length; i += 2)
            row.put((String) pairs[i], pairs[i + 1]);
        return row;
    }

    private static void create(DBApp db, String table, String key, String... columns) throws Exception {
        Hashtable<String, String> types = new Hashtable<>(), mins = new Hashtable<>(), maxes = new Hashtable<>();
        for (String c : columns) {
            String[] p = c.split(":");
            types.put(p[0], p[1]);
            mins.put(p[0], p[2]);
            maxes.put(p[0], p[3]);
        }
        db.createTable(table, key, types, mins, maxes);
    }

    private static Date date(int year, int month, int day) throws DBAppException {
        return (Date) Util.parse("java.util.Date", String.format("%04d-%02d-%02d", year, month, day));
    }

    private static void typesAndValidation() throws Exception {
        DBApp db = new DBApp();

        create(db, "ByString", "k", "k:java.lang.String:A:ZZZZZZZZZZ", "a:java.lang.Integer:0:100", "b:java.lang.Double:0:1");
        create(db, "ByDate", "k", "k:java.util.Date:2000-01-01:2030-12-31", "a:java.lang.Integer:0:100", "b:java.lang.Double:0:1");
        create(db, "ByDouble", "k", "k:java.lang.Double:0:1000", "a:java.lang.Integer:0:100", "b:java.lang.Double:0:1");
        db.createIndex("ByString", new String[]{"k", "a", "b"});
        db.createIndex("ByDate", new String[]{"k", "a", "b"});
        db.createIndex("ByDouble", new String[]{"k", "a", "b"});
        for (int i = 50; i > 0; i--) {
            db.insertIntoTable("ByString", row("k", "K" + (100 + i), "a", i, "b", 0.5));
            db.insertIntoTable("ByDate", row("k", date(2000 + i % 30, 1 + i % 12, 1 + i % 28), "a", i, "b", 0.5));
            db.insertIntoTable("ByDouble", row("k", i + 0.25, "a", i, "b", 0.5));
        }
        db.updateTable("ByString", "K120", row("a", 99));
        db.updateTable("ByDate", "2020-09-21", row("a", 99)); // i = 20
        db.updateTable("ByDouble", "20.25", row("a", 99));
        for (String[] t : new String[][]{{"ByString", "K120"}, {"ByDate", null}, {"ByDouble", null}}) {
            Iterator it = db.selectFromTable(new SQLTerm[]{new SQLTerm(t[0], "a", "=", 99)}, new String[0]);
            check(it.hasNext() && ((Tuple) it.next()).get("a").equals(99) && !it.hasNext(), "update by key on " + t[0]);
        }

        Hashtable<String, String> types = new Hashtable<>(), mins = new Hashtable<>(), maxes = new Hashtable<>();
        types.put("id", "java.lang.Integer"); mins.put("id", "0"); maxes.put("id", "100");
        types.put("gpa", "java.lang.double"); mins.put("gpa", "0"); maxes.put("gpa", "5");
        types.put("name", "java.lang.String"); mins.put("name", "A"); maxes.put("name", "zzzz");
        db.createTable("S", "id", types, mins, maxes);
        db.insertIntoTable("S", row("id", 1, "gpa", 1.5, "name", "Ali"));

        rejects("wrong type", () -> db.insertIntoTable("S", row("id", "1")));
        rejects("no key", () -> db.insertIntoTable("S", row("gpa", 1.0)));
        rejects("duplicate key", () -> db.insertIntoTable("S", row("id", 1)));
        rejects("above max", () -> db.insertIntoTable("S", row("id", 101)));
        rejects("string above max", () -> db.insertIntoTable("S", row("id", 3, "name", "~~")));
        rejects("string below min", () -> db.insertIntoTable("S", row("id", 3, "name", "0")));
        rejects("unknown column", () -> db.insertIntoTable("S", row("id", 2, "nope", 1)));
        rejects("unknown table", () -> db.insertIntoTable("Nope", row("id", 2)));
        rejects("existing table", () -> create(db, "S", "id", "id:java.lang.Integer:0:1"));
        rejects("bad type", () -> create(db, "T", "id", "id:java.lang.Long:0:1"));
        rejects("min above max", () -> create(db, "T", "id", "id:java.lang.Integer:5:1"));
        rejects("path in name", () -> create(db, "../T", "id", "id:java.lang.Integer:0:1"));
        rejects("key not a column", () -> create(db, "T", "x", "id:java.lang.Integer:0:1"));
        rejects("index of 1", () -> db.createIndex("S", new String[]{"gpa"}));
        rejects("index of 2", () -> db.createIndex("S", new String[]{"gpa", "id"}));
        rejects("repeated index column", () -> db.createIndex("S", new String[]{"gpa", "id", "id"}));
        rejects("update key", () -> db.updateTable("S", "1", row("id", 2)));
        rejects("bad key text", () -> db.updateTable("S", "abc", row("gpa", 2.0)));
        rejects("delete wrong type", () -> db.deleteFromTable("S", row("gpa", "x")));
        rejects("bad term operator", () -> db.selectFromTable(new SQLTerm[]{new SQLTerm("S", "id", "~", 1)}, new String[0]));
        rejects("bad connector", () -> db.selectFromTable(new SQLTerm[]{new SQLTerm("S", "id", "=", 1), new SQLTerm("S", "id", "=", 1)}, new String[]{"NAND"}));
        rejects("wrong connector count", () -> db.selectFromTable(new SQLTerm[]{new SQLTerm("S", "id", "=", 1)}, new String[]{"OR"}));
        rejects("join", () -> db.selectFromTable(new SQLTerm[]{new SQLTerm("S", "id", "=", 1), new SQLTerm("ByDate", "a", "=", 1)}, new String[]{"OR"}));
        rejects("term wrong type", () -> db.selectFromTable(new SQLTerm[]{new SQLTerm("S", "id", "=", "1")}, new String[0]));

        db.insertIntoTable("S", row("id", 2));
        check(count(db.selectFromTable(new SQLTerm[]{new SQLTerm("S", "gpa", "!=", 9.0)}, new String[0])) == 1, "NULL matches nothing");
        db.updateTable("S", "77", row("gpa", 2.0));
        db.deleteFromTable("S", row("id", 77));
        db.deleteFromTable("S", new Hashtable<>());
        check(count(db.selectFromTable(new SQLTerm[]{new SQLTerm("S", "id", ">=", 0)}, new String[0])) == 0, "delete all");
    }

    private static int count(Iterator it) {
        int n = 0;
        for (; it.hasNext(); it.next())
            n++;
        return n;
    }

    private static void precedenceExample() throws Exception {
        DBApp db = new DBApp();
        create(db, "P", "id", "id:java.lang.Integer:0:10", "x:java.lang.Integer:0:10");
        db.insertIntoTable("P", row("id", 1, "x", 1));
        SQLTerm yes = new SQLTerm("P", "id", "=", 1), no = new SQLTerm("P", "id", "=", 2);
        String[][] cases = {{"OR", "AND"}, {"XOR", "AND"}, {"XOR", "OR"}, {"OR", "XOR"}};
        SQLTerm[][] terms = {{yes, no, no}, {yes, yes, yes}, {yes, yes, yes}, {yes, yes, yes}};
        // yes OR (no AND no) = true; yes XOR (yes AND yes) = false;
        // (yes XOR yes) OR yes = true; yes OR (yes XOR yes) = true
        boolean[] expected = {true, false, true, true};
        for (int i = 0; i < cases.length; i++)
            check(db.selectFromTable(terms[i], cases[i]).hasNext() == expected[i], "precedence " + Arrays.toString(cases[i]));
    }

    private static void indexedMatchesUnindexed(Path home) throws Exception {
        String[] schema = {"id:java.lang.Integer:0:1000000", "name:java.lang.String:A:ZZZZZZZZZZ",
                "gpa:java.lang.Double:0:5", "born:java.util.Date:1990-01-01:2010-12-31", "age:java.lang.Integer:0:100"};
        DBApp db = new DBApp();
        create(db, "Plain", "id", schema);
        create(db, "Indexed", "id", schema);

        TreeMap<Integer, Hashtable<String, Object>> model = new TreeMap<>();
        for (int i = 0; i < 1500; i++)
            addRandomRow(db, model);
        db.createIndex("Indexed", new String[]{"name", "gpa", "born"});
        for (int i = 0; i < 1500; i++)
            addRandomRow(db, model);
        checkLayout(home, "Indexed", 200);

        for (int round = 0; round < 3; round++) {
            db = new DBApp();
            for (int i = 0; i < 150; i++) {
                Integer id = randomKey(model);
                Hashtable<String, Object> change = row("gpa", randomGpa(), "age", random.nextInt(101));
                db.updateTable("Plain", id.toString(), change);
                db.updateTable("Indexed", id.toString(), change);
                model.get(id).putAll(change);
            }
            for (int i = 0; i < 40; i++) {
                Hashtable<String, Object> where = row("name", NAMES[random.nextInt(NAMES.length)], "age", random.nextInt(101));
                db.deleteFromTable("Plain", where);
                db.deleteFromTable("Indexed", where);
                model.values().removeIf(r -> r.get("name").equals(where.get("name")) && r.get("age").equals(where.get("age")));
            }
            for (int i = 0; i < 300; i++)
                compareQuery(db, model);
            addRandomRow(db, model);
            checkLayout(home, "Indexed", 200);

            Octree index = Octree.load("Indexed", "namegpabornIndex");
            List<Object> indexed = index.search(new Object[3], new Object[3]);
            check(indexed.size() == model.size() && new HashSet<>(indexed).equals(model.keySet()), "index must hold exactly the table's rows");
            Hashtable<String, Object> r = model.firstEntry().getValue();
            String[] c = index.columns();
            Object[] point = {r.get(c[0]), r.get(c[1]), r.get(c[2])};
            check(index.search(point, point).contains(r.get("id")), "index must hold a row at its current values");
        }

        db.deleteFromTable("Plain", new Hashtable<>());
        check(pageFiles(home, "Plain").length == 0, "empty table keeps no pages");
        db.insertIntoTable("Plain", row("id", 5, "name", "Ahmed"));
        check(pageFiles(home, "Plain").length == 1, "first page after refill");

        Hashtable<String, Object> target = model.firstEntry().getValue();
        SQLTerm[] byIndex = {new SQLTerm("Indexed", "name", "=", target.get("name")),
                new SQLTerm("Indexed", "gpa", "=", target.get("gpa")),
                new SQLTerm("Indexed", "born", "=", target.get("born")),
                new SQLTerm("Indexed", "age", "=", target.get("age"))};
        File[] pages = pageFiles(home, "Indexed");
        File spare = null;
        Vector<Tuple> matches = new Vector<>();
        for (Iterator it = db.selectFromTable(byIndex, new String[]{"AND", "AND", "AND"}); it.hasNext(); )
            matches.add((Tuple) it.next());
        for (File page : pages) {
            boolean holdsMatch = false;
            @SuppressWarnings("unchecked") Vector<Tuple> rows = (Vector<Tuple>) Util.read(page.toPath());
            for (Tuple r : rows)
                for (Tuple m : matches)
                    holdsMatch |= r.get("id").equals(m.get("id"));
            if (!holdsMatch)
                spare = page;
        }
        check(spare != null, "need a page with no matching rows");
        File hidden = new File(spare.getPath() + ".hidden");
        check(spare.renameTo(hidden), "hide page");
        try {
            check(count(db.selectFromTable(byIndex, new String[]{"AND", "AND", "AND"})) == matches.size(), "select must use the index");
            SQLTerm[] plainScan = {new SQLTerm("Indexed", "age", "=", target.get("age"))};
            rejects("a scan should hit the hidden page", () -> new DBApp().selectFromTable(plainScan, new String[0]));
        } finally {
            check(hidden.renameTo(spare), "restore page");
        }

        create(db, "Dup", "id", "id:java.lang.Integer:0:1000", "a:java.lang.Integer:0:9", "b:java.lang.Integer:0:9", "c:java.lang.Integer:0:9");
        db.createIndex("Dup", new String[]{"a", "b", "c"});
        for (int i = 0; i < 100; i++)
            db.insertIntoTable("Dup", row("id", i, "a", 1, "b", 2, "c", 3));
        check(count(db.selectFromTable(new SQLTerm[]{new SQLTerm("Dup", "a", "=", 1)}, new String[0])) == 100, "duplicates in index");
        db.deleteFromTable("Dup", row("id", 50));
        check(count(db.selectFromTable(new SQLTerm[]{new SQLTerm("Dup", "b", "=", 2)}, new String[0])) == 99, "delete a duplicate");
    }

    private static Double randomGpa() {
        return random.nextInt(21) / 4.0;
    }

    private static Integer randomKey(TreeMap<Integer, Hashtable<String, Object>> model) {
        Integer key = model.ceilingKey(random.nextInt(1000001));
        return key != null ? key : model.firstKey();
    }

    private static void addRandomRow(DBApp db, TreeMap<Integer, Hashtable<String, Object>> model) throws Exception {
        int id;
        do {
            id = random.nextInt(1000001);
        } while (model.containsKey(id));
        Hashtable<String, Object> r = row("id", id, "name", NAMES[random.nextInt(NAMES.length)], "gpa", randomGpa(),
                "born", date(1990 + random.nextInt(21), 1 + random.nextInt(12), 1 + random.nextInt(28)), "age", random.nextInt(101));
        db.insertIntoTable("Plain", r);
        db.insertIntoTable("Indexed", r);
        model.put(id, new Hashtable<>(r));
    }

    private static void compareQuery(DBApp db, TreeMap<Integer, Hashtable<String, Object>> model) throws Exception {
        String[] ops = {">", ">=", "<", "<=", "!=", "="}, connectors = {"AND", "OR", "XOR"};
        Hashtable<String, Object> sample = model.get(randomKey(model));
        String[] columns = {"id", "name", "gpa", "born", "age"};
        int n = 1 + random.nextInt(4);
        SQLTerm[] plain = new SQLTerm[n], indexed = new SQLTerm[n];
        String[] between = new String[n - 1];
        for (int i = 0; i < n; i++) {
            String column = columns[random.nextInt(columns.length)];
            String op = random.nextBoolean() ? "=" : ops[random.nextInt(ops.length)];
            plain[i] = new SQLTerm("Plain", column, op, sample.get(column));
            indexed[i] = new SQLTerm("Indexed", column, op, sample.get(column));
            if (i > 0)
                between[i - 1] = connectors[random.nextInt(connectors.length)];
        }

        TreeSet<Integer> expected = new TreeSet<>();
        for (Hashtable<String, Object> r : model.values())
            if (expectedMatch(r, plain, between))
                expected.add((Integer) r.get("id"));

        String query = Arrays.toString(between) + " on " + sample;
        check(ids(db.selectFromTable(plain, between)).equals(expected), "plain select disagrees with the model: " + query);
        check(ids(db.selectFromTable(indexed, between)).equals(expected), "indexed select disagrees with the model: " + query);
    }

    private static boolean expectedMatch(Hashtable<String, Object> r, SQLTerm[] terms, String[] between) {
        List<Boolean> values = new ArrayList<>();
        for (SQLTerm t : terms) {
            int c = Util.compare(r.get(t._strColumnName), t._objValue);
            switch (t._strOperator) {
                case ">": values.add(c > 0); break;
                case ">=": values.add(c >= 0); break;
                case "<": values.add(c < 0); break;
                case "<=": values.add(c <= 0); break;
                case "!=": values.add(c != 0); break;
                default: values.add(c == 0);
            }
        }
        List<String> connectors = new ArrayList<>(Arrays.asList(between));
        for (String level : new String[]{"AND", "XOR", "OR"})
            for (int i = 0; i < connectors.size(); )
                if (connectors.get(i).equals(level)) {
                    boolean a = values.get(i), b = values.remove(i + 1);
                    values.set(i, level.equals("AND") ? a && b : level.equals("XOR") ? a ^ b : a || b);
                    connectors.remove(i);
                } else {
                    i++;
                }
        return values.get(0);
    }

    private static TreeSet<Integer> ids(Iterator it) {
        TreeSet<Integer> ids = new TreeSet<>();
        while (it.hasNext())
            check(ids.add((Integer) ((Tuple) it.next()).get("id")), "a row was returned twice");
        return ids;
    }

    private static File[] pageFiles(Path home, String table) {
        return home.resolve("data").resolve(table).toFile().listFiles((d, name) -> name.startsWith("page_") && name.endsWith(".class"));
    }

    private static void checkLayout(Path home, String table, int maxRows) throws Exception {
        File[] files = pageFiles(home, table);
        check(files.length > 1, "table should span several pages");
        List<int[]> ranges = new ArrayList<>();
        for (File f : files) {
            Object page = Util.read(f.toPath());
            check(page.getClass() == Vector.class, "a page must be a Vector");
            Vector<?> rows = (Vector<?>) page;
            check(!rows.isEmpty() && rows.size() <= maxRows, "page size " + rows.size());
            int previous = -1;
            for (Object o : rows) {
                int id = (Integer) ((Tuple) o).get("id");
                check(id > previous, "rows sorted within a page");
                previous = id;
            }
            ranges.add(new int[]{(Integer) ((Tuple) rows.firstElement()).get("id"), previous});
        }
        ranges.sort(Comparator.comparingInt(r -> r[0]));
        for (int i = 1; i < ranges.size(); i++)
            check(ranges.get(i - 1)[1] < ranges.get(i)[0], "pages must not overlap");
    }
}
