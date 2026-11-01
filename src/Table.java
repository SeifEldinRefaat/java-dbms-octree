import java.io.Serializable;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

public class Table implements Serializable {
    private static final long serialVersionUID = 1L;

    private static class PageInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        final int id;
        Object min, max;
        int size;

        PageInfo(int id) {
            this.id = id;
        }
    }

    private final String name;
    private final String key;
    private int nextPageId = 0;
    private final Vector<PageInfo> pages = new Vector<>();

    public Table(String name, String clusteringKey) {
        this.name = name;
        this.key = clusteringKey;
    }

    public static Table load(String name) throws DBAppException {
        return (Table) Util.read(Util.tableFile(name, "table.class"));
    }

    public void save() throws DBAppException {
        Util.write(Util.tableFile(name, "table.class"), this);
    }

    private Path pageFile(PageInfo page) {
        return Util.tableFile(name, "page_" + page.id + ".class");
    }

    @SuppressWarnings("unchecked")
    private Vector<Tuple> loadPage(PageInfo page) throws DBAppException {
        return (Vector<Tuple>) Util.read(pageFile(page));
    }

    private void savePage(PageInfo page, Vector<Tuple> rows) throws DBAppException {
        if (rows.isEmpty()) {
            Util.delete(pageFile(page));
            pages.remove(page);
            return;
        }
        page.min = rows.firstElement().get(key);
        page.max = rows.lastElement().get(key);
        page.size = rows.size();
        Util.write(pageFile(page), rows);
    }

    private int pageIndexFor(Object value) {
        int low = 0, high = pages.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (Util.compare(pages.get(mid).max, value) < 0) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    private int rowIndexFor(Vector<Tuple> rows, Object value) {
        int low = 0, high = rows.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (Util.compare(rows.get(mid).get(key), value) < 0) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    private boolean isAt(Vector<Tuple> rows, int position, Object value) {
        return position < rows.size() && Util.compare(rows.get(position).get(key), value) == 0;
    }

    public void insert(Tuple tuple, int maxRows) throws DBAppException {
        Object value = tuple.get(key);
        int target = pageIndexFor(value);
        if (target > 0 && pages.get(target - 1).size < maxRows
                && (target == pages.size()
                || (pages.get(target).size >= maxRows && Util.compare(value, pages.get(target).min) < 0)))
            target--;

        Tuple row = tuple;
        for (int i = target; ; i++) {
            if (i == pages.size()) {
                PageInfo page = new PageInfo(nextPageId++);
                pages.add(page);
                Vector<Tuple> rows = new Vector<>();
                rows.add(row);
                savePage(page, rows);
                return;
            }
            PageInfo page = pages.get(i);
            Vector<Tuple> rows = loadPage(page);
            int position = rowIndexFor(rows, row.get(key));
            if (isAt(rows, position, row.get(key)))
                throw new DBAppException("Duplicate clustering key: " + row.get(key));
            rows.add(position, row);
            Tuple overflow = rows.size() > maxRows ? rows.remove(rows.size() - 1) : null;
            savePage(page, rows);
            if (overflow == null)
                return;
            row = overflow;
        }
    }

    public Tuple find(Object value) throws DBAppException {
        int p = pageIndexFor(value);
        if (p == pages.size())
            return null;
        Vector<Tuple> rows = loadPage(pages.get(p));
        int position = rowIndexFor(rows, value);
        return isAt(rows, position, value) ? rows.get(position) : null;
    }

    public void replace(Tuple tuple) throws DBAppException {
        PageInfo page = pages.get(pageIndexFor(tuple.get(key)));
        Vector<Tuple> rows = loadPage(page);
        rows.set(rowIndexFor(rows, tuple.get(key)), tuple);
        savePage(page, rows);
    }

    public Vector<Tuple> scan(Predicate<Tuple> filter) throws DBAppException {
        Vector<Tuple> result = new Vector<>();
        for (PageInfo page : pages)
            for (Tuple t : loadPage(page))
                if (filter.test(t))
                    result.add(t);
        return result;
    }

    public Vector<Tuple> fetch(Collection<Object> keys, Predicate<Tuple> filter) throws DBAppException {
        TreeSet<Object> sorted = new TreeSet<>(Util::compare);
        sorted.addAll(keys);
        Vector<Tuple> result = new Vector<>();
        int loaded = -1;
        Vector<Tuple> rows = null;
        for (Object value : sorted) {
            int p = pageIndexFor(value);
            if (p == pages.size())
                break;
            if (p != loaded) {
                rows = loadPage(pages.get(p));
                loaded = p;
            }
            int position = rowIndexFor(rows, value);
            if (isAt(rows, position, value) && filter.test(rows.get(position)))
                result.add(rows.get(position));
        }
        return result;
    }

    public void delete(Collection<Tuple> tuples) throws DBAppException {
        TreeSet<Object> keys = new TreeSet<>(Util::compare);
        Set<PageInfo> touched = new LinkedHashSet<>();
        for (Tuple t : tuples) {
            keys.add(t.get(key));
            touched.add(pages.get(pageIndexFor(t.get(key))));
        }
        for (PageInfo page : touched) {
            Vector<Tuple> rows = loadPage(page);
            rows.removeIf(t -> keys.contains(t.get(key)));
            savePage(page, rows);
        }
    }
}
