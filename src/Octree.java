import java.io.Serializable;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;

public class Octree implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final int MAX_DEPTH = 32;

    private static class Entry implements Serializable {
        private static final long serialVersionUID = 1L;
        final Object[] point;
        final Object key;

        Entry(Object[] point, Object key) {
            this.point = point;
            this.key = key;
        }
    }

    private static class Node implements Serializable {
        private static final long serialVersionUID = 1L;
        final Object[] low, high;
        final int depth;
        Vector<Entry> entries = new Vector<>();
        Object[] mid;
        Node[] children;

        Node(Object[] low, Object[] high, int depth) {
            this.low = low;
            this.high = high;
            this.depth = depth;
        }
    }

    private final String table, name, key;
    private final String[] columns;
    private final int maxEntries;
    private final Node root;

    public Octree(String table, String name, String key, String[] columns,
                  Object[] low, Object[] high, int maxEntries) {
        this.table = table;
        this.name = name;
        this.key = key;
        this.columns = columns;
        this.maxEntries = maxEntries;
        this.root = new Node(low, high, 0);
    }

    private static Path file(String table, String name) {
        return Util.tableFile(table, "index_" + name + ".class");
    }

    public static Octree load(String table, String name) throws DBAppException {
        return (Octree) Util.read(file(table, name));
    }

    public void save() throws DBAppException {
        Util.write(file(table, name), this);
    }

    public String[] columns() {
        return columns;
    }

    private Object[] pointOf(Tuple t) {
        Object[] point = new Object[3];
        for (int d = 0; d < 3; d++)
            point[d] = t.get(columns[d]);
        return point;
    }

    private static int octant(Object[] point, Object[] mid) {
        int octant = 0;
        for (int d = 0; d < 3; d++)
            if (point[d] != null && Util.compare(point[d], mid[d]) >= 0)
                octant |= 1 << d;
        return octant;
    }

    public void insert(Tuple t) {
        insert(root, new Entry(pointOf(t), t.get(key)));
    }

    private void insert(Node node, Entry entry) {
        while (node.children != null)
            node = node.children[octant(entry.point, node.mid)];
        node.entries.add(entry);
        if (node.entries.size() > maxEntries && node.depth < MAX_DEPTH && !samePoint(node.entries))
            split(node);
    }

    private static boolean samePoint(Vector<Entry> entries) {
        for (Entry e : entries)
            if (!Arrays.equals(e.point, entries.get(0).point))
                return false;
        return true;
    }

    private void split(Node node) {
        node.mid = new Object[3];
        for (int d = 0; d < 3; d++)
            node.mid[d] = midpoint(node.low[d], node.high[d]);
        node.children = new Node[8];
        for (int i = 0; i < 8; i++) {
            Object[] low = new Object[3], high = new Object[3];
            for (int d = 0; d < 3; d++) {
                boolean upper = (i >> d & 1) == 1;
                low[d] = upper ? node.mid[d] : node.low[d];
                high[d] = upper ? node.high[d] : node.mid[d];
            }
            node.children[i] = new Node(low, high, node.depth + 1);
        }
        Vector<Entry> entries = node.entries;
        node.entries = null;
        for (Entry e : entries)
            insert(node, e);
    }

    static Object midpoint(Object low, Object high) {
        if (low instanceof Integer) {
            long l = (Integer) low, h = (Integer) high;
            return (int) (l + (h - l + 1) / 2);
        }
        if (low instanceof Double)
            return (Double) low / 2 + (Double) high / 2;
        if (low instanceof Date) {
            long l = ((Date) low).getTime(), h = ((Date) high).getTime();
            return new Date(l + (h - l + 1) / 2);
        }
        return midpoint((String) low, (String) high);
    }

    private static String midpoint(String low, String high) {
        int length = Math.max(low.length(), high.length()) + 1;
        BigInteger sum = digits(low, length).add(digits(high, length)).shiftRight(1);
        char[] mid = new char[length];
        for (int i = length - 1; i >= 0; i--) {
            mid[i] = (char) sum.intValue();
            sum = sum.shiftRight(16);
        }
        int end = length;
        while (end > 0 && mid[end - 1] == 0)
            end--;
        return new String(mid, 0, end);
    }

    private static BigInteger digits(String s, int length) {
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < length; i++)
            value = value.shiftLeft(16).or(BigInteger.valueOf(i < s.length() ? s.charAt(i) : 0));
        return value;
    }

    public void delete(Tuple t) {
        Object[] point = pointOf(t);
        Object value = t.get(key);
        Node node = root;
        while (node.children != null)
            node = node.children[octant(point, node.mid)];
        node.entries.removeIf(e -> e.key.equals(value));
    }

    public List<Object> search(Object[] low, Object[] high) {
        List<Object> keys = new ArrayList<>();
        search(root, low, high, keys);
        return keys;
    }

    private void search(Node node, Object[] low, Object[] high, List<Object> keys) {
        if (node.children == null) {
            for (Entry e : node.entries)
                if (inBounds(e.point, low, high))
                    keys.add(e.key);
            return;
        }
        for (int i = 0; i < 8; i++)
            if (overlaps(i, node.mid, low, high))
                search(node.children[i], low, high, keys);
    }

    private static boolean overlaps(int octant, Object[] mid, Object[] low, Object[] high) {
        for (int d = 0; d < 3; d++) {
            boolean upper = (octant >> d & 1) == 1;
            if (upper && high[d] != null && Util.compare(high[d], mid[d]) < 0) return false;
            if (!upper && low[d] != null && Util.compare(low[d], mid[d]) >= 0) return false;
        }
        return true;
    }

    private static boolean inBounds(Object[] point, Object[] low, Object[] high) {
        for (int d = 0; d < 3; d++) {
            if (low[d] != null && (point[d] == null || Util.compare(point[d], low[d]) < 0)) return false;
            if (high[d] != null && (point[d] == null || Util.compare(point[d], high[d]) > 0)) return false;
        }
        return true;
    }
}
